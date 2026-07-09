"""Tests for published research multi-specialist layout builders."""

from __future__ import annotations

import pytest

from app.core.node_executor import FALLBACK_HANDLERS, HANDLERS
from app.core.research_graph import (
    build_earnings_reaction_layout,
    build_single_name_research_layout,
    build_watchlist_digest_layout,
)
from app.models.workflow import Edge, Node


KNOWN_HANDLERS = HANDLERS | FALLBACK_HANDLERS

LAYOUT_BUILDERS = [
    ("single_name", lambda: build_single_name_research_layout()),
    ("single_name_portfolio", lambda: build_single_name_research_layout(include_portfolio=True)),
    ("earnings", lambda: build_earnings_reaction_layout()),
    ("watchlist", lambda: build_watchlist_digest_layout()),
]


def _node_ids(layout: dict) -> list[str]:
    return [n["id"] for n in layout["nodes"]]


def _handlers(layout: dict) -> list[str]:
    return [n["data"]["handler"] for n in layout["nodes"]]


@pytest.mark.parametrize("name,builder", LAYOUT_BUILDERS)
def test_layout_has_unique_node_ids(name, builder):
    layout = builder()
    ids = _node_ids(layout)
    assert ids, f"{name}: expected nodes"
    assert len(ids) == len(set(ids)), f"{name}: duplicate node ids: {ids}"


@pytest.mark.parametrize("name,builder", LAYOUT_BUILDERS)
def test_layout_handlers_are_registered(name, builder):
    layout = builder()
    for node in layout["nodes"]:
        handler = node["data"]["handler"]
        assert handler in KNOWN_HANDLERS, (
            f"{name}: unknown handler {handler!r} on node {node['id']!r}"
        )


@pytest.mark.parametrize("name,builder", LAYOUT_BUILDERS)
def test_layout_edges_reference_known_node_ids(name, builder):
    layout = builder()
    known = set(_node_ids(layout))
    assert layout["edges"], f"{name}: expected edges"
    for edge in layout["edges"]:
        assert edge["source"] in known, f"{name}: edge source {edge['source']!r} unknown"
        assert edge["target"] in known, f"{name}: edge target {edge['target']!r} unknown"


@pytest.mark.parametrize("name,builder", LAYOUT_BUILDERS)
def test_layout_compatible_with_node_edge_models(name, builder):
    layout = builder()
    nodes = [Node.model_validate(n) for n in layout["nodes"]]
    edges = [Edge.model_validate(e) for e in layout["edges"]]
    assert len(nodes) == len(layout["nodes"])
    assert len(edges) == len(layout["edges"])
    assert all(n.data.handler for n in nodes)


def test_single_name_contains_aggregate():
    layout = build_single_name_research_layout()
    assert "finance.stock_recommendation_aggregate" in _handlers(layout)
    assert layout["metadata"]["pattern"] == "fan_in_aggregate"
    assert layout["workflowKey"] == "stock_analysis_v2"
    assert layout["engine"] == "langgraph"
    assert "fundamentals" in layout["metadata"]["specialists"]
    assert "critique_aggregate" in layout["metadata"]["specialists"]


def test_single_name_portfolio_inserts_portfolio_specialist():
    layout = build_single_name_research_layout(include_portfolio=True)
    ids = _node_ids(layout)
    assert "portfolio_context" in ids
    assert "portfolio_context" in layout["metadata"]["specialists"]
    handlers_by_id = {n["id"]: n["data"]["handler"] for n in layout["nodes"]}
    assert handlers_by_id["portfolio_context"] == "general.agent"
    # portfolio sits before aggregate in the sequential chain
    assert ids.index("portfolio_context") < ids.index("aggregate")


def test_single_name_require_approval_metadata():
    layout = build_single_name_research_layout(require_approval=True)
    assert layout["metadata"]["requireApproval"] is True


def test_single_name_subject_placeholder_in_prompts():
    layout = build_single_name_research_layout(subject_placeholder="{{ticker}}")
    fund = next(n for n in layout["nodes"] if n["id"] == "fundamentals")
    assert "{{ticker}}" in fund["data"]["prompt"]
    assert "{subject}" not in fund["data"]["prompt"]


def test_earnings_contains_aggregate():
    layout = build_earnings_reaction_layout()
    assert "finance.stock_recommendation_aggregate" in _handlers(layout)
    assert layout["metadata"]["pattern"] == "earnings_reaction"
    assert layout["workflowKey"] == "earnings_reaction"
    expected = {
        "finance.market_analysis",
        "finance.financial_interpretation",
        "finance.industry_news",
        "finance.stock_recommendation_aggregate",
    }
    assert expected.issubset(set(_handlers(layout)))


def test_watchlist_digest_layout():
    layout = build_watchlist_digest_layout()
    assert layout["workflowKey"] == "watchlist_digest"
    assert layout["metadata"]["pattern"] == "watchlist_digest"
    handlers = set(_handlers(layout))
    assert "finance.market_analysis" in handlers
    assert "finance.industry_news" in handlers
    assert "finance.risk_assessment" in handlers
    # lightweight digest — no full recommendation aggregate required
    assert "finance.stock_recommendation_aggregate" not in handlers


def test_sequential_edges_form_single_path():
    """Each non-end node has exactly one outgoing edge; single entry from start."""
    layout = build_single_name_research_layout()
    ids = set(_node_ids(layout))
    outgoing = {}
    incoming = {}
    for edge in layout["edges"]:
        outgoing.setdefault(edge["source"], []).append(edge["target"])
        incoming.setdefault(edge["target"], []).append(edge["source"])
    assert "start" in ids and "end" in ids
    assert "start" not in incoming
    assert "end" not in outgoing
    for node_id in ids - {"end"}:
        assert len(outgoing.get(node_id, [])) == 1, f"{node_id} should have one out-edge"
    for node_id in ids - {"start"}:
        assert len(incoming.get(node_id, [])) == 1, f"{node_id} should have one in-edge"
