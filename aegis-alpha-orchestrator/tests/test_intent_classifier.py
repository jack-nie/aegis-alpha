"""Tests for intent classifier."""

import pytest
from unittest.mock import AsyncMock, MagicMock


def test_build_tools():
    from app.config import Settings
    from app.core.intent_classifier import IntentClassifier
    from app.core.llm_client import LLMClient
    settings = Settings(MARKETMIND_LANGCHAIN_API_KEY="test")
    client = LLMClient(settings)
    classifier = IntentClassifier(client)
    workflows = [
        {"workflowKey": "stock-analysis", "name": "Stock Analysis", "routingDescription": "Analyze a stock"},
    ]
    tools = classifier._build_tools(workflows)
    assert len(tools) == 1
    assert tools[0]["type"] == "function"
    fn = tools[0]["function"]
    assert fn["name"] == "run_stock_analysis"
    assert "Analyze" in fn["description"] or "stock" in fn["description"].lower()


def test_build_tools_multiple():
    from app.config import Settings
    from app.core.intent_classifier import IntentClassifier
    from app.core.llm_client import LLMClient
    settings = Settings(MARKETMIND_LANGCHAIN_API_KEY="test")
    client = LLMClient(settings)
    classifier = IntentClassifier(client)
    workflows = [
        {"workflowKey": "stock-analysis", "name": "Stock Analysis"},
        {"workflowKey": "market-overview", "name": "Market Overview"},
    ]
    tools = classifier._build_tools(workflows)
    assert len(tools) == 2


def test_keyword_fallback_match():
    from app.config import Settings
    from app.core.intent_classifier import IntentClassifier
    from app.core.llm_client import LLMClient
    settings = Settings(MARKETMIND_LANGCHAIN_API_KEY="test")
    client = LLMClient(settings)
    classifier = IntentClassifier(client)
    workflows = [
        {"workflowKey": "stock-analysis", "name": "Stock Analysis", "triggerKeywords": "stock analyze market"},
    ]
    result = classifier._keyword_fallback("analyze stock", workflows)
    assert result is not None
    assert result.workflow_key == "stock-analysis"


def test_keyword_fallback_no_match():
    from app.config import Settings
    from app.core.intent_classifier import IntentClassifier
    from app.core.llm_client import LLMClient
    settings = Settings(MARKETMIND_LANGCHAIN_API_KEY="test")
    client = LLMClient(settings)
    classifier = IntentClassifier(client)
    workflows = [
        {"workflowKey": "stock-analysis", "name": "Stock Analysis", "triggerKeywords": "stock analyze"},
    ]
    result = classifier._keyword_fallback("weather forecast", workflows)
    assert result.workflow_key is None
    assert result.confidence == 0


def test_keyword_fallback_ticker_extraction():
    from app.config import Settings
    from app.core.intent_classifier import IntentClassifier
    from app.core.llm_client import LLMClient
    settings = Settings(MARKETMIND_LANGCHAIN_API_KEY="test")
    client = LLMClient(settings)
    classifier = IntentClassifier(client)
    workflows = [
        {"workflowKey": "stock-analysis", "name": "Stock Analysis"},
    ]
    result = classifier._keyword_fallback("AAPL analysis", workflows)
    assert result.ticker == "AAPL"


@pytest.mark.asyncio
async def test_classify_empty_message():
    from app.config import Settings
    from app.core.intent_classifier import IntentClassifier
    from app.core.llm_client import LLMClient
    settings = Settings(MARKETMIND_LANGCHAIN_API_KEY="test")
    client = LLMClient(settings)
    classifier = IntentClassifier(client)
    result = await classifier.classify("", [{"workflowKey": "test"}])
    assert result.workflow_key is None
    assert result.confidence == 0