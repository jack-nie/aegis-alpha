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


def test_authorization_defaults_to_node_token():
    from app.config import Settings
    from app.core.tools import ToolBackendClient

    settings = Settings(
        AEGIS_ALPHA_LANGCHAIN_API_KEY="test",
        AEGIS_ALPHA_NODE_EXECUTION_TOKEN="node-tok",
        AEGIS_ALPHA_DELEGATED_TOKEN="",
    )
    client = ToolBackendClient(settings)
    assert client._authorization_header() == "Bearer node-tok"


def test_authorization_uses_delegated_token_config():
    from app.config import Settings
    from app.core.tools import ToolBackendClient

    settings = Settings(
        AEGIS_ALPHA_LANGCHAIN_API_KEY="test",
        AEGIS_ALPHA_NODE_EXECUTION_TOKEN="node-tok",
        AEGIS_ALPHA_DELEGATED_TOKEN="deleg-tok",
    )
    client = ToolBackendClient(settings)
    assert client._authorization_header() == "Bearer deleg-tok"


def test_authorization_override_contextvar_wins():
    from app.config import Settings
    from app.core.tools import ToolBackendClient, use_authorization

    settings = Settings(
        AEGIS_ALPHA_LANGCHAIN_API_KEY="test",
        AEGIS_ALPHA_NODE_EXECUTION_TOKEN="node-tok",
        AEGIS_ALPHA_DELEGATED_TOKEN="deleg-tok",
    )
    client = ToolBackendClient(settings)
    with use_authorization("Bearer override-tok"):
        assert client._authorization_header() == "Bearer override-tok"
    assert client._authorization_header() == "Bearer deleg-tok"


def test_set_extra_headers_authorization():
    from app.config import Settings
    from app.core.tools import ToolBackendClient

    settings = Settings(
        AEGIS_ALPHA_LANGCHAIN_API_KEY="test",
        AEGIS_ALPHA_NODE_EXECUTION_TOKEN="node-tok",
        AEGIS_ALPHA_DELEGATED_TOKEN="",
    )
    client = ToolBackendClient(settings)
    client.set_extra_headers({"Authorization": "Bearer sticky-tok", "X-Trace": "1"})
    headers = client._request_headers()
    assert headers["Authorization"] == "Bearer sticky-tok"
    assert headers["X-Trace"] == "1"
    client.clear_extra_headers()
    assert client._authorization_header() == "Bearer node-tok"

# ---------------- P1-9: portfolio tools require delegation + numeric id ----------------


def _portfolio_tools(delegated: str = ""):
    from app.config import Settings
    from app.core import tools as tools_mod

    settings = Settings(
        AEGIS_ALPHA_LANGCHAIN_API_KEY="test",
        AEGIS_ALPHA_BACKEND_URL="http://localhost:5178",
        AEGIS_ALPHA_NODE_EXECUTION_TOKEN="shared-node-token",
        AEGIS_ALPHA_DELEGATED_TOKEN=delegated,
    )
    tools_mod._backend_client = None
    built = {t.name: t for t in tools_mod.create_tools(settings)}
    client = tools_mod.get_backend_client(settings)
    response = MagicMock()
    response.status_code = 200
    response.json.return_value = {"positions": []}
    http = AsyncMock()
    http.get = AsyncMock(return_value=response)
    http.is_closed = False
    client._client = http
    return tools_mod, built, http


@pytest.mark.asyncio
async def test_portfolio_tool_without_delegation_is_denied():
    tools_mod, built, http = _portfolio_tools()
    try:
        for name in ("get_portfolio_positions", "get_portfolio_summary"):
            result = await built[name].ainvoke({"portfolio_id": "1"})
            assert result == {"error": "delegation_required", "status": "denied"}
        http.get.assert_not_called()
    finally:
        tools_mod._backend_client = None


@pytest.mark.asyncio
@pytest.mark.parametrize("bad_id", ["1/../../admin", "abc", "", "1234567890123", "1?x=1"])
async def test_portfolio_tool_rejects_invalid_id(bad_id):
    tools_mod, built, http = _portfolio_tools()
    try:
        with tools_mod.use_authorization("delegation-token"):
            result = await built["get_portfolio_positions"].ainvoke({"portfolio_id": bad_id})
        assert result == {"error": "invalid_portfolio_id", "status": "denied"}
        http.get.assert_not_called()
    finally:
        tools_mod._backend_client = None


@pytest.mark.asyncio
async def test_portfolio_tool_with_delegation_calls_backend():
    tools_mod, built, http = _portfolio_tools()
    try:
        with tools_mod.use_authorization("delegation-token"):
            result = await built["get_portfolio_summary"].ainvoke({"portfolio_id": "42"})
        assert result == {"positions": []}
        url = http.get.call_args.args[0]
        assert url.endswith("/_backend/portfolio/42/summary")
        assert http.get.call_args.kwargs["headers"]["Authorization"] == "Bearer delegation-token"
    finally:
        tools_mod._backend_client = None


@pytest.mark.asyncio
async def test_portfolio_tool_with_configured_delegated_token_calls_backend():
    tools_mod, built, http = _portfolio_tools(delegated="env-delegation")
    try:
        result = await built["get_portfolio_positions"].ainvoke({"portfolio_id": "7"})
        assert result == {"positions": []}
        assert http.get.call_args.kwargs["headers"]["Authorization"] == "Bearer env-delegation"
    finally:
        tools_mod._backend_client = None
