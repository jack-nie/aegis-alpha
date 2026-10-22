"""LangGraph workflow engine."""

from __future__ import annotations

import hashlib
import json
import logging
import uuid
from collections import defaultdict, OrderedDict
from datetime import datetime, timezone
from typing import Annotated, Any, AsyncIterator, Optional
from typing_extensions import TypedDict

from langgraph.checkpoint.memory import MemorySaver
from langgraph.graph import StateGraph, START, END
from langgraph.prebuilt import ToolNode
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
from .checkpointer_lifecycle import ensure_async_checkpointer
from .context_assembly import apply_trace_usage, memory_hit_count
from .memory_selector import load_run_memory, read_store_value
from .memory_store import MemoryStoreManager
from .node_executor import NodeExecutor
from .tool_registry import TOOL_SPECS, resolve_agent_roles, tools_for_roles

logger = logging.getLogger(__name__)

_GRAPH_CACHE_MAX = 100
# Max agent→tools→agent iterations per specialist node (avoids infinite tool loops).
MAX_TOOL_ROUNDS = 3


def _is_tools_node_name(name: str) -> bool:
    return name == "tools" or name.startswith("tools__")


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
    """Slice conversation from the last HumanMessage when tools just returned.

    Used so a mid-loop re-entry continues the same Human/AI/Tool thread without
    re-seeding a new HumanMessage.
    """
    if not messages:
        return []
    last = messages[-1]
    if not _is_tool_message(last):
        return []
    start = 0
    for i in range(len(messages) - 1, -1, -1):
        msg = messages[i]
        cls_name = type(msg).__name__
        role = getattr(msg, "type", None) or getattr(msg, "role", None)
        if cls_name == "HumanMessage" or role in ("human", "user"):
            start = i
            break
    return list(messages[start:])


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


class WorkflowState(TypedDict):
    final_state: Annotated[dict[str, Any], deep_merge]
    trace: Annotated[list[dict], concat_lists]
    node_outputs: Annotated[dict[str, Any], deep_merge]
    subject: str
    require_approval: bool
    messages: Annotated[list[Any], concat_lists]



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
    ):
        self._node_executor = node_executor
        self._checkpointer = ensure_async_checkpointer(checkpointer)
        self._tools = tools or []
        self._tool_registry = tool_registry
        self._store = store
        # Shared ToolNode for backward-compatible single-tools-list mode.
        self._tool_node = ToolNode(self._tools) if self._tools else None
        # Persist layout/flags per thread so resume rebuilds the same graph.
        self._thread_meta: dict[str, dict[str, Any]] = {}

    def _has_tools_capability(self) -> bool:
        return bool(self._tools) or bool(self._tool_registry)

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
        """Store layout and approval flag for later resume on the same thread."""
        self._thread_meta[thread_id] = {
            "require_approval": require_approval,
            "nodes": nodes,
            "edges": edges,
        }

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
            f"{bool(self._tools)}:{bool(self._tool_registry)}"
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

        for edge in edges:
            if edge.source in node_ids and edge.target in node_ids:
                outgoing[edge.source].append(edge.target)
                incoming[edge.target].append(edge.source)

        for node in nodes:
            graph.add_node(node.id, self._make_node_fn(node))

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
                graph.add_node(tools_node_id, ToolNode(filtered))
                agent_tools_node_ids[node.id] = tools_node_id
                agent_nodes.append(node.id)
        elif self._tool_node:
            # Backward compatible: single shared tools node for all agents.
            graph.add_node("tools", self._tool_node)
            for node in nodes:
                if node.data.handler == "general.agent":
                    agent_tools_node_ids[node.id] = "tools"
                    agent_nodes.append(node.id)

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

            graph.add_node("approval_gate", approval_gate)

        for node in nodes:
            if not incoming[node.id]:
                graph.add_edge(START, node.id)

        if agent_nodes and agent_tools_node_ids:
            for agent_id in agent_nodes:
                tools_target = agent_tools_node_ids[agent_id]

                def route_to_tools(
                    state: WorkflowState,
                    _agent_id: str = agent_id,
                    _tools_target: str = tools_target,
                ) -> str:
                    messages = state.get("messages", [])
                    last_msg = messages[-1] if messages else None
                    if last_msg and hasattr(last_msg, "tool_calls") and last_msg.tool_calls:
                        return _tools_target
                    return _agent_id + "__next"

                next_nodes = [e.target for e in edges if e.source == agent_id and e.target in node_ids]
                if not next_nodes:
                    next_nodes = [END]
                # Always register __next so route_to_tools return value is valid
                # whether there is one or many successors (next_nodes defaults to [END]).
                routing_map: dict[str, Any] = {tools_target: tools_target}
                for n in next_nodes:
                    routing_map[n] = n
                routing_map[agent_id + "__next"] = next_nodes[0]
                graph.add_conditional_edges(agent_id, route_to_tools, routing_map)

            if use_per_agent_tools:
                # Unique tools node per agent: tools__{agent_id} always returns to that agent.
                for agent_id, tools_node_id in agent_tools_node_ids.items():
                    graph.add_edge(tools_node_id, agent_id)
            else:
                # Shared tools node: route back via final_state.last_agent_id.
                def route_after_tools(state: WorkflowState, _agents: list[str] = list(agent_nodes)) -> str:
                    last = (state.get("final_state") or {}).get("last_agent_id")
                    if last in _agents:
                        return last
                    return _agents[0]

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
            messages = list(state.get("messages") or [])
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
                        cached_data = await read_store_value(store, ("ticker", ticker), cache_key)
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

            logger.info(f"[THINKING] Workflow node starting: id={node.id} handler={handler} label={node_label} subject={subject}")

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
                            ("ticker", ticker), cache_key,
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
                "messages": agent_result.messages_to_append,
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

        if store and handler != "start" and handler != "end" and not self._node_reads_portfolio(node):
            try:
                ticker = final_state.get("ticker", "") or final_state.get("symbol", "")
                cache_key = self._node_result_cache_key(node, handler)
                if ticker and result.ok:
                    await store.aput(
                        ("ticker", ticker),
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
            out["messages"] = append_msgs
        return out

    async def execute_workflow(
        self,
        nodes: list[Node],
        edges: list[Edge],
        state: dict[str, Any],
        subject: str,
        require_approval: bool = False,
        thread_id: str | None = None,
    ) -> WorkflowResult:
        """Execute workflow non-streaming."""
        logger.info(f"[THINKING] Workflow execution starting: subject={subject} nodes={[n.id for n in nodes]} require_approval={require_approval}")
        graph = self.build_graph(nodes, edges, require_approval=require_approval)
        tid = thread_id or str(uuid.uuid4())
        await self._persist_thread_layout(
            tid, require_approval=require_approval, nodes=nodes, edges=edges
        )
        config = {"configurable": {"thread_id": tid}}
        initial_state: WorkflowState = {
            "final_state": state,
            "trace": [],
            "node_outputs": {},
            "subject": subject,
            "require_approval": require_approval,
            "messages": [],
        }

        try:
            final_state = await graph.ainvoke(
                initial_state, config=config, **self._graph_run_kwargs()
            )
            node_ids = [n.id for n in nodes]
            logger.info(f"[THINKING] Workflow execution completed: subject={subject} nodes={node_ids} ok=True")
            return self._result_from_graph_output(final_state)
        except GraphInterrupt:
            logger.info(
                "[THINKING] Workflow paused for approval: subject=%s thread_id=%s",
                subject,
                tid,
            )
            return await self._result_from_interrupt(graph, config, state)
        except Exception as e:
            logger.error(f"[THINKING] Workflow execution failed: subject={subject} error={e}")
            return WorkflowResult(ok=False, error=str(e), final_state=state)

    async def stream_workflow(
        self,
        nodes: list[Node],
        edges: list[Edge],
        state: dict[str, Any],
        subject: str,
        require_approval: bool = False,
        thread_id: str | None = None,
    ) -> AsyncIterator[SSEEvent]:
        """Execute workflow with SSE streaming."""
        logger.info(f"[THINKING] Workflow streaming starting: subject={subject} nodes={[n.id for n in nodes]} require_approval={require_approval}")
        graph = self.build_graph(nodes, edges, require_approval=require_approval)
        tid = thread_id or str(uuid.uuid4())
        await self._persist_thread_layout(
            tid, require_approval=require_approval, nodes=nodes, edges=edges
        )
        config = {"configurable": {"thread_id": tid}}
        initial_state: WorkflowState = {
            "final_state": state,
            "trace": [],
            "node_outputs": {},
            "subject": subject,
            "require_approval": require_approval,
            "messages": [],
        }

        degraded_reasons: list[str] = []
        saw_interrupt = False
        streamed_state: dict[str, Any] = dict(state or {})
        streamed_outputs: dict[str, Any] = {}
        try:
            async for chunk in graph.astream(
                initial_state,
                config=config,
                stream_mode="updates",
                **self._graph_run_kwargs(),
            ):
                if not isinstance(chunk, dict):
                    continue
                for node_name, update in chunk.items():
                    if node_name == "__interrupt__":
                        saw_interrupt = True
                        yield SSEEvent(
                            event="human_interrupt",
                            data={"message": "Workflow paused for approval", "ok": True, "interrupted": True},
                        )
                        continue
                    if not isinstance(update, dict):
                        continue
                    if isinstance(update.get("final_state"), dict):
                        streamed_state = deep_merge(streamed_state, update["final_state"])
                    if isinstance(update.get("node_outputs"), dict):
                        streamed_outputs.update(update["node_outputs"])
                    trace_entry = (update.get("trace") or [{}])[0]
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
                    else:
                        node_id = trace_entry.get("nodeId", node_name)
                        node_status = trace_entry.get("status", "")
                        node_ok = trace_entry.get("ok", False)
                        node_duration = trace_entry.get("durationMs", 0)
                        node_degraded = bool(trace_entry.get("degraded", False))
                        if node_degraded:
                            degraded_reasons.append(f"node:{node_id}")
                        # Inspect aggregate policy in node_outputs
                        outputs = update.get("node_outputs") or {}
                        for _nid, payload in outputs.items():
                            if isinstance(payload, dict) and payload.get("degraded"):
                                degraded_reasons.append(f"output:{_nid}")
                            if isinstance(payload, dict) and isinstance(payload.get("data"), dict):
                                missing = payload["data"].get("missingData") or []
                                for m in missing:
                                    degraded_reasons.append(str(m))
                        logger.info(f"[THINKING] SSE node_update: id={node_id} status={node_status} ok={node_ok} duration={node_duration}ms")
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
                return
            if degraded_reasons:
                # Dedupe reasons
                unique_reasons = list(dict.fromkeys(degraded_reasons))
                yield SSEEvent(event="degraded", data={"ok": True, "reasons": unique_reasons})
            yield SSEEvent(
                event="workflow_complete",
                data={
                    "ok": True,
                    "degraded": bool(degraded_reasons),
                    "final_state": build_stream_final_state(streamed_state, streamed_outputs),
                },
            )
        except GraphInterrupt:
            yield SSEEvent(
                event="human_interrupt",
                data={"message": "Workflow paused for approval", "ok": True, "interrupted": True},
            )
        except Exception as e:
            logger.error(f"[THINKING] Workflow streaming failed: subject={subject} error={e}")
            yield SSEEvent(event="error", data={"ok": False, "error": str(e), "code": "WORKFLOW_ERROR"})

    async def stream_workflow_tokens(
        self,
        nodes: list[Node],
        edges: list[Edge],
        state: dict[str, Any],
        subject: str,
        require_approval: bool = False,
        thread_id: str | None = None,
    ) -> AsyncIterator[SSEEvent]:
        graph = self.build_graph(nodes, edges, require_approval=require_approval)
        tid = thread_id or str(uuid.uuid4())
        await self._persist_thread_layout(
            tid, require_approval=require_approval, nodes=nodes, edges=edges
        )
        config = {"configurable": {"thread_id": tid}}
        initial_state: WorkflowState = {
            "final_state": state,
            "trace": [],
            "node_outputs": {},
            "subject": subject,
            "require_approval": require_approval,
            "messages": [],
        }

        aggregate_node = next(
            (n for n in nodes if n.data.handler == "finance.stock_recommendation_aggregate"),
            None,
        )

        saw_interrupt = False
        streamed_state: dict[str, Any] = dict(state or {})
        streamed_outputs: dict[str, Any] = {}
        try:
            async for chunk in graph.astream(
                initial_state,
                config=config,
                stream_mode="updates",
                **self._graph_run_kwargs(),
            ):
                if not isinstance(chunk, dict):
                    continue
                for node_name, update in chunk.items():
                    if node_name == "__interrupt__":
                        saw_interrupt = True
                        yield SSEEvent(
                            event="human_interrupt",
                            data={"message": "Workflow paused for approval", "ok": True, "interrupted": True},
                        )
                        continue
                    if not isinstance(update, dict):
                        continue
                    if isinstance(update.get("final_state"), dict):
                        streamed_state = deep_merge(streamed_state, update["final_state"])
                    if isinstance(update.get("node_outputs"), dict):
                        streamed_outputs.update(update["node_outputs"])
                    trace_entry = (update.get("trace") or [{}])[0]

                    if aggregate_node and node_name == aggregate_node.id:
                        node_result = (update.get("node_outputs") or {}).get(aggregate_node.id)
                        if node_result and node_result.get("content"):
                            content = node_result["content"]
                            chunk_size = 50
                            for i in range(0, len(content), chunk_size):
                                yield SSEEvent(
                                    event="token",
                                    data={
                                        "nodeId": aggregate_node.id,
                                        "content": content[i:i + chunk_size],
                                    },
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
                    else:
                        node_id = trace_entry.get("nodeId", node_name)
                        node_status = trace_entry.get("status", "")
                        node_ok = trace_entry.get("ok", False)
                        node_duration = trace_entry.get("durationMs", 0)
                        logger.info(f"[THINKING] SSE token-stream node_update: id={node_id} status={node_status} ok={node_ok} duration={node_duration}ms")
                        yield SSEEvent(
                            event="node_update",
                            data={
                                "nodeId": node_id,
                                "nodeName": trace_entry.get("nodeName", node_name),
                                "handler": trace_entry.get("handler", ""),
                                "status": node_status,
                                "ok": node_ok,
                                "degraded": trace_entry.get("degraded", False),
                                "startedAt": trace_entry.get("startedAt", ""),
                                "completedAt": trace_entry.get("completedAt", ""),
                                "durationMs": node_duration,
                            },
                        )
                if saw_interrupt:
                    break

            if saw_interrupt:
                return
            yield SSEEvent(
                event="workflow_complete",
                data={"ok": True, "final_state": build_stream_final_state(streamed_state, streamed_outputs)},
            )
        except GraphInterrupt:
            yield SSEEvent(
                event="human_interrupt",
                data={"message": "Workflow paused for approval", "ok": True, "interrupted": True},
            )
        except Exception as e:
            logger.error(f"[THINKING] Workflow token streaming failed: subject={subject} error={e}")
            yield SSEEvent(event="error", data={"ok": False, "error": str(e)})

    async def resume_workflow(
        self,
        thread_id: str,
        resume_value: dict[str, Any] | None = None,
        nodes: list[Node] | None = None,
        edges: list[Edge] | None = None,
        require_approval: bool | None = None,
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
        config = {"configurable": {"thread_id": thread_id}}

        try:
            from langgraph.types import Command as LgCommand
            final_state = await graph.ainvoke(
                LgCommand(resume=resume_value),
                config=config,
                **self._graph_run_kwargs(),
            )
            return self._result_from_graph_output(final_state)
        except GraphInterrupt:
            logger.info("[THINKING] Workflow resume paused again: thread_id=%s", thread_id)
            return await self._result_from_interrupt(graph, config, {})
        except Exception as e:
            logger.error(f"[THINKING] Workflow resume failed: thread_id={thread_id} error={e}")
            return WorkflowResult(ok=False, error=str(e), final_state={})

    @staticmethod
    def topological_sort(nodes: list[Node], edges: list[Edge]) -> list[Node]:
        """Sort nodes in topological order using Kahn's algorithm."""
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

        return result
