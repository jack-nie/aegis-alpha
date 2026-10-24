"""P4-9: event protocol v2 — real deltas, per-call tool events, gated aggregate release, token alias."""
import json
from datetime import datetime, timezone
from pathlib import Path
from unittest.mock import AsyncMock, MagicMock

import pytest
from langchain_core.messages import AIMessage, AIMessageChunk
from langchain_core.tools import tool
from langgraph.checkpoint.memory import MemorySaver

from app.core.llm_client import AgentToolResult, LLMClient, LLMResponse
from app.models.responses import NodeResult
from app.models.workflow import Edge, Node, NodeData

FIXTURES = Path(__file__).resolve().parents[2] / "docs/superpowers/specs/help-agent-contracts/fixtures"
AGG = "finance.stock_recommendation_aggregate"


@tool
def get_stock_quote(symbol: str) -> dict:
    """Quote."""
    raise RuntimeError("HTTP 503 Service Unavailable")


def _agent_layout():
    nodes = [
        Node(id="start", data=NodeData(handler="start", nodeType="start")),
        Node(id="agent", data=NodeData(handler="general.agent", nodeType="agent", label="A")),
        Node(id="end", data=NodeData(handler="end", nodeType="end")),
    ]
    return nodes, [Edge(source="start", target="agent"), Edge(source="agent", target="end")]


def _scripted_llm():
    calls = []

    async def fake(**kwargs):
        calls.append(kwargs)
        on_delta = kwargs.get("on_delta")
        if len(calls) == 1:
            if on_delta:
                on_delta("Checking ")
                on_delta("the quote")
            msg = AIMessage(
                content="Checking the quote",
                tool_calls=[{"name": "get_stock_quote", "args": {"symbol": "AAPL"}, "id": "call-1"}],
                usage_metadata={"input_tokens": 10, "output_tokens": 5, "total_tokens": 15},
            )
            return AgentToolResult(msg, has_tool_calls=True)
        if on_delta:
            on_delta("Quote unavailable.")
        return AgentToolResult(
            AIMessage(content="Quote unavailable."),
            llm_response=LLMResponse(
                summary="Quote unavailable.", confidence=0.3,
                token_usage={"inputTokens": 20, "outputTokens": 7, "totalTokens": 27},
            ),
        )

    llm = MagicMock()
    llm.invoke_agent_with_tools = AsyncMock(side_effect=fake)
    return llm, calls


@pytest.mark.asyncio
async def test_stream_emits_real_deltas_before_node_update_and_tool_pairs(mock_node_executor):
    from app.core.workflow_engine import WorkflowEngine

    llm, calls = _scripted_llm()
    mock_node_executor._llm_client = llm
    nodes, edges = _agent_layout()
    WorkflowEngine._graph_cache.clear()
    try:
        engine = WorkflowEngine(mock_node_executor, tools=[get_stock_quote], checkpointer=MemorySaver())
        events = [e async for e in engine.stream_workflow(nodes, edges, {"ticker": "AAPL"}, "s")]
    finally:
        WorkflowEngine._graph_cache.clear()

    names = [e.event for e in events]
    assert names[-1] == "workflow_complete", names
    assert all("on_delta" in c for c in calls)
    first_delta = names.index("message_delta")
    first_agent_update = next(i for i, e in enumerate(events) if e.event == "node_update" and e.data["nodeId"] == "agent")
    assert first_delta < first_agent_update
    starts = [e for e in events if e.event == "node_start" and e.data["nodeId"] == "agent"]
    assert [s.data["turn"] for s in starts] == [1, 2]
    assert starts[0].data["handler"] == "general.agent"
    assert names.index("node_start") < first_delta
    deltas = [e.data for e in events if e.event == "message_delta"]
    assert [d["delta"] for d in deltas] == ["Checking ", "the quote", "Quote unavailable."]
    assert [d["turn"] for d in deltas] == [1, 1, 2] and all(d["final"] is False for d in deltas)

    tool_start = [e.data for e in events if e.event == "tool_execution_start"]
    tool_end = [e.data for e in events if e.event == "tool_execution_end"]
    assert [t["toolCallId"] for t in tool_start] == ["call-1"] == [t["toolCallId"] for t in tool_end]
    assert names.index("tool_execution_start") < names.index("tool_execution_end")
    assert tool_end[0]["isError"] is True and tool_end[0]["toolName"] == "get_stock_quote"
    assert "tool_call" not in names  # v1 empty aggregate tool event is gone
    usage = [e.data for e in events if e.event == "usage"]
    assert {u["scope"] for u in usage} == {"turn", "node"}
    assert usage[-1]["usage"]["totalTokens"] == 42


@pytest.mark.asyncio
async def test_sync_run_does_not_request_deltas(mock_node_executor):
    from app.core.workflow_engine import WorkflowEngine

    llm, calls = _scripted_llm()
    mock_node_executor._llm_client = llm
    nodes, edges = _agent_layout()
    WorkflowEngine._graph_cache.clear()
    try:
        engine = WorkflowEngine(mock_node_executor, tools=[get_stock_quote], checkpointer=MemorySaver())
        result = await engine.execute_workflow(nodes, edges, {"ticker": "AAPL"}, "s")
    finally:
        WorkflowEngine._graph_cache.clear()
    assert result.ok, result.error
    assert calls and all("on_delta" not in c for c in calls)


def _aggregate_result(*, forced: bool) -> NodeResult:
    return NodeResult(
        ok=True, status="completed", handler=AGG, node_id="aggregate", node_name="Report", subject="s",
        summary="[INSUFFICIENT_DATA] report" if forced else "[HOLD] report",
        content="RAW MODEL PROSE: strong BUY" if forced else "Full gated report",
        data={
            "recommendation": "INSUFFICIENT_DATA" if forced else "HOLD",
            "policy": {"forcedInsufficient": forced},
            "critique": {"ok": not forced},
        },
        duration_ms=1,
    )


async def _stream_aggregate(mock_node_executor, method: str, forced: bool):
    from app.core.workflow_engine import WorkflowEngine

    mock_node_executor.execute = AsyncMock(return_value=_aggregate_result(forced=forced))
    nodes = [Node(id="aggregate", data=NodeData(handler=AGG, label="Report"))]
    WorkflowEngine._graph_cache.clear()
    try:
        engine = WorkflowEngine(mock_node_executor, checkpointer=MemorySaver())
        return [e async for e in getattr(engine, method)(nodes, [], {"ticker": "AAPL"}, "s")]
    finally:
        WorkflowEngine._graph_cache.clear()


@pytest.mark.asyncio
@pytest.mark.parametrize("method", ["stream_workflow", "stream_workflow_tokens"])
async def test_failed_gate_never_streams_raw_aggregate_content(mock_node_executor, method):
    events = await _stream_aggregate(mock_node_executor, method, forced=True)
    text = json.dumps([e.model_dump() for e in events if e.event != "workflow_complete"], ensure_ascii=False)
    assert "RAW MODEL PROSE" not in text
    final = [e.data for e in events if e.event == "message_delta" and e.data.get("final")]
    assert final == [{"nodeId": "aggregate", "delta": "[INSUFFICIENT_DATA] report", "final": True,
                      "gatePassed": False, "withheld": True}]
    names = [e.event for e in events]
    assert names.index("message_delta") < names.index("workflow_complete")


@pytest.mark.asyncio
async def test_passed_gate_releases_content_once_with_token_alias(mock_node_executor):
    events = await _stream_aggregate(mock_node_executor, "stream_workflow_tokens", forced=False)
    names = [e.event for e in events]
    final = [e.data for e in events if e.event == "message_delta" and e.data.get("final")]
    assert final == [{"nodeId": "aggregate", "delta": "Full gated report", "final": True, "gatePassed": True}]
    tokens = [e.data for e in events if e.event == "token"]
    assert tokens == [{"nodeId": "aggregate", "content": "Full gated report"}]  # one alias, not 50-char slices
    assert names.index("message_delta") < names.index("token")
    plain = await _stream_aggregate(mock_node_executor, "stream_workflow", forced=False)
    assert "token" not in [e.event for e in plain]


@pytest.mark.asyncio
async def test_node_executor_withholds_raw_content_when_gate_fails(mock_node_executor):
    mock_node_executor._llm_client.invoke = AsyncMock(return_value=LLMResponse(
        summary="Strong buy", confidence=0.9,
        content='RAW: buy now\n```json\n{"recommendation":"BUY"}\n```',
        data={"recommendation": "BUY"},
    ))
    node = Node(id="aggregate", data=NodeData(handler=AGG, label="Report"))
    result = await mock_node_executor._invoke_llm(
        node=node, handler=AGG, state={"nodes": {}}, subject="AAPL", agent=None, api_key="k",
        base_url="", model=None, started_at=datetime.now(timezone.utc).isoformat(),
    )
    assert result.data["recommendation"] == "INSUFFICIENT_DATA"
    assert result.data["contentWithheld"] is True
    assert "RAW: buy now" not in (result.content or "")
    assert result.content.startswith("[INSUFFICIENT_DATA]")


@pytest.mark.asyncio
async def test_llm_client_streams_deltas_and_aggregates_tool_calls(mock_settings):
    chunks = [
        AIMessageChunk(content="Hel"),
        AIMessageChunk(content="lo", tool_call_chunks=[
            {"name": "get_stock_quote", "args": '{"symbol":', "id": "c1", "index": 0}]),
        AIMessageChunk(content="", tool_call_chunks=[{"name": None, "args": '"AAPL"}', "id": None, "index": 0}],
                       usage_metadata={"input_tokens": 3, "output_tokens": 2, "total_tokens": 5}),
    ]

    class FakeClient:
        def bind_tools(self, tools):
            return self

        async def astream(self, messages):
            for c in chunks:
                yield c

        async def ainvoke(self, messages):  # pragma: no cover - must not be used when streaming
            raise AssertionError("non-stream path used")

    client = LLMClient(mock_settings)
    client._build_client = MagicMock(return_value=FakeClient())
    seen: list[str] = []
    result = await client.invoke_agent_with_tools(
        system="s", prompt="p", tools=[get_stock_quote], model="gpt-4o-mini", on_delta=seen.append,
    )
    assert seen == ["Hel", "lo"]
    assert result.has_tool_calls
    assert result.ai_message.tool_calls[0]["args"] == {"symbol": "AAPL"}
    assert result.turn_usage == {"inputTokens": 3, "outputTokens": 2, "totalTokens": 5}


@pytest.mark.parametrize("name", ["stream_v2_agent_tool_loop.json", "stream_v2_aggregate_gate_failed.json"])
def test_v2_contract_fixtures(name):
    from app.core.workflow_engine import STREAM_V2_EVENTS

    fx = json.loads((FIXTURES / name).read_text(encoding="utf-8"))
    names = [e["event"] for e in fx["events"]]
    assert names[-1] == "workflow_complete"
    assert set(names) - {"node_update", "workflow_complete", "token", "degraded"} <= STREAM_V2_EVENTS
    for e in fx["events"]:
        assert set(fx["requiredKeys"].get(e["event"], [])) <= set(e["data"]), e
    if "tool_execution_start" in names:
        assert names.index("message_delta") < names.index("node_update")
        ids = lambda ev: [e["data"]["toolCallId"] for e in fx["events"] if e["event"] == ev]  # noqa: E731
        assert ids("tool_execution_start") == ids("tool_execution_end")
    finals = [e["data"] for e in fx["events"] if e["event"] == "message_delta" and e["data"].get("final")]
    for f in finals:
        assert f["gatePassed"] or f.get("withheld") is True
