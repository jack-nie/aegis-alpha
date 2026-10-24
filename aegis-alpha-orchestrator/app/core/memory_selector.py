"""Read cross-thread memory for a workflow run.

Item-or-dict safe. Expired rows and store meta keys are not returned.
"""

from __future__ import annotations

import json
import time
from typing import Any

_META_KEYS = ("_expires_at", "_updated_at", "_ttl_remaining")
_APPROVED_CONCLUSION_KEY = "approved_conclusion"
_SEARCH_LIMIT = 100
_ITEM_DICT_KEYS = {"namespace", "key", "value", "created_at"}


def ticker_namespace(symbol: str) -> tuple[str, str]:
    """Build a store namespace for a ticker.

    LangGraph store labels cannot contain periods, so exchange-qualified
    tickers such as ``600183.SH`` become ``600183_SH``.
    """
    label = str(symbol or "").strip().replace(".", "_").replace("/", "_")
    return ("ticker", label)


def _is_reserved_key(key: str) -> bool:
    return (not key) or key.startswith("_") or key in _META_KEYS


def _is_expired(value: dict[str, Any], now: float | None = None) -> bool:
    raw = value.get("_expires_at")
    if raw is None:
        return False
    try:
        expires_at = float(raw)
    except (TypeError, ValueError):
        return False
    # Non-positive stamps are not an expiry (ttl 0 must stay readable).
    if expires_at <= 0:
        return False
    if now is None:
        now = time.time()
    return now > expires_at


def _payload_from_item(item: Any) -> dict[str, Any] | None:
    """Accept a LangGraph Item or a plain dict payload."""
    if item is None:
        return None
    if isinstance(item, dict):
        if _ITEM_DICT_KEYS <= set(item.keys()) and isinstance(item.get("value"), dict):
            raw = item["value"]
        else:
            raw = item
    else:
        raw = getattr(item, "value", None)
    if not isinstance(raw, dict):
        return None
    return raw


def _strip_meta(value: dict[str, Any]) -> dict[str, Any]:
    return {key: field for key, field in value.items() if key not in _META_KEYS}


def _backend(store: Any) -> Any:
    if store is None:
        return None
    if hasattr(store, "aget"):
        return store
    inner = getattr(store, "store", None)
    if inner is not None and hasattr(inner, "aget"):
        return inner
    return None


async def _maybe_await(result: Any) -> Any:
    if hasattr(result, "__await__"):
        return await result
    return result


async def read_store_value(store, namespace: tuple, key: str) -> dict | None:
    """Item or dict safe. None if missing or expired. Strips _expires_at, _updated_at, _ttl_remaining."""
    backend = _backend(store)
    if backend is None or not key:
        return None
    item = await _maybe_await(backend.aget(namespace, key))
    payload = _payload_from_item(item)
    if payload is None:
        return None
    if _is_expired(payload):
        deleter = getattr(backend, "adelete", None)
        if deleter is not None:
            await _maybe_await(deleter(namespace, key))
        return None
    return _strip_meta(payload)


def _preference_fragment(payload: dict[str, Any]) -> str:
    if "value" in payload:
        inner = payload["value"]
        if isinstance(inner, str):
            return inner
        return json.dumps(inner, ensure_ascii=False, default=str, sort_keys=True)
    return json.dumps(payload, ensure_ascii=False, default=str, sort_keys=True)


def _conclusion_text(payload: dict[str, Any]) -> str:
    summary = payload.get("summary")
    if isinstance(summary, str) and summary:
        return summary
    return json.dumps(payload, ensure_ascii=False, default=str, sort_keys=True)


async def load_run_memory(store, *, user_id: str | None, symbol: str | None) -> dict:
    """
    Returns {"snippets": [{"kind": "user_preference"|"approved_conclusion", "text": str}, ...], "hitCount": int}.
    hitCount == len(snippets). No store / no ids -> {"snippets": [], "hitCount": 0}.
    """
    backend = _backend(store)
    if backend is None or (not user_id and not symbol):
        return {"snippets": [], "hitCount": 0}

    snippets: list[dict[str, str]] = []
    if user_id:
        search = getattr(backend, "asearch", None)
        found: list[Any] = []
        if search is not None:
            found = list(await _maybe_await(search(("user", user_id), limit=_SEARCH_LIMIT)) or [])
        fragments: list[tuple[str, str]] = []
        for item in found:
            if isinstance(item, dict) and "key" in item:
                item_key = str(item.get("key") or "")
            else:
                item_key = str(getattr(item, "key", "") or "")
            if _is_reserved_key(item_key):
                continue
            payload = _payload_from_item(item)
            if payload is None or _is_expired(payload):
                continue
            cleaned = _strip_meta(payload)
            fragments.append((item_key, _preference_fragment(cleaned)))
        if fragments:
            fragments.sort(key=lambda pair: pair[0])
            snippets.append({
                "kind": "user_preference",
                "text": "; ".join(fragment for _, fragment in fragments),
            })

    if symbol:
        conclusion = await read_store_value(backend, ticker_namespace(symbol), _APPROVED_CONCLUSION_KEY)
        if conclusion is not None:
            snippets.append({
                "kind": "approved_conclusion",
                "text": _conclusion_text(conclusion),
            })

    return {"snippets": snippets, "hitCount": len(snippets)}
