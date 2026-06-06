"""Tests for memory store manager."""

import os
import pytest
import tempfile


@pytest.fixture
async def memory_mgr():
    from app.config import Settings
    from app.core.memory_store import MemoryStoreManager
    with tempfile.TemporaryDirectory() as tmpdir:
        settings = Settings(
            MARKETMIND_LANGCHAIN_API_KEY="test",
            MARKETMIND_DATA_DIR=tmpdir,
            MARKETMIND_STORE_TTL_SECONDS=3600,
        )
        mgr = MemoryStoreManager(settings)
        await mgr.initialize()
        yield mgr
        await mgr.close()


@pytest.mark.asyncio
async def test_user_preference(memory_mgr):
    await memory_mgr.store_user_preference("user1", "risk_tolerance", "conservative")
    result = await memory_mgr.get_user_preference("user1", "risk_tolerance")
    assert result == "conservative"


@pytest.mark.asyncio
async def test_user_preference_missing(memory_mgr):
    result = await memory_mgr.get_user_preference("nonexistent", "key")
    assert result is None


@pytest.mark.asyncio
async def test_ticker_insight(memory_mgr):
    await memory_mgr.store_ticker_insight("AAPL", "analysis", {"summary": "Bullish", "confidence": 0.8})
    result = await memory_mgr.get_ticker_insight("AAPL", "analysis")
    assert result is not None
    assert result["summary"] == "Bullish"


@pytest.mark.asyncio
async def test_workflow_pattern(memory_mgr):
    await memory_mgr.store_workflow_pattern("stock-analysis", "default_params", {"ticker": "AAPL"})
    result = await memory_mgr.get_workflow_pattern("stock-analysis", "default_params")
    assert result["ticker"] == "AAPL"


@pytest.mark.asyncio
async def test_cleanup(memory_mgr):
    count = await memory_mgr.cleanup()
    assert isinstance(count, int)


@pytest.mark.asyncio
async def test_search(memory_mgr):
    await memory_mgr.store_user_preference("user1", "pref1", "val1")
    await memory_mgr.store_user_preference("user1", "pref2", "val2")
    results = await memory_mgr.search(("user", "user1"), limit=10)
    assert len(results) >= 2


@pytest.mark.asyncio
async def test_delete(memory_mgr):
    await memory_mgr.store_user_preference("user1", "pref_del", "val")
    await memory_mgr.delete(("user", "user1"), "pref_del")
    result = await memory_mgr.get_user_preference("user1", "pref_del")
    assert result is None


@pytest.mark.asyncio
async def test_metadata_stripped(memory_mgr):
    await memory_mgr.store_user_preference("user1", "meta_test", "value1")
    result = await memory_mgr.get(("user", "user1"), "meta_test")
    assert "_expires_at" not in result
    assert "_updated_at" not in result