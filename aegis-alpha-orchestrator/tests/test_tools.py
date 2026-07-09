"""Tests for LangChain tools."""

import pytest
from unittest.mock import AsyncMock, patch, MagicMock


def test_create_tools_returns_list():
    from app.config import Settings
    from app.core.tools import create_tools
    settings = Settings(AEGIS_ALPHA_LANGCHAIN_API_KEY="test")
    tools = create_tools(settings)
    assert isinstance(tools, list)
    assert len(tools) == 6


def test_tool_names():
    from app.config import Settings
    from app.core.tools import create_tools
    settings = Settings(AEGIS_ALPHA_LANGCHAIN_API_KEY="test")
    tools = create_tools(settings)
    tool_names = [t.name for t in tools]
    assert "get_stock_quote" in tool_names
    assert "get_financials" in tool_names
    assert "get_news" in tool_names
    assert "get_company_overview" in tool_names
    assert "get_portfolio_positions" in tool_names
    assert "get_portfolio_summary" in tool_names


def test_tools_have_descriptions():
    from app.config import Settings
    from app.core.tools import create_tools
    settings = Settings(AEGIS_ALPHA_LANGCHAIN_API_KEY="test")
    tools = create_tools(settings)
    for tool in tools:
        assert tool.description is not None
        assert len(tool.description) > 0


@pytest.mark.asyncio
async def test_backend_client_get():
    from app.config import Settings
    from app.core.tools import ToolBackendClient
    settings = Settings(
        AEGIS_ALPHA_LANGCHAIN_API_KEY="test",
        AEGIS_ALPHA_LANGCHAIN_BASE_URL="",
        AEGIS_ALPHA_BACKEND_URL="http://localhost:5178",
        AEGIS_ALPHA_NODE_EXECUTION_TOKEN="test-token",
    )
    client = ToolBackendClient(settings)
    mock_response = MagicMock()
    mock_response.status_code = 200
    mock_response.json.return_value = {"symbol": "AAPL", "price": 150.0}
    mock_http_client = AsyncMock()
    mock_http_client.get = AsyncMock(return_value=mock_response)
    mock_http_client.is_closed = False
    client._client = mock_http_client
    client._initialized = True
    result = await client.get("/_backend/market-data/quote", {"symbol": "AAPL"})
    assert result["symbol"] == "AAPL"


@pytest.mark.asyncio
async def test_backend_client_error():
    from app.config import Settings
    from app.core.tools import ToolBackendClient
    settings = Settings(
        AEGIS_ALPHA_LANGCHAIN_API_KEY="test",
        AEGIS_ALPHA_BACKEND_URL="http://localhost:5178",
    )
    client = ToolBackendClient(settings)
    with patch("httpx.AsyncClient") as mock_cls:
        mock_instance = AsyncMock()
        mock_response = MagicMock()
        mock_response.status_code = 500
        mock_instance.get = AsyncMock(return_value=mock_response)
        mock_instance.is_closed = False
        mock_cls.return_value = mock_instance
        client._client = mock_instance
        client._client = mock_instance
        result = await client.get("/_backend/market-data/quote", {"symbol": "FAIL"})
        assert "error" in result


def test_get_backend_client_singleton():
    from app.config import Settings
    from app.core.tools import get_backend_client, _backend_client
    import app.core.tools as tools_module
    tools_module._backend_client = None
    settings = Settings(AEGIS_ALPHA_LANGCHAIN_API_KEY="test")
    c1 = get_backend_client(settings)
    c2 = get_backend_client(settings)
    assert c1 is c2
    tools_module._backend_client = None