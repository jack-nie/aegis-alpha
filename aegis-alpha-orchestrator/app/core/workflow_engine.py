"""LangGraph workflow engine."""

from __future__ import annotations

import json
import logging
import uuid
from collections import defaultdict, OrderedDict
from datetime import datetime, timezone
from typing import Annotated, Any, AsyncIterator
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
from langgraph.types import interrupt, Command

from ..models.workflow import Node, Edge, TraceEntry
from ..models.responses import NodeResult, WorkflowResult, SSEEvent
from .memory_store import MemoryStoreManager
from .node_executor import NodeExecutor

logger = logging.getLogger(__name__)

_GRAPH_CACHE_MAX = 100


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


class WorkflowEngine:
    """LangGraph-based workflow orchestration engine."""

    _graph_cache: OrderedDict[str, Any] = OrderedDict()

    def __init__(
        self,
        node_executor: NodeExecutor,
        tools: list | None = None,
        store: BaseStore | None = None,
        checkpointer: Any | None = None,
    ):
        self._node_executor = node_executor
        self._checkpointer = checkpointer
        self._tools = tools or []
        self._store = store
        self._tool_node = ToolNode(self._tools) if self._tools else None
        # Persist layout/flags per thread so resume rebuilds the same graph.
        self._thread_meta: dict[str, dict[str, Any]] = {}

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

    def build_graph(self, nodes: list[Node], edges: list[Edge], require_approval: bool = False) -> Any:
        """Build and compile a LangGraph StateGraph with caching."""
        topo_hash = self._topology_hash(nodes, edges)
        cache_key = f"{topo_hash}:{require_approval}:{bool(self._tools)}"
        cached = self._get_cached_graph(cache_key)
        if cached is not None:
            return cached

        graph = StateGraph(WorkflowState)

        node_ids = {n.id for n in nodes}
        incoming: dict[str, list[str]] = defaultdict(list)
        outgoing: dict[str, list[str]] = defaultdict(list)
        agent_nodes: list[str] = []

        for edge in edges:
            if edge.source in node_ids and edge.target in node_ids:
                outgoing[edge.source].append(edge.target)
                incoming[edge.target].append(edge.source)

        for node in nodes:
            graph.add_node(node.id, self._make_node_fn(node))
            if node.data.handler == "general.agent" and self._tool_node:
                agent_nodes.append(node.id)

        if self._tool_node:
            graph.add_node("tools", self._tool_node)

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
                if response and not response.get("approved"):
                    return {"final_state": {"approval_status": "rejected"}}
                return {"final_state": {"approval_status": "approved"}}

            graph.add_node("approval_gate", approval_gate)

        for node in nodes:
            if not incoming[node.id]:
                graph.add_edge(START, node.id)

        if agent_nodes and self._tool_node:
            for agent_id in agent_nodes:
                def route_to_tools(state: WorkflowState, _agent_id: str = agent_id) -> str:
                    messages = state.get("messages", [])
                    last_msg = messages[-1] if messages else None
                    if last_msg and hasattr(last_msg, "tool_calls") and last_msg.tool_calls:
                        return "tools"
                    return _agent_id + "__next"

                next_nodes = [e.target for e in edges if e.source == agent_id and e.target in node_ids]
                if not next_nodes:
                    next_nodes = [END]
                # Always register __next so route_to_tools return value is valid
                # whether there is one or many successors (next_nodes defaults to [END]).
                routing_map: dict[str, Any] = {"tools": "tools"}
                for n in next_nodes:
                    routing_map[n] = n
                routing_map[agent_id + "__next"] = next_nodes[0]
                graph.add_conditional_edges(agent_id, route_to_tools, routing_map)

            # Route tools back to the agent that last ran (final_state.last_agent_id).
            def route_after_tools(state: WorkflowState, _agents: list[str] = list(agent_nodes)) -> str:
                last = (state.get("final_state") or {}).get("last_agent_id")
                if last in _agents:
                    return last
                return _agents[0]

            tools_routing = {agent_id: agent_id for agent_id in agent_nodes}
            graph.add_conditional_edges("tools", route_after_tools, tools_routing)

        for edge in edges:
            if edge.source not in node_ids or edge.target not in node_ids:
                continue
            if edge.source in agent_nodes and self._tool_node:
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
            graph.add_edge("approval_gate", aggregate_node.id)

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
        is_agent_with_tools = handler == "general.agent" and bool(self._tools)

        async def node_fn(state: WorkflowState, *, store: BaseStore | None = None) -> dict:
            started_at = datetime.now(timezone.utc).isoformat()
            subject = state.get("subject", "Aegis Alpha workflow")
            node_label = node.data.label or node.data.title or node.id

            if store and handler != "start" and handler != "end":
                try:
                    ticker = state.get("final_state", {}).get("ticker", "") or state.get("final_state", {}).get("symbol", "")
                    cache_key = f"{handler}:v2"
                    if ticker:
                        cached = await store.aget(("ticker", ticker), cache_key)
                        if cached and isinstance(cached, dict) and cached.get("value"):
                            ttl_remaining = cached.get("_ttl_remaining") or cached.get("ttl_remaining")
                            if ttl_remaining is None or ttl_remaining > 60:
                                logger.info(f"[THINKING] Cache hit for ticker={ticker} handler={handler}, skipping LLM")
                                cached_data = cached.get("value", cached)
                                if isinstance(cached_data, dict):
                                    result = NodeResult(**{k: v for k, v in cached_data.items() if k in NodeResult.model_fields})
                                else:
                                    result = NodeResult(
                                        ok=True, status="cached", handler=handler,
                                        node_id=node.id, node_name=node_label,
                                        subject=subject, summary=str(cached_data)[:200],
                                        confidence=0.8, provider="cache", model="cache",
                                    )
                                trace_entry = {
                                    "nodeId": result.node_id, "nodeName": result.node_name,
                                    "handler": result.handler, "status": result.status,
                                    "ok": result.ok, "degraded": True,
                                    "startedAt": started_at,
                                    "completedAt": datetime.now(timezone.utc).isoformat(),
                                    "durationMs": 0,
                                }
                                state_update = {node.id: result.model_dump()}
                                if result.handler:
                                    state_update[result.handler] = result.model_dump()
                                return {
                                    "final_state": state_update,
                                    "trace": [trace_entry],
                                    "node_outputs": {node.id: result.model_dump()},
                                }
                except Exception as cache_err:
                    logger.warning(f"[THINKING] Store read error for node {node.id}: {cache_err}")

            logger.info(f"[THINKING] Workflow node starting: id={node.id} handler={handler} label={node_label} subject={subject}")

            try:
                result = await self._node_executor.execute(
                    node=node,
                    state=state.get("final_state", {}),
                    subject=subject,
                )
            except Exception as e:
                logger.error(f"[THINKING] Workflow node exception: id={node.id} handler={handler} label={node_label} error={e}")
                result = NodeResult(
                    ok=False, status="error", handler="logic",
                    node_id=node.id, node_name=node_label,
                    subject=subject, summary=str(e), duration_ms=0,
                )

            trace_entry = {
                "nodeId": result.node_id,
                "nodeName": result.node_name,
                "handler": result.handler,
                "status": result.status,
                "ok": result.ok,
                "degraded": result.degraded,
                "startedAt": started_at,
                "completedAt": datetime.now(timezone.utc).isoformat(),
                "durationMs": result.duration_ms,
            }
            logger.info(f"[THINKING] Workflow node completed: id={node.id} handler={result.handler} label={node_label} status={result.status} ok={result.ok} duration={result.duration_ms}ms")

            if store and handler != "start" and handler != "end":
                try:
                    ticker = state.get("final_state", {}).get("ticker", "") or state.get("final_state", {}).get("symbol", "")
                    cache_key = f"{handler}:v2"
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
            if is_agent_with_tools:
                # Attribution for tools → agent return path
                state_update["last_agent_id"] = node.id

            return {
                "final_state": state_update,
                "trace": [trace_entry],
                "node_outputs": {node.id: result.model_dump()},
            }

        return node_fn

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
        self._remember_thread(tid, require_approval=require_approval, nodes=nodes, edges=edges)
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
            final_state = await graph.ainvoke(initial_state, config=config)
            node_ids = [n.id for n in nodes]
            logger.info(f"[THINKING] Workflow execution completed: subject={subject} nodes={node_ids} ok=True")
            return WorkflowResult(
                ok=True,
                final_state=final_state.get("final_state", {}),
                trace=[TraceEntry(**t) for t in final_state.get("trace", [])],
                node_outputs=final_state.get("node_outputs", {}),
                state=final_state.get("final_state", {}),
            )
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
        self._remember_thread(tid, require_approval=require_approval, nodes=nodes, edges=edges)
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
        try:
            async for chunk in graph.astream(initial_state, config=config, stream_mode="updates"):
                for node_name, update in chunk.items():
                    if node_name == "__interrupt__":
                        yield SSEEvent(
                            event="human_interrupt",
                            data={"message": "Workflow paused for approval", "ok": True},
                        )
                        continue
                    trace_entry = (update.get("trace") or [{}])[0]
                    if node_name == "tools":
                        yield SSEEvent(
                            event="tool_call",
                            data={
                                "nodeId": "tools",
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

            if degraded_reasons:
                # Dedupe reasons
                unique_reasons = list(dict.fromkeys(degraded_reasons))
                yield SSEEvent(event="degraded", data={"ok": True, "reasons": unique_reasons})
            yield SSEEvent(
                event="workflow_complete",
                data={"ok": True, "degraded": bool(degraded_reasons)},
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
        self._remember_thread(tid, require_approval=require_approval, nodes=nodes, edges=edges)
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

        try:
            async for chunk in graph.astream(initial_state, config=config, stream_mode="updates"):
                for node_name, update in chunk.items():
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

                    if node_name == "tools":
                        yield SSEEvent(
                            event="tool_call",
                            data={
                                "nodeId": "tools",
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

            yield SSEEvent(event="workflow_complete", data={"ok": True})
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
        meta = self._thread_meta.get(thread_id, {})
        if nodes is None:
            nodes = meta.get("nodes")
        if edges is None:
            edges = meta.get("edges")
        if nodes is None or edges is None:
            return WorkflowResult(
                ok=False,
                error="Cannot resume: original workflow layout not available. Provide nodes and edges.",
            )

        if require_approval is None:
            if "require_approval" in meta:
                require_approval = bool(meta["require_approval"])
            else:
                # Resume targets approval interrupts; keep approval_gate in the graph.
                require_approval = True

        graph = self.build_graph(nodes, edges, require_approval=require_approval)
        config = {"configurable": {"thread_id": thread_id}}

        try:
            from langgraph.types import Command as LgCommand
            final_state = await graph.ainvoke(
                LgCommand(resume=resume_value or {"approved": True}),
                config=config,
            )
            return WorkflowResult(
                ok=True,
                final_state=final_state.get("final_state", {}),
                trace=[TraceEntry(**t) for t in final_state.get("trace", [])],
                node_outputs=final_state.get("node_outputs", {}),
                state=final_state.get("final_state", {}),
            )
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
