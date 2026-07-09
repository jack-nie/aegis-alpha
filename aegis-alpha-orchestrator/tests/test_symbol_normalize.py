"""Tests for symbol/market normalization hard-fail rules."""

import pytest

from app.utils.symbol_normalize import normalize_symbol, try_normalize_symbol, SymbolNormalizationError


def test_us_ticker():
    assert normalize_symbol("aapl") == {"symbol": "AAPL", "market": "US"}


def test_a_share_with_suffix():
    assert normalize_symbol("600519.SH") == {"symbol": "600519.SH", "market": "SH"}
    assert normalize_symbol("000001.SZ") == {"symbol": "000001.SZ", "market": "SZ"}


def test_bare_a_share_requires_market():
    with pytest.raises(SymbolNormalizationError):
        normalize_symbol("600519")
    assert normalize_symbol("600519", "SH")["symbol"] == "600519.SH"


def test_numeric_not_us():
    with pytest.raises(SymbolNormalizationError):
        normalize_symbol("600519", "US")


def test_try_normalize_ok_false():
    result = try_normalize_symbol("600519")
    assert result["ok"] is False
    assert "ambiguous" in result["error"]
