"""P1-12: explicit structured-output protocol, bounded call counts and retry budget."""

from __future__ import annotations

from unittest.mock import AsyncMock

import pytest
from langchain_core.messages import AIMessage

from app.models.workflow import Node, NodeData


class _CountingChat:
    """Fake chat model counting structured and plain calls."""

    def __init__(self, *, structured_ok: bool):
        self.structured_ok = structured_ok
        self.structured_calls = 0
        self.plain_calls = 0
        self.methods: list[str | None] = []

    def bind_tools(self, tools):  # pragma: no cover - not used
        raise AssertionError("no tools expected")

    def with_structured_output(self, schema, include_raw=False, method=None, **kwargs):
        from app.core.llm_client import AnalystResult

        assert schema is AnalystResult
        assert include_raw is True
        self.methods.append(method)
        outer = self

        class _Structured:
            async def ainvoke(self, messages):
                outer.structured_calls += 1
                if not outer.structured_ok:
                    raise ValueError("schema validation failed")
                return {
                    "raw": AIMessage(content=""),
                    "parsed": AnalystResult(summary="structured", confidence=0.7, data={}),
                    "parsing_error": None,
                }

        return _Structured()

    async def ainvoke(self, messages):
        self.plain_calls += 1
        return AIMessage(content='{"summary":"plain","signals":[],"sources":[],"confidence":0.4,"data":{}}')


def _client(mock_settings, chat):
    from app.core.llm_client import LLMClient

    client = LLMClient(mock_settings)
    client._build_client = lambda *args, **kwargs: chat
    return client


@pytest.mark.asyncio
async def test_structured_success_uses_one_call_with_function_calling(mock_settings):
    chat = _CountingChat(structured_ok=True)
    response = await _client(mock_settings, chat).invoke("sys", "prompt", structured=True)
    assert response.summary == "structured"
    assert chat.structured_calls + chat.plain_calls == 1
    assert chat.methods == ["function_calling"]


@pytest.mark.asyncio
async def test_structured_failure_makes_at_most_two_calls(mock_settings):
    chat = _CountingChat(structured_ok=False)
    response = await _client(mock_settings, chat).invoke("sys", "prompt", structured=True)
    assert response.summary == "plain"
    assert chat.structured_calls == 1
    assert chat.plain_calls == 1


@pytest.mark.asyncio
async def test_explicit_unstructured_ignores_prompt_keywords(mock_settings):
    chat = _CountingChat(structured_ok=True)
    # The legacy heuristic would have looked at "aggregate" in the system prompt; the explicit
    # flag wins either way.
    await _client(mock_settings, chat).invoke("an aggregate report", "prompt", structured=True)
    assert chat.structured_calls == 1
    chat2 = _CountingChat(structured_ok=True)
    await _client(mock_settings, chat2).invoke("plain analyst", "prompt", structured=False)
    assert chat2.structured_calls == 0 and chat2.plain_calls == 1


@pytest.mark.asyncio
async def test_structured_method_is_configurable(mock_settings):
    settings = mock_settings.model_copy(update={"structured_output_method": "json_schema"})
    chat = _CountingChat(structured_ok=True)
    await _client(settings, chat).invoke("sys", "prompt", structured=True)
    assert chat.methods == ["json_schema"]


@pytest.mark.asyncio
async def test_node_executor_passes_explicit_protocol(mock_node_executor):
    from app.core.llm_client import LLMResponse

    calls: list[dict] = []

    async def _invoke(**kwargs):
        calls.append(kwargs)
        return LLMResponse(summary="ok", content="report\n```json\n{\"recommendation\":\"HOLD\"}\n```")

    mock_node_executor._llm_client.invoke = _invoke
    await mock_node_executor.execute(
        node=Node(id="m", data=NodeData(handler="finance.market_analysis", label="aggregate-sounding label")),
        state={"ticker": "AAPL"},
        subject="AAPL",
    )
    await mock_node_executor.execute(
        node=Node(id="a", data=NodeData(handler="finance.stock_recommendation_aggregate")),
        state={"ticker": "AAPL"},
        subject="AAPL",
    )
    assert calls[0]["structured"] is True
    assert calls[1]["structured"] is False
    assert "```json" in calls[1]["system"]


def test_aggregate_prompt_is_shared_and_allows_insufficient_data():
    import inspect

    from app.core import node_executor

    prompt = node_executor.aggregate_system_prompt("Agent")
    assert "INSUFFICIENT_DATA" in prompt
    source = inspect.getsource(node_executor.NodeExecutor.invoke_stream)
    assert "aggregate_system_prompt(" in source
    assert "Do NOT say 'data insufficient'" not in inspect.getsource(node_executor)


def test_parse_aggregate_trailer_takes_last_valid_block():
    from app.core.node_executor import parse_aggregate_trailer

    text = 'x\n```json\n{"recommendation":"BUY"}\n```\nmore\n```json\n{"recommendation":"HOLD","claims":[]}\n```'
    assert parse_aggregate_trailer(text) == {"recommendation": "HOLD", "claims": []}
    assert parse_aggregate_trailer("no trailer") == {}
    assert parse_aggregate_trailer("```json\n{broken\n```") == {}


@pytest.mark.asyncio
async def test_retryable_errors_respect_max_attempts_and_jitter(mock_node_executor):
    sleeps: list[float] = []

    async def _sleep(delay):
        sleeps.append(delay)

    attempts = {"n": 0}

    async def _boom(**kwargs):
        attempts["n"] += 1
        raise TimeoutError("timeout talking to provider")

    mock_node_executor._invoke_llm = _boom
    mock_node_executor._sleep = _sleep
    mock_node_executor._random = lambda: 0.0  # lowest jitter: 0.5x
    result = await mock_node_executor.execute(
        node=Node(id="m", data=NodeData(handler="finance.market_analysis")),
        state={"ticker": "AAPL"},
        subject="AAPL",
    )
    assert attempts["n"] == 3
    assert sleeps == [0.5, 1.0]
    assert result.ok is False or result.degraded is True


@pytest.mark.asyncio
async def test_retry_stops_at_total_deadline(mock_node_executor):
    now = {"t": 0.0}

    async def _sleep(delay):
        now["t"] += delay

    attempts = {"n": 0}

    async def _slow_boom(**kwargs):
        attempts["n"] += 1
        now["t"] += 50.0  # each attempt burns 50s of the budget
        raise TimeoutError("timeout")

    mock_node_executor._config = mock_node_executor._config.model_copy(
        update={"node_retry_deadline_ms": 60000, "node_retry_max_attempts": 5}
    )
    mock_node_executor._invoke_llm = _slow_boom
    mock_node_executor._sleep = _sleep
    mock_node_executor._clock = lambda: now["t"]
    mock_node_executor._random = lambda: 0.5
    await mock_node_executor.execute(
        node=Node(id="m", data=NodeData(handler="finance.market_analysis")),
        state={"ticker": "AAPL"},
        subject="AAPL",
    )
    # Attempt 1 ends at t=50; backoff 1s → 51 < 60 so attempt 2 runs and ends at t=101 > deadline.
    assert attempts["n"] == 2
    assert now["t"] <= 60.0 + 50.0


@pytest.mark.asyncio
async def test_non_retryable_error_is_single_attempt(mock_node_executor):
    attempts = {"n": 0}

    async def _auth(**kwargs):
        attempts["n"] += 1
        raise RuntimeError("401 unauthorized")

    mock_node_executor._invoke_llm = _auth
    mock_node_executor._sleep = AsyncMock()
    await mock_node_executor.execute(
        node=Node(id="m", data=NodeData(handler="finance.market_analysis")),
        state={"ticker": "AAPL"},
        subject="AAPL",
    )
    assert attempts["n"] == 1


def test_default_aggregate_prompt_renders_despite_json_braces():
    from app.core.node_executor import render_prompt
    from app.prompts import PromptManager

    template = PromptManager.get_prompt("finance.stock_recommendation_aggregate")
    rendered = render_prompt(template, subject="AAPL", agent_name="Agent")
    assert "AAPL" in rendered
    assert '{"recommendation"' in rendered
    assert render_prompt("Hi {agent_name}: {unknown} {subject}", subject="S", agent_name="A") == "Hi A: {unknown} S"


@pytest.mark.asyncio
async def test_aggregate_node_with_default_prompt_reaches_the_model(mock_node_executor):
    from app.core.llm_client import LLMResponse

    calls: list[dict] = []

    async def _invoke(**kwargs):
        calls.append(kwargs)
        return LLMResponse(summary="ok", content='report\n```json\n{"recommendation":"HOLD"}\n```')

    mock_node_executor._llm_client.invoke = _invoke
    result = await mock_node_executor.execute(
        node=Node(id="a", data=NodeData(handler="finance.stock_recommendation_aggregate")),
        state={"ticker": "AAPL"},
        subject="AAPL",
    )
    assert len(calls) == 1
    assert result.status != "model_failed"
