"""LangChain tools for agent tool-calling."""

from __future__ import annotations

import logging
from typing import Any

import httpx
from langchain_core.tools import tool

from ..config import Settings

logger = logging.getLogger(__name__)


class ToolBackendClient:
    """HTTP client for calling Java backend APIs from tools."""

    def __init__(self, config: Settings):
        self._config = config
        self._client: httpx.AsyncClient | None = None

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
                headers={"Authorization": f"Bearer {self._config.node_execution_token}"},
            )
            if response.status_code == 200:
                return response.json()
            return {"error": f"HTTP {response.status_code}", "status": "unavailable"}
        except Exception as e:
            logger.error(f"Tool backend call failed: {path} - {e}")
            return {"error": str(e), "status": "error"}


_backend_client: ToolBackendClient | None = None


def get_backend_client(config: Settings) -> ToolBackendClient:
    global _backend_client
    if _backend_client is None:
        _backend_client = ToolBackendClient(config)
    return _backend_client


def create_tools(config: Settings) -> list:
    """Create all LangChain tools."""
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
        # PortfolioController is under /_backend/portfolio; endpoints require user auth (authService.me).
        return await client.get(f"/_backend/portfolio/{portfolio_id}/positions")

    @tool
    async def get_portfolio_summary(portfolio_id: str) -> dict[str, Any]:
        """Get portfolio summary including total value, P&L, allocation.
        
        Args:
            portfolio_id: Portfolio ID (e.g., '1', '2')
        """
        # PortfolioController is under /_backend/portfolio; endpoints require user auth (authService.me).
        return await client.get(f"/_backend/portfolio/{portfolio_id}/summary")

    return [
        get_stock_quote,
        get_financials,
        get_news,
        get_company_overview,
        get_portfolio_positions,
        get_portfolio_summary,
    ]
