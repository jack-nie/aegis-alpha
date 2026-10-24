"""Persistent store backed by SQLite with TTL support."""

from __future__ import annotations

import json
import logging
import os
import time
from typing import Any

import aiosqlite
from langgraph.store.memory import InMemoryStore
from langgraph.store.base import BaseStore, Item

logger = logging.getLogger(__name__)

# Internal fields. Callers never see these on MemoryStoreManager.get.
_META_KEYS = ("_expires_at", "_updated_at", "_ttl_remaining")


def _expiry_timestamp(now: float, ttl_seconds: int | None, default_ttl_seconds: int) -> float | None:
    """Resolve a storage expiry. ttl_seconds <= 0 means no expiry, not 'already expired'."""
    if ttl_seconds is None:
        if default_ttl_seconds > 0:
            return now + default_ttl_seconds
        return None
    if ttl_seconds <= 0:
        return None
    return now + ttl_seconds


def _encode_namespace(namespace: tuple[str, ...]) -> str:
    """P3-6: JSON-encode namespaces so labels containing "/" round-trip unambiguously."""
    return json.dumps(list(namespace), ensure_ascii=False, separators=(",", ":"))


def _legacy_namespace(namespace: tuple[str, ...]) -> str:
    """Pre-P3-6 encoding ("/"-joined); still read and cleaned up on write/delete."""
    return "/".join(namespace)


def _decode_namespace(raw: str) -> tuple[str, ...]:
    if raw.startswith("["):
        try:
            decoded = json.loads(raw)
        except ValueError:
            decoded = None
        if isinstance(decoded, list) and all(isinstance(part, str) for part in decoded):
            return tuple(decoded)
    return tuple(raw.split("/"))


def _is_expired(value: dict[str, Any] | None, now: float) -> bool:
    """True when _expires_at is a positive timestamp strictly before now.

    A missing stamp, or a non-positive one (ttl 0 stored as 0), does not expire.
    """
    if not isinstance(value, dict):
        return False
    raw = value.get("_expires_at")
    if raw is None:
        return False
    try:
        expires_at = float(raw)
    except (TypeError, ValueError):
        return False
    if expires_at <= 0:
        return False
    return now > expires_at


class PersistentStore(BaseStore):
    """SQLite-backed store with TTL support.

    Reads go through in-memory cache (InMemoryStore) for speed.
    Writes persist to SQLite file.
    TTL: entries with _expires_at field are auto-expired on read.
    """

    def __init__(self, db_path: str, default_ttl_seconds: int = 3600):
        self._db_path = db_path
        self._default_ttl = default_ttl_seconds
        self._memory = InMemoryStore()
        # P3-6: own key index for prefix scans instead of reading InMemoryStore private state.
        self._keys: dict[tuple[str, ...], set[str]] = {}
        self._db: aiosqlite.Connection | None = None
        self._initialized = False

    async def _ensure_db(self) -> None:
        if self._initialized:
            return
        os.makedirs(os.path.dirname(self._db_path) or ".", exist_ok=True)
        self._db = await aiosqlite.connect(self._db_path)
        await self._db.execute("""
            CREATE TABLE IF NOT EXISTS store (
                namespace TEXT NOT NULL,
                key TEXT NOT NULL,
                value TEXT NOT NULL,
                expires_at REAL,
                updated_at REAL NOT NULL,
                PRIMARY KEY (namespace, key)
            )
        """)
        await self._db.execute(
            "CREATE INDEX IF NOT EXISTS idx_expires ON store(expires_at)"
        )
        await self._db.commit()
        await self._load_from_db()
        self._initialized = True

    async def _load_from_db(self) -> None:
        # Drop the previous cache first. Otherwise cleanup deletes SQLite rows
        # and the in-memory copy keeps serving them.
        self._memory = InMemoryStore()
        self._keys = {}
        now = time.time()
        cursor = await self._db.execute(
            "SELECT namespace, key, value, expires_at FROM store "
            "WHERE expires_at IS NULL OR expires_at <= 0 OR expires_at > ?",
            (now,),
        )
        rows = await cursor.fetchall()
        loaded = 0
        for row in rows:
            namespace_str, key, value_json, expires_at = row
            namespace = _decode_namespace(namespace_str)
            value = json.loads(value_json)
            if not isinstance(value, dict):
                value = {"value": value}
            column_expiry: float | None = None
            if expires_at is not None:
                try:
                    column_expiry = float(expires_at)
                except (TypeError, ValueError):
                    column_expiry = None
            # NULL or <= 0 is persistent. Strip a stale _expires_at so ttl 0
            # is not treated as already expired on the next read.
            if column_expiry is None or column_expiry <= 0:
                value.pop("_expires_at", None)
            else:
                value["_expires_at"] = column_expiry
            await self._cache_put(namespace, key, value)
            loaded += 1
        logger.info(f"Loaded {loaded} entries from persistent store")

    async def aput(
        self,
        namespace: tuple[str, ...],
        key: str,
        value: dict[str, Any],
        ttl_seconds: int | None = None,
    ) -> None:
        await self._ensure_db()
        now = time.time()
        expires_at = _expiry_timestamp(now, ttl_seconds, self._default_ttl)
        # Drop caller meta before re-stamping. A ttl of 0 must not inherit a
        # previous _expires_at and look already expired.
        value_with_meta = {
            key_name: field
            for key_name, field in value.items()
            if key_name not in _META_KEYS
        }
        value_with_meta["_updated_at"] = now
        if expires_at is not None:
            value_with_meta["_expires_at"] = expires_at

        namespace_str = _encode_namespace(namespace)
        value_json = json.dumps(value_with_meta, ensure_ascii=False, default=str)

        await self._db.execute(
            "INSERT OR REPLACE INTO store (namespace, key, value, expires_at, updated_at) "
            "VALUES (?, ?, ?, ?, ?)",
            (namespace_str, key, value_json, expires_at, now),
        )
        await self._delete_legacy_row(namespace, key)
        await self._db.commit()
        await self._cache_put(namespace, key, value_with_meta)

    async def aget(self, namespace: tuple[str, ...], key: str) -> Item | None:
        await self._ensure_db()
        item = await self._memory.aget(namespace, key)
        if item is None:
            return None
        if _is_expired(item.value, time.time()):
            await self.adelete(namespace, key)
            return None
        return item

    async def adelete(self, namespace: tuple[str, ...], key: str) -> None:
        await self._ensure_db()
        await self._db.execute(
            "DELETE FROM store WHERE namespace = ? AND key = ?",
            (_encode_namespace(namespace), key),
        )
        await self._delete_legacy_row(namespace, key)
        await self._db.commit()
        await self._memory.adelete(namespace, key)
        keys = self._keys.get(namespace)
        if keys is not None:
            keys.discard(key)
            if not keys:
                self._keys.pop(namespace, None)

    async def _delete_legacy_row(self, namespace: tuple[str, ...], key: str) -> None:
        legacy = _legacy_namespace(namespace)
        if legacy != _encode_namespace(namespace):
            await self._db.execute(
                "DELETE FROM store WHERE namespace = ? AND key = ?",
                (legacy, key),
            )

    async def _cache_put(self, namespace: tuple[str, ...], key: str, value: dict[str, Any]) -> None:
        await self._memory.aput(namespace, key, value)
        self._keys.setdefault(namespace, set()).add(key)

    async def _items_in_namespace(self, namespace: tuple[str, ...]) -> list[Item]:
        """Prefix scan over the key index (InMemoryStore.asearch applies limit before TTL filtering)."""
        matched: list[Item] = []
        for item_namespace, keys in list(self._keys.items()):
            if len(item_namespace) >= len(namespace) and item_namespace[: len(namespace)] == namespace:
                for key in sorted(keys):
                    item = await self._memory.aget(item_namespace, key)
                    if item is not None:
                        matched.append(item)
        return matched

    async def asearch(
        self,
        namespace: tuple[str, ...],
        *,
        limit: int = 10,
        offset: int = 0,
    ) -> list[Item]:
        await self._ensure_db()
        now = time.time()
        valid: list[Item] = []
        expired: list[Item] = []
        for item in await self._items_in_namespace(namespace):
            if _is_expired(item.value, now):
                expired.append(item)
            else:
                valid.append(item)
        for item in expired:
            await self.adelete(tuple(item.namespace), item.key)
        return valid[offset : offset + limit]

    async def cleanup_expired(self) -> int:
        await self._ensure_db()
        now = time.time()
        cursor = await self._db.execute(
            "SELECT namespace, key FROM store "
            "WHERE expires_at IS NOT NULL AND expires_at > 0 AND expires_at <= ?",
            (now,),
        )
        rows = await cursor.fetchall()
        await self._db.execute(
            "DELETE FROM store "
            "WHERE expires_at IS NOT NULL AND expires_at > 0 AND expires_at <= ?",
            (now,),
        )
        await self._db.commit()
        # Rebuild from SQLite so expired keys cannot remain in the memory cache.
        await self._load_from_db()
        count = len(rows)
        if count > 0:
            logger.info(f"Cleaned up {count} expired entries from persistent store")
        return count

    async def close(self) -> None:
        if self._db:
            await self._db.close()
            self._db = None
            self._initialized = False

    async def abatch(self, ops: list) -> list:
        results = []
        for op in ops:
            if hasattr(op, 'namespace') and hasattr(op, 'key'):
                if hasattr(op, 'value'):
                    await self.aput(op.namespace, op.key, op.value)
                    results.append(None)
                else:
                    item = await self.aget(op.namespace, op.key)
                    results.append(item)
            else:
                results.append(None)
        return results

    def batch(self, ops: list) -> list:
        raise NotImplementedError("Use abatch for async operations")