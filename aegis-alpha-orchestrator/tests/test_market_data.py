"""Tests for market data service."""

import pytest
from unittest.mock import AsyncMock, MagicMock, patch


def test_should_hydrate_yes():
    from app.config import Settings
    from app.core.market_data import MarketDataService
    settings = Settings(AEGIS_ALPHA_LANGCHAIN_API_KEY="test")
    service = MarketDataService(settings)
    assert service.should_hydrate("finance.market_analysis") is True
    assert service.should_hydrate("finance.technical_analysis") is True
    assert service.should_hydrate("general.agent") is True


def test_should_hydrate_aggregate():
    from app.config import Settings
    from app.core.market_data import MarketDataService
    settings = Settings(AEGIS_ALPHA_LANGCHAIN_API_KEY="test")
    service = MarketDataService(settings)
    assert service.should_hydrate("finance.stock_recommendation_aggregate") is False


def test_extract_ticker_from_state():
    from app.config import Settings
    from app.core.market_data import MarketDataService
    settings = Settings(AEGIS_ALPHA_LANGCHAIN_API_KEY="test")
    service = MarketDataService(settings)
    assert service._extract_ticker({"ticker": "AAPL"}, "") == "AAPL"
    assert service._extract_ticker({"symbol": "MSFT"}, "") == "MSFT"
    assert service._extract_ticker({"stockTicker": "GOOGL"}, "") == "GOOGL"


def test_extract_ticker_from_subject():
    from app.config import Settings
    from app.core.market_data import MarketDataService
    settings = Settings(AEGIS_ALPHA_LANGCHAIN_API_KEY="test")
    service = MarketDataService(settings)
    assert service._extract_ticker({}, "AAPL analysis") == "AAPL"
    assert service._extract_ticker({}, "MSFT deep dive") == "MSFT"


def test_extract_ticker_no_match():
    from app.config import Settings
    from app.core.market_data import MarketDataService
    settings = Settings(AEGIS_ALPHA_LANGCHAIN_API_KEY="test")
    service = MarketDataService(settings)
    assert service._extract_ticker({}, "analyze the market") == ""


def test_extract_ticker_priority():
    from app.config import Settings
    from app.core.market_data import MarketDataService
    settings = Settings(AEGIS_ALPHA_LANGCHAIN_API_KEY="test")
    service = MarketDataService(settings)
    assert service._extract_ticker({"ticker": "AAPL"}, "MSFT analysis") == "AAPL"