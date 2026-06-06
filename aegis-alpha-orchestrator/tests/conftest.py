"""Shared test fixtures."""

import os
import pytest
import asyncio
from unittest.mock import AsyncMock, MagicMock, patch

os.environ.setdefault("MARKETMIND_LANGCHAIN_MOCK", "true")
os.environ.setdefault("MARKETMIND_LANGCHAIN_API_KEY", "test-key")


@pytest.fixture
def event_loop():
    loop = asyncio.new_event_loop()
    yield loop
    loop.close()


@pytest.fixture
def mock_settings():
    from app.config import Settings
    return Settings(
        MARKETMIND_LANGGRAPH_PORT=8787,
        MARKETMIND_LANGGRAPH_HOST="0.0.0.0",
        MARKETMIND_LANGCHAIN_PROVIDER="openai",
        MARKETMIND_LANGCHAIN_MODEL="gpt-4o-mini",
        MARKETMIND_LANGCHAIN_API_KEY="test-key",
        MARKETMIND_LANGCHAIN_MOCK=True,
        MARKETMIND_DATA_DIR="/tmp/aegis-test-data",
        MARKETMIND_STORE_TTL_SECONDS=3600,
    )


@pytest.fixture
def mock_llm_client(mock_settings):
    from app.core.llm_client import LLMClient, LLMResponse
    client = LLMClient(mock_settings)
    client._build_client = MagicMock(return_value=MagicMock())
    return client


@pytest.fixture
def mock_market_data(mock_settings):
    from app.core.market_data import MarketDataService
    service = MarketDataService(mock_settings)
    service.hydrate = AsyncMock(return_value=None)
    return service


@pytest.fixture
def mock_node_executor(mock_settings, mock_llm_client, mock_market_data):
    from app.core.node_executor import NodeExecutor
    return NodeExecutor(mock_settings, mock_llm_client, mock_market_data)


@pytest.fixture
def sample_nodes():
    from app.models.workflow import Node, NodeData
    return [
        Node(id="start", data=NodeData(handler="start", nodeType="start")),
        Node(id="market", data=NodeData(handler="finance.market_analysis", label="Market Analysis")),
        Node(id="aggregate", data=NodeData(handler="finance.stock_recommendation_aggregate", label="Report")),
        Node(id="end", data=NodeData(handler="end", nodeType="end")),
    ]


@pytest.fixture
def sample_edges():
    from app.models.workflow import Edge
    return [
        Edge(source="start", target="market"),
        Edge(source="market", target="aggregate"),
        Edge(source="aggregate", target="end"),
    ]