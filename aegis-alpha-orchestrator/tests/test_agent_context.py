"""P4-8: transform_context (elide old tool results, compact over budget, overflow retry) + maxToolRounds."""
import json
from unittest.mock import AsyncMock, MagicMock

import pytest
from langchain_core.messages import AIMessage, HumanMessage, SystemMessage, ToolMessage
from langchain_core.tools import tool

from app.core.agent_context import (
    ELIDED_PREFIX,
    ContextPolicy,
    estimate_messages_tokens,
    is_context_overflow,
    transform_context,
)
from app.core.llm_client import AgentToolResult, LLMClient, LLMResponse
from app.models.workflow import Edge, Node, NodeData


def _loop(rounds: int, payload_chars: int = 2000) -> list:
    msgs: list = [HumanMessage(content="Analyse AAPL")]
    for i in range(rounds):
        msgs.append(AIMessage(content="", tool_calls=[{"name": "get_stock_quote", "args": {"symbol": "AAPL"}, "id": f"c{i}"}]))
        body = json.dumps({"evidenceId": f"ev-quote-{i}", "blob": "x" * payload_chars})
        msgs.append(ToolMessage(content=body, tool_call_id=f"c{i}", name="get_stock_quote",
                                artifact={"evidenceIds": [f"ev-quote-{i}"]}))
    return msgs


def test_old_tool_results_elided_with_evidence_and_pairing_kept():
    msgs = _loop(6)
    before = [m.content for m in msgs]
    out, stats = transform_context(msgs, ContextPolicy(keep_recent_tool_results=4, max_agent_tokens=10**6))
    tools = [m for m in out if isinstance(m, ToolMessage)]
    assert [m.tool_call_id for m in tools] == [f"c{i}" for i in range(6)]  # order / pairing intact
    assert tools[0].content.startswith(ELIDED_PREFIX) and "ev-quote-0" in tools[0].content
    assert tools[1].content.startswith(ELIDED_PREFIX) and "ev-quote-1" in tools[1].content
    assert all(not m.content.startswith(ELIDED_PREFIX) for m in tools[2:])
    assert stats["elidedToolResults"] == 2 and stats["compacted"] is False
    assert [m.content for m in msgs] == before  # input untouched (graph state is never rewritten)


def test_over_budget_compacts_to_latest_tool_result():
    msgs = _loop(6, payload_chars=8000)
    policy = ContextPolicy(keep_recent_tool_results=4, max_agent_tokens=3000)
    out, stats = transform_context(msgs, policy)
    assert stats["compacted"] is True
    assert stats["estimatedTokens"] <= policy.max_agent_tokens
    assert estimate_messages_tokens(out) <= policy.max_agent_tokens
    tools = [m for m in out if isinstance(m, ToolMessage)]
    assert all(m.content.startswith(ELIDED_PREFIX) for m in tools[:-1])
    assert not tools[-1].content.startswith(ELIDED_PREFIX)
    for i in range(5):
        assert f"ev-quote-{i}" in tools[i].content  # evidence ids survive compaction


def test_context_overflow_detection():
    assert is_context_overflow(RuntimeError("Error code: 400 - context_length_exceeded"))
    assert is_context_overflow(ValueError("This model's maximum context length is 65536 tokens"))
    assert not is_context_overflow(RuntimeError("503 upstream"))


def _client_with(mock_settings, side_effect):
    client = LLMClient(mock_settings)
    bound = MagicMock()
    bound.ainvoke = AsyncMock(side_effect=side_effect)
    base = MagicMock()
    base.bind_tools = MagicMock(return_value=bound)
    client._build_client = MagicMock(return_value=base)
    return client, bound


@pytest.mark.asyncio
async def test_sixth_request_sends_elided_history_within_budget(mock_settings):
    mock_settings.agent_max_context_tokens = 4000
    client, bound = _client_with(mock_settings, [AIMessage(content='{"summary":"ok","confidence":0.6}')])
    prior = _loop(5, payload_chars=6000)
    result = await client.invoke_agent_with_tools(system="s", prompt="p", tools=[MagicMock()], prior_messages=prior)
    sent = bound.ainvoke.await_args.args[0]
    assert isinstance(sent[0], SystemMessage)
    sent_tools = [m for m in sent if isinstance(m, ToolMessage)]
    assert sent_tools[0].content.startswith(ELIDED_PREFIX)
    assert estimate_messages_tokens(sent[1:]) <= 4000
    assert result.context_stats["compacted"] is True
    assert len(prior[2].content) > 1000  # caller's history unchanged


@pytest.mark.asyncio
async def test_overflow_compacts_once_and_retries(mock_settings):
    client, bound = _client_with(
        mock_settings,
        [RuntimeError("context_length_exceeded"), AIMessage(content='{"summary":"ok","confidence":0.6}')],
    )
    prior = _loop(2, payload_chars=100)
    result = await client.invoke_agent_with_tools(system="s", prompt="p", tools=[MagicMock()], prior_messages=prior)
    assert bound.ainvoke.await_count == 2
    retry_tools = [m for m in bound.ainvoke.await_args_list[1].args[0] if isinstance(m, ToolMessage)]
    assert retry_tools[0].content.startswith(ELIDED_PREFIX) and not retry_tools[-1].content.startswith(ELIDED_PREFIX)
    assert result.context_stats["overflowRetry"] is True


@pytest.mark.asyncio
async def test_second_overflow_propagates(mock_settings):
    client, _ = _client_with(mock_settings, [RuntimeError("context_length_exceeded")] * 2)
    with pytest.raises(RuntimeError):
        await client.invoke_agent_with_tools(system="s", prompt="p", tools=[MagicMock()], prior_messages=_loop(1))


@pytest.mark.asyncio
async def test_node_max_tool_rounds_is_honoured(mock_node_executor):
    from app.core.workflow_engine import WorkflowEngine, max_tool_rounds_for

    @tool
    def ping() -> str:
        """Return pong."""
        return "pong"

    calls = {"n": 0}

    async def always_tools(node, state, subject="", tools=None, prior_messages=None, force_final=False, **kw):
        if force_final:
            return AgentToolResult(AIMessage(content="final"), llm_response=LLMResponse(summary="final", confidence=0.5))
        calls["n"] += 1
        seed = None if prior_messages else HumanMessage(content="go")
        ai = AIMessage(content="", tool_calls=[{"name": "ping", "args": {}, "id": f"p{calls['n']}"}])
        return AgentToolResult(ai, has_tool_calls=True, seed_human=seed)

    mock_node_executor.invoke_agent_with_tools = AsyncMock(side_effect=always_tools)
    node = Node(id="agent", data=NodeData(handler="general.agent", maxToolRounds=1))
    assert max_tool_rounds_for(node) == 1
    assert max_tool_rounds_for(Node(id="x", data=NodeData(maxToolRounds=99))) == 8
    assert max_tool_rounds_for(Node(id="x")) == 3
    WorkflowEngine._graph_cache.clear()
    try:
        engine = WorkflowEngine(mock_node_executor, tools=[ping])
        result = await engine.execute_workflow(
            nodes=[node, Node(id="end", data=NodeData(handler="end", nodeType="end"))],
            edges=[Edge(source="agent", target="end")], state={}, subject="cap",
        )
    finally:
        WorkflowEngine._graph_cache.clear()
    assert result.ok
    assert mock_node_executor.invoke_agent_with_tools.await_count == 2  # 1 tool round + forced final
