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
        return_value={"function": "run_stock_analysis", "ticker": "AAPL", "confidence": 0.9}
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


# ---- P1-1: confidence comes from the model, never a constant ----


def _classifier_with(result):
    from app.config import Settings
    from app.core.intent_classifier import IntentClassifier
    from app.core.llm_client import LLMClient

    client = LLMClient(Settings(AEGIS_ALPHA_LANGCHAIN_API_KEY="test"))
    client.classify_intent = AsyncMock(return_value=result)
    return IntentClassifier(client)


_WORKFLOWS = [
    {"workflowKey": "daily", "name": "Daily", "triggerKeywords": "daily,日报"},
    {"workflowKey": "deep_dive", "name": "Deep Dive", "triggerKeywords": "deep dive"},
]


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "raw, expected",
    [(0.3, 0.3), (0.95, 0.95), (1.7, 1.0), (-2, 0.0), ("0.4", 0.4), ("bogus", 0.5), (None, 0.5)],
)
async def test_confidence_is_taken_from_model_and_clamped(raw, expected):
    result = await _classifier_with(
        {"function": "run_daily", "ticker": "", "confidence": raw}
    ).classify("给我一份盘前日报", _WORKFLOWS)
    assert result.workflow_key == "daily"
    assert result.source == "llm_function_calling"
    assert result.confidence == pytest.approx(expected)


@pytest.mark.asyncio
async def test_missing_confidence_defaults_to_half():
    result = await _classifier_with({"function": "run_daily", "ticker": ""}).classify(
        "给我一份盘前日报", _WORKFLOWS
    )
    assert result.confidence == 0.5


@pytest.mark.asyncio
async def test_corrupted_arguments_fall_back_to_keywords():
    result = await _classifier_with(
        {"function": "run_deep_dive", "ticker": "", "confidence": None, "argsError": True}
    ).classify("daily briefing please", _WORKFLOWS)
    assert result.source == "keyword_fallback"
    assert result.workflow_key == "daily"


@pytest.mark.asyncio
async def test_unknown_function_falls_back_to_keywords():
    result = await _classifier_with(
        {"function": "run_unknown", "ticker": "", "confidence": 0.99}
    ).classify("hello there", _WORKFLOWS)
    assert result.source in {"keyword_fallback", "no_match"}
    assert result.confidence < 0.9


def test_tools_require_confidence_parameter():
    from app.config import Settings
    from app.core.intent_classifier import IntentClassifier
    from app.core.llm_client import LLMClient

    classifier = IntentClassifier(LLMClient(Settings(AEGIS_ALPHA_LANGCHAIN_API_KEY="test")))
    tools, _ = classifier._build_tools(_WORKFLOWS)
    params = tools[0]["function"]["parameters"]
    assert params["properties"]["confidence"]["type"] == "number"
    assert "confidence" in params["required"]
