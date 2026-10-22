"""Process-lifetime checkpointer holders.

P2-16: production uses ``AsyncSqliteSaver`` (aiosqlite), opened inside the FastAPI lifespan
because it binds to the running event loop: :func:`open_async_checkpointer` /
:func:`close_async_checkpointer`. The sync ``SqliteSaver`` holder plus
:class:`SyncCheckpointAsyncAdapter` below are kept for callers without a running loop (legacy).

Legacy notes (sync holder):

SqliteSaver.from_conn_string is a context manager. Enter it once and keep the
yielded saver until shutdown. Do not wrap the import in a with-block that
exits immediately, or the connection closes before the first resume.
"""

from __future__ import annotations

import asyncio
import os
from typing import Any

from langgraph.checkpoint.base import BaseCheckpointSaver

_context_manager: Any = None
_saver: Any = None


def open_checkpointer(db_path: str) -> Any:
    """Enter SqliteSaver.from_conn_string and return the yielded saver.

    Raises ImportError when langgraph.checkpoint.sqlite is not installed.
    Repeated calls return the already-open saver.
    """
    global _context_manager, _saver
    if _saver is not None:
        return _saver

    from langgraph.checkpoint.sqlite import SqliteSaver

    parent = os.path.dirname(db_path)
    if parent:
        os.makedirs(parent, exist_ok=True)

    factory = SqliteSaver.from_conn_string(db_path)
    # Current langgraph-checkpoint-sqlite returns a @contextmanager, not a saver.
    if hasattr(factory, "__enter__") and not hasattr(factory, "get_next_version"):
        _context_manager = factory
        _saver = factory.__enter__()
    else:
        _context_manager = None
        _saver = factory
    return _saver


class SyncCheckpointAsyncAdapter(BaseCheckpointSaver):
    """Run a sync SqliteSaver from async graph execution.

    LangGraph's ainvoke/astream call aget_tuple/aput/aput_writes. SqliteSaver
    rejects those. The underlying connection stays the sync saver opened by
    open_checkpointer so checkpoints remain durable across process restarts.
    """

    def __init__(self, saver: Any):
        super().__init__(serde=getattr(saver, "serde", None))
        self._saver = saver

    def get_tuple(self, config):
        return self._saver.get_tuple(config)

    def list(self, config, *, filter=None, before=None, limit=None):
        return self._saver.list(config, filter=filter, before=before, limit=limit)

    def put(self, config, checkpoint, metadata, new_versions):
        return self._saver.put(config, checkpoint, metadata, new_versions)

    def put_writes(self, config, writes, task_id, task_path=""):
        return self._saver.put_writes(config, writes, task_id, task_path)

    def delete_thread(self, thread_id: str) -> None:
        delete = getattr(self._saver, "delete_thread", None)
        if delete is not None:
            delete(thread_id)

    def get_next_version(self, current, channel):
        return self._saver.get_next_version(current, channel)

    async def aget_tuple(self, config):
        return await asyncio.to_thread(self._saver.get_tuple, config)

    async def alist(self, config, *, filter=None, before=None, limit=None):
        rows = await asyncio.to_thread(
            lambda: list(self._saver.list(config, filter=filter, before=before, limit=limit))
        )
        for row in rows:
            yield row

    async def aput(self, config, checkpoint, metadata, new_versions):
        return await asyncio.to_thread(
            self._saver.put, config, checkpoint, metadata, new_versions
        )

    async def aput_writes(self, config, writes, task_id, task_path=""):
        return await asyncio.to_thread(
            self._saver.put_writes, config, writes, task_id, task_path
        )


def ensure_async_checkpointer(checkpointer: Any) -> Any:
    """Wrap sync SqliteSaver so async workflow execution can persist checkpoints."""
    if checkpointer is None or isinstance(checkpointer, SyncCheckpointAsyncAdapter):
        return checkpointer
    try:
        from langgraph.checkpoint.sqlite import SqliteSaver
    except ImportError:
        return checkpointer
    if isinstance(checkpointer, SqliteSaver):
        return SyncCheckpointAsyncAdapter(checkpointer)
    return checkpointer


def close_checkpointer() -> None:
    """Exit the held SqliteSaver context manager, if one is open."""
    global _context_manager, _saver
    context_manager = _context_manager
    _context_manager = None
    _saver = None
    if context_manager is not None:
        context_manager.__exit__(None, None, None)


_async_context_manager: Any = None
_async_saver: Any = None


async def open_async_checkpointer(db_path: str) -> Any:
    """Enter ``AsyncSqliteSaver.from_conn_string`` once per process and run its table setup.

    Must be awaited on the serving event loop (FastAPI lifespan). Repeated calls return the
    already-open saver. Raises ImportError when langgraph-checkpoint-sqlite / aiosqlite is missing.
    """
    global _async_context_manager, _async_saver
    if _async_saver is not None:
        return _async_saver

    from langgraph.checkpoint.sqlite.aio import AsyncSqliteSaver

    parent = os.path.dirname(db_path)
    if parent:
        os.makedirs(parent, exist_ok=True)
    context_manager = AsyncSqliteSaver.from_conn_string(db_path)
    saver = await context_manager.__aenter__()
    try:
        await saver.setup()
    except Exception:
        await context_manager.__aexit__(None, None, None)
        raise
    _async_context_manager, _async_saver = context_manager, saver
    return saver


async def close_async_checkpointer() -> None:
    """Close the AsyncSqliteSaver connection opened by :func:`open_async_checkpointer`."""
    global _async_context_manager, _async_saver
    context_manager = _async_context_manager
    _async_context_manager = None
    _async_saver = None
    if context_manager is not None:
        await context_manager.__aexit__(None, None, None)
