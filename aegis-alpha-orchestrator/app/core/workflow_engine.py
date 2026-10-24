"""LangGraph workflow engine."""

from __future__ import annotations

import asyncio
import contextlib
import hashlib
import json
import logging
import threading
import uuid
from collections import defaultdict, OrderedDict
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Annotated, Any, AsyncIterator, Optional
from typing_extensions import TypedDict

from langgraph.checkpoint.memory import MemorySaver
from langgraph.graph import StateGraph, START, END
from langchain_core.messages import ToolMessage
from langchain_core.runnables import RunnableConfig
from langgraph.prebuilt import ToolNode  # noqa: F401 - re-exported for tests
from langgraph.store.base import BaseStore

try:
    from langgraph.checkpoint.sqlite import SqliteSaver
    HAS_SQLITE_CHECKPOINTER = True
except ImportError:
    HAS_SQLITE_CHECKPOINTER = False
from langgraph.errors import GraphInterrupt
from langgraph.types import interrupt, Command

from ..models.workflow import Node, Edge, TraceEntry
from ..models.responses import NodeResult, WorkflowResult, SSEEvent
from .log_safety import text_len
from .checkpointer_lifecycle import ensure_async_checkpointer
from .context_assembly import apply_trace_usage, memory_hit_count
from .memory_selector import load_run_memory, read_store_value, ticker_namespace
from .market_data import run_scoped_market_cache
from .memory_store import MemoryStoreManager
from .message_queue import (
    CONFIG_QUEUE_KEY,
    SKIPPED_TOOL_RESULT_TEXT,
    MessageQueueRegistry,
    QueueOwner,
    ThreadMessageQueue,
    is_queued_human_message,
    queue_from_config,
    to_human_messages,
)
from .node_executor import NodeExecutor
from .tool_pipeline import ToolPolicy, build_tool_node, count_tool_errors
from .tool_registry import TOOL_SPECS, resolve_agent_roles, tools_for_roles

logger = logging.getLogger(__name__)

_GRAPH_CACHE_MAX = 100
# Max agent→tools→agent iterations per specialist node (avoids infinite tool loops).
MAX_TOOL_ROUNDS = 3


def _is_tools_node_name(name: str) -> bool:
    return name == "tools" or name.startswith("tools__")


INBOX_NODE_PREFIX = "inbox__"


def _is_inbox_node_name(name: str) -> bool:
    """Queue delivery nodes (``inbox__{agent}``) are internal: no node_update SSE."""
    return name.startswith(INBOX_NODE_PREFIX)


def _is_tool_message(msg: Any) -> bool:
    """True if msg is a LangChain ToolMessage (or duck-typed equivalent)."""
    if msg is None:
        return False
    cls_name = type(msg).__name__
    if cls_name == "ToolMessage":
        return True
    role = getattr(msg, "type", None) or getattr(msg, "role", None)
    return role in ("tool", "function")


def _messages_for_tool_continuation(messages: list[Any]) -> list[Any]:
    """Slice conversation from the last seed HumanMessage when the loop continues.

    Used so a mid-loop re-entry continues the same Human/AI/Tool thread without
    re-seeding a new HumanMessage. The loop continues when tools just returned,
    or when queued steering / follow-up user messages were injected (those are
    HumanMessages marked by ``message_queue``; they never start a new slice).
    """
    if not messages:
        return []
    last = messages[-1]
    if not (_is_tool_message(last) or is_queued_human_message(last)):
        return []
    start = 0
    for i in range(len(messages) - 1, -1, -1):
        msg = messages[i]
        cls_name = type(msg).__name__
        role = getattr(msg, "type", None) or getattr(msg, "role", None)
        if (cls_name == "HumanMessage" or role in ("human", "user")) and not is_queued_human_message(msg):
            start = i
            break
    return list(messages[start:])


def _owner_from_state(state: Any) -> dict[str, str] | None:
    """Fallback run owner when the caller did not send ``owner`` explicitly."""
    if not isinstance(state, dict):
        return None
    user_id = state.get("userId") or state.get("user_id")
    if not user_id:
        return None
    return {"userId": str(user_id), "tenantId": str(state.get("tenantId") or state.get("tenant_id") or "")}


def _graph_output_interrupted(graph_out: Any) -> bool:
    """``ainvoke`` returns (not raises) with ``__interrupt__`` when a gate pauses the run."""
    return isinstance(graph_out, dict) and bool(graph_out.get("__interrupt__"))


def _tool_messages_from(output: Any) -> list[Any] | None:
    """ToolNode output -> list of ToolMessages, or None for Command/other shapes."""
    if isinstance(output, dict) and isinstance(output.get("messages"), list):
        return list(output["messages"])
    if isinstance(output, list) and all(_is_tool_message(m) for m in output):
        return list(output)
    return None


async def _merge_queue_events(
    source: AsyncIterator[Any], queue: ThreadMessageQueue | None
) -> AsyncIterator[tuple[str, Any]]:
    """Interleave graph stream chunks with message-queue events.

    Yields ``("chunk", chunk)`` / ``("queue", event)``. Queue events raised
    synchronously inside a node are delivered before that node's update chunk.
    Closing this generator cancels the underlying graph stream (client abort / timeout).
    """
    if queue is None:
        try:
            async for chunk in source:
                yield ("chunk", chunk)
        finally:
            aclose = getattr(source, "aclose", None)
            if aclose is not None:
                with contextlib.suppress(Exception):
                    await aclose()
        return

    out: asyncio.Queue = asyncio.Queue()
    loop = asyncio.get_running_loop()
    loop_thread = threading.get_ident()

    def _listener(event: dict[str, Any]) -> None:
        if threading.get_ident() == loop_thread:
            out.put_nowait(("queue", event))
        else:  # enqueue from a worker thread
            loop.call_soon_threadsafe(out.put_nowait, ("queue", event))

    async def _pump() -> None:
        try:
            async for chunk in source:
                await out.put(("chunk", chunk))
        except asyncio.CancelledError:
            raise
        except BaseException as exc:  # GraphInterrupt and real errors re-raised in consumer
            await out.put(("error", exc))
            return
        await out.put(("done", None))

    unsubscribe = queue.subscribe(_listener)
    task = asyncio.create_task(_pump())
    try:
        while True:
            kind, value = await out.get()
            if kind == "done":
                break
            if kind == "error":
                raise value
            yield (kind, value)
        # Drain queue events emitted right before completion.
        while not out.empty():
            kind, value = out.get_nowait()
            if kind == "queue":
                yield (kind, value)
    finally:
        unsubscribe()
        if not task.done():
            task.cancel()
            with contextlib.suppress(asyncio.CancelledError, Exception):
                await task
        aclose = getattr(source, "aclose", None)
        if aclose is not None:
            with contextlib.suppress(Exception):
                await aclose()


def deep_merge(current: dict, update: dict) -> dict:
    result = current.copy()
    for key, value in update.items():
        if key in result and isinstance(result[key], dict) and isinstance(value, dict):
            result[key] = deep_merge(result[key], value)
        else:
            result[key] = value
    return result


def concat_lists(current: list, update: list) -> list:
    return current + update


def merge_agent_messages(current: dict, update: dict) -> dict:
    """Per-agent message channels: concatenate each agent's list independently (P2-8)."""
    result = dict(current or {})
    for agent_id, msgs in (update or {}).items():
        result[agent_id] = list(result.get(agent_id) or []) + list(msgs or [])
    return result


def _last_agent_message(state: dict, agent_id: str) -> Any:
    msgs = (state.get("agent_messages") or {}).get(agent_id) or []
    return msgs[-1] if msgs else None


def _has_pending_tool_calls(message: Any) -> bool:
    return bool(message is not None and getattr(message, "tool_calls", None))


class WorkflowState(TypedDict):
    final_state: Annotated[dict[str, Any], deep_merge]
    trace: Annotated[list[dict], concat_lists]
    node_outputs: Annotated[dict[str, Any], deep_merge]
    subject: str
    require_approval: bool
    # Legacy shared channel; no longer written by agents (kept for checkpoint compatibility).
    messages: Annotated[list[Any], concat_lists]
    # P2-8: tool-loop conversation per general.agent node id, so parallel agents never see
    # (or route on) each other's tool calls.
    agent_messages: Annotated[dict[str, list[Any]], merge_agent_messages]



# Size cap for the final_state carried by the SSE workflow_complete event (JSON characters).
STREAM_FINAL_STATE_MAX_CHARS = 16000
_STREAM_SUMMARY_CHARS = 600
_STREAM_SIGNALS_MAX = 5
_STREAM_RECOMMENDATION_KEYS = (
    "recommendation", "confidence", "missingData", "degraded", "forcedInsufficient", "claims",
)


def build_stream_final_state(
    final_state: dict[str, Any] | None,
    node_outputs: dict[str, Any] | None,
    *,
    max_chars: int = STREAM_FINAL_STATE_MAX_CHARS,
) -> dict[str, Any]:
    """Trimmed final_state for ``workflow_complete``: recommendation + per-node summary/signals.

    Never the raw graph state (prompts, tool dumps, memory); bounded by ``max_chars`` of JSON.
    """
    final_state = final_state if isinstance(final_state, dict) else {}
    node_outputs = node_outputs if isinstance(node_outputs, dict) else {}
    nodes: dict[str, Any] = {}
    recommendation: dict[str, Any] = {}
    for node_id, payload in node_outputs.items():
        if not isinstance(payload, dict):
            continue
        summary = payload.get("summary")
        signals = payload.get("signals") if isinstance(payload.get("signals"), list) else []
        nodes[str(node_id)] = {
            "handler": payload.get("handler", ""),
            "ok": payload.get("ok", True),
            "degraded": bool(payload.get("degraded", False)),
            "summary": (str(summary) if summary is not None else "")[:_STREAM_SUMMARY_CHARS],
            "signals": signals[:_STREAM_SIGNALS_MAX],
        }
        data = payload.get("data") if isinstance(payload.get("data"), dict) else {}
        if payload.get("handler") == "finance.stock_recommendation_aggregate" or "recommendation" in data:
            recommendation = {k: data[k] for k in _STREAM_RECOMMENDATION_KEYS if k in data}
    evidence = final_state.get("evidence")
    trimmed: dict[str, Any] = {
        "ticker": final_state.get("ticker") or final_state.get("symbol"),
        "subject": final_state.get("subject"),
        "recommendation": recommendation,
        "nodes": nodes,
        "evidenceIds": sorted(str(k) for k in evidence) if isinstance(evidence, dict) else [],
    }

    def size(value: Any) -> int:
        return len(json.dumps(value, ensure_ascii=False, default=str))

    if size(trimmed) > max_chars:
        recommendation.pop("claims", None)
        for entry in nodes.values():
            entry["summary"] = entry["summary"][:160]
            entry["signals"] = entry["signals"][:2]
        trimmed["truncated"] = True
    while size(trimmed) > max_chars and nodes:
        nodes.pop(next(iter(nodes)))
        trimmed["truncated"] = True
    return trimmed


def is_valid_resume_value(value: Any) -> bool:
    """A resume payload must carry an explicit boolean decision."""
    return isinstance(value, dict) and isinstance(value.get("approved"), bool)


def is_explicit_approval(value: Any) -> bool:
    """Only ``{"approved": True}`` approves; anything else (missing, None, truthy strings) rejects."""
    return is_valid_resume_value(value) and value.get("approved") is True

@dataclass(frozen=True)
class GraphGuardrails:
    """P2-12: bounds for one graph run (all configurable via Settings)."""

    recursion_limit: int = 100
    timeout_seconds: float = 900.0
    thread_meta_max: int = 1000
    cleanup_checkpoints: bool = False

    @classmethod
    def from_settings(cls, settings: Any) -> "GraphGuardrails":
        return cls(
            recursion_limit=int(getattr(settings, "workflow_recursion_limit", 100)),
            timeout_seconds=float(getattr(settings, "workflow_timeout_seconds", 900.0)),
            thread_meta_max=int(getattr(settings, "workflow_thread_meta_max", 1000)),
            cleanup_checkpoints=bool(getattr(settings, "workflow_cleanup_checkpoints", False)),
        )


class WorkflowTimeout(Exception):
    def __init__(self, seconds: float):
        super().__init__(f"Workflow timed out after {seconds:g}s")
        self.seconds = seconds


APPROVED_CONCLUSION_KEY = "approved_conclusion"
_CONCLUSION_LABELS = {"BUY", "HOLD", "SELL", "WATCH"}


def approved_conclusion_payload(
    result: WorkflowResult, nodes: list[Node], thread_id: str
) -> tuple[str, dict[str, Any]] | None:
    """P2-15: the ``approved_conclusion`` memory entry for an approved run, or None.

    Written only when the human approved, the aggregate finished ok, and the aggregate's
    recommendation policy ran and passed (policy block present, not degraded, a definite label,
    at least one evidence-bound claim). Rejections and policy-failing drafts write nothing.
    """
    final = result.final_state or {}
    if final.get("approval_status") != "approved" or final.get("interrupted"):
        return None
    symbol = str(final.get("ticker") or final.get("symbol") or "").strip()
    aggregate = next((n for n in nodes if n.data.handler == "finance.stock_recommendation_aggregate"), None)
    if not symbol or aggregate is None:
        return None
    output = (result.node_outputs or {}).get(aggregate.id)
    if not isinstance(output, dict) or output.get("ok") is False:
        return None
    data = output.get("data") if isinstance(output.get("data"), dict) else {}
    label = str(data.get("recommendation") or "").upper()
    claims = [
        {"text": str(c.get("text") or "")[:200], "evidenceId": str(c.get("evidenceId"))}
        for c in (data.get("claims") or [])
        if isinstance(c, dict) and c.get("evidenceId")
    ]
    if (
        not isinstance(data.get("policy"), dict)
        or data.get("degraded")
        or output.get("degraded")
        or label not in _CONCLUSION_LABELS
        or not claims
    ):
        return None
    summary = str(output.get("summary") or "").strip()
    return symbol, {
        "summary": summary[:600] or f"[{label}]",
        "recommendation": label,
        "confidence": data.get("confidence"),
        "claims": claims[:10],
        "threadId": thread_id,
        "approvedAt": datetime.now(timezone.utc).isoformat(),
    }


def _interrupt_event() -> SSEEvent:
    return SSEEvent(
        event="human_interrupt",
        data={"message": "Workflow paused for approval", "ok": True, "interrupted": True},
    )


class WorkflowEngine:
    """LangGraph-based workflow orchestration engine."""

    _graph_cache: OrderedDict[str, Any] = OrderedDict()

    def __init__(
        self,
        node_executor: NodeExecutor,
        tools: list | None = None,
        store: BaseStore | None = None,
        checkpointer: Any | None = None,
        tool_registry: dict[str, Any] | None = None,
        guardrails: "GraphGuardrails | None" = None,
        message_queues: MessageQueueRegistry | None = None,
        tool_policy: ToolPolicy | None = None,
    ):
        self._node_executor = node_executor
        # P4-1: validate / classify / truncate / fence every tool call.
        self._tool_policy = tool_policy or ToolPolicy.from_settings(getattr(node_executor, "_config", None))
        # Per-thread steering / follow-up queues (pi dual-loop semantics).
        self._message_queues = message_queues or MessageQueueRegistry()
        self._guardrails = guardrails or GraphGuardrails()
        self._cache_token = uuid.uuid4().hex
        self._checkpointer = ensure_async_checkpointer(checkpointer)
        self._tools = tools or []
        self._tool_registry = tool_registry
        self._store = store
        # Shared ToolNode for backward-compatible single-tools-list mode.
        self._tool_node = build_tool_node(self._tools, self._tool_policy) if self._tools else None
        # Persist layout/flags per thread so resume rebuilds the same graph. Bounded LRU (P2-12);
        # evicted threads fall back to the durable layout in the store.
        self._thread_meta: OrderedDict[str, dict[str, Any]] = OrderedDict()

    def attach_checkpointer(self, checkpointer: Any) -> None:
        """Swap the checkpointer (lifespan opens the durable one on the serving loop, P2-16).

        Compiled graphs embed their checkpointer, so this engine's cache token is renewed.
        """
        self._checkpointer = ensure_async_checkpointer(checkpointer)
        self._cache_token = uuid.uuid4().hex

    @property
    def message_queues(self) -> MessageQueueRegistry:
        return self._message_queues

    # ── message queue (steering / follow-up) ─────────────────────────────
    def _open_message_queue(self, thread_id: str, owner: Any) -> ThreadMessageQueue | None:
        return self._message_queues.open_run(thread_id, QueueOwner.from_any(owner))

    @staticmethod
    def _queue_summary_if_used(queue: ThreadMessageQueue | None) -> dict[str, Any] | None:
        if queue is None:
            return None
        summary = queue.summary()
        if summary["delivered"] or summary["undelivered"] or summary["skippedToolCalls"]:
            return summary
        return None

    def _finish_message_queue(self, queue: ThreadMessageQueue | None, outcome: str) -> None:
        """Documented lifecycle: paused keeps the queue, every other outcome closes it."""
        if queue is None:
            return
        if outcome == "paused":
            queue.pause()
            return
        undelivered = queue.close(outcome)
        if undelivered:
            logger.info(
                "[QUEUE] run %s %s with %d undelivered queued message(s)",
                queue.thread_id,
                outcome,
                len(undelivered),
            )

    def _attach_queue_summary(
        self, result: WorkflowResult, queue: ThreadMessageQueue | None
    ) -> WorkflowResult:
        summary = self._queue_summary_if_used(queue)
        if summary is not None and isinstance(result.final_state, dict):
            result.final_state["messageQueue"] = summary
        return result

    def _close_queue_event(self, queue: ThreadMessageQueue | None, outcome: str) -> SSEEvent | None:
        """Close the run queue and return the ``message_queue_closed`` SSE event."""
        if queue is None:
            return None
        self._finish_message_queue(queue, outcome)
        summary = queue.summary()
        return SSEEvent(
            event="message_queue_closed",
            data={
                "threadId": queue.thread_id,
                "reason": outcome,
                "delivered": summary["delivered"],
                "undelivered": summary["undelivered"],
            },
        )

    @staticmethod
    def _queue_sse(event: dict[str, Any]) -> SSEEvent:
        data = {k: v for k, v in event.items() if k != "type"}
        return SSEEvent(event=str(event.get("type")), data=data)

    def _agent_tools_fn(self, tool_node: Any, agent_ids: list[str]):
        """Run ToolNode on each listed agent's own pending tool calls (P2-8 isolation).

        Queue-aware (pi inner loop) when the run has a message queue in config:

        * steering pending after a tool call finishes -> the remaining tool calls of the
          same assistant message get an explicit skipped ToolMessage
          (``skip_remaining_tools``; off = pi HEAD batch semantics);
        * after the batch, pending steering is appended to *that agent's* channel as marked
          HumanMessages so it reaches the agent's next LLM call.

        Without a run queue this is exactly the phase-2 per-channel ToolNode call.
        """
        skip_remaining = self._message_queues.settings.skip_remaining_tools

        async def _run_calls(msgs: list[Any], config: RunnableConfig, queue, agent_id: str) -> list[Any]:
            last = msgs[-1]
            calls = list(getattr(last, "tool_calls", None) or [])
            if queue is None or not skip_remaining or len(calls) <= 1:
                out = await tool_node.ainvoke({"messages": msgs}, config)
                return out.get("messages", []) if isinstance(out, dict) else list(out or [])
            results: list[Any] = []
            for index, call in enumerate(calls):
                single = last.model_copy(update={"tool_calls": [call]})
                part = await tool_node.ainvoke({"messages": [*msgs[:-1], single]}, config)
                part_msgs = _tool_messages_from(part)
                if part_msgs is None:
                    # Command-style tool output: keep ToolNode semantics, no skipping.
                    out = await tool_node.ainvoke({"messages": msgs}, config)
                    return out.get("messages", []) if isinstance(out, dict) else list(out or [])
                results.extend(part_msgs)
                if index < len(calls) - 1 and queue.has_steering(node_id=agent_id):
                    for rest in calls[index + 1:]:
                        results.append(
                            ToolMessage(
                                content=SKIPPED_TOOL_RESULT_TEXT,
                                tool_call_id=str(rest.get("id") or ""),
                                name=rest.get("name"),
                                status="error",
                            )
                        )
                        queue.record_skipped_tool(node_id=agent_id, tool_call=rest)
                    logger.info(
                        "[QUEUE] steering pending: skipped %d tool call(s) for agent=%s",
                        len(calls) - index - 1,
                        agent_id,
                    )
                    break
            return results

        async def tools_fn(state: WorkflowState, config: RunnableConfig) -> dict:
            queue = queue_from_config(config)
            updates: dict[str, list[Any]] = {}
            for agent_id in agent_ids:
                msgs = list((state.get("agent_messages") or {}).get(agent_id) or [])
                if not msgs or not _has_pending_tool_calls(msgs[-1]):
                    continue
                produced = list(await _run_calls(msgs, config, queue, agent_id))
                if queue is not None and queue.has_steering(node_id=agent_id):
                    steering = queue.drain("steer", node_id=agent_id)
                    if steering:
                        produced.extend(to_human_messages(steering))
                updates[agent_id] = produced
            return {"agent_messages": updates} if updates else {}

        return tools_fn

    def _make_inbox_node(self, agent_id: str):
        """Deliver queued messages when the agent's inner loop would end.

        Steering first (pi polls steering before follow-ups), else follow-up
        (pi outer loop). Messages go to this agent's own channel; the routing flag
        ``final_state.message_queue_inbox[agent]`` tells the edge whether to loop back.
        """

        async def inbox(state: WorkflowState, config: RunnableConfig) -> dict:
            queue = queue_from_config(config)
            delivered: list[Any] = []
            if queue is not None:
                delivered = queue.drain("steer", node_id=agent_id) or queue.drain(
                    "follow_up", node_id=agent_id
                )
            update: dict[str, Any] = {
                "final_state": {"message_queue_inbox": {agent_id: bool(delivered)}}
            }
            if delivered:
                update["agent_messages"] = {agent_id: to_human_messages(delivered)}
            return update

        return inbox

    def _has_tools_capability(self) -> bool:
        return bool(self._tools) or bool(self._tool_registry)

    def _queue_receivers(self, nodes: list[Node]) -> list[str]:
        """Agent nodes that own a tool loop (and so an ``inbox__`` delivery point) in build_graph."""
        agents = [n for n in nodes if (n.data.handler or "") == "general.agent"]
        if self._tool_registry:
            return [n.id for n in agents if tools_for_roles(self._tool_registry, resolve_agent_roles(n))]
        if self._tool_node:
            return [n.id for n in agents]
        return []

    def _open_run_queue(self, thread_id: str, owner: Any, nodes: list[Node]) -> ThreadMessageQueue | None:
        queue = self._open_message_queue(thread_id, owner)
        if queue is not None:
            queue.set_receivers(self._queue_receivers(nodes))
        return queue

    def _tools_for_node(self, node: Node) -> list:
        """Role-filtered tools for a general.agent node (empty if none available)."""
        if self._tool_registry:
            roles = resolve_agent_roles(node)
            return tools_for_roles(self._tool_registry, roles)
        if self._tools:
            return list(self._tools)
        return []

    @classmethod
    def _node_content_fingerprint(cls, node: Node) -> dict[str, Any]:
        """Stable executable fields that affect compiled node behavior."""
        data = node.data
        return {
            "id": node.id,
            "handler": data.handler or "",
            "prompt": data.prompt or "",
            "agentId": data.agent_id or "",
            "functionName": data.function_name or "",
            "nodeType": data.node_type or "",
        }

    @classmethod
    def _node_result_cache_key(cls, node: Node, handler: str) -> str:
        """Per-ticker node result cache key, bound to the node's executable content.

        Uses a stable sha256 (not ``hash()``, which is salted per process) so two nodes sharing a
        handler but with different prompts never read each other's cached result.
        """
        payload = json.dumps(cls._node_content_fingerprint(node), sort_keys=True, default=str)
        digest = hashlib.sha256(payload.encode("utf-8")).hexdigest()[:16]
        return f"{handler}:v3:{digest}"

    def _node_reads_portfolio(self, node: Node) -> bool:
        """True when the node is a tool-bound agent that can call portfolio_read tools.

        Such results are user-specific and must not live in the per-ticker cache.
        """
        if (node.data.handler or "") != "general.agent" or not self._has_tools_capability():
            return False
        for bound in self._tools_for_node(node):
            spec = TOOL_SPECS.get(getattr(bound, "name", ""))
            if spec is not None and spec.sandbox_class == "portfolio_read":
                return True
        return False

    @classmethod
    def _topology_hash(cls, nodes: list[Node], edges: list[Edge]) -> str:
        """Hash topology and executable node content so cache invalidates on edits."""
        sorted_nodes = [
            cls._node_content_fingerprint(n)
            for n in sorted(nodes, key=lambda n: n.id)
        ]
        sorted_edges = sorted(
            (
                e.source,
                e.target,
                json.dumps(e.condition, sort_keys=True, default=str) if e.condition else None,
            )
            for e in edges
        )
        payload = json.dumps({"nodes": sorted_nodes, "edges": sorted_edges}, sort_keys=True)
        return str(hash(payload))

    def _remember_thread(
        self,
        thread_id: str,
        *,
        require_approval: bool,
        nodes: list[Node],
        edges: list[Edge],
    ) -> None:
        """Store layout and approval flag for later resume on the same thread (bounded LRU)."""
        self._thread_meta[thread_id] = {
            "require_approval": require_approval,
            "nodes": nodes,
            "edges": edges,
        }
        self._thread_meta.move_to_end(thread_id)
        while len(self._thread_meta) > max(1, self._guardrails.thread_meta_max):
            self._thread_meta.popitem(last=False)

    def _run_config(self, thread_id: str, queue: ThreadMessageQueue | None = None) -> dict[str, Any]:
        configurable: dict[str, Any] = {"thread_id": thread_id}
        if queue is not None:
            # Non-primitive configurable values are not persisted into checkpoint metadata.
            configurable[CONFIG_QUEUE_KEY] = queue
        return {
            "configurable": configurable,
            "recursion_limit": self._guardrails.recursion_limit,
        }

    async def _ainvoke_guarded(self, graph: Any, graph_input: Any, config: dict[str, Any]) -> Any:
        """``ainvoke`` bounded by the configured whole-run timeout, with a run-scoped quote cache."""
        try:
            with run_scoped_market_cache():
                return await asyncio.wait_for(
                    graph.ainvoke(graph_input, config=config, **self._graph_run_kwargs()),
                    timeout=self._guardrails.timeout_seconds,
                )
        except asyncio.TimeoutError:
            raise WorkflowTimeout(self._guardrails.timeout_seconds) from None

    async def _store_approved_conclusion(
        self, result: WorkflowResult, nodes: list[Node], thread_id: str
    ) -> None:
        if self._store is None:
            return
        entry = approved_conclusion_payload(result, nodes, thread_id)
        if entry is None:
            logger.info("[THINKING] Approved run wrote no conclusion (policy gate) thread_id=%s", thread_id)
            return
        symbol, payload = entry
        try:
            # ttl 0 = no expiry for approved conclusions (same as MemoryStoreManager).
            await self._store.aput(ticker_namespace(symbol), APPROVED_CONCLUSION_KEY, payload, ttl_seconds=0)
        except TypeError:
            await self._store.aput(ticker_namespace(symbol), APPROVED_CONCLUSION_KEY, payload)
        except Exception as exc:  # noqa: BLE001
            logger.warning("[THINKING] approved_conclusion write failed thread_id=%s error=%s",
                           thread_id, type(exc).__name__)

    async def _cleanup_thread(self, thread_id: str) -> None:
        """Drop checkpoint + layout of a thread that reached a terminal state (opt-in)."""
        if not self._guardrails.cleanup_checkpoints:
            return
        self._thread_meta.pop(thread_id, None)
        delete = getattr(self._checkpointer, "adelete_thread", None)
        if delete is not None:
            try:
                await delete(thread_id)
            except Exception as exc:  # noqa: BLE001
                logger.warning("[THINKING] Checkpoint cleanup failed thread_id=%s error=%s", thread_id, type(exc).__name__)
        if self._store is not None:
            try:
                await self._store.adelete(("thread", thread_id), "layout")
            except Exception as exc:  # noqa: BLE001
                logger.warning("[THINKING] Layout cleanup failed thread_id=%s error=%s", thread_id, type(exc).__name__)

    async def _persist_thread_layout(
        self,
        thread_id: str,
        *,
        require_approval: bool,
        nodes: list[Node],
        edges: list[Edge],
    ) -> None:
        """Remember layout in memory and, when a store exists, in SQLite."""
        self._remember_thread(
            thread_id,
            require_approval=require_approval,
            nodes=nodes,
            edges=edges,
        )
        if self._store is None:
            return
        payload = {
            "nodes": [node.model_dump() for node in nodes],
            "edges": [edge.model_dump() for edge in edges],
            "require_approval": bool(require_approval),
        }
        try:
            await self._store.aput(
                ("thread", thread_id),
                "layout",
                payload,
                ttl_seconds=0,
            )
        except Exception as exc:
            logger.warning(
                "[THINKING] Failed to persist thread layout thread_id=%s error=%s",
                thread_id,
                exc,
            )

    async def _load_thread_layout(
        self, thread_id: str
    ) -> tuple[list[Node], list[Edge], bool] | None:
        """Rebuild nodes/edges/require_approval from the durable thread layout."""
        if self._store is None:
            return None
        try:
            payload = await read_store_value(self._store, ("thread", thread_id), "layout")
        except Exception as exc:
            logger.warning(
                "[THINKING] Failed to load thread layout thread_id=%s error=%s",
                thread_id,
                exc,
            )
            return None
        if not isinstance(payload, dict):
            return None
        raw_nodes = payload.get("nodes")
        raw_edges = payload.get("edges")
        if not isinstance(raw_nodes, list) or not isinstance(raw_edges, list):
            return None
        if not raw_nodes:
            return None
        nodes = [Node.model_validate(item) for item in raw_nodes]
        edges = [Edge.model_validate(item) for item in raw_edges]
        return nodes, edges, bool(payload.get("require_approval"))

    async def _execution_state_for_node(
        self,
        *,
        node: Node,
        handler: str,
        workflow_state: WorkflowState,
        store: BaseStore | None,
    ) -> dict[str, Any]:
        """Copy final_state and inject memoryContext for every node, including start/end.

        Control nodes do not call the model, but the run trace still records how many
        memory items were injected, and a later model node sees the same snippets.
        """
        del node, handler
        final_state = dict(workflow_state.get("final_state") or {})
        if store is None:
            return final_state
        user_id = final_state.get("userId") or final_state.get("user_id")
        symbol = final_state.get("ticker") or final_state.get("symbol")
        memory_context = await load_run_memory(store, user_id=user_id, symbol=symbol)
        if not isinstance(memory_context, dict):
            memory_context = {"snippets": [], "hitCount": 0}
        return {**final_state, "memoryContext": memory_context}

    @staticmethod
    def _merge_evidence(state_update: dict[str, Any], result: NodeResult) -> dict[str, Any]:
        """Lift server-issued evidence ids from a node result into ``final_state.evidence``."""
        data = result.data if isinstance(result.data, dict) else {}
        evidence = data.get("evidence")
        if isinstance(evidence, dict) and evidence:
            state_update["evidence"] = {str(k): v for k, v in evidence.items()}
        return state_update

    @staticmethod
    def _annotate_trace(
        trace_entry: dict[str, Any],
        result_data: dict[str, Any] | None,
        exec_state: dict[str, Any],
    ) -> dict[str, Any]:
        traced = apply_trace_usage(trace_entry, result_data)
        if not isinstance(traced, dict):
            traced = trace_entry
        traced["memoryHits"] = memory_hit_count(exec_state if isinstance(exec_state, dict) else {})
        return traced

    @staticmethod
    def _node_result_from_cache(payload: Any) -> NodeResult | None:
        """Accept a flat NodeResult dump. aget Items are unwrapped by read_store_value."""
        if not isinstance(payload, dict):
            return None
        if not any(key in payload for key in ("ok", "handler", "summary", "node_id")):
            return None
        try:
            allowed = NodeResult.model_fields
            return NodeResult.model_validate(
                {key: value for key, value in payload.items() if key in allowed}
            )
        except Exception:
            return None

    def _result_from_graph_output(self, graph_out: Any) -> WorkflowResult:
        """Map ainvoke output to WorkflowResult. Interrupts stay ok so resume can continue."""
        if not isinstance(graph_out, dict):
            graph_out = {}
        interrupted = bool(graph_out.get("__interrupt__"))
        inner = dict(graph_out.get("final_state") or {})
        if interrupted:
            inner["interrupted"] = True
        trace: list[TraceEntry] = []
        for entry in graph_out.get("trace") or []:
            if isinstance(entry, dict):
                trace.append(TraceEntry(**entry))
        return WorkflowResult(
            ok=True,
            error=None,
            final_state=inner,
            trace=trace,
            node_outputs=graph_out.get("node_outputs") or {},
            state=dict(inner),
        )

    async def _result_from_interrupt(
        self,
        graph: Any,
        config: dict[str, Any],
        fallback_state: dict[str, Any],
    ) -> WorkflowResult:
        """GraphInterrupt means the checkpoint already holds the pause. Do not fail the run."""
        final_state: dict[str, Any] = {"interrupted": True}
        trace: list[TraceEntry] = []
        node_outputs: dict[str, Any] = {}
        try:
            snapshot = await graph.aget_state(config)
            values = getattr(snapshot, "values", None) or {}
            if isinstance(values, dict):
                final_state = dict(values.get("final_state") or {})
                final_state["interrupted"] = True
                for entry in values.get("trace") or []:
                    if isinstance(entry, dict):
                        trace.append(TraceEntry(**entry))
                node_outputs = values.get("node_outputs") or {}
        except Exception as exc:
            logger.info("[THINKING] Interrupt snapshot unavailable: %s", exc)
            if isinstance(fallback_state, dict):
                final_state = {**fallback_state, "interrupted": True}
        return WorkflowResult(
            ok=True,
            error=None,
            final_state=final_state,
            trace=trace,
            node_outputs=node_outputs,
            state=dict(final_state),
        )

    @classmethod
    def _get_cached_graph(cls, key: str) -> Any | None:
        if key in cls._graph_cache:
            cls._graph_cache.move_to_end(key)
            return cls._graph_cache[key]
        return None

    @classmethod
    def _put_cached_graph(cls, key: str, graph: Any) -> None:
        if key in cls._graph_cache:
            cls._graph_cache.move_to_end(key)
        else:
            if len(cls._graph_cache) >= _GRAPH_CACHE_MAX:
                cls._graph_cache.popitem(last=False)
        cls._graph_cache[key] = graph

    def _graph_run_kwargs(self) -> dict[str, Any]:
        """Sync durability only when a checkpointer will actually create the future."""
        if self._checkpointer is None:
            return {}
        return {"durability": "sync"}

    def build_graph(self, nodes: list[Node], edges: list[Edge], require_approval: bool = False) -> Any:
        """Build and compile a LangGraph StateGraph with caching."""
        topo_hash = self._topology_hash(nodes, edges)
        cache_key = (
            f"{topo_hash}:{require_approval}:"
            f"{bool(self._tools)}:{bool(self._tool_registry)}:"
            f"mq={self._message_queues.enabled}:{self._message_queues.settings.skip_remaining_tools}:"
            # Compiled graphs embed this engine's executor, checkpointer and store: never share
            # them across engine instances or across a checkpointer swap.
            f"{self._cache_token}"
        )
        cached = self._get_cached_graph(cache_key)
        if cached is not None:
            return cached

        graph = StateGraph(WorkflowState)

        node_ids = {n.id for n in nodes}
        incoming: dict[str, list[str]] = defaultdict(list)
        outgoing: dict[str, list[str]] = defaultdict(list)
        # agent node id -> tools graph node id ("tools" or "tools__{agent_id}")
        agent_tools_node_ids: dict[str, str] = {}
        agent_nodes: list[str] = []
        use_per_agent_tools = bool(self._tool_registry)
        queue_enabled = self._message_queues.enabled

        for edge in edges:
            if edge.source in node_ids and edge.target in node_ids:
                outgoing[edge.source].append(edge.target)
                incoming[edge.target].append(edge.source)

        # P2-14: a node with several distinct predecessors is a fan-in join. Deferring it makes
        # LangGraph run it once, after every pending branch (including multi-step agent tool loops)
        # has finished, instead of once per superstep in which some predecessor completed.
        fan_in_ids = {nid for nid, sources in incoming.items() if len(set(sources)) > 1}
        for node in nodes:
            graph.add_node(node.id, self._make_node_fn(node), defer=node.id in fan_in_ids)

        if use_per_agent_tools:
            # Per-agent ToolNode with role-filtered tools; unique id tools__{agent_id}.
            for node in nodes:
                if node.data.handler != "general.agent":
                    continue
                roles = resolve_agent_roles(node)
                filtered = tools_for_roles(self._tool_registry, roles)
                if not filtered:
                    continue
                tools_node_id = f"tools__{node.id}"
                graph.add_node(tools_node_id, self._agent_tools_fn(build_tool_node(filtered, self._tool_policy), [node.id]))
                agent_tools_node_ids[node.id] = tools_node_id
                agent_nodes.append(node.id)
        elif self._tool_node:
            # Backward compatible: single shared tools node for all agents; it executes each
            # agent's pending tool calls on that agent's own message channel.
            for node in nodes:
                if node.data.handler == "general.agent":
                    agent_tools_node_ids[node.id] = "tools"
                    agent_nodes.append(node.id)
            graph.add_node("tools", self._agent_tools_fn(self._tool_node, list(agent_nodes)))

        aggregate_node = next(
            (n for n in nodes if n.data.handler == "finance.stock_recommendation_aggregate"),
            None,
        )

        if require_approval and aggregate_node:
            async def approval_gate(state: WorkflowState) -> dict:
                response = interrupt({
                    "message": "Please review the analysis before generating the final report",
                    "state_summary": {
                        k: v for k, v in state.get("final_state", {}).items()
                        if k != "marketDataContext"
                    },
                })
                # Fail closed: only an explicit boolean approval releases the aggregate node.
                if is_explicit_approval(response):
                    return {"final_state": {"approval_status": "approved"}}
                return {"final_state": {"approval_status": "rejected"}}

            graph.add_node("approval_gate", approval_gate, defer=aggregate_node.id in fan_in_ids)

        for node in nodes:
            if not incoming[node.id]:
                graph.add_edge(START, node.id)

        if agent_nodes and agent_tools_node_ids:
            for agent_id in agent_nodes:
                tools_target = agent_tools_node_ids[agent_id]

                next_nodes = [e.target for e in edges if e.source == agent_id and e.target in node_ids]
                if require_approval and aggregate_node:
                    # Agents must not bypass the approval gate in front of the aggregate (P2-14).
                    next_nodes = ["approval_gate" if t == aggregate_node.id else t for t in next_nodes]
                if not next_nodes:
                    next_nodes = [END]

                inbox_id = f"{INBOX_NODE_PREFIX}{agent_id}"

                def _successors(_agent_id: str, _next: tuple) -> Any:
                    # Fan out to every successor (P2-8), not only the first one.
                    if len(_next) == 1:
                        return _agent_id + "__next"
                    return list(_next)

                def route_to_tools(
                    state: WorkflowState,
                    config: RunnableConfig,
                    _agent_id: str = agent_id,
                    _tools_target: str = tools_target,
                    _next: tuple = tuple(next_nodes),
                    _inbox_id: str = inbox_id,
                ) -> Any:
                    if _has_pending_tool_calls(_last_agent_message(state, _agent_id)):
                        return _tools_target
                    # Inner loop would end: deliver pending steering / follow-up first (pi outer loop).
                    queue = queue_from_config(config) if queue_enabled else None
                    if queue is not None and queue.has_pending(node_id=_agent_id):
                        return _inbox_id
                    if queue is not None:
                        queue.mark_agent_done(_agent_id)
                    return _successors(_agent_id, _next)

                # Always register __next so route_to_tools return value is valid
                # whether there is one or many successors (next_nodes defaults to [END]).
                routing_map: dict[str, Any] = {tools_target: tools_target}
                for n in next_nodes:
                    routing_map[n] = n
                routing_map[agent_id + "__next"] = next_nodes[0]
                if queue_enabled:
                    routing_map[inbox_id] = inbox_id
                graph.add_conditional_edges(agent_id, route_to_tools, routing_map)

                if queue_enabled:
                    graph.add_node(inbox_id, self._make_inbox_node(agent_id))

                    def route_after_inbox(
                        state: WorkflowState,
                        config: RunnableConfig,
                        _agent_id: str = agent_id,
                        _next: tuple = tuple(next_nodes),
                    ) -> Any:
                        flags = (state.get("final_state") or {}).get("message_queue_inbox") or {}
                        if flags.get(_agent_id):
                            return _agent_id
                        queue = queue_from_config(config)
                        if queue is not None:
                            queue.mark_agent_done(_agent_id)
                        # Same successors as route_to_tools: the approval-gate mapping and the
                        # parallel fan-out list are preserved; deferred fan-in still waits.
                        return _successors(_agent_id, _next)

                    inbox_map: dict[str, Any] = {agent_id: agent_id}
                    for n in next_nodes:
                        inbox_map[n] = n
                    inbox_map[agent_id + "__next"] = next_nodes[0]
                    graph.add_conditional_edges(inbox_id, route_after_inbox, inbox_map)

            if use_per_agent_tools:
                # Unique tools node per agent: tools__{agent_id} always returns to that agent.
                for agent_id, tools_node_id in agent_tools_node_ids.items():
                    graph.add_edge(tools_node_id, agent_id)
            else:
                # Shared tools node: route back to every agent whose channel now ends with tool results.
                def route_after_tools(state: WorkflowState, _agents: list[str] = list(agent_nodes)) -> Any:
                    # A channel may end with steering injected after its tool results.
                    ready = [
                        aid for aid in _agents
                        if _is_tool_message(_last_agent_message(state, aid))
                        or is_queued_human_message(_last_agent_message(state, aid))
                    ]
                    if ready:
                        return ready if len(ready) > 1 else ready[0]
                    last = (state.get("final_state") or {}).get("last_agent_id")
                    return last if last in _agents else _agents[0]

                tools_routing = {aid: aid for aid in agent_nodes}
                graph.add_conditional_edges("tools", route_after_tools, tools_routing)

        for edge in edges:
            if edge.source not in node_ids or edge.target not in node_ids:
                continue
            if edge.source in agent_nodes:
                continue

            target = edge.target
            if require_approval and aggregate_node and edge.target == aggregate_node.id:
                target = "approval_gate"

            condition = getattr(edge, "condition", None)
            if condition:
                def condition_fn(state, cond=condition, tgt=target):
                    field = cond.get("field", "")
                    expected = cond.get("value", "")
                    else_target = cond.get("else", cond.get("default_target", ""))
                    actual = state.get("final_state", {}).get(field, "")
                    if str(actual) == str(expected):
                        return tgt
                    return else_target if else_target else tgt

                routing = {target: target}
                else_target = condition.get("else", condition.get("default_target", ""))
                if else_target and else_target in node_ids and else_target != target:
                    routing[else_target] = else_target

                graph.add_conditional_edges(
                    edge.source,
                    condition_fn,
                    routing,
                )
            else:
                graph.add_edge(edge.source, target)

        if require_approval and aggregate_node:
            def route_after_approval(state: WorkflowState, _target: str = aggregate_node.id) -> str:
                status = (state.get("final_state") or {}).get("approval_status")
                return _target if status == "approved" else END

            graph.add_conditional_edges(
                "approval_gate",
                route_after_approval,
                {aggregate_node.id: aggregate_node.id, END: END},
            )

        for node in nodes:
            if not outgoing[node.id] and node.id not in agent_nodes:
                graph.add_edge(node.id, END)

        compile_kwargs: dict[str, Any] = {}
        if self._checkpointer is not None:
            compile_kwargs["checkpointer"] = self._checkpointer
        if self._store is not None:
            compile_kwargs["store"] = self._store
        compiled = graph.compile(**compile_kwargs)
        self._put_cached_graph(cache_key, compiled)
        return compiled

    def _make_node_fn(self, node: Node):
        """Create async function for a graph node."""
        handler = node.data.handler or node.data.function_name or "logic"
        is_agent_with_tools = handler == "general.agent" and self._has_tools_capability()

        # Optional[BaseStore] is the annotation LangGraph injects. `BaseStore | None`
        # is not recognized, so the store argument stayed empty and memory never loaded.
        async def node_fn(state: WorkflowState, *, store: Optional[BaseStore] = None) -> dict:
            started_at = datetime.now(timezone.utc).isoformat()
            subject = state.get("subject", "Aegis Alpha workflow")
            node_label = node.data.label or node.data.title or node.id
            agent_tools = self._tools_for_node(node) if is_agent_with_tools else []
            messages = list((state.get("agent_messages") or {}).get(node.id) or [])
            prior_for_tools = _messages_for_tool_continuation(messages)
            mid_tool_loop = bool(prior_for_tools)

            exec_state = await self._execution_state_for_node(
                node=node,
                handler=handler,
                workflow_state=state,
                store=store,
            )

            # Portfolio-reading agents are user-specific: never served from / written to the ticker cache.
            cacheable = handler != "start" and handler != "end" and not self._node_reads_portfolio(node)
            # Skip ticker cache mid tool-loop so we re-enter with ToolMessages.
            if store and cacheable and not mid_tool_loop:
                try:
                    ticker = exec_state.get("ticker", "") or exec_state.get("symbol", "")
                    cache_key = self._node_result_cache_key(node, handler)
                    if ticker:
                        cached_data = await read_store_value(store, ticker_namespace(ticker), cache_key)
                        result = self._node_result_from_cache(cached_data)
                        if result is not None:
                            logger.info(f"[THINKING] Cache hit for ticker={ticker} handler={handler}, skipping LLM")
                            trace_entry = self._annotate_trace(
                                {
                                    "nodeId": result.node_id, "nodeName": result.node_name,
                                    "handler": result.handler, "status": result.status,
                                    "ok": result.ok, "degraded": result.degraded,
                                    "cacheHit": True,
                                    "startedAt": started_at,
                                    "completedAt": datetime.now(timezone.utc).isoformat(),
                                    "durationMs": 0,
                                },
                                result.data,
                                exec_state,
                            )
                            state_update = {node.id: result.model_dump()}
                            if result.handler:
                                state_update[result.handler] = result.model_dump()
                            self._merge_evidence(state_update, result)
                            return {
                                "final_state": state_update,
                                "trace": [trace_entry],
                                "node_outputs": {node.id: result.model_dump()},
                            }
                except GraphInterrupt:
                    raise
                except Exception as cache_err:
                    logger.warning(f"[THINKING] Store read error for node {node.id}: {cache_err}")

            logger.info(f"[THINKING] Workflow node starting: id={node.id} handler={handler} label={node_label} subject_len={text_len(subject)}")

            # True tool-calling loop for general.agent when tools are bound.
            if agent_tools:
                try:
                    return await self._run_agent_tool_turn(
                        node=node,
                        state=state,
                        exec_state=exec_state,
                        subject=subject,
                        node_label=node_label,
                        handler=handler,
                        agent_tools=agent_tools,
                        prior_for_tools=prior_for_tools,
                        mid_tool_loop=mid_tool_loop,
                        started_at=started_at,
                        store=store,
                    )
                except GraphInterrupt:
                    raise
                except Exception as e:
                    logger.error(
                        f"[THINKING] Agent tool-loop failed, falling back to execute: "
                        f"id={node.id} error={e}"
                    )

            try:
                result = await self._node_executor.execute(
                    node=node,
                    state=exec_state,
                    subject=subject,
                )
            except GraphInterrupt:
                raise
            except Exception as e:
                logger.error(f"[THINKING] Workflow node exception: id={node.id} handler={handler} label={node_label} error={e}")
                result = NodeResult(
                    ok=False, status="error", handler="logic",
                    node_id=node.id, node_name=node_label,
                    subject=subject, summary=str(e), duration_ms=0,
                )

            trace_entry = self._annotate_trace(
                {
                    "nodeId": result.node_id,
                    "nodeName": result.node_name,
                    "handler": result.handler,
                    "status": result.status,
                    "ok": result.ok,
                    "degraded": result.degraded,
                    "startedAt": started_at,
                    "completedAt": datetime.now(timezone.utc).isoformat(),
                    "durationMs": result.duration_ms,
                },
                result.data,
                exec_state,
            )
            logger.info(f"[THINKING] Workflow node completed: id={node.id} handler={result.handler} label={node_label} status={result.status} ok={result.ok} duration={result.duration_ms}ms")

            if store and cacheable:
                try:
                    ticker = state.get("final_state", {}).get("ticker", "") or state.get("final_state", {}).get("symbol", "")
                    cache_key = self._node_result_cache_key(node, handler)
                    if ticker and result.ok:
                        await store.aput(
                            ticker_namespace(ticker), cache_key,
                            result.model_dump(),
                            ttl_seconds=1800,
                        )
                        logger.info(f"[THINKING] Cached result for ticker={ticker} handler={handler}")
                except Exception as cache_err:
                    logger.warning(f"[THINKING] Store write error for node {node.id}: {cache_err}")

            state_update = {node.id: result.model_dump()}
            if result.handler:
                state_update[result.handler] = result.model_dump()
            self._merge_evidence(state_update, result)
            if is_agent_with_tools:
                # Attribution for tools → agent return path
                state_update["last_agent_id"] = node.id

            return {
                "final_state": state_update,
                "trace": [trace_entry],
                "node_outputs": {node.id: result.model_dump()},
            }

        return node_fn

    async def _run_agent_tool_turn(
        self,
        *,
        node: Node,
        state: WorkflowState,
        exec_state: dict[str, Any],
        subject: str,
        node_label: str,
        handler: str,
        agent_tools: list,
        prior_for_tools: list[Any],
        mid_tool_loop: bool,
        started_at: str,
        store: BaseStore | None,
    ) -> dict:
        """Execute one general.agent turn: request tools or produce final NodeResult."""
        final_state = exec_state if isinstance(exec_state, dict) else (state.get("final_state") or {})
        tool_rounds = final_state.get("tool_rounds") or {}
        if not isinstance(tool_rounds, dict):
            tool_rounds = {}
        current_round = int(tool_rounds.get(node.id, 0) or 0)
        force_final = current_round >= MAX_TOOL_ROUNDS

        agent_result = await self._node_executor.invoke_agent_with_tools(
            node=node,
            state=final_state,
            subject=subject,
            tools=agent_tools,
            prior_messages=prior_for_tools if mid_tool_loop else None,
            force_final=force_final,
        )

        if agent_result.has_tool_calls and not force_final:
            next_round = current_round + 1
            tool_names: list[str] = []
            ai_msg = agent_result.ai_message
            for tc in getattr(ai_msg, "tool_calls", None) or []:
                if isinstance(tc, dict):
                    tool_names.append(str(tc.get("name") or tc.get("function", {}).get("name") or ""))
                else:
                    tool_names.append(str(getattr(tc, "name", "") or ""))
            logger.info(
                f"[THINKING] Agent requesting tools: id={node.id} round={next_round}/{MAX_TOOL_ROUNDS} "
                f"tools={tool_names}"
            )
            trace_entry = self._annotate_trace(
                {
                    "nodeId": node.id,
                    "nodeName": node_label,
                    "handler": handler,
                    "status": "awaiting_tools",
                    "ok": True,
                    "degraded": False,
                    "startedAt": started_at,
                    "completedAt": datetime.now(timezone.utc).isoformat(),
                    "durationMs": int(
                        (datetime.now(timezone.utc) - datetime.fromisoformat(started_at)).total_seconds()
                        * 1000
                    ),
                },
                None,
                final_state,
            )
            # messages reducer concatenates; seed Human only on first loop entry.
            return {
                "agent_messages": {node.id: agent_result.messages_to_append},
                "final_state": {
                    "last_agent_id": node.id,
                    "tool_rounds": {node.id: next_round},
                },
                "trace": [trace_entry],
            }

        # Final answer (no tool_calls, or forced after max rounds).
        result = self._node_executor.agent_tool_result_to_node_result(
            node=node,
            subject=subject,
            agent_result=agent_result,
            started_at=started_at,
            tool_errors=count_tool_errors(prior_for_tools if mid_tool_loop else []),
        )
        if force_final and agent_result.has_tool_calls:
            # Model still tried to call tools past the cap; surface as degraded text.
            result = NodeResult(
                **{
                    **result.model_dump(),
                    "degraded": True,
                    "summary": result.summary
                    or f"Tool-call limit ({MAX_TOOL_ROUNDS}) reached for {node.id}",
                    "data": {
                        **(result.data or {}),
                        "tool_round_capped": True,
                        "tool_rounds": current_round,
                    },
                }
            )

        trace_entry = self._annotate_trace(
            {
                "nodeId": result.node_id,
                "nodeName": result.node_name,
                "handler": result.handler,
                "status": result.status,
                "ok": result.ok,
                "degraded": result.degraded,
                "startedAt": started_at,
                "completedAt": datetime.now(timezone.utc).isoformat(),
                "durationMs": result.duration_ms,
            },
            result.data,
            final_state,
        )
        logger.info(
            f"[THINKING] Agent tool-loop final: id={node.id} status={result.status} "
            f"ok={result.ok} rounds={current_round}"
        )

        # A turn shaped by queued user messages is user-specific: never write it to the
        # shared per-ticker cache.
        steered = any(
            is_queued_human_message(m)
            for m in ((state.get("agent_messages") or {}).get(node.id) or [])
        )
        if (
            store and not steered
            and handler != "start" and handler != "end" and not self._node_reads_portfolio(node)
        ):
            try:
                ticker = final_state.get("ticker", "") or final_state.get("symbol", "")
                cache_key = self._node_result_cache_key(node, handler)
                if ticker and result.ok:
                    await store.aput(
                        ticker_namespace(ticker),
                        cache_key,
                        result.model_dump(),
                        ttl_seconds=1800,
                    )
            except Exception as cache_err:
                logger.warning(f"[THINKING] Store write error for node {node.id}: {cache_err}")

        state_update: dict[str, Any] = {
            node.id: result.model_dump(),
            "last_agent_id": node.id,
        }
        if result.handler:
            state_update[result.handler] = result.model_dump()
        self._merge_evidence(state_update, result)

        out: dict[str, Any] = {
            "final_state": state_update,
            "trace": [trace_entry],
            "node_outputs": {node.id: result.model_dump()},
        }
        # Keep final AI text in message history when present (no tool_calls).
        append_msgs = agent_result.messages_to_append
        if append_msgs:
            out["agent_messages"] = {node.id: append_msgs}
        return out

    def _initial_state(self, state: dict[str, Any], subject: str, require_approval: bool) -> WorkflowState:
        return {
            "final_state": state,
            "trace": [],
            "node_outputs": {},
            "subject": subject,
            "require_approval": require_approval,
            "messages": [],
            "agent_messages": {},
        }

    async def _start_thread(
        self,
        nodes: list[Node],
        edges: list[Edge],
        require_approval: bool,
        thread_id: str | None,
        *,
        owner: Any = None,
        state: Any = None,
    ) -> tuple[Any, str, dict[str, Any], ThreadMessageQueue | None]:
        graph = self.build_graph(nodes, edges, require_approval=require_approval)
        tid = thread_id or str(uuid.uuid4())
        await self._persist_thread_layout(
            tid, require_approval=require_approval, nodes=nodes, edges=edges
        )
        queue = self._open_run_queue(
            tid, owner if owner is not None else _owner_from_state(state), nodes
        )
        return graph, tid, self._run_config(tid, queue), queue

    async def execute_workflow(
        self,
        nodes: list[Node],
        edges: list[Edge],
        state: dict[str, Any],
        subject: str,
        require_approval: bool = False,
        thread_id: str | None = None,
        owner: Any = None,
    ) -> WorkflowResult:
        """Execute workflow non-streaming."""
        logger.info(f"[THINKING] Workflow execution starting: subject_len={text_len(subject)} nodes={[n.id for n in nodes]} require_approval={require_approval}")
        logger.debug("Workflow subject: %s", subject)
        graph, tid, config, queue = await self._start_thread(
            nodes, edges, require_approval, thread_id, owner=owner, state=state
        )
        initial_state = self._initial_state(state, subject, require_approval)

        outcome = "aborted"
        try:
            final_state = await self._ainvoke_guarded(graph, initial_state, config)
            node_ids = [n.id for n in nodes]
            logger.info(f"[THINKING] Workflow execution completed: subject_len={text_len(subject)} nodes={node_ids} ok=True")
            outcome = "paused" if _graph_output_interrupted(final_state) else "completed"
            self._finish_message_queue(queue, outcome)
            result = self._attach_queue_summary(self._result_from_graph_output(final_state), queue)
            if not result.final_state.get("interrupted"):
                await self._cleanup_thread(tid)
            return result
        except GraphInterrupt:
            logger.info(
                "[THINKING] Workflow paused for approval: subject=%s thread_id=%s",
                subject,
                tid,
            )
            outcome = "paused"
            self._finish_message_queue(queue, outcome)
            return await self._result_from_interrupt(graph, config, state)
        except WorkflowTimeout as e:
            logger.error("[THINKING] Workflow execution timed out: thread_id=%s", tid)
            outcome = "timeout"
            self._finish_message_queue(queue, outcome)
            return self._attach_queue_summary(WorkflowResult(ok=False, error=str(e), final_state=state), queue)
        except Exception as e:
            logger.error(f"[THINKING] Workflow execution failed: subject_len={text_len(subject)} error={e}")
            outcome = "error"
            self._finish_message_queue(queue, outcome)
            return self._attach_queue_summary(WorkflowResult(ok=False, error=str(e), final_state=state), queue)
        finally:
            if outcome == "aborted":  # cancelled (client gone / server shutdown)
                self._finish_message_queue(queue, outcome)

    async def _stream_events(
        self,
        graph: Any,
        graph_input: Any,
        config: dict[str, Any],
        state: dict[str, Any],
        *,
        tid: str,
        token_node_id: str | None,
        report_degraded: bool,
        label: str,
        queue: ThreadMessageQueue | None = None,
    ) -> AsyncIterator[SSEEvent]:
        """Shared ``astream`` → SSE translation for both streaming endpoints (P2-12).

        ``token_node_id`` re-emits that node's content as ``token`` chunks; ``report_degraded``
        collects degraded reasons and emits a ``degraded`` event before completion. The whole run
        is bounded by the configured timeout; on expiry an ``error`` event with code
        ``WORKFLOW_TIMEOUT`` is emitted.

        Message queue: queue events (``message_queued`` / ``message_dequeued`` / ``tool_skipped`` /
        ``message_queue_cleared``) are interleaved with node updates; an interrupt pauses the queue,
        every terminal outcome closes it and emits ``message_queue_closed`` before
        ``workflow_complete`` / ``error``; a closed generator (client abort) closes it as ``aborted``.
        """
        degraded_reasons: list[str] = []
        saw_interrupt = False
        streamed_state: dict[str, Any] = dict(state or {})
        streamed_outputs: dict[str, Any] = {}
        loop = asyncio.get_running_loop()
        deadline = loop.time() + self._guardrails.timeout_seconds
        market_scope = run_scoped_market_cache()
        market_scope.__enter__()
        stream = _merge_queue_events(
            graph.astream(
                graph_input,
                config=config,
                stream_mode="updates",
                **self._graph_run_kwargs(),
            ),
            queue,
        ).__aiter__()
        outcome = "aborted"
        try:
            while True:
                remaining = deadline - loop.time()
                if remaining <= 0:
                    raise WorkflowTimeout(self._guardrails.timeout_seconds)
                try:
                    kind, chunk = await asyncio.wait_for(stream.__anext__(), timeout=remaining)
                except StopAsyncIteration:
                    break
                except asyncio.TimeoutError:
                    raise WorkflowTimeout(self._guardrails.timeout_seconds) from None
                if kind == "queue":
                    yield self._queue_sse(chunk)
                    continue
                if not isinstance(chunk, dict):
                    continue
                for node_name, update in chunk.items():
                    if node_name == "__interrupt__":
                        saw_interrupt = True
                        yield _interrupt_event()
                        continue
                    if not isinstance(update, dict):
                        continue
                    if isinstance(update.get("final_state"), dict):
                        streamed_state = deep_merge(streamed_state, update["final_state"])
                    if isinstance(update.get("node_outputs"), dict):
                        streamed_outputs.update(update["node_outputs"])
                    if _is_inbox_node_name(str(node_name)):
                        continue
                    trace_entry = (update.get("trace") or [{}])[0]

                    if token_node_id and node_name == token_node_id:
                        node_result = (update.get("node_outputs") or {}).get(token_node_id)
                        if node_result and node_result.get("content"):
                            content = node_result["content"]
                            chunk_size = 50
                            for i in range(0, len(content), chunk_size):
                                yield SSEEvent(
                                    event="token",
                                    data={"nodeId": token_node_id, "content": content[i:i + chunk_size]},
                                )
                            continue

                    if _is_tools_node_name(str(node_name)):
                        yield SSEEvent(
                            event="tool_call",
                            data={
                                "nodeId": node_name,
                                "nodeName": "Tool Execution",
                                "handler": "tool",
                                "status": "completed",
                                "ok": True,
                                "results": update.get("node_outputs", {}),
                            },
                        )
                        continue
                    node_id = trace_entry.get("nodeId", node_name)
                    node_status = trace_entry.get("status", "")
                    node_ok = trace_entry.get("ok", False)
                    node_duration = trace_entry.get("durationMs", 0)
                    node_degraded = bool(trace_entry.get("degraded", False))
                    if report_degraded:
                        if node_degraded:
                            degraded_reasons.append(f"node:{node_id}")
                        for _nid, payload in (update.get("node_outputs") or {}).items():
                            if isinstance(payload, dict) and payload.get("degraded"):
                                degraded_reasons.append(f"output:{_nid}")
                            if isinstance(payload, dict) and isinstance(payload.get("data"), dict):
                                for m in payload["data"].get("missingData") or []:
                                    degraded_reasons.append(str(m))
                    logger.info(f"[THINKING] SSE {label} node_update: id={node_id} status={node_status} ok={node_ok} duration={node_duration}ms")
                    yield SSEEvent(
                        event="node_update",
                        data={
                            "nodeId": node_id,
                            "nodeName": trace_entry.get("nodeName", node_name),
                            "handler": trace_entry.get("handler", ""),
                            "status": node_status,
                            "ok": node_ok,
                            "degraded": node_degraded,
                            "startedAt": trace_entry.get("startedAt", ""),
                            "completedAt": trace_entry.get("completedAt", ""),
                            "durationMs": node_duration,
                        },
                    )
                if saw_interrupt:
                    break

            if saw_interrupt:
                outcome = "paused"
                self._finish_message_queue(queue, outcome)
                return
            complete: dict[str, Any] = {"ok": True}
            if report_degraded:
                if degraded_reasons:
                    yield SSEEvent(
                        event="degraded",
                        data={"ok": True, "reasons": list(dict.fromkeys(degraded_reasons))},
                    )
                complete["degraded"] = bool(degraded_reasons)
            complete["final_state"] = build_stream_final_state(streamed_state, streamed_outputs)
            await self._cleanup_thread(tid)
            outcome = "completed"
            closed = self._close_queue_event(queue, outcome)
            if closed is not None:
                yield closed
            queue_summary = self._queue_summary_if_used(queue)
            if queue_summary is not None:
                complete["messageQueue"] = queue_summary
            yield SSEEvent(event="workflow_complete", data=complete)
        except GraphInterrupt:
            outcome = "paused"
            self._finish_message_queue(queue, outcome)
            yield _interrupt_event()
        except WorkflowTimeout as e:
            logger.error("[THINKING] Workflow %s timed out: thread_id=%s", label, tid)
            outcome = "timeout"
            closed = self._close_queue_event(queue, outcome)
            if closed is not None:
                yield closed
            yield SSEEvent(event="error", data={"ok": False, "error": str(e), "code": "WORKFLOW_TIMEOUT"})
        except Exception as e:
            logger.error(f"[THINKING] Workflow {label} failed: thread_id={tid} error={e}")
            outcome = "error"
            closed = self._close_queue_event(queue, outcome)
            if closed is not None:
                yield closed
            yield SSEEvent(event="error", data={"ok": False, "error": str(e), "code": "WORKFLOW_ERROR"})
        finally:
            if outcome == "aborted":
                # Client disconnected / generator closed: discard undelivered messages.
                self._finish_message_queue(queue, outcome)
            aclose = getattr(stream, "aclose", None)
            if aclose is not None:
                try:
                    await aclose()
                except Exception:  # noqa: BLE001 - generator already torn down by the timeout
                    pass
            market_scope.__exit__(None, None, None)

    async def stream_workflow(
        self,
        nodes: list[Node],
        edges: list[Edge],
        state: dict[str, Any],
        subject: str,
        require_approval: bool = False,
        thread_id: str | None = None,
        owner: Any = None,
    ) -> AsyncIterator[SSEEvent]:
        """Execute workflow with SSE streaming."""
        logger.info(f"[THINKING] Workflow streaming starting: subject_len={text_len(subject)} nodes={[n.id for n in nodes]} require_approval={require_approval}")
        logger.debug("Workflow subject: %s", subject)
        graph, tid, config, queue = await self._start_thread(
            nodes, edges, require_approval, thread_id, owner=owner, state=state
        )
        # aclosing: a client abort (aclose on this generator) must close the inner stream now,
        # not at GC time, so the graph run is cancelled and the queue closes as "aborted".
        async with contextlib.aclosing(
            self._stream_events(
                graph,
                self._initial_state(state, subject, require_approval),
                config,
                state,
                tid=tid,
                token_node_id=None,
                report_degraded=True,
                label="streaming",
                queue=queue,
            )
        ) as events:
            async for event in events:
                yield event

    async def stream_workflow_tokens(
        self,
        nodes: list[Node],
        edges: list[Edge],
        state: dict[str, Any],
        subject: str,
        require_approval: bool = False,
        thread_id: str | None = None,
        owner: Any = None,
    ) -> AsyncIterator[SSEEvent]:
        graph, tid, config, queue = await self._start_thread(
            nodes, edges, require_approval, thread_id, owner=owner, state=state
        )
        aggregate_node = next(
            (n for n in nodes if n.data.handler == "finance.stock_recommendation_aggregate"),
            None,
        )
        # aclosing: a client abort (aclose on this generator) must close the inner stream now,
        # not at GC time, so the graph run is cancelled and the queue closes as "aborted".
        async with contextlib.aclosing(
            self._stream_events(
                graph,
                self._initial_state(state, subject, require_approval),
                config,
                state,
                tid=tid,
                token_node_id=aggregate_node.id if aggregate_node else None,
                report_degraded=False,
                label="token-stream",
                queue=queue,
            )
        ) as events:
            async for event in events:
                yield event

    async def resume_workflow(
        self,
        thread_id: str,
        resume_value: dict[str, Any] | None = None,
        nodes: list[Node] | None = None,
        edges: list[Edge] | None = None,
        require_approval: bool | None = None,
        owner: Any = None,
    ) -> WorkflowResult:
        """Resume an interrupted workflow using Command(resume=...).

        Rebuilds the graph with the same require_approval flag used at start.
        Prefers explicit args, then per-thread metadata saved during execute/stream.
        When unknown, defaults require_approval=True because resume is used for
        approval-gate interrupts.
        """
        if not is_valid_resume_value(resume_value):
            return WorkflowResult(
                ok=False,
                error="resume_value must be an object with a boolean 'approved' field",
                final_state={},
            )
        meta = dict(self._thread_meta.get(thread_id) or {})
        had_layout = bool(meta.get("nodes")) and bool(meta.get("edges"))
        if nodes is None:
            nodes = meta.get("nodes")
        if edges is None:
            edges = meta.get("edges")
        loaded_require: bool | None = None
        if nodes is None or edges is None:
            loaded = await self._load_thread_layout(thread_id)
            if loaded is not None:
                nodes, edges, loaded_require = loaded
                self._remember_thread(
                    thread_id,
                    require_approval=loaded_require,
                    nodes=nodes,
                    edges=edges,
                )
        if nodes is None or edges is None:
            return WorkflowResult(
                ok=False,
                error="Cannot resume: original workflow layout not available. Provide nodes and edges.",
            )

        if require_approval is None:
            if had_layout and "require_approval" in meta:
                require_approval = bool(meta["require_approval"])
            elif loaded_require is not None:
                require_approval = loaded_require
            elif "require_approval" in meta:
                require_approval = bool(meta["require_approval"])
            else:
                # Resume targets approval interrupts; keep approval_gate in the graph.
                require_approval = True

        graph = self.build_graph(nodes, edges, require_approval=require_approval)
        # Queued messages kept while paused are delivered after the explicit resume
        # (never used as the approval decision itself).
        queue = self._open_run_queue(thread_id, owner, nodes)
        config = self._run_config(thread_id, queue)

        outcome = "aborted"
        try:
            from langgraph.types import Command as LgCommand
            final_state = await self._ainvoke_guarded(graph, LgCommand(resume=resume_value), config)
            outcome = "paused" if _graph_output_interrupted(final_state) else "completed"
            self._finish_message_queue(queue, outcome)
            result = self._attach_queue_summary(self._result_from_graph_output(final_state), queue)
            if not result.final_state.get("interrupted"):
                if is_explicit_approval(resume_value):
                    await self._store_approved_conclusion(result, nodes, thread_id)
                await self._cleanup_thread(thread_id)
            return result
        except GraphInterrupt:
            logger.info("[THINKING] Workflow resume paused again: thread_id=%s", thread_id)
            outcome = "paused"
            self._finish_message_queue(queue, outcome)
            return await self._result_from_interrupt(graph, config, {})
        except WorkflowTimeout as e:
            logger.error("[THINKING] Workflow resume timed out: thread_id=%s", thread_id)
            outcome = "timeout"
            self._finish_message_queue(queue, outcome)
            return WorkflowResult(ok=False, error=str(e), final_state={})
        except Exception as e:
            logger.error(f"[THINKING] Workflow resume failed: thread_id={thread_id} error={e}")
            outcome = "error"
            self._finish_message_queue(queue, outcome)
            return WorkflowResult(ok=False, error=str(e), final_state={})
        finally:
            if outcome == "aborted":
                self._finish_message_queue(queue, outcome)

    @staticmethod
    def topological_sort(nodes: list[Node], edges: list[Edge]) -> list[Node]:
        """Sort nodes in topological order using Kahn's algorithm.

        P3-9: not on the execution path (LangGraph compiles the graph); kept as a validation helper
        and raises on cycles instead of silently dropping the nodes on the cycle.
        """
        node_map = {n.id: n for n in nodes}
        in_degree: dict[str, int] = {n.id: 0 for n in nodes}
        adjacency: dict[str, list[str]] = defaultdict(list)

        for edge in edges:
            if edge.source in node_map and edge.target in node_map:
                adjacency[edge.source].append(edge.target)
                in_degree[edge.target] += 1

        queue = [nid for nid, deg in in_degree.items() if deg == 0]
        result = []

        while queue:
            nid = queue.pop(0)
            result.append(node_map[nid])
            for neighbor in adjacency[nid]:
                in_degree[neighbor] -= 1
                if in_degree[neighbor] == 0:
                    queue.append(neighbor)

        if len(result) != len(nodes):
            remaining = sorted(nid for nid, deg in in_degree.items() if deg > 0)
            raise ValueError(f"Workflow graph contains a cycle: {remaining}")
        return result
