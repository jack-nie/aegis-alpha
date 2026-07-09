"""LangChain tools for agent tool-calling.

Tool instances are built here; role allowlists live in ``tool_registry``.
``create_tools`` remains the public full-list factory (backward compatible).

Portfolio reads on the Java backend accept:
- user Bearer token
- service node-execution-token
- run-scoped delegation token (typ=delegation, scope portfolio:read)

Use ``authorization_override`` contextvar or ``AEGIS_ALPHA_DELEGATED_TOKEN`` to
inject a delegation token without changing the default node token path.
"""

from __future__ import annotations

import logging
from contextlib import contextmanager
from contextvars import ContextVar
from typing import Any, Iterator

import httpx
from langchain_core.tools import tool

from ..config import Settings

logger = logging.getLogger(__name__)

# Run-scoped Authorization override for future inject from workflow executor.
authorization_override: ContextVar[str | None] = ContextVar("authorization_override", default=None)


class ToolBackendClient:
    """HTTP client for calling Java backend APIs from tools."""

    def __init__(self, config: Settings):
        self._config = config
        self._client: httpx.AsyncClient | None = None
        self._extra_headers: dict[str, str] = {}

    def set_extra_headers(self, headers: dict[str, str] | None) -> None:
        """Merge/replace sticky extra headers on subsequent requests."""
        self._extra_headers = dict(headers or {})

    def clear_extra_headers(self) -> None:
        self._extra_headers = {}

    def _authorization_header(self) -> str:
        """Resolve Authorization: context override > extra headers > delegated token > node token.

        ``Settings.delegated_token`` maps env ``AEGIS_ALPHA_DELEGATED_TOKEN`` (tests/local inject).
        """
        override = authorization_override.get()
        if override:
            return override if override.lower().startswith("bearer ") else f"Bearer {override}"

        extra_auth = self._extra_headers.get("Authorization") or self._extra_headers.get("authorization")
        if extra_auth:
            return extra_auth

        delegated = (getattr(self._config, "delegated_token", None) or "").strip()
        if delegated:
            return delegated if delegated.lower().startswith("bearer ") else f"Bearer {delegated}"

        return f"Bearer {self._config.node_execution_token}"

    def _request_headers(self) -> dict[str, str]:
        headers = dict(self._extra_headers)
        headers["Authorization"] = self._authorization_header()
        return headers

    async def start(self) -> None:
        if self._client is None or self._client.is_closed:
            self._client = httpx.AsyncClient(timeout=self._config.market_data_timeout_ms / 1000)

    async def close(self) -> None:
        if self._client is not None and not self._client.is_closed:
            await self._client.aclose()
            self._client = None

    async def get(self, path: str, params: dict[str, Any] | None = None) -> dict[str, Any]:
        await self.start()
        backend_url = self._config.effective_backend_url
        try:
            response = await self._client.get(
                f"{backend_url}{path}",
                params=params or {},
                headers=self._request_headers(),
            )
            if response.status_code == 200:
                return response.json()
            return {"error": f"HTTP {response.status_code}", "status": "unavailable"}
        except Exception as e:
            logger.error(f"Tool backend call failed: {path} - {e}")
            return {"error": str(e), "status": "error"}


@contextmanager
def use_authorization(token: str | None) -> Iterator[None]:
    """Temporarily override Authorization for tool backend calls in this context."""
    token_handle = authorization_override.set(token)
    try:
        yield
    finally:
        authorization_override.reset(token_handle)


_backend_client: ToolBackendClient | None = None


def get_backend_client(config: Settings) -> ToolBackendClient:
    global _backend_client
    if _backend_client is None:
        _backend_client = ToolBackendClient(config)
    return _backend_client


def create_tools(config: Settings) -> list:
    """Create all LangChain tools (full list; backward compatible with pre-registry callers)."""
    client = get_backend_client(config)

    @tool
    async def get_stock_quote(symbol: str) -> dict[str, Any]:
        """Get real-time stock quote including price, volume, market cap, P/E ratio.

        Args:
            symbol: Stock ticker symbol (e.g., 'AAPL', 'MSFT', '600519.SH')
        """
        return await client.get("/_backend/market-data/quote", {"symbol": symbol})

    @tool
    async def get_financials(symbol: str) -> dict[str, Any]:
        """Get financial statements and fundamentals (revenue, earnings, margins, ROE).

        Args:
            symbol: Stock ticker symbol (e.g., 'AAPL', 'MSFT')
        """
        return await client.get("/_backend/market-data/financials", {"symbol": symbol})

    @tool
    async def get_news(symbol: str) -> dict[str, Any]:
        """Get recent news articles and sentiment data for a stock.

        Args:
            symbol: Stock ticker symbol (e.g., 'AAPL', 'MSFT')
        """
        return await client.get("/_backend/market-data/news", {"symbol": symbol})

    @tool
    async def get_company_overview(symbol: str) -> dict[str, Any]:
        """Get company overview including industry, sector, description, key metrics.

        Args:
            symbol: Stock ticker symbol (e.g., 'AAPL', 'MSFT')
        """
        return await client.get("/_backend/market-data/overview", {"symbol": symbol})

    @tool
    async def get_portfolio_positions(portfolio_id: str) -> dict[str, Any]:
        """Get current holdings and positions in a portfolio.

        Args:
            portfolio_id: Portfolio ID (e.g., '1', '2')
        """
        # Portfolio positions/summary accept user token, node token, or portfolio:read delegation.
        return await client.get(f"/_backend/portfolio/{portfolio_id}/positions")

    @tool
    async def get_portfolio_summary(portfolio_id: str) -> dict[str, Any]:
        """Get portfolio summary including total value, P&L, allocation.

        Args:
            portfolio_id: Portfolio ID (e.g., '1', '2')
        """
        return await client.get(f"/_backend/portfolio/{portfolio_id}/summary")

    return [
        get_stock_quote,
        get_financials,
        get_news,
        get_company_overview,
        get_portfolio_positions,
        get_portfolio_summary,
    ]


def create_tools_for_roles(config: Settings, roles: list[str]) -> list:
    """Create tools filtered by specialist role allowlists."""
    from .tool_registry import create_tool_registry, tools_for_roles

    registry = create_tool_registry(config)
    return tools_for_roles(registry, roles)
