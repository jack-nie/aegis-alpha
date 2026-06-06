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
        now = time.time()
        cursor = await self._db.execute(
            "SELECT namespace, key, value, expires_at FROM store "
            "WHERE expires_at IS NULL OR expires_at > ?",
            (now,),
        )
        rows = await cursor.fetchall()
        for row in rows:
            namespace_str, key, value_json, expires_at = row
            namespace = tuple(namespace_str.split("/"))
            value = json.loads(value_json)
            if expires_at:
                value["_expires_at"] = expires_at
            await self._memory.aput(namespace, key, value)
        logger.info(f"Loaded {len(rows)} entries from persistent store")

    async def aput(
        self,
        namespace: tuple[str, ...],
        key: str,
        value: dict[str, Any],
        ttl_seconds: int | None = None,
    ) -> None:
        await self._ensure_db()
        now = time.time()
        expires_at: float | None = None
        if ttl_seconds is not None:
            if ttl_seconds > 0:
                expires_at = now + ttl_seconds
            else:
                expires_at = None
        elif self._default_ttl > 0:
            expires_at = now + self._default_ttl

        value_with_meta = {**value, "_updated_at": now}
        if expires_at is not None:
            value_with_meta["_expires_at"] = expires_at

        namespace_str = "/".join(namespace)
        value_json = json.dumps(value_with_meta, ensure_ascii=False, default=str)

        await self._db.execute(
            "INSERT OR REPLACE INTO store (namespace, key, value, expires_at, updated_at) "
            "VALUES (?, ?, ?, ?, ?)",
            (namespace_str, key, value_json, expires_at, now),
        )
        await self._db.commit()
        await self._memory.aput(namespace, key, value_with_meta)

    async def aget(self, namespace: tuple[str, ...], key: str) -> Item | None:
        await self._ensure_db()
        item = await self._memory.aget(namespace, key)
        if item is None:
            return None
        expires_at = item.value.get("_expires_at")
        if expires_at and time.time() > expires_at:
            await self.adelete(namespace, key)
            return None
        return item

    async def adelete(self, namespace: tuple[str, ...], key: str) -> None:
        await self._ensure_db()
        namespace_str = "/".join(namespace)
        await self._db.execute(
            "DELETE FROM store WHERE namespace = ? AND key = ?",
            (namespace_str, key),
        )
        await self._db.commit()
        await self._memory.adelete(namespace, key)

    async def asearch(
        self,
        namespace: tuple[str, ...],
        *,
        limit: int = 10,
        offset: int = 0,
    ) -> list[Item]:
        await self._ensure_db()
        items = await self._memory.asearch(namespace, limit=limit, offset=offset)
        now = time.time()
        valid: list[Item] = []
        for item in items:
            expires_at = item.value.get("_expires_at")
            if expires_at and now > expires_at:
                await self.adelete(namespace, item.key)
                continue
            valid.append(item)
        return valid

    async def cleanup_expired(self) -> int:
        await self._ensure_db()
        now = time.time()
        cursor = await self._db.execute(
            "DELETE FROM store WHERE expires_at IS NOT NULL AND expires_at <= ?",
            (now,),
        )
        await self._db.commit()
        count = cursor.rowcount
        if count > 0:
            logger.info(f"Cleaned up {count} expired entries from persistent store")
            await self._load_from_db()
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