"""Symbol / market normalization with hard-fail on ambiguous cases."""

from __future__ import annotations

import re
from typing import Any


class SymbolNormalizationError(ValueError):
    """Raised when symbol/market cannot be resolved safely."""


_A_SHARE_DIGITS = re.compile(r"^(\d{6})(?:\.(SH|SZ|SS))?$", re.IGNORECASE)
_US_TICKER = re.compile(r"^[A-Z]{1,5}(?:\.[A-Z])?$")
_HK_TICKER = re.compile(r"^(\d{1,5})(?:\.HK)?$", re.IGNORECASE)


def normalize_symbol(raw: str | None, market_hint: str | None = None) -> dict[str, str]:
    """
    Normalize a ticker into {symbol, market}.

    Rules:
    - 6-digit China codes → .SH/.SZ when suffix or market_hint present; bare 6 digits fail.
    - US tickers: 1-5 letters.
    - HK: digits with .HK or market_hint HK.
    """
    if raw is None or not str(raw).strip():
        raise SymbolNormalizationError("empty_symbol")

    text = str(raw).strip().upper().replace(" ", "")
    hint = (market_hint or "").strip().upper()

    # Explicit exchange suffix
    if text.endswith(".SH") or text.endswith(".SS"):
        code = text.split(".")[0]
        if not code.isdigit() or len(code) != 6:
            raise SymbolNormalizationError("invalid_a_share_symbol")
        return {"symbol": f"{code}.SH", "market": "SH"}
    if text.endswith(".SZ"):
        code = text.split(".")[0]
        if not code.isdigit() or len(code) != 6:
            raise SymbolNormalizationError("invalid_a_share_symbol")
        return {"symbol": f"{code}.SZ", "market": "SZ"}
    if text.endswith(".HK"):
        code = text.split(".")[0].lstrip("0") or "0"
        return {"symbol": f"{code.zfill(4)}.HK", "market": "HK"}

    a_match = _A_SHARE_DIGITS.match(text)
    if a_match and not a_match.group(2):
        # Bare 6 digits — require market hint
        if hint in ("SH", "SS", "SHA"):
            return {"symbol": f"{text}.SH", "market": "SH"}
        if hint in ("SZ", "SZA"):
            return {"symbol": f"{text}.SZ", "market": "SZ"}
        raise SymbolNormalizationError("ambiguous_a_share_requires_market")

    if hint in ("US", "NYSE", "NASDAQ") or _US_TICKER.match(text):
        if not _US_TICKER.match(text):
            raise SymbolNormalizationError("invalid_us_symbol")
        # Reject pure digits as US
        if text.isdigit():
            raise SymbolNormalizationError("numeric_symbol_not_us")
        return {"symbol": text, "market": "US"}

    if hint == "HK" or _HK_TICKER.match(text):
        if text.isdigit():
            return {"symbol": f"{text.zfill(4)}.HK", "market": "HK"}
        raise SymbolNormalizationError("invalid_hk_symbol")

    raise SymbolNormalizationError(f"unrecognized_symbol:{text}")


def try_normalize_symbol(raw: str | None, market_hint: str | None = None) -> dict[str, Any]:
    """Non-raising helper returning {ok, symbol?, market?, error?}."""
    try:
        result = normalize_symbol(raw, market_hint)
        return {"ok": True, **result}
    except SymbolNormalizationError as exc:
        return {"ok": False, "error": str(exc), "symbol": raw, "market": market_hint}
