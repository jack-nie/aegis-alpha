"""P4-10 (R11): streaming resume of an approval-gate interrupt."""
from unittest.mock import AsyncMock

import pytest
from langgraph.checkpoint.memory import MemorySaver

from app.models.responses import NodeResult
from app.models.workflow import Edge, Node, NodeData

AGG = "finance.stock_recommendation_aggregate"


def _layout():
    nodes = [
        Node(id="start", data=NodeData(handler="start", nodeType="start")),
        Node(id="market", data=NodeData(handler="finance.market_analysis", label="Market")),
        Node(id="aggregate", data=NodeData(handler=AGG, label="Report")),
        Node(id="end", data=NodeData(handler="end", nodeType="end")),
    ]
    edges = [Edge(source="start", target="market"), Edge(source="market", target="aggregate"),
             Edge(source="aggregate", target="end")]
    return nodes, edges


def _result(node_id: str, handler: str) -> NodeResult:
    gated = handler == AGG
    return NodeResult(
        ok=True, status="completed", handler=handler, node_id=node_id, node_name=node_id, subject="s",
        summary="[HOLD] report" if gated else "market ok", content="Full report" if gated else "",
        data={"recommendation": "HOLD", "policy": {"forcedInsufficient": False}, "critique": {"ok": True}}
        if gated else {},
        duration_ms=1,
    )


@pytest.mark.asyncio
async def test_resume_stream_emits_v2_events_after_approval(mock_node_executor):
    from app.core.workflow_engine import WorkflowEngine

    async def fake_execute(node, *args, **kwargs):
        return _result(node.id, node.data.handler)

    mock_node_executor.execute = AsyncMock(side_effect=fake_execute)
    nodes, edges = _layout()
    WorkflowEngine._graph_cache.clear()
    try:
        engine = WorkflowEngine(mock_node_executor, checkpointer=MemorySaver())
        first = [e async for e in engine.stream_workflow(
            nodes, edges, {"ticker": "AAPL"}, "s", require_approval=True, thread_id="t-resume")]
        assert first[-1].event == "human_interrupt"
        assert not any(e.event == "message_delta" for e in first)  # aggregate never ran before approval

        resumed = [e async for e in engine.resume_workflow_stream("t-resume", {"approved": True})]
    finally:
        WorkflowEngine._graph_cache.clear()
    names = [e.event for e in resumed]
    assert names[-1] == "workflow_complete", names
    assert "node_start" in names
    final = [e.data for e in resumed if e.event == "message_delta" and e.data.get("final")]
    assert final == [{"nodeId": "aggregate", "delta": "Full report", "final": True, "gatePassed": True}]
    assert names.index("message_delta") < names.index("workflow_complete")
    assert any(e.event == "node_update" and e.data["nodeId"] == "aggregate" for e in resumed)
    # D10b: per-node results of the resumed nodes (not the trimmed final_state summaries).
    complete = resumed[-1].data
    assert "aggregate" in complete["node_outputs"] and "market" not in complete["node_outputs"]
    assert complete["node_outputs"]["aggregate"]["content"] == "Full report"
    assert complete["node_outputs"]["aggregate"]["data"]["recommendation"] == "HOLD"


def test_bounded_node_outputs_caps_and_flags():
    from app.core.workflow_engine import bounded_node_outputs

    big = {"handler": "h", "ok": True, "summary": "s", "content": "x" * 5000}
    small = {"handler": "h", "ok": True, "summary": "s", "content": "y"}
    out = bounded_node_outputs({"a": small, "b": big, "c": "not-a-dict"}, per_node_max=1000)
    assert out["a"] == small
    assert out["b"]["truncated"] is True and "content" not in out["b"]
    assert "c" not in out
    capped = bounded_node_outputs({"a": small, "b": small}, total_max=len('{"handler": "h", "ok": true, "summary": "s", "content": "y"}') + 1)
    assert "truncated" not in capped["a"] and capped["b"]["truncated"] is True


@pytest.mark.asyncio
async def test_resume_stream_rejects_missing_decision(mock_node_executor):
    from app.core.workflow_engine import WorkflowEngine

    engine = WorkflowEngine(mock_node_executor, checkpointer=MemorySaver())
    events = [e async for e in engine.resume_workflow_stream("t-x", {"approve": "yes"})]
    assert [e.event for e in events] == ["error"]
    assert events[0].data["code"] == "RESUME_INVALID"


@pytest.mark.asyncio
async def test_resume_stream_route_applies_delegated_token(monkeypatch):
    from app.core import tools as tools_module
    from app.models.responses import SSEEvent
    from app.routers import workflow as workflow_router

    seen: list = []

    async def fake_stream(thread_id, resume_value, owner=None):
        seen.append((thread_id, resume_value, tools_module.authorization_override.get()))
        yield SSEEvent(event="workflow_complete", data={"ok": True})

    monkeypatch.setattr(workflow_router.workflow_engine, "resume_workflow_stream", fake_stream)
    body = workflow_router.ResumeRequest.model_validate(
        {"threadId": "run-1", "resumeValue": {"approved": True}, "delegatedToken": "resume-token"}
    )
    response = await workflow_router.resume_workflow_stream(body)
    chunks = [c async for c in response.body_iterator]
    assert chunks == ['event: workflow_complete\ndata: {"ok": true}\n\n']
    assert seen == [("run-1", {"approved": True}, "resume-token")]
    assert tools_module.authorization_override.get() is None
