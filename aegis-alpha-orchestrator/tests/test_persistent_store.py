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
    # Omitting ttl_seconds still uses the store default (fixture: 3600s).
    assert item.value["_expires_at"] >= time.time() + 3500


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
        await s.aput(("test", "persist"), "key1", {"value": "forever", "_expires_at": time.time() - 10}, ttl_seconds=0)
        item = await s.aget(("test", "persist"), "key1")
        assert item is not None
        assert item.value["value"] == "forever"
        assert "_expires_at" not in item.value
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
        # Cleanup must drop the cache row, not only the SQLite row.
        cached = await s._memory.aget(("test", "cleanup"), "exp1")
        assert cached is None
        kept = await s.aget(("test", "cleanup"), "keep1")
        assert kept is not None
        assert kept.value["value"] == "stay"
        assert await s.aget(("test", "cleanup"), "exp1") is None
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

# ---------------- P3-6: namespace encoding and no private InMemoryStore access ----------------


@pytest.mark.asyncio
async def test_namespace_with_slash_round_trips_and_does_not_collide():
    from app.core.persistent_store import PersistentStore
    with tempfile.TemporaryDirectory() as tmpdir:
        db_path = os.path.join(tmpdir, "slash.db")
        s1 = PersistentStore(db_path, default_ttl_seconds=0)
        await s1.aput(("user", "a/b"), "k", {"v": "slash"})
        await s1.aput(("user", "a", "b"), "k", {"v": "nested"})
        await s1.close()

        s2 = PersistentStore(db_path, default_ttl_seconds=0)
        assert (await s2.aget(("user", "a/b"), "k")).value["v"] == "slash"
        assert (await s2.aget(("user", "a", "b"), "k")).value["v"] == "nested"
        found = await s2.asearch(("user", "a/b"), limit=10)
        assert [item.value["v"] for item in found] == ["slash"]
        assert {tuple(i.namespace) for i in await s2.asearch(("user",), limit=10)} == {
            ("user", "a/b"),
            ("user", "a", "b"),
        }
        await s2.close()


@pytest.mark.asyncio
async def test_legacy_slash_joined_rows_are_read_and_replaced():
    from app.core.persistent_store import PersistentStore
    import aiosqlite
    with tempfile.TemporaryDirectory() as tmpdir:
        db_path = os.path.join(tmpdir, "legacy.db")
        s1 = PersistentStore(db_path, default_ttl_seconds=0)
        await s1._ensure_db()
        await s1._db.execute(
            "INSERT INTO store (namespace, key, value, expires_at, updated_at) VALUES (?, ?, ?, ?, ?)",
            ("memory/user1", "pref", '{"v": "legacy"}', None, time.time()),
        )
        await s1._db.commit()
        await s1.close()

        s2 = PersistentStore(db_path, default_ttl_seconds=0)
        assert (await s2.aget(("memory", "user1"), "pref")).value["v"] == "legacy"
        await s2.aput(("memory", "user1"), "pref", {"v": "new"})
        await s2.close()
        async with aiosqlite.connect(db_path) as db:
            rows = await (await db.execute("SELECT namespace FROM store WHERE key = 'pref'")).fetchall()
        assert rows == [('["memory","user1"]',)]


def test_store_does_not_touch_inmemorystore_private_data():
    from pathlib import Path
    source = (Path(__file__).resolve().parents[1] / "app" / "core" / "persistent_store.py").read_text()
    assert "_memory._data" not in source and "._data." not in source
