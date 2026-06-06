"""Tests for LLM client."""

import pytest
from unittest.mock import AsyncMock, MagicMock, patch


def test_resolve_model_default():
    from app.config import Settings
    from app.core.llm_client import LLMClient
    settings = Settings(MARKETMIND_LANGCHAIN_API_KEY="test-key", MARKETMIND_LANGCHAIN_BASE_URL="", MARKETMIND_LANGCHAIN_MODEL="gpt-4o-mini")
    client = LLMClient(settings)
    assert client._resolve_model() == "gpt-4o-mini"


def test_resolve_model_explicit():
    from app.config import Settings
    from app.core.llm_client import LLMClient
    settings = Settings(MARKETMIND_LANGCHAIN_API_KEY="test-key", MARKETMIND_LANGCHAIN_BASE_URL="")
    client = LLMClient(settings)
    assert client._resolve_model("gpt-4") == "gpt-4"


def test_resolve_model_deepseek():
    from app.config import Settings
    from app.core.llm_client import LLMClient
    settings = Settings(
        MARKETMIND_LANGCHAIN_API_KEY="test",
        MARKETMIND_LANGCHAIN_BASE_URL="https://api.deepseek.com/v1",
        MARKETMIND_LANGCHAIN_MODEL="gpt-4",
    )
    client = LLMClient(settings)
    assert client._resolve_model() == "deepseek-v4-flash"


def test_resolve_model_deepseek_supported():
    from app.config import Settings
    from app.core.llm_client import LLMClient
    settings = Settings(
        MARKETMIND_LANGCHAIN_API_KEY="test",
        MARKETMIND_LANGCHAIN_BASE_URL="https://api.deepseek.com/v1",
        MARKETMIND_LANGCHAIN_MODEL="deepseek-v4-pro",
    )
    client = LLMClient(settings)
    assert client._resolve_model() == "deepseek-v4-pro"


def test_uses_deepseek_endpoint():
    from app.core.llm_client import LLMClient
    from app.config import Settings
    settings = Settings(MARKETMIND_LANGCHAIN_API_KEY="test")
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