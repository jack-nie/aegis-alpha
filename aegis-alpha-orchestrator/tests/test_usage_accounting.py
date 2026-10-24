"""P4-7: full usage — tool-requesting turns counted, cache/reasoning tokens, config-only cost, run totals."""
from unittest.mock import AsyncMock, MagicMock

import pytest
from langchain_core.messages import AIMessage
from langchain_core.tools import tool
from langgraph.checkpoint.memory import MemorySaver

from app.core.context_assembly import normalize_token_usage, sum_token_usage, usage_totals, with_estimated_cost
from app.core.llm_client import AgentToolResult, LLMResponse
from app.core.model_registry import ModelRegistry
from app.models.workflow import Edge, Node, NodeData


@tool
def get_stock_quote(symbol: str) -> dict:
    """Quote."""
    return {"symbol": symbol, "price": 1}


def test_normalize_reads_cache_and_reasoning_tokens():
    langchain_shape = {
        "input_tokens": 100, "output_tokens": 40, "total_tokens": 140,
        "input_token_details": {"cache_read": 60}, "output_token_details": {"reasoning": 25},
    }
    assert normalize_token_usage(langchain_shape) == {
        "inputTokens": 100, "outputTokens": 40, "totalTokens": 140, "cacheReadTokens": 60, "reasoningTokens": 25,
    }
    deepseek_shape = {"prompt_tokens": 1, "input_tokens": 10, "output_tokens": 2, "prompt_cache_hit_tokens": 7}
    assert normalize_token_usage(deepseek_shape)["cacheReadTokens"] == 7
    plain = normalize_token_usage({"input_tokens": 1, "output_tokens": 2})
    assert plain == {"inputTokens": 1, "outputTokens": 2, "totalTokens": 3}  # no invented fields


def test_cost_only_from_configured_prices():
    usage = {"inputTokens": 1_000_000, "outputTokens": 1_000_000, "totalTokens": 2_000_000}
    unpriced = ModelRegistry.from_mapping({"models": {"m": {}}})
    assert with_estimated_cost(usage, "m", unpriced) == usage
    priced = ModelRegistry.from_mapping({"models": {"m": {"pricing": {"inputPerMTok": 0.5, "outputPerMTok": 1.5}}}})
    out = with_estimated_cost(usage, "m", priced)
    assert out["costUsd"] == pytest.approx(2.0) and out["costEstimated"] is True
    assert normalize_token_usage(out)["costUsd"] == pytest.approx(2.0)  # survives re-normalization


def test_sum_and_totals():
    a = {"inputTokens": 10, "outputTokens": 5, "totalTokens": 15, "costUsd": 0.1, "costEstimated": True}
    b = {"inputTokens": 20, "outputTokens": 7, "totalTokens": 27, "cacheReadTokens": 4}
    total = sum_token_usage([a, b])
    assert total == {"inputTokens": 30, "outputTokens": 12, "totalTokens": 42, "cacheReadTokens": 4}  # cost partial → omitted
    both = sum_token_usage([a, dict(a)])
    assert both["costUsd"] == pytest.approx(0.2)
    assert usage_totals({"n1": a, "n2": b})["totalTokens"] == 42
    assert usage_totals({"n1": a, "n2": b})["nodes"] == 2
    assert usage_totals({}) is None


@pytest.mark.asyncio
async def test_tool_request_turn_usage_is_counted(mock_node_executor):
    from app.core.workflow_engine import WorkflowEngine

    tool_turn = AIMessage(
        content="",
        tool_calls=[{"name": "get_stock_quote", "args": {"symbol": "AAPL"}, "id": "call-1"}],
        usage_metadata={"input_tokens": 10, "output_tokens": 5, "total_tokens": 15},
    )
    final_turn = AgentToolResult(
        AIMessage(content="done"),
        llm_response=LLMResponse(
            summary="done", confidence=0.7,
            token_usage={"inputTokens": 20, "outputTokens": 7, "totalTokens": 27},
        ),
    )
    llm = MagicMock()
    llm.invoke_agent_with_tools = AsyncMock(side_effect=[AgentToolResult(tool_turn, has_tool_calls=True), final_turn])
    mock_node_executor._llm_client = llm

    nodes = [
        Node(id="start", data=NodeData(handler="start", nodeType="start")),
        Node(id="agent", data=NodeData(handler="general.agent", nodeType="agent", label="A")),
        Node(id="end", data=NodeData(handler="end", nodeType="end")),
    ]
    edges = [Edge(source="start", target="agent"), Edge(source="agent", target="end")]
    WorkflowEngine._graph_cache.clear()
    try:
        engine = WorkflowEngine(mock_node_executor, tools=[get_stock_quote], checkpointer=MemorySaver())
        result = await engine.execute_workflow(nodes, edges, {"ticker": "AAPL"}, "s")
    finally:
        WorkflowEngine._graph_cache.clear()

    assert result.ok, result.error
    data = result.node_outputs["agent"]["data"]
    assert data["tokenUsage"] == {"inputTokens": 30, "outputTokens": 12, "totalTokens": 42}
    assert [t["kind"] for t in data["usageTurns"]] == ["tool_request", "final"]
    entries = [t.model_dump(by_alias=True) for t in result.trace if t.node_id == "agent"]
    awaiting = [e for e in entries if e["status"] == "awaiting_tools"]
    assert awaiting and awaiting[0]["turnUsage"]["totalTokens"] == 15
    assert "tokenUsage" not in awaiting[0]  # per-turn usage never double counts as a node total
    assert entries[-1]["tokenUsage"]["totalTokens"] == 42 and len(entries[-1]["turns"]) == 2
    assert result.final_state["usageTotals"]["totalTokens"] == 42


def test_agent_tool_result_turn_usage_from_message():
    msg = AIMessage(content="", usage_metadata={"input_tokens": 3, "output_tokens": 4, "total_tokens": 7})
    assert AgentToolResult(msg, has_tool_calls=True).turn_usage == {"inputTokens": 3, "outputTokens": 4, "totalTokens": 7}
    assert AgentToolResult(AIMessage(content="x"), has_tool_calls=True).turn_usage is None
