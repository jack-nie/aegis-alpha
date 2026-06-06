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
from langgraph.types import interrupt

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

    @classmethod
    def _topology_hash(cls, nodes: list[Node], edges: list[Edge]) -> str:
        sorted_node_ids = sorted(n.id for n in nodes)
        sorted_edge_pairs = sorted((e.source, e.target) for e in edges)
        payload = json.dumps({"nodes": sorted_node_ids, "edges": sorted_edge_pairs}, sort_keys=True)
        return str(hash(payload))

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

        for edge in edges:
            if edge.source in node_ids and edge.target in node_ids:
                outgoing[edge.source].append(edge.target)
                incoming[edge.target].append(edge.source)

        for node in nodes:
            graph.add_node(node.id, self._make_node_fn(node))

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

        for edge in edges:
            if edge.source not in node_ids or edge.target not in node_ids:
                continue

            target = edge.target
            if require_approval and aggregate_node and edge.target == aggregate_node.id:
                target = "approval_gate"

            condition = getattr(edge, "condition", None)
            if condition:
                def condition_fn(state, cond=condition, tgt=target):
                    field = cond.get("field", "")
                    expected = cond.get("value", "")
                    actual = state.get("final_state", {}).get(field, "")
                    return tgt if str(actual) == str(expected) else "default"

                graph.add_conditional_edges(
                    edge.source,
                    condition_fn,
                    {target: target, "default": target},
                )
            else:
                graph.add_edge(edge.source, target)

        if require_approval and aggregate_node:
            graph.add_edge("approval_gate", aggregate_node.id)

        for node in nodes:
            if not outgoing[node.id]:
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

        async def node_fn(state: WorkflowState) -> dict:
            started_at = datetime.now(timezone.utc).isoformat()
            subject = state.get("subject", "Aegis Alpha workflow")
            node_label = node.data.label or node.data.title or node.id
            logger.info(f"[THINKING] Workflow node starting: id={node.id} handler={node.data.handler} label={node_label} subject={subject}")

            try:
                result = await self._node_executor.execute(
                    node=node,
                    state=state.get("final_state", {}),
                    subject=subject,
                )
            except Exception as e:
                logger.error(f"[THINKING] Workflow node exception: id={node.id} handler={node.data.handler} label={node_label} error={e}")
                result = NodeResult(
                    ok=False,
                    status="error",
                    handler="logic",
                    node_id=node.id,
                    node_name=node.id,
                    subject=subject,
                    summary=str(e),
                    duration_ms=0,
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

            state_update = {node.id: result.model_dump()}
            if result.handler:
                state_update[result.handler] = result.model_dump()

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
            async for chunk in graph.astream(initial_state, config=config, stream_mode="updates"):
                for node_name, update in chunk.items():
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
                        logger.info(f"[THINKING] SSE node_update: id={node_id} status={node_status} ok={node_ok} duration={node_duration}ms")
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
            logger.error(f"[THINKING] Workflow streaming failed: subject={subject} error={e}")
            yield SSEEvent(event="error", data={"ok": False, "error": str(e)})

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
