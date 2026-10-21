"""P1-16: workflow_complete carries a trimmed, size-capped final_state (shared SSE contract fixture)."""
import json
from pathlib import Path

import pytest

FIXTURE = (
    Path(__file__).resolve().parents[2]
    / "docs/superpowers/specs/help-agent-contracts/fixtures/stream_workflow_complete.json"
)


def _fixture():
    return json.loads(FIXTURE.read_text(encoding="utf-8"))


def test_fixture_final_state_within_cap():
    from app.core.workflow_engine import STREAM_FINAL_STATE_MAX_CHARS

    fx = _fixture()
    assert fx["maxFinalStateChars"] == STREAM_FINAL_STATE_MAX_CHARS
    complete = [e for e in fx["events"] if e["event"] == "workflow_complete"]
    assert len(complete) == 1
    fs = complete[0]["data"]["final_state"]
    assert set(fx["finalStateKeys"]) <= set(fs)
    assert len(json.dumps(fs, ensure_ascii=False)) <= fx["maxFinalStateChars"]


def test_build_stream_final_state_trims_and_caps():
    from app.core.workflow_engine import build_stream_final_state

    outputs = {
        f"n{i}": {
            "handler": "general.agent",
            "summary": "S" * 5000,
            "signals": [{"name": "x", "value": i}] * 20,
            "data": {"raw": "R" * 5000, "prompt": "secret prompt"},
        }
        for i in range(30)
    }
    outputs["aggregate"] = {
        "handler": "finance.stock_recommendation_aggregate",
        "summary": "HOLD",
        "data": {"recommendation": "HOLD", "confidence": 0.5, "missingData": [], "content": "C" * 9000},
    }
    fs = build_stream_final_state({"ticker": "AAPL", "memory": "M" * 9000}, outputs, max_chars=4000)
    text = json.dumps(fs, ensure_ascii=False)
    assert len(text) <= 4000
    assert fs["truncated"] is True
    assert fs["recommendation"]["recommendation"] == "HOLD"
    assert "secret prompt" not in text and "MMMM" not in text and "CCCC" not in text


def _engine_and_layout(mock_node_executor, sample_nodes, sample_edges):
    from langgraph.checkpoint.memory import MemorySaver

    from app.core.workflow_engine import WorkflowEngine

    WorkflowEngine._graph_cache.clear()
    return WorkflowEngine(mock_node_executor, checkpointer=MemorySaver()), sample_nodes, sample_edges


@pytest.mark.asyncio
@pytest.mark.parametrize("method", ["stream_workflow", "stream_workflow_tokens"])
async def test_stream_workflow_complete_has_capped_final_state(
    method, mock_node_executor, sample_nodes, sample_edges
):
    from app.core.workflow_engine import WorkflowEngine

    fx = _fixture()
    engine, nodes, edges = _engine_and_layout(mock_node_executor, sample_nodes, sample_edges)
    try:
        events = []
        async for ev in getattr(engine, method)(nodes, edges, {"ticker": "AAPL"}, "AAPL analysis"):
            events.append(ev)
        names = [e.event for e in events]
        assert names[-1] == "workflow_complete", names
        data = events[-1].data
        fs = data["final_state"]
        assert set(fx["finalStateKeys"]) <= set(fs)
        assert len(json.dumps(fs, ensure_ascii=False, default=str)) <= fx["maxFinalStateChars"]
        assert fs["ticker"] == "AAPL"
        for node in fs["nodes"].values():
            assert set(node) == set(fx["finalStateNodeKeys"])
        for ev in events:
            if ev.event == "node_update":
                assert set(ev.data) == set(fx["nodeUpdateKeys"])
    finally:
        WorkflowEngine._graph_cache.clear()
