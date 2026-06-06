"""LangGraph workflow engine."""

from __future__ import annotations

import logging
from collections import defaultdict
from datetime import datetime, timezone
from typing import Any, AsyncIterator

from langgraph.graph import StateGraph, START, END
from typing_extensions import TypedDict, Annotated

from ..models.workflow import Node, Edge, TraceEntry
from ..models.responses import NodeResult, WorkflowResult, SSEEvent
from .node_executor import NodeExecutor

logger = logging.getLogger(__name__)


def merge_dicts(current: dict, update: dict) -> dict:
    """Reducer: merge two dicts."""
    return {**current, **update}


def concat_lists(current: list, update: list) -> list:
    """Reducer: concatenate two lists."""
    return current + update


class WorkflowState(TypedDict):
    """Workflow execution state."""

    final_state: Annotated[dict[str, Any], merge_dicts]
    trace: Annotated[list[dict], concat_lists]
    node_outputs: Annotated[dict[str, Any], merge_dicts]
    subject: str


class WorkflowEngine:
    """LangGraph-based workflow orchestration engine."""

    def __init__(self, node_executor: NodeExecutor):
        self._node_executor = node_executor

    def build_graph(self, nodes: list[Node], edges: list[Edge]) -> Any:
        """Build and compile a LangGraph StateGraph."""
        graph = StateGraph(WorkflowState)

        # Build adjacency maps
        node_ids = {n.id for n in nodes}
        incoming: dict[str, list[str]] = defaultdict(list)
        outgoing: dict[str, list[str]] = defaultdict(list)

        for edge in edges:
            if edge.source in node_ids and edge.target in node_ids:
                outgoing[edge.source].append(edge.target)
                incoming[edge.target].append(edge.source)

        # Add node functions
        for node in nodes:
            graph.add_node(node.id, self._make_node_fn(node))

        # Connect START to source nodes (no incoming edges)
        for node in nodes:
            if not incoming[node.id]:
                graph.add_edge(START, node.id)

        # Connect inter-node edges
        for edge in edges:
            if edge.source in node_ids and edge.target in node_ids:
                graph.add_edge(edge.source, edge.target)

        # Connect sink nodes (no outgoing edges) to END
        for node in nodes:
            if not outgoing[node.id]:
                graph.add_edge(node.id, END)

        return graph.compile()

    def _make_node_fn(self, node: Node):
        """Create async function for a graph node."""

        async def node_fn(state: WorkflowState) -> dict:
            started_at = datetime.now(timezone.utc).isoformat()
            subject = state.get("subject", "Aegis Alpha workflow")

            try:
                result = await self._node_executor.execute(
                    node=node,
                    state=state.get("final_state", {}),
                    subject=subject,
                )
            except Exception as e:
                logger.error(f"Node {node.id} failed: {e}")
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
    ) -> WorkflowResult:
        """Execute workflow non-streaming."""
        graph = self.build_graph(nodes, edges)
        initial_state: WorkflowState = {
            "final_state": state,
            "trace": [],
            "node_outputs": {},
            "subject": subject,
        }

        try:
            final_state = await graph.ainvoke(initial_state)
            return WorkflowResult(
                ok=True,
                final_state=final_state.get("final_state", {}),
                trace=[TraceEntry(**t) for t in final_state.get("trace", [])],
                node_outputs=final_state.get("node_outputs", {}),
                state=final_state.get("final_state", {}),
            )
        except Exception as e:
            logger.error(f"Workflow execution failed: {e}")
            return WorkflowResult(ok=False, error=str(e), final_state=state)

    async def stream_workflow(
        self,
        nodes: list[Node],
        edges: list[Edge],
        state: dict[str, Any],
        subject: str,
    ) -> AsyncIterator[SSEEvent]:
        """Execute workflow with SSE streaming."""
        graph = self.build_graph(nodes, edges)
        initial_state: WorkflowState = {
            "final_state": state,
            "trace": [],
            "node_outputs": {},
            "subject": subject,
        }

        try:
            async for chunk in graph.astream(initial_state, stream_mode="updates"):
                for node_name, update in chunk.items():
                    trace_entry = (update.get("trace") or [{}])[0]
                    yield SSEEvent(
                        event="node_update",
                        data={
                            "nodeId": trace_entry.get("nodeId", node_name),
                            "nodeName": trace_entry.get("nodeName", node_name),
                            "handler": trace_entry.get("handler", ""),
                            "status": trace_entry.get("status", ""),
                            "ok": trace_entry.get("ok", False),
                            "degraded": trace_entry.get("degraded", False),
                            "startedAt": trace_entry.get("startedAt", ""),
                            "completedAt": trace_entry.get("completedAt", ""),
                            "durationMs": trace_entry.get("durationMs", 0),
                        },
                    )

            yield SSEEvent(event="workflow_complete", data={"ok": True})
        except Exception as e:
            logger.error(f"Workflow streaming failed: {e}")
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
