"""Market data hydration service."""

from __future__ import annotations

import logging
from typing import Any

import httpx

from ..config import Settings
from ..utils.symbol_normalize import try_normalize_symbol

logger = logging.getLogger(__name__)

# Handlers that don't need market data hydration
SKIP_HYDRATION_HANDLERS = {"finance.stock_recommendation_aggregate"}


class MarketDataService:
    """Fetches real-time market data from backend service."""

    def __init__(self, config: Settings):
        self._config = config
        self._client: httpx.AsyncClient | None = None

    async def start(self) -> None:
        """Initialize the shared HTTP client."""
        if self._client is None or self._client.is_closed:
            self._client = httpx.AsyncClient(timeout=self._config.market_data_timeout_ms / 1000)

    async def close(self) -> None:
        """Close the shared HTTP client."""
        if self._client is not None and not self._client.is_closed:
            await self._client.aclose()
            self._client = None

    def should_hydrate(self, handler: str) -> bool:
        """Check if handler needs market data hydration."""
        return handler not in SKIP_HYDRATION_HANDLERS

    async def hydrate(
        self,
        handler: str,
        state: dict[str, Any],
        subject: str,
        node: dict[str, Any],
    ) -> dict[str, Any] | None:
        """Fetch market data for the given subject."""
        if not self.should_hydrate(handler):
            return None

        resolved = self._resolve_symbol(state, subject)
        if not resolved.get("ok"):
            error = resolved.get("error") or "symbol_unresolved"
            raw = resolved.get("raw") or ""
            if not raw and error in ("empty_symbol", "no_ticker"):
                return None
            logger.warning(
                "Market hydration skipped: symbol normalize failed raw=%s error=%s",
                raw,
                error,
            )
            return {
                "ok": False,
                "status": "missing_symbol",
                "error": error,
                "rawSymbol": raw,
                "missing": ["normalized_symbol"],
            }

        ticker = resolved["symbol"]
        backend_url = self._config.effective_backend_url

        try:
            await self.start()
            response = await self._client.get(
                f"{backend_url}/_backend/market-data/quote",
                params={"symbol": ticker},
                headers={
                    "Authorization": f"Bearer {self._config.node_execution_token}",
                    "X-Node-Id": node.get("id", "unknown"),
                },
            )
            if response.status_code == 200:
                data = response.json()
                logger.info(f"Hydrated market data for {ticker}")
                if isinstance(data, dict):
                    data = {
                        **data,
                        "normalizedSymbol": ticker,
                        "market": resolved.get("market"),
                    }
                return data
            else:
                logger.warning(
                    f"Market data fetch failed: {response.status_code}"
                )
                return {"ok": False, "status": "unavailable", "error": f"HTTP {response.status_code}"}
        except Exception as e:
            logger.error(f"Market data hydration error: {e}")
            return {"ok": False, "status": "unavailable", "error": str(e)}

    def _resolve_symbol(self, state: dict[str, Any], subject: str) -> dict[str, Any]:
        """Extract and normalize ticker; hard-fail ambiguous A-share bare codes."""
        raw = self._extract_ticker(state, subject)
        if not raw:
            return {"ok": False, "error": "no_ticker", "raw": ""}

        market_hint = None
        if isinstance(state, dict):
            market_hint = (
                state.get("market")
                or state.get("marketHint")
                or state.get("exchange")
            )
            if market_hint is not None:
                market_hint = str(market_hint)

        normalized = try_normalize_symbol(raw, market_hint)
        if not normalized.get("ok"):
            return {
                "ok": False,
                "error": normalized.get("error") or "symbol_normalize_failed",
                "raw": raw,
                "symbol": raw,
            }
        return {
            "ok": True,
            "symbol": normalized["symbol"],
            "market": normalized.get("market"),
            "raw": raw,
        }

    def _extract_ticker(self, state: dict[str, Any], subject: str) -> str:
        """Extract ticker symbol from state or subject."""
        # Try state first
        ticker = state.get("ticker") or state.get("symbol") or state.get("stockTicker")
        if ticker:
            return str(ticker).strip()

        # Try to extract from subject (e.g., "AAPL analysis" -> "AAPL")
        if subject:
            parts = subject.split()
            if parts:
                candidate = parts[0].strip()
                upper = candidate.upper()
                # US-style: 1-5 letters
                if 1 <= len(upper) <= 5 and upper.isalpha():
                    return upper
                # A-share / HK style tokens (digits or digits.suffix)
                if any(ch.isdigit() for ch in candidate) and len(candidate) <= 12:
                    return candidate
        return ""
