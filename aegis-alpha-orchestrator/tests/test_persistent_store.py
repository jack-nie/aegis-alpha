"""Tests for persistent store."""

import os
import time
import pytest
import tempfile


@pytest.fixture
async def store():
    from app.core.persistent_store import PersistentStore
    with tempfile.TemporaryDirectory() as tmpdir:
        db_path = os.path.join(tmpdir, "test_store.db")
        s = PersistentStore(db_path, default_ttl_seconds=3600)
        await s._ensure_db()
        yield s
        await s.close()


@pytest.mark.asyncio
async def test_put_and_get(store):
    await store.aput(("test", "ns1"), "key1", {"value": "hello"})
    item = await store.aget(("test", "ns1"), "key1")
    assert item is not None
    assert item.value["value"] == "hello"


@pytest.mark.asyncio
async def test_get_nonexistent(store):
    item = await store.aget(("test", "missing"), "nokey")
    assert item is None


@pytest.mark.asyncio
async def test_delete(store):
    await store.aput(("test", "ns1"), "key1", {"value": "bye"})
    await store.adelete(("test", "ns1"), "key1")
    item = await store.aget(("test", "ns1"), "key1")
    assert item is None


@pytest.mark.asyncio
async def test_search(store):
    await store.aput(("test", "ns1"), "key1", {"value": "a"})
    await store.aput(("test", "ns1"), "key2", {"value": "b"})
    results = await store.asearch(("test", "ns1"), limit=10)
    assert len(results) >= 2


@pytest.mark.asyncio
async def test_ttl_expiry():
    from app.core.persistent_store import PersistentStore
    with tempfile.TemporaryDirectory() as tmpdir:
        db_path = os.path.join(tmpdir, "ttl_test.db")
        s = PersistentStore(db_path, default_ttl_seconds=0)
        await s._ensure_db()
        await s.aput(("test", "ttl"), "key1", {"value": "expires"}, ttl_seconds=1)
        item = await s.aget(("test", "ttl"), "key1")
        assert item is not None
        time.sleep(1.1)
        item = await s.aget(("test", "ttl"), "key1")
        assert item is None
        await s.close()


@pytest.mark.asyncio
async def test_ttl_no_expiry():
    from app.core.persistent_store import PersistentStore
    with tempfile.TemporaryDirectory() as tmpdir:
        db_path = os.path.join(tmpdir, "no_ttl_test.db")
        s = PersistentStore(db_path, default_ttl_seconds=0)
        await s._ensure_db()
        await s.aput(("test", "persist"), "key1", {"value": "forever"}, ttl_seconds=0)
        item = await s.aget(("test", "persist"), "key1")
        assert item is not None
        assert item.value["value"] == "forever"
        await s.close()


@pytest.mark.asyncio
async def test_cleanup_expired():
    from app.core.persistent_store import PersistentStore
    with tempfile.TemporaryDirectory() as tmpdir:
        db_path = os.path.join(tmpdir, "cleanup_test.db")
        s = PersistentStore(db_path, default_ttl_seconds=0)
        await s._ensure_db()
        await s.aput(("test", "cleanup"), "exp1", {"value": "gone"}, ttl_seconds=1)
        await s.aput(("test", "cleanup"), "keep1", {"value": "stay"}, ttl_seconds=0)
        time.sleep(1.1)
        count = await s.cleanup_expired()
        assert count >= 1
        await s.close()


@pytest.mark.asyncio
async def test_persistence_across_restarts():
    from app.core.persistent_store import PersistentStore
    with tempfile.TemporaryDirectory() as tmpdir:
        db_path = os.path.join(tmpdir, "restart_test.db")
        s1 = PersistentStore(db_path, default_ttl_seconds=0)
        await s1._ensure_db()
        await s1.aput(("test", "restart"), "key1", {"value": "persisted"}, ttl_seconds=0)
        await s1.close()

        s2 = PersistentStore(db_path, default_ttl_seconds=0)
        await s2._ensure_db()
        item = await s2.aget(("test", "restart"), "key1")
        assert item is not None
        assert item.value["value"] == "persisted"
        await s2.close()


@pytest.mark.asyncio
async def test_upsert(store):
    await store.aput(("test", "upsert"), "key1", {"value": "v1"})
    await store.aput(("test", "upsert"), "key1", {"value": "v2"})
    item = await store.aget(("test", "upsert"), "key1")
    assert item.value["value"] == "v2"