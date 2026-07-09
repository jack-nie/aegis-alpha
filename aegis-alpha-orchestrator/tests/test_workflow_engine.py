"""Tests for workflow engine."""

import pytest
from unittest.mock import AsyncMock, MagicMock, patch

from app.models.workflow import Node, NodeData, Edge


def test_topology_hash(mock_node_executor):
    from app.core.workflow_engine import WorkflowEngine
    engine = WorkflowEngine(mock_node_executor)
    nodes = [
        Node(id="start", data=NodeData(handler="start")),
        Node(id="end", data=NodeData(handler="end")),
    ]
    edges = [Edge(source="start", target="end")]
    h1 = engine._topology_hash(nodes, edges)
    h2 = engine._topology_hash(nodes, edges)
    assert h1 == h2


def test_topology_hash_different(mock_node_executor):
    from app.core.workflow_engine import WorkflowEngine
    engine = WorkflowEngine(mock_node_executor)
    nodes1 = [Node(id="start", data=NodeData(handler="start"))]
    nodes2 = [Node(id="start", data=NodeData(handler="start")), Node(id="end", data=NodeData(handler="end"))]
    h1 = engine._topology_hash(nodes1, [])
    h2 = engine._topology_hash(nodes2, [])
    assert h1 != h2


def test_topology_hash_changes_when_prompt_changes(mock_node_executor):
    from app.core.workflow_engine import WorkflowEngine
    engine = WorkflowEngine(mock_node_executor)
    nodes_a = [Node(id="agent", data=NodeData(handler="general.agent", prompt="analyze A"))]
    nodes_b = [Node(id="agent", data=NodeData(handler="general.agent", prompt="analyze B"))]
    edges = []
    assert engine._topology_hash(nodes_a, edges) != engine._topology_hash(nodes_b, edges)


def test_topology_hash_changes_when_handler_changes(mock_node_executor):
    from app.core.workflow_engine import WorkflowEngine
    engine = WorkflowEngine(mock_node_executor)
    nodes_a = [Node(id="n1", data=NodeData(handler="finance.market_analysis"))]
    nodes_b = [Node(id="n1", data=NodeData(handler="finance.technical_analysis"))]
    edges = []
    assert engine._topology_hash(nodes_a, edges) != engine._topology_hash(nodes_b, edges)


def test_topology_hash_changes_when_agent_id_changes(mock_node_executor):
    from app.core.workflow_engine import WorkflowEngine
    engine = WorkflowEngine(mock_node_executor)
    nodes_a = [Node(id="n1", data=NodeData(handler="general.agent", agentId="agent-a"))]
    nodes_b = [Node(id="n1", data=NodeData(handler="general.agent", agentId="agent-b"))]
    assert engine._topology_hash(nodes_a, []) != engine._topology_hash(nodes_b, [])


def test_graph_cache_hit(mock_node_executor):
    from app.core.workflow_engine import WorkflowEngine
    engine = WorkflowEngine(mock_node_executor)
    nodes = [
        Node(id="start", data=NodeData(handler="start", nodeType="start")),
        Node(id="end", data=NodeData(handler="end", nodeType="end")),
    ]
    edges = [Edge(source="start", target="end")]
    g1 = engine.build_graph(nodes, edges)
    g2 = engine.build_graph(nodes, edges)
    assert g1 is g2


def test_graph_cache_miss_on_prompt_change(mock_node_executor):
    from app.core.workflow_engine import WorkflowEngine
    engine = WorkflowEngine(mock_node_executor)
    nodes_a = [
        Node(id="start", data=NodeData(handler="start", nodeType="start")),
        Node(id="agent", data=NodeData(handler="general.agent", prompt="v1")),
    ]
    nodes_b = [
        Node(id="start", data=NodeData(handler="start", nodeType="start")),
        Node(id="agent", data=NodeData(handler="general.agent", prompt="v2")),
    ]
    edges = [Edge(source="start", target="agent")]
    g1 = engine.build_graph(nodes_a, edges)
    g2 = engine.build_graph(nodes_b, edges)
    assert g1 is not g2


def test_build_graph_linear(mock_node_executor):
    from app.core.workflow_engine import WorkflowEngine
    engine = WorkflowEngine(mock_node_executor)
    nodes = [
        Node(id="start", data=NodeData(handler="start", nodeType="start")),
        Node(id="end", data=NodeData(handler="end", nodeType="end")),
    ]
    edges = [Edge(source="start", target="end")]
    graph = engine.build_graph(nodes, edges)
    assert graph is not None


def test_build_graph_with_condition(mock_node_executor):
    from app.core.workflow_engine import WorkflowEngine
    engine = WorkflowEngine(mock_node_executor)
    nodes = [
        Node(id="start", data=NodeData(handler="start", nodeType="start")),
        Node(id="market", data=NodeData(handler="finance.market_analysis")),
        Node(id="end", data=NodeData(handler="end", nodeType="end")),
    ]
    edges = [
        Edge(source="start", target="market", condition={"field": "trend", "value": "bullish"}),
        Edge(source="market", target="end"),
    ]
    graph = engine.build_graph(nodes, edges)
    assert graph is not None


def test_build_graph_with_approval(mock_node_executor):
    from app.core.workflow_engine import WorkflowEngine
    engine = WorkflowEngine(mock_node_executor)
    nodes = [
        Node(id="market", data=NodeData(handler="finance.market_analysis")),
        Node(id="aggregate", data=NodeData(handler="finance.stock_recommendation_aggregate")),
    ]
    edges = [Edge(source="market", target="aggregate")]
    graph = engine.build_graph(nodes, edges, require_approval=True)
    assert graph is not None


def test_agent_single_successor_routing_map_includes_next(mock_node_executor):
    """Single-successor agent must register agent__next in the conditional path map."""
    from app.core.workflow_engine import WorkflowEngine
    from langgraph.graph import StateGraph
    from langchain_core.tools import tool

    @tool
    def ping() -> str:
        """Ping tool for routing tests."""
        return "pong"

    # Avoid class-level graph cache hiding the build path under test.
    WorkflowEngine._graph_cache.clear()

    captured = []
    original = StateGraph.add_conditional_edges

    def wrapper(self, source, path, path_map=None, **kwargs):
        if path_map is not None:
            captured.append({"source": source, "path_map": dict(path_map)})
            return original(self, source, path, path_map, **kwargs)
        return original(self, source, path, **kwargs)

    engine = WorkflowEngine(mock_node_executor, tools=[ping])
    nodes = [
        Node(id="agent", data=NodeData(handler="general.agent", nodeType="agent")),
        Node(id="next_node", data=NodeData(handler="end", nodeType="end")),
    ]
    edges = [Edge(source="agent", target="next_node")]

    with patch.object(StateGraph, "add_conditional_edges", wrapper):
        graph = engine.build_graph(nodes, edges)
        assert graph is not None

    agent_entries = [c for c in captured if c["source"] == "agent"]
    assert len(agent_entries) == 1
    path_map = agent_entries[0]["path_map"]
    assert path_map.get("tools") == "tools"
    assert path_map.get("agent__next") == "next_node"


def test_topological_sort(mock_node_executor):
    from app.core.workflow_engine import WorkflowEngine
    engine = WorkflowEngine(mock_node_executor)
    nodes = [
        Node(id="start", data=NodeData(handler="start")),
        Node(id="market", data=NodeData(handler="finance.market_analysis")),
        Node(id="end", data=NodeData(handler="end")),
    ]
    edges = [
        Edge(source="start", target="market"),
        Edge(source="market", target="end"),
    ]
    sorted_nodes = WorkflowEngine.topological_sort(nodes, edges)
    ids = [n.id for n in sorted_nodes]
    assert ids.index("start") < ids.index("market")
    assert ids.index("market") < ids.index("end")


def test_deep_merge():
    from app.core.workflow_engine import deep_merge
    result = deep_merge({"a": 1}, {"b": 2})
    assert result == {"a": 1, "b": 2}


def test_deep_merge_nested():
    from app.core.workflow_engine import deep_merge
    result = deep_merge({"a": {"x": 1}}, {"a": {"y": 2}})
    assert result == {"a": {"x": 1, "y": 2}}


def test_deep_merge_overwrite():
    from app.core.workflow_engine import deep_merge
    result = deep_merge({"a": 1}, {"a": 2})
    assert result == {"a": 2}


def test_concat_lists():
    from app.core.workflow_engine import concat_lists
    assert concat_lists([1], [2]) == [1, 2]
    assert concat_lists([], [1]) == [1]


@pytest.mark.asyncio
async def test_resume_preserves_require_approval_from_thread_meta(mock_node_executor):
    """Resume must rebuild the graph with the require_approval used at start."""
    from app.core.workflow_engine import WorkflowEngine

    engine = WorkflowEngine(mock_node_executor)
    nodes = [
        Node(id="market", data=NodeData(handler="finance.market_analysis")),
        Node(id="aggregate", data=NodeData(handler="finance.stock_recommendation_aggregate")),
    ]
    edges = [Edge(source="market", target="aggregate")]
    thread_id = "thread-approval-1"
    engine._remember_thread(thread_id, require_approval=True, nodes=nodes, edges=edges)

    mock_graph = MagicMock()
    mock_graph.ainvoke = AsyncMock(
        return_value={
            "final_state": {"approval_status": "approved"},
            "trace": [],
            "node_outputs": {},
        }
    )

    with patch.object(engine, "build_graph", return_value=mock_graph) as mock_build:
        result = await engine.resume_workflow(
            thread_id=thread_id,
            resume_value={"approved": True},
        )

    assert result.ok is True
    mock_build.assert_called_once()
    call_args = mock_build.call_args
    assert call_args.kwargs.get("require_approval") is True or (
        len(call_args.args) >= 3 and call_args.args[2] is True
    )


@pytest.mark.asyncio
async def test_resume_explicit_require_approval_overrides_meta(mock_node_executor):
    from app.core.workflow_engine import WorkflowEngine

    engine = WorkflowEngine(mock_node_executor)
    nodes = [
        Node(id="market", data=NodeData(handler="finance.market_analysis")),
        Node(id="aggregate", data=NodeData(handler="finance.stock_recommendation_aggregate")),
    ]
    edges = [Edge(source="market", target="aggregate")]
    thread_id = "thread-approval-2"
    engine._remember_thread(thread_id, require_approval=False, nodes=nodes, edges=edges)

    mock_graph = MagicMock()
    mock_graph.ainvoke = AsyncMock(
        return_value={"final_state": {}, "trace": [], "node_outputs": {}}
    )

    with patch.object(engine, "build_graph", return_value=mock_graph) as mock_build:
        result = await engine.resume_workflow(
            thread_id=thread_id,
            resume_value={"approved": True},
            require_approval=True,
        )

    assert result.ok is True
    call_args = mock_build.call_args
    assert call_args.kwargs.get("require_approval") is True or (
        len(call_args.args) >= 3 and call_args.args[2] is True
    )


@pytest.mark.asyncio
async def test_resume_defaults_require_approval_true_without_meta(mock_node_executor):
    from app.core.workflow_engine import WorkflowEngine

    engine = WorkflowEngine(mock_node_executor)
    nodes = [
        Node(id="market", data=NodeData(handler="finance.market_analysis")),
        Node(id="aggregate", data=NodeData(handler="finance.stock_recommendation_aggregate")),
    ]
    edges = [Edge(source="market", target="aggregate")]

    mock_graph = MagicMock()
    mock_graph.ainvoke = AsyncMock(
        return_value={"final_state": {}, "trace": [], "node_outputs": {}}
    )

    with patch.object(engine, "build_graph", return_value=mock_graph) as mock_build:
        result = await engine.resume_workflow(
            thread_id="unknown-thread",
            resume_value={"approved": True},
            nodes=nodes,
            edges=edges,
        )

    assert result.ok is True
    call_args = mock_build.call_args
    assert call_args.kwargs.get("require_approval") is True or (
        len(call_args.args) >= 3 and call_args.args[2] is True
    )