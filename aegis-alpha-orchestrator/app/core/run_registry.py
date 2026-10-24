"""P4-3: registry of active workflow runs, for cooperative cancellation (pi ``AbortSignal``).

Java stays the source of truth for run state: ``POST /cancel-run`` only stops the executing graph
on this process; Java records CANCELLED itself through its CAS update.

Each run owns a :class:`RunHandle`. The engine waits on the graph *and* ``handle.cancelled`` so a
cancel request stops the graph task (running nodes receive ``CancelledError``) without cancelling
the HTTP request task that streams the response; the run then ends with the ``cancelled`` outcome
(queue closed as ``cancelled``, SSE ``cancelled`` event). Ownership is checked like the message
queue: a run without an owner cannot be cancelled by anyone (fail closed).
"""

from __future__ import annotations

import asyncio
import logging
import time
from dataclasses import dataclass, field
from typing import Any

from .message_queue import QueueOwner

logger = logging.getLogger(__name__)


class RunCancelled(Exception):
    """The run was cancelled through the registry (distinct from timeout / client abort)."""

    def __init__(self, thread_id: str, reason: str = "cancelled"):
        super().__init__(f"Workflow run cancelled: {thread_id}")
        self.thread_id = thread_id
        self.reason = reason


class RunRegistryError(Exception):
    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code
        self.message = message


@dataclass
class RunHandle:
    thread_id: str
    owner: QueueOwner | None
    started_at: float = field(default_factory=time.time)
    cancelled: asyncio.Event = field(default_factory=asyncio.Event)
    cancel_reason: str | None = None

    @property
    def is_cancelled(self) -> bool:
        return self.cancelled.is_set()

    def request_cancel(self, reason: str = "cancelled") -> None:
        if not self.cancelled.is_set():
            self.cancel_reason = reason
            self.cancelled.set()

    def to_dict(self) -> dict[str, Any]:
        return {
            "threadId": self.thread_id,
            "startedAt": self.started_at,
            "cancelled": self.is_cancelled,
            "reason": self.cancel_reason,
        }


class RunRegistry:
    """In-process map thread_id → RunHandle (see P4-16 for the multi-replica plan)."""

    def __init__(self) -> None:
        self._runs: dict[str, RunHandle] = {}

    def open(self, thread_id: str, owner: Any) -> RunHandle:
        handle = RunHandle(thread_id=thread_id, owner=QueueOwner.from_any(owner))
        previous = self._runs.get(thread_id)
        if previous is not None and not previous.is_cancelled:
            logger.warning("[RUN] thread %s re-opened while a run was registered", thread_id)
        self._runs[thread_id] = handle
        return handle

    def close(self, handle: RunHandle | None) -> None:
        if handle is not None and self._runs.get(handle.thread_id) is handle:
            del self._runs[handle.thread_id]

    def get(self, thread_id: str) -> RunHandle | None:
        return self._runs.get(thread_id)

    def active(self) -> list[str]:
        return list(self._runs)

    def cancel(self, thread_id: str, owner: Any, reason: str = "cancelled") -> dict[str, Any]:
        handle = self._runs.get(thread_id)
        if handle is None:
            raise RunRegistryError("NOT_ACTIVE", "no active run for this thread")
        caller = QueueOwner.from_any(owner)
        if handle.owner is None or not handle.owner.matches(caller):
            raise RunRegistryError("OWNER_MISMATCH", "caller does not own this run")
        already = handle.is_cancelled
        handle.request_cancel(reason)
        logger.info("[RUN] cancel requested: thread=%s already=%s", thread_id, already)
        return {"threadId": thread_id, "cancelled": True, "alreadyCancelled": already}


async def await_or_cancel(awaitable: Any, handle: RunHandle | None, timeout: float | None) -> Any:
    """Await ``awaitable`` in its own task, bounded by ``timeout``, stoppable through ``handle``.

    Raises ``asyncio.TimeoutError`` on timeout and :class:`RunCancelled` on a registry cancel. When
    the *calling* task is cancelled (client disconnect, shutdown) the inner task is cancelled and
    ``CancelledError`` propagates unchanged.
    """
    task = asyncio.ensure_future(awaitable)
    if handle is None:
        try:
            return await asyncio.wait_for(task, timeout=timeout)
        except asyncio.CancelledError:
            task.cancel()
            raise
    waiter = asyncio.ensure_future(handle.cancelled.wait())
    try:
        done, _ = await asyncio.wait({task, waiter}, timeout=timeout, return_when=asyncio.FIRST_COMPLETED)
    except asyncio.CancelledError:
        task.cancel()
        waiter.cancel()
        raise
    if task in done:
        waiter.cancel()
        return task.result()
    waiter.cancel()
    task.cancel()
    try:
        await task
    except (asyncio.CancelledError, Exception):  # noqa: BLE001 - the run is being discarded
        pass
    if handle.is_cancelled:
        raise RunCancelled(handle.thread_id, handle.cancel_reason or "cancelled")
    raise asyncio.TimeoutError()
