"""Tests for node executor."""

import pytest
from unittest.mock import AsyncMock, MagicMock

from app.models.workflow import Node, NodeData, AgentTemplate
from app.models.responses import NodeResult


def test_handlers_set():
    from app.core.node_executor import HANDLERS, FALLBACK_HANDLERS
    assert "finance.market_analysis" in HANDLERS
    assert "finance.stock_recommendation_aggregate" in HANDLERS
    assert "general.agent" in HANDLERS
    assert "start" in FALLBACK_HANDLERS
    assert "end" in FALLBACK_HANDLERS


def test_resolve_handler(mock_node_executor):
    node = Node(id="test", data=NodeData(handler="finance.market_analysis"))
    assert mock_node_executor._resolve_handler(node) == "finance.market_analysis"


def test_resolve_handler_fallback_function_name(mock_node_executor):
    node = Node(id="test", data=NodeData(handler="", functionName="finance.technical_analysis"))
    assert mock_node_executor._resolve_handler(node) == "finance.technical_analysis"


def test_resolve_handler_default(mock_node_executor):
    node = Node(id="test", data=NodeData(handler="", functionName=""))
    assert mock_node_executor._resolve_handler(node) == "logic"


def test_is_control_flow_start(mock_node_executor):
    assert mock_node_executor._is_control_flow("start", "start") is True


def test_is_control_flow_end(mock_node_executor):
    assert mock_node_executor._is_control_flow("end", "end") is True


def test_is_control_flow_condition(mock_node_executor):
    assert mock_node_executor._is_control_flow("condition", "condition") is True


def test_is_control_flow_business(mock_node_executor):
    assert mock_node_executor._is_control_flow("finance.market_analysis", "") is False


def test_is_retryable_timeout():
    from app.core.node_executor import NodeExecutor
    assert NodeExecutor._is_retryable(Exception("Connection timeout")) is True


def test_is_retryable_rate_limit():
    from app.core.node_executor import NodeExecutor
    assert NodeExecutor._is_retryable(Exception("429 rate limit exceeded")) is True


def test_is_retryable_server_error():
    from app.core.node_executor import NodeExecutor
    assert NodeExecutor._is_retryable(Exception("502 Bad Gateway")) is True


def test_is_retryable_auth_error():
    from app.core.node_executor import NodeExecutor
    assert NodeExecutor._is_retryable(Exception("401 Unauthorized")) is False


def test_is_retryable_api_key_error():
    from app.core.node_executor import NodeExecutor
    assert NodeExecutor._is_retryable(Exception("Invalid API key")) is False


def test_is_retryable_forbidden():
    from app.core.node_executor import NodeExecutor
    assert NodeExecutor._is_retryable(Exception("403 Forbidden")) is False


@pytest.mark.asyncio
async def test_execute_start_node(mock_node_executor):
    node = Node(id="start", data=NodeData(handler="start", nodeType="start"))
    result = await mock_node_executor.execute(node=node, state={}, subject="test")
    assert result.ok is True
    assert result.status == "tool-mock"
    assert result.degraded is True


@pytest.mark.asyncio
async def test_execute_mock_mode(mock_settings, mock_llm_client, mock_market_data):
    from app.core.node_executor import NodeExecutor
    from app.config import Settings
    settings = Settings(MARKETMIND_LANGCHAIN_API_KEY="", MARKETMIND_LANGCHAIN_MOCK=True)
    executor = NodeExecutor(settings, mock_llm_client, mock_market_data)
    node = Node(id="test", data=NodeData(handler="finance.market_analysis", label="Test"))
    result = await executor.execute(node=node, state={}, subject="AAPL")
    assert result.ok is True
    assert result.degraded is True


@pytest.mark.asyncio
async def test_unsupported_provider(mock_node_executor):
    node = Node(id="test", data=NodeData(handler="finance.market_analysis", label="Test"))
    result = await mock_node_executor.execute(node=node, state={}, subject="AAPL", provider="anthropic")
    assert result.ok is False
    assert result.status == "unsupported_provider"


def test_fallback_result_aggregate(mock_node_executor):
    node = Node(id="agg", data=NodeData(handler="finance.stock_recommendation_aggregate", label="Aggregate"))
    result = mock_node_executor._fallback_result(
        node=node, handler="finance.stock_recommendation_aggregate",
        subject="AAPL", state={}, started_at="2025-01-01T00:00:00Z", error="test error"
    )
    assert result.ok is False
    assert result.status == "model_failed"
    assert result.confidence == 0.0


def test_fallback_result_regular(mock_node_executor):
    node = Node(id="test", data=NodeData(handler="finance.market_analysis", label="Test"))
    result = mock_node_executor._fallback_result(
        node=node, handler="finance.market_analysis",
        subject="AAPL", state={}, started_at="2025-01-01T00:00:00Z", error="test error"
    )
    assert result.ok is True
    assert result.status == "degraded"
    assert result.confidence == 0.58