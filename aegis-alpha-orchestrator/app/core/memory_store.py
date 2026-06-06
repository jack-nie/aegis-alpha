"""Cross-thread memory store with TTL and SQLite persistence."""

from __future__ import annotations

import logging
from typing import Any

from ..config import Settings
from .persistent_store import PersistentStore  # noqa: E402

logger = logging.getLogger(__name__)

TICKER_INSIGHT_TTL = 1800  # 30 minutes
USER_PREFERENCE_TTL = 0  # No expiry (persistent)
WORKFLOW_PATTERN_TTL = 0  # No expiry (persistent)


class MemoryStoreManager:
    """Manages cross-thread memory store with TTL and persistence."""

    def __init__(self, config: Settings):
        self._config = config
        db_path = f"{config.data_dir}/store.db"
        self._store = PersistentStore(db_path, default_ttl_seconds=config.store_default_ttl_seconds)

    @property
    def store(self) -> PersistentStore:
        return self._store

    async def initialize(self) -> None:
        """Initialize the store (creates DB, loads from disk)."""
        await self._store._ensure_db()

    async def close(self) -> None:
        """Close the store."""
        await self._store.close()

    async def cleanup(self) -> int:
        """Remove expired entries. Returns count of removed entries."""
        return await self._store.cleanup_expired()

    async def put(self, namespace: tuple[str, str], key: str, value: dict[str, Any], ttl_seconds: int | None = None) -> None:
        if ttl_seconds is not None:
            await self._store.aput(namespace, key, value, ttl_seconds=ttl_seconds)
        else:
            await self._store.aput(namespace, key, value)

    async def get(self, namespace: tuple[str, str], key: str) -> dict[str, Any] | None:
        item = await self._store.aget(namespace, key)
        if item is None:
            return None
        value = dict(item.value)
        value.pop("_expires_at", None)
        value.pop("_updated_at", None)
        value.pop("_ttl_remaining", None)
        return value

    async def search(self, namespace: tuple[str, str], limit: int = 10) -> list[dict[str, Any]]:
        items = await self._store.asearch(namespace, limit=limit)
        results = []
        for item in items:
            value = dict(item.value)
            value.pop("_expires_at", None)
            value.pop("_updated_at", None)
            value.pop("_ttl_remaining", None)
            results.append(value)
        return results

    async def delete(self, namespace: tuple[str, str], key: str) -> None:
        await self._store.adelete(namespace, key)

    async def store_user_preference(self, user_id: str, preference_key: str, value: Any) -> None:
        await self.put(("user", user_id), preference_key, {"value": value}, ttl_seconds=USER_PREFERENCE_TTL)

    async def get_user_preference(self, user_id: str, preference_key: str) -> Any:
        result = await self.get(("user", user_id), preference_key)
        return result.get("value") if result else None

    async def store_ticker_insight(self, symbol: str, insight_key: str, value: dict[str, Any]) -> None:
        await self.put(("ticker", symbol), insight_key, value, ttl_seconds=TICKER_INSIGHT_TTL)

    async def get_ticker_insight(self, symbol: str, insight_key: str) -> dict[str, Any] | None:
        return await self.get(("ticker", symbol), insight_key)

    async def store_workflow_pattern(self, workflow_key: str, pattern_key: str, value: dict[str, Any]) -> None:
        await self.put(("workflow", workflow_key), pattern_key, value, ttl_seconds=WORKFLOW_PATTERN_TTL)

    async def get_workflow_pattern(self, workflow_key: str, pattern_key: str) -> dict[str, Any] | None:
        return await self.get(("workflow", workflow_key), pattern_key)