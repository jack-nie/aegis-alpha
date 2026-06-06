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