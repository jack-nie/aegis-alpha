"""Tests for LLM client."""

import pytest
from unittest.mock import AsyncMock, MagicMock, patch


def test_resolve_model_default():
    from app.config import Settings
    from app.core.llm_client import LLMClient
    settings = Settings(AEGIS_ALPHA_LANGCHAIN_API_KEY="test-key", AEGIS_ALPHA_LANGCHAIN_BASE_URL="", AEGIS_ALPHA_LANGCHAIN_MODEL="gpt-4o-mini")
    client = LLMClient(settings)
    assert client._resolve_model() == "gpt-4o-mini"


def test_resolve_model_explicit():
    from app.config import Settings
    from app.core.llm_client import LLMClient
    settings = Settings(AEGIS_ALPHA_LANGCHAIN_API_KEY="test-key", AEGIS_ALPHA_LANGCHAIN_BASE_URL="")
    client = LLMClient(settings)
    assert client._resolve_model("gpt-4") == "gpt-4"


def test_resolve_model_deepseek():
    from app.config import Settings
    from app.core.llm_client import LLMClient
    settings = Settings(
        AEGIS_ALPHA_LANGCHAIN_API_KEY="test",
        AEGIS_ALPHA_LANGCHAIN_BASE_URL="https://api.deepseek.com/v1",
        AEGIS_ALPHA_LANGCHAIN_MODEL="gpt-4",
    )
    client = LLMClient(settings)
    assert client._resolve_model() == "deepseek-v4-flash"


def test_resolve_model_deepseek_supported():
    from app.config import Settings
    from app.core.llm_client import LLMClient
    settings = Settings(
        AEGIS_ALPHA_LANGCHAIN_API_KEY="test",
        AEGIS_ALPHA_LANGCHAIN_BASE_URL="https://api.deepseek.com/v1",
        AEGIS_ALPHA_LANGCHAIN_MODEL="deepseek-v4-pro",
    )
    client = LLMClient(settings)
    assert client._resolve_model() == "deepseek-v4-pro"


def test_uses_deepseek_endpoint():
    from app.core.llm_client import LLMClient
    from app.config import Settings
    settings = Settings(AEGIS_ALPHA_LANGCHAIN_API_KEY="test")
    client = LLMClient(settings)
    assert client._uses_deepseek_endpoint("https://api.deepseek.com/v1") is True
    assert client._uses_deepseek_endpoint("https://api.openai.com/v1") is False
    assert client._uses_deepseek_endpoint("") is False
    assert client._uses_deepseek_endpoint(None) is False


def test_normalize_content_json(mock_settings):
    from app.core.llm_client import LLMClient
    client = LLMClient(mock_settings)
    result = client._normalize_content('{"summary": "test", "confidence": 0.8}')
    assert result.summary == "test"
    assert result.confidence == 0.8


def test_normalize_content_markdown(mock_settings):
    from app.core.llm_client import LLMClient
    client = LLMClient(mock_settings)
    md = "# Analysis Report\n\n## Section 1\n\nThis is a detailed analysis with **bold** text and a table:\n\n| Metric | Value |\n|---|---|\n| P/E | 15 |\n\n" + "The analysis continues here with more details about the company performance. " * 20
    result = client._normalize_content(md)
    assert result.confidence == 0.75
    assert "Analysis Report" in result.content


def test_normalize_content_plain_text(mock_settings):
    from app.core.llm_client import LLMClient
    client = LLMClient(mock_settings)
    result = client._normalize_content("Just some plain text")
    assert result.summary == "Just some plain text"
    assert result.confidence == 0.5


def test_normalize_content_code_fence(mock_settings):
    from app.core.llm_client import LLMClient
    client = LLMClient(mock_settings)
    content = '```json\n{"summary": "fenced", "confidence": 0.7}\n```'
    result = client._normalize_content(content)
    assert result.summary == "fenced"


def test_normalize_content_empty(mock_settings):
    from app.core.llm_client import LLMClient
    client = LLMClient(mock_settings)
    result = client._normalize_content("")
    assert result.summary == "No response from LLM"


def test_clamp_confidence():
    from app.core.llm_client import LLMClient
    assert LLMClient._clamp_confidence(1.5) == 1.0


@pytest.mark.asyncio
async def test_invoke_agent_with_tools_returns_tool_calls(mock_settings):
    from unittest.mock import AsyncMock, MagicMock
    from langchain_core.messages import AIMessage
    from app.core.llm_client import LLMClient

    client = LLMClient(mock_settings)
    ai = AIMessage(
        content="",
        tool_calls=[{"name": "get_news", "args": {"ticker": "AAPL"}, "id": "tc1"}],
    )
    bound = MagicMock()
    bound.ainvoke = AsyncMock(return_value=ai)
    base = MagicMock()
    base.bind_tools = MagicMock(return_value=bound)
    client._build_client = MagicMock(return_value=base)

    result = await client.invoke_agent_with_tools(
        system="You are an agent",
        prompt="Get news for AAPL",
        tools=[MagicMock(name="get_news")],
    )

    assert result.has_tool_calls is True
    assert result.ai_message.tool_calls
    assert result.seed_human is not None
    assert result.llm_response is None
    base.bind_tools.assert_called_once()


@pytest.mark.asyncio
async def test_invoke_agent_with_tools_final_text(mock_settings):
    from unittest.mock import AsyncMock, MagicMock
    from langchain_core.messages import AIMessage, HumanMessage, ToolMessage
    from app.core.llm_client import LLMClient

    client = LLMClient(mock_settings)
    ai = AIMessage(content='{"summary": "done", "confidence": 0.8}')
    bound = MagicMock()
    bound.ainvoke = AsyncMock(return_value=ai)
    base = MagicMock()
    base.bind_tools = MagicMock(return_value=bound)
    client._build_client = MagicMock(return_value=base)

    prior = [
        HumanMessage(content="task"),
        AIMessage(content="", tool_calls=[{"name": "ping", "args": {}, "id": "1"}]),
        ToolMessage(content="pong", tool_call_id="1"),
    ]
    result = await client.invoke_agent_with_tools(
        system="You are an agent",
        prompt="task",
        tools=[MagicMock()],
        prior_messages=prior,
    )

    assert result.has_tool_calls is False
    assert result.llm_response is not None
    assert result.llm_response.summary == "done"
    assert result.seed_human is None  # continuation: no new human seed


@pytest.mark.asyncio
async def test_invoke_agent_with_tools_force_final_skips_bind(mock_settings):
    from unittest.mock import AsyncMock, MagicMock
    from langchain_core.messages import AIMessage
    from app.core.llm_client import LLMClient

    client = LLMClient(mock_settings)
    ai = AIMessage(content="final answer without tools")
    base = MagicMock()
    base.ainvoke = AsyncMock(return_value=ai)
    base.bind_tools = MagicMock()
    client._build_client = MagicMock(return_value=base)

    result = await client.invoke_agent_with_tools(
        system="sys",
        prompt="prompt",
        tools=[MagicMock()],
        force_final=True,
    )

    assert result.has_tool_calls is False
    base.bind_tools.assert_not_called()
    assert "final answer" in (result.llm_response.summary or "")
    assert LLMClient._clamp_confidence(-0.5) == 0.0
    assert LLMClient._clamp_confidence(0.7) == 0.7
    assert LLMClient._clamp_confidence("abc") == 0.5


def test_client_cache(mock_settings):
    from app.core.llm_client import LLMClient
    client = LLMClient(mock_settings)
    c1 = client._build_client("gpt-4o-mini", 0.2, 25000)
    c2 = client._build_client("gpt-4o-mini", 0.2, 25000)
    assert c1 is c2


def test_client_cache_different_params(mock_settings):
    from app.core.llm_client import LLMClient
    client = LLMClient(mock_settings)
    c1 = client._build_client("gpt-4o-mini", 0.2, 25000)
    c2 = client._build_client("gpt-4o-mini", 0.5, 25000)
    assert c1 is not c2