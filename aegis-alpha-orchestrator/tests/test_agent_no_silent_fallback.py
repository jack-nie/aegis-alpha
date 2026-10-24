"""P4-2: no silent fallback to execute(); bounded agent retries; finish_reason=length tool calls refused."""
from unittest.mock import AsyncMock, MagicMock

import pytest
from langchain_core.messages import AIMessage, ToolMessage
from langchain_core.tools import tool
from langgraph.checkpoint.memory import MemorySaver

from app.core.llm_client import AgentToolResult, LLMClient, LLMResponse
from app.core.node_executor import AgentTurnError
from app.models.workflow import Edge, Node, NodeData

EXECUTED: list[str] = []


@tool
def get_stock_quote(symbol: str) -> dict:
    """Quote."""
    EXECUTED.append(symbol)
    return {"symbol": symbol, "price": 1}


def _layout():
    nodes = [
        Node(id="start", data=NodeData(handler="start", nodeType="start")),
        Node(id="agent", data=NodeData(handler="general.agent", nodeType="agent", label="Agent")),
        Node(id="end", data=NodeData(handler="end", nodeType="end")),
    ]
    return nodes, [Edge(source="start", target="agent"), Edge(source="agent", target="end")]


async def _run(executor, tools):
    from app.core.workflow_engine import WorkflowEngine

    WorkflowEngine._graph_cache.clear()
    engine = WorkflowEngine(executor, tools=tools, checkpointer=MemorySaver())
    nodes, edges = _layout()
    try:
        return await engine.execute_workflow(nodes, edges, {"ticker": "AAPL"}, "s")
    finally:
        WorkflowEngine._graph_cache.clear()


@pytest.mark.asyncio
async def test_agent_failure_is_explicit_and_execute_is_not_called(mock_node_executor):
    mock_node_executor.invoke_agent_with_tools = AsyncMock(side_effect=AgentTurnError("agent", RuntimeError("boom")))
    original_execute = mock_node_executor.execute
    executed_nodes: list[str] = []

    async def spy_execute(node, state, subject="", **kw):
        executed_nodes.append(node.id)
        return await original_execute(node=node, state=state, subject=subject, **kw)

    mock_node_executor.execute = spy_execute
    result = await _run(mock_node_executor, [get_stock_quote])
    assert result.ok, result.error
    assert "agent" not in executed_nodes  # no silent fallback to a tool-less execute()
    out = result.node_outputs["agent"]
    assert out["status"] == "agent_failed" and out["degraded"] is True
    assert out["signals"] == [] and out["confidence"] == 0.0
    assert "boom" in out["data"]["error"] and out["data"]["fallback_policy"] == "no_fallback"
    trace = [t for t in result.trace if t.node_id == "agent"]
    assert trace[-1].status == "agent_failed" and trace[-1].degraded is True


def _executor_with_llm(mock_node_executor, side_effect):
    llm = MagicMock()
    llm.invoke_agent_with_tools = AsyncMock(side_effect=side_effect)
    mock_node_executor._llm_client = llm
    mock_node_executor._sleep = AsyncMock()
    mock_node_executor._random = lambda: 0.0
    return llm


@pytest.mark.asyncio
async def test_agent_turn_retries_retryable_errors(mock_node_executor):
    final = AgentToolResult(AIMessage(content="ok"), llm_response=LLMResponse(summary="ok"))
    llm = _executor_with_llm(mock_node_executor, [RuntimeError("503 upstream"), RuntimeError("timeout"), final])
    node = Node(id="agent", data=NodeData(handler="general.agent"))
    out = await mock_node_executor.invoke_agent_with_tools(node=node, state={}, subject="s", tools=[])
    assert out is final
    assert llm.invoke_agent_with_tools.await_count == 3
    assert mock_node_executor._sleep.await_count == 2


@pytest.mark.asyncio
async def test_agent_turn_non_retryable_fails_fast(mock_node_executor):
    llm = _executor_with_llm(mock_node_executor, [RuntimeError("401 unauthorized")])
    node = Node(id="agent", data=NodeData(handler="general.agent"))
    with pytest.raises(AgentTurnError):
        await mock_node_executor.invoke_agent_with_tools(node=node, state={}, subject="s", tools=[])
    assert llm.invoke_agent_with_tools.await_count == 1


@pytest.mark.asyncio
async def test_agent_turn_retry_budget_exhausted_raises(mock_node_executor):
    llm = _executor_with_llm(mock_node_executor, RuntimeError("503"))
    node = Node(id="agent", data=NodeData(handler="general.agent"))
    with pytest.raises(AgentTurnError) as info:
        await mock_node_executor.invoke_agent_with_tools(node=node, state={}, subject="s", tools=[])
    assert llm.invoke_agent_with_tools.await_count == mock_node_executor._retry_budget()[0]
    assert "503" in str(info.value.cause)


def _client_returning(mock_settings, message):
    client = LLMClient(mock_settings)
    bound = MagicMock()
    bound.ainvoke = AsyncMock(return_value=message)
    base = MagicMock()
    base.bind_tools = MagicMock(return_value=bound)
    client._build_client = MagicMock(return_value=base)
    return client


@pytest.mark.asyncio
async def test_length_truncated_tool_calls_are_flagged(mock_settings):
    from app.core.tool_pipeline import is_truncated_tool_call_message

    msg = AIMessage(
        content="",
        tool_calls=[{"name": "get_stock_quote", "args": {"symbol": "AAPL"}, "id": "t1"}],
        invalid_tool_calls=[{"name": "get_news", "args": '{"symb', "id": "t2", "error": "json"}],
        response_metadata={"finish_reason": "length"},
    )
    client = _client_returning(mock_settings, msg)
    result = await client.invoke_agent_with_tools(system="s", prompt="p", tools=[MagicMock()])
    assert result.has_tool_calls is True
    assert [c["id"] for c in result.ai_message.tool_calls] == ["t1", "t2"]
    assert is_truncated_tool_call_message(result.ai_message)


@pytest.mark.asyncio
async def test_length_truncated_tool_calls_are_not_executed(mock_node_executor):
    EXECUTED.clear()
    seen: dict = {}
    truncated = AIMessage(
        content="",
        tool_calls=[{"name": "get_stock_quote", "args": {"symbol": "AAPL"}, "id": "t1"}],
        response_metadata={"finish_reason": "length"},
    )

    async def fake(node, state, subject="", tools=None, prior_messages=None, force_final=False, **_):
        if not prior_messages:
            return AgentToolResult(truncated, has_tool_calls=True)
        seen["tools"] = [m for m in prior_messages if isinstance(m, ToolMessage)]
        return AgentToolResult(AIMessage(content="final"), llm_response=LLMResponse(summary="final"))

    mock_node_executor.invoke_agent_with_tools = fake
    result = await _run(mock_node_executor, [get_stock_quote])
    assert result.ok, result.error
    assert EXECUTED == []
    assert [m.status for m in seen["tools"]] == ["error"]
    assert "finish_reason=length" in seen["tools"][0].content
    assert result.node_outputs["agent"]["degraded"] is True
