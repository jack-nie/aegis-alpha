"""P4-16: seams for running more than one orchestrator replica (interfaces only, no new deps).

Today all per-run coordination is process-local:

- the LangGraph checkpointer is a local SQLite file (``AsyncSqliteSaver``);
- the steering / follow-up queue (``MessageQueueRegistry``) and the cancel flags
  (``RunRegistry``) live in process memory.

With two replicas behind a load balancer, ``/resume-workflow``, ``/message-queue/*`` and
``/cancel-run`` can land on a replica that never saw the run. The design (see
``docs/多副本设计.md``) is to put both behind the two interfaces below; this module ships the
interfaces, the current single-replica implementations, and settings-based selection that fails
loudly for backends that need dependencies not yet approved (postgres / redis: not adopted, decision D9 — no new infra deps).
"""
from __future__ import annotations

from typing import Any, Protocol, runtime_checkable

from langgraph.checkpoint.base import BaseCheckpointSaver

from .message_queue import MessageQueueRegistry, MessageQueueSettings, QueuedMessage, QueueOwner

CHECKPOINTER_BACKENDS = ("sqlite", "memory")
RUN_QUEUE_BACKENDS = ("memory",)
# Recognised but not installed: need langgraph-checkpoint-postgres / redis (decision D9: not adopted, not in requirements).
PLANNED_BACKENDS = {"postgres": "langgraph-checkpoint-postgres + psycopg", "redis": "redis (redis-py)"}


class BackendUnavailable(RuntimeError):
    """A configured backend is planned but its dependency is not installed / approved."""


@runtime_checkable
class RunQueueBackend(Protocol):
    """Per-run steering / follow-up queue keyed by thread id (== Java runId).

    Contract a shared backend (e.g. Redis streams) must keep:

    - ``open_run`` is idempotent and reactivates a paused run's queue on resume;
    - ``enqueue`` rejects foreign owners (``OWNER_MISMATCH``) and closed runs (``NOT_ACTIVE``);
    - delivery is at-most-once per message id (``drain`` happens on the replica executing the run);
    - ``finish_run`` returns the undelivered messages so the API can report them.
    """

    @property
    def enabled(self) -> bool: ...

    def open_run(self, thread_id: str, owner: QueueOwner | None) -> Any: ...

    def get(self, thread_id: str) -> Any: ...

    def enqueue(self, thread_id: str, kind: str, content: str, owner: QueueOwner | None,
                message_id: str | None = None, target: str | None = None) -> dict[str, Any]: ...

    def finish_run(self, thread_id: str, reason: str) -> list[QueuedMessage]: ...


@runtime_checkable
class CheckpointerFactory(Protocol):
    """Opens / closes the process checkpointer on the serving event loop (FastAPI lifespan)."""

    name: str
    durable: bool  # survives a process restart
    shared: bool  # safe for several replicas to use concurrently

    async def open(self) -> BaseCheckpointSaver: ...

    async def close(self) -> None: ...


class SqliteCheckpointerFactory:
    """Current production default: durable, single replica (file lock / local disk)."""

    name = "sqlite"
    durable = True
    shared = False

    def __init__(self, db_path: str):
        self.db_path = db_path

    async def open(self) -> BaseCheckpointSaver:
        from .checkpointer_lifecycle import open_async_checkpointer

        return await open_async_checkpointer(self.db_path)

    async def close(self) -> None:
        from .checkpointer_lifecycle import close_async_checkpointer

        await close_async_checkpointer()


class MemoryCheckpointerFactory:
    """Tests / demos: nothing survives a restart; approvals pending at shutdown are lost."""

    name = "memory"
    durable = False
    shared = False

    def __init__(self) -> None:
        self._saver: BaseCheckpointSaver | None = None

    async def open(self) -> BaseCheckpointSaver:
        from langgraph.checkpoint.memory import MemorySaver

        if self._saver is None:
            self._saver = MemorySaver()
        return self._saver

    async def close(self) -> None:
        self._saver = None


def _choice(value: Any, default: str) -> str:
    return (str(value or "").strip().lower()) or default


def checkpointer_factory_from_settings(settings: Any, db_path: str) -> CheckpointerFactory:
    backend = _choice(getattr(settings, "checkpointer_backend", None), "sqlite")
    if backend == "sqlite":
        return SqliteCheckpointerFactory(db_path)
    if backend == "memory":
        return MemoryCheckpointerFactory()
    if backend in PLANNED_BACKENDS:
        raise BackendUnavailable(
            f"checkpointer backend '{backend}' needs {PLANNED_BACKENDS[backend]} (not installed; decision D9)"
        )
    raise ValueError(f"unknown checkpointer backend '{backend}'; expected one of {CHECKPOINTER_BACKENDS}")


def run_queue_backend_from_settings(settings: Any) -> RunQueueBackend:
    backend = _choice(getattr(settings, "run_queue_backend", None), "memory")
    if backend == "memory":
        return MessageQueueRegistry(MessageQueueSettings.from_app_settings(settings))
    if backend in PLANNED_BACKENDS:
        raise BackendUnavailable(
            f"run queue backend '{backend}' needs {PLANNED_BACKENDS[backend]} (not installed; decision D9)"
        )
    raise ValueError(f"unknown run queue backend '{backend}'; expected one of {RUN_QUEUE_BACKENDS}")


def replica_safety_warnings(settings: Any, factory: CheckpointerFactory) -> list[str]:
    """Warnings for a deployment that declares more than one replica on process-local backends."""
    replicas = int(getattr(settings, "replicas", 1) or 1)
    if replicas <= 1:
        return []
    warnings = []
    if not factory.shared:
        warnings.append(f"checkpointer '{factory.name}' is not shared: resume must hit the run's replica")
    if _choice(getattr(settings, "run_queue_backend", None), "memory") == "memory":
        warnings.append("run queue / cancel flags are in-process: route /message-queue and /cancel-run "
                        "with sticky sessions on runId")
    return warnings


class ReplicaConfigError(RuntimeError):
    """Decision D9: >1 replica declared on process-local backends without an explicit override."""


def enforce_replica_safety(settings: Any, factory: CheckpointerFactory) -> list[str]:
    """Fail fast (D9) when replicas>1 run on process-local backends.

    Returns the warnings to log when the operator set the explicit override
    ``AEGIS_ALPHA_ALLOW_LOCAL_BACKENDS_MULTI_REPLICA=true`` (they guarantee every request of a run —
    stream, resume, message-queue, cancel — reaches the replica that owns it, e.g. sticky routing on
    runId/threadId). Without the override a misrouted resume/cancel would silently act on nothing, so
    startup is refused instead of only warning.
    """
    warnings = replica_safety_warnings(settings, factory)
    if not warnings:
        return []
    if bool(getattr(settings, "allow_local_backends_multi_replica", False)):
        return [f"{w} (allowed by AEGIS_ALPHA_ALLOW_LOCAL_BACKENDS_MULTI_REPLICA)" for w in warnings]
    raise ReplicaConfigError(
        "AEGIS_ALPHA_REPLICAS>1 with process-local backends: " + "; ".join(warnings)
        + ". Use one replica, or set AEGIS_ALPHA_ALLOW_LOCAL_BACKENDS_MULTI_REPLICA=true only if every "
        "request of a run is routed to the same replica (docs/多副本设计.md)."
    )
