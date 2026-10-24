"""Tests for memory store manager."""

import time

import pytest
import tempfile


@pytest.fixture
async def memory_mgr():
    from app.config import Settings
    from app.core.memory_store import MemoryStoreManager
    with tempfile.TemporaryDirectory() as tmpdir:
        settings = Settings(
            AEGIS_ALPHA_LANGCHAIN_API_KEY="test",
            AEGIS_ALPHA_DATA_DIR=tmpdir,
            AEGIS_ALPHA_STORE_TTL_SECONDS=3600,
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


def test_ticker_namespace_replaces_periods():
    from app.core.memory_selector import ticker_namespace

    assert ticker_namespace("600183.SH") == ("ticker", "600183_SH")
    assert ticker_namespace(" AAPL ") == ("ticker", "AAPL")


@pytest.mark.asyncio
async def test_ticker_insight_with_exchange_suffix(memory_mgr):
    await memory_mgr.store_ticker_insight("600183.SH", "analysis", {"summary": "Bullish"})
    result = await memory_mgr.get_ticker_insight("600183.SH", "analysis")
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
    assert "_ttl_remaining" not in result


async def _open_manager(tmp_path):
    from app.config import Settings
    from app.core.memory_store import MemoryStoreManager

    settings = Settings(
        AEGIS_ALPHA_LANGCHAIN_API_KEY="test",
        AEGIS_ALPHA_DATA_DIR=str(tmp_path),
        AEGIS_ALPHA_STORE_TTL_SECONDS=3600,
    )
    manager = MemoryStoreManager(settings)
    await manager.initialize()
    return manager


@pytest.mark.asyncio
async def test_ticker_roundtrip_ttl_and_approved_conclusion(tmp_path):
    """Stored payload round-trips with meta stripped; ttl 0 outlives ttl 1."""
    from app.core.memory_selector import load_run_memory, read_store_value

    manager = await _open_manager(tmp_path)
    try:
        payload = {"ok": True, "summary": "cached-summary", "node_id": "n1"}
        await manager.store_ticker_insight("AAPL", "market:v2", payload)
        loaded = await manager.get_ticker_insight("AAPL", "market:v2")
        assert loaded == payload
        assert loaded["summary"] == "cached-summary"
        assert loaded["node_id"] == "n1"
        for meta_key in ("_expires_at", "_updated_at", "_ttl_remaining"):
            assert meta_key not in loaded
        via_helper = await read_store_value(manager.store, ("ticker", "AAPL"), "market:v2")
        assert via_helper == payload
        raw = await manager.store.aget(("ticker", "AAPL"), "market:v2")
        assert raw.value["_expires_at"] >= time.time() + 1700

        await manager.put(("ticker", "AAPL"), "ephemeral", {"summary": "gone-soon"}, ttl_seconds=1)
        assert (await manager.get(("ticker", "AAPL"), "ephemeral"))["summary"] == "gone-soon"

        await manager.store_user_preference("user1", "risk_tolerance", "conservative")
        await manager.store_user_preference("user1", "horizon", "long-only")
        await manager.store_approved_conclusion(
            "AAPL",
            {"summary": "approved-conclusion-text", "recommendation": "HOLD"},
        )
        await manager.put(
            ("ticker", "AAPL"),
            "finance.market_analysis:v2",
            {"ok": True, "summary": "cached-not-approved", "node_id": "cache"},
            ttl_seconds=1800,
        )

        memory = await load_run_memory(manager.store, user_id="user1", symbol="AAPL")
        assert memory["hitCount"] == len(memory["snippets"]) == 2
        by_kind = {snippet["kind"]: snippet["text"] for snippet in memory["snippets"]}
        assert "conservative" in by_kind["user_preference"]
        assert "long-only" in by_kind["user_preference"]
        assert by_kind["approved_conclusion"] == "approved-conclusion-text"
        assert "cached-not-approved" not in by_kind["approved_conclusion"]
        assert "cached-summary" not in by_kind["approved_conclusion"]

        preference_only = await load_run_memory(manager.store, user_id="user1", symbol=None)
        assert preference_only["hitCount"] == 1
        assert preference_only["snippets"][0]["kind"] == "user_preference"

        cache_only = await load_run_memory(manager.store, user_id=None, symbol="MSFT")
        assert cache_only == {"snippets": [], "hitCount": 0}
        assert await load_run_memory(None, user_id="user1", symbol="AAPL") == {"snippets": [], "hitCount": 0}
        assert await load_run_memory(manager.store, user_id=None, symbol=None) == {"snippets": [], "hitCount": 0}

        time.sleep(1.1)
        assert await manager.get(("ticker", "AAPL"), "ephemeral") is None
        assert await read_store_value(manager.store, ("ticker", "AAPL"), "ephemeral") is None
        assert await manager.get_user_preference("user1", "risk_tolerance") == "conservative"
        after_ttl = await load_run_memory(manager.store, user_id="user1", symbol="AAPL")
        assert after_ttl["hitCount"] == 2
        assert any("approved-conclusion-text" in snippet["text"] for snippet in after_ttl["snippets"])
        conclusion = await manager.get(("ticker", "AAPL"), "approved_conclusion")
        assert conclusion == {"summary": "approved-conclusion-text", "recommendation": "HOLD"}
    finally:
        await manager.close()


@pytest.mark.asyncio
async def test_read_store_value_accepts_item_or_dict():
    from app.core.memory_selector import read_store_value

    class _Item:
        def __init__(self, value):
            self.value = value

    class _Store:
        def __init__(self, item):
            self._item = item
            self.deleted = False

        async def aget(self, namespace, key):
            return self._item

        async def adelete(self, namespace, key):
            self.deleted = True

    fresh = _Store({"summary": "cached-summary", "_updated_at": 1, "_ttl_remaining": 9, "_expires_at": time.time() + 60})
    assert await read_store_value(fresh, ("ticker", "AAPL"), "market:v2") == {"summary": "cached-summary"}

    wrapped = _Store(_Item({"ok": True, "node_id": "n1", "_expires_at": time.time() + 30}))
    assert await read_store_value(wrapped, ("ticker", "AAPL"), "k") == {"ok": True, "node_id": "n1"}

    expired = _Store({"summary": "stale", "_expires_at": time.time() - 5})
    assert await read_store_value(expired, ("ticker", "AAPL"), "k") is None
    assert expired.deleted is True

    missing = _Store(None)
    assert await read_store_value(missing, ("ticker", "AAPL"), "k") is None