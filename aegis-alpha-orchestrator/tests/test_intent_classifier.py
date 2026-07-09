"""Tests for intent classifier."""

import pytest
from unittest.mock import AsyncMock


def test_build_tools():
    from app.config import Settings
    from app.core.intent_classifier import IntentClassifier
    from app.core.llm_client import LLMClient
    settings = Settings(AEGIS_ALPHA_LANGCHAIN_API_KEY="test")
    client = LLMClient(settings)
    classifier = IntentClassifier(client)
    workflows = [
        {"workflowKey": "stock-analysis", "name": "Stock Analysis", "routingDescription": "Analyze a stock"},
    ]
    tools, function_name_map = classifier._build_tools(workflows)
    assert len(tools) == 1
    assert tools[0]["type"] == "function"
    fn = tools[0]["function"]
    assert fn["name"] == "run_stock_analysis"
    assert "Analyze" in fn["description"] or "stock" in fn["description"].lower()
    assert function_name_map["run_stock_analysis"] == "stock-analysis"


def test_build_tools_multiple():
    from app.config import Settings
    from app.core.intent_classifier import IntentClassifier
    from app.core.llm_client import LLMClient
    settings = Settings(AEGIS_ALPHA_LANGCHAIN_API_KEY="test")
    client = LLMClient(settings)
    classifier = IntentClassifier(client)
    workflows = [
        {"workflowKey": "stock-analysis", "name": "Stock Analysis"},
        {"workflowKey": "market-overview", "name": "Market Overview"},
    ]
    tools, function_name_map = classifier._build_tools(workflows)
    assert len(tools) == 2
    assert function_name_map["run_stock_analysis"] == "stock-analysis"
    assert function_name_map["run_market_overview"] == "market-overview"


def test_build_tools_underscore_keys():
    """Seeded keys like stock_analysis must round-trip without becoming stock-analysis."""
    from app.config import Settings
    from app.core.intent_classifier import IntentClassifier
    from app.core.llm_client import LLMClient
    settings = Settings(AEGIS_ALPHA_LANGCHAIN_API_KEY="test")
    client = LLMClient(settings)
    classifier = IntentClassifier(client)
    workflows = [
        {"workflowKey": "stock_analysis", "name": "Stock Analysis"},
        {"workflowKey": "deep_dive", "name": "Deep Dive"},
    ]
    tools, function_name_map = classifier._build_tools(workflows)
    assert len(tools) == 2
    assert tools[0]["function"]["name"] == "run_stock_analysis"
    assert tools[1]["function"]["name"] == "run_deep_dive"
    assert function_name_map["run_stock_analysis"] == "stock_analysis"
    assert function_name_map["run_deep_dive"] == "deep_dive"


def test_build_tools_collision_prefers_first():
    """Keys that differ only by - vs _ map to the same function name; keep first."""
    from app.config import Settings
    from app.core.intent_classifier import IntentClassifier
    from app.core.llm_client import LLMClient
    settings = Settings(AEGIS_ALPHA_LANGCHAIN_API_KEY="test")
    client = LLMClient(settings)
    classifier = IntentClassifier(client)
    workflows = [
        {"workflowKey": "stock-analysis", "name": "Hyphen"},
        {"workflowKey": "stock_analysis", "name": "Underscore"},
    ]
    tools, function_name_map = classifier._build_tools(workflows)
    assert len(tools) == 2
    assert function_name_map["run_stock_analysis"] == "stock-analysis"


def test_keyword_fallback_match():
    from app.config import Settings
    from app.core.intent_classifier import IntentClassifier
    from app.core.llm_client import LLMClient
    settings = Settings(AEGIS_ALPHA_LANGCHAIN_API_KEY="test")
    client = LLMClient(settings)
    classifier = IntentClassifier(client)
    workflows = [
        {"workflowKey": "stock-analysis", "name": "Stock Analysis", "triggerKeywords": "stock analyze market"},
    ]
    result = classifier._keyword_fallback("analyze stock", workflows)
    assert result is not None
    assert result.workflow_key == "stock-analysis"


def test_keyword_fallback_underscore_key():
    from app.config import Settings
    from app.core.intent_classifier import IntentClassifier
    from app.core.llm_client import LLMClient
    settings = Settings(AEGIS_ALPHA_LANGCHAIN_API_KEY="test")
    client = LLMClient(settings)
    classifier = IntentClassifier(client)
    workflows = [
        {"workflowKey": "stock_analysis", "name": "Stock Analysis", "triggerKeywords": "stock analyze market"},
    ]
    result = classifier._keyword_fallback("analyze stock", workflows)
    assert result.workflow_key == "stock_analysis"


def test_keyword_fallback_no_match():
    from app.config import Settings
    from app.core.intent_classifier import IntentClassifier
    from app.core.llm_client import LLMClient
    settings = Settings(AEGIS_ALPHA_LANGCHAIN_API_KEY="test")
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
    settings = Settings(AEGIS_ALPHA_LANGCHAIN_API_KEY="test")
    client = LLMClient(settings)
    classifier = IntentClassifier(client)
    workflows = [
        {"workflowKey": "stock-analysis", "name": "Stock Analysis"},
    ]
    result = classifier._keyword_fallback("AAPL analysis", workflows)
    assert result.ticker == "AAPL"


def test_intent_result_serializes_workflow_key_camel_case():
    """JSON/API output must use workflowKey for Java IntentRouterService."""
    from app.models.responses import IntentResult

    result = IntentResult(
        workflow_key="stock_analysis",
        ticker="AAPL",
        confidence=0.9,
        source="llm_function_calling",
    )
    data = result.model_dump()
    assert "workflowKey" in data
    assert data["workflowKey"] == "stock_analysis"
    assert "workflow_key" not in data

    # Explicit by_alias remains correct
    data_alias = result.model_dump(by_alias=True)
    assert data_alias["workflowKey"] == "stock_analysis"

    # Also accept snake_case on input (populate_by_name)
    from_snake = IntentResult.model_validate({"workflow_key": "deep_dive", "confidence": 0.5})
    assert from_snake.workflow_key == "deep_dive"
    from_camel = IntentResult.model_validate({"workflowKey": "deep_dive", "confidence": 0.5})
    assert from_camel.workflow_key == "deep_dive"


@pytest.mark.asyncio
async def test_classify_empty_message():
    from app.config import Settings
    from app.core.intent_classifier import IntentClassifier
    from app.core.llm_client import LLMClient
    settings = Settings(AEGIS_ALPHA_LANGCHAIN_API_KEY="test")
    client = LLMClient(settings)
    classifier = IntentClassifier(client)
    result = await classifier.classify("", [{"workflowKey": "test"}])
    assert result.workflow_key is None
    assert result.confidence == 0


@pytest.mark.asyncio
async def test_classify_llm_preserves_underscore_workflow_key():
    from app.config import Settings
    from app.core.intent_classifier import IntentClassifier
    from app.core.llm_client import LLMClient

    settings = Settings(AEGIS_ALPHA_LANGCHAIN_API_KEY="test")
    client = LLMClient(settings)
    client.classify_intent = AsyncMock(
        return_value={"function": "run_stock_analysis", "ticker": "AAPL"}
    )
    classifier = IntentClassifier(client)
    workflows = [
        {"workflowKey": "stock_analysis", "name": "Stock Analysis"},
        {"workflowKey": "deep_dive", "name": "Deep Dive"},
    ]
    result = await classifier.classify("analyze AAPL", workflows)
    assert result.workflow_key == "stock_analysis"
    assert result.ticker == "AAPL"
    assert result.confidence == 0.9


@pytest.mark.asyncio
async def test_classify_llm_preserves_hyphen_workflow_key():
    from app.config import Settings
    from app.core.intent_classifier import IntentClassifier
    from app.core.llm_client import LLMClient

    settings = Settings(AEGIS_ALPHA_LANGCHAIN_API_KEY="test")
    client = LLMClient(settings)
    client.classify_intent = AsyncMock(
        return_value={"function": "run_stock_analysis", "ticker": "MSFT"}
    )
    classifier = IntentClassifier(client)
    workflows = [
        {"workflowKey": "stock-analysis", "name": "Stock Analysis"},
    ]
    result = await classifier.classify("analyze MSFT", workflows)
    assert result.workflow_key == "stock-analysis"
    assert result.ticker == "MSFT"
