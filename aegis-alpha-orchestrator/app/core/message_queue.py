"""Per-thread message queue for the agent loop (steering + follow-up).

Modeled on pi-mono's dual-loop agent (``packages/agent/src/agent.ts``
``PendingMessageQueue`` + ``packages/agent/src/agent-loop.ts`` ``runLoop``):

* **steering** messages are delivered *inside* the inner (tool) loop: after the
  tool call that is currently executing finishes, the remaining tool calls of
  the same assistant message are skipped (each gets an explicit "skipped" tool
  result) and the steering message is injected before the next LLM call.
* **follow-up** messages are delivered only when the inner loop would end
  (the agent produced a final answer and no steering is pending); the outer
  loop then re-enters the agent with the follow-up as a new user turn.

Queue modes mirror pi's ``QueueMode``: ``"one-at-a-time"`` drains only the oldest
message per delivery point, ``"all"`` drains everything queued.

Concurrency: the event loop is single-threaded, but enqueue may also be called
from threadpool handlers, so every mutation runs inside a ``threading.Lock``
critical section that never awaits. Listeners (SSE fan-out) are invoked outside
of the lock.

Abort / lifecycle policy (documented in 消息排队实现说明.md):

* A queue exists only while its run is registered (``open_run``). Enqueueing to
  an unknown thread -> ``NOT_ACTIVE`` (Java falls back to starting a new turn).
* When the run pauses at the approval interrupt the queue becomes ``paused``:
  already-queued messages are kept for delivery after an explicit resume, new
  messages are rejected (``RUN_PAUSED``). Queued text can never resume or
  approve a paused run; only ``/resume-workflow`` with an explicit boolean does.
* When the run completes, errors, or the client aborts the stream, the queue is
  closed and undelivered messages are discarded and reported
  (``message_queue_closed`` event / ``final_state.messageQueue``).

Parallel fan-out (phase 2 runs several tool agents in the same superstep, each on
its own ``agent_messages`` channel):

* An untargeted message is delivered to exactly one agent: whichever reaches a
  delivery point first (drain is atomic under the lock).
* ``target`` pins a message to one agent node id; other agents never drain it.
* The engine registers the run's receiver agents (``set_receivers``) and marks an
  agent done once it leaves its loop (``mark_agent_done``). Enqueueing to an unknown
  target -> ``INVALID_TARGET``; to a finished target -> ``TARGET_FINISHED``; when no
  receiver can still take a message -> ``NO_RECEIVER`` (Java starts a new turn instead).
"""

from __future__ import annotations

import logging
import threading
import time
import uuid
from collections import OrderedDict, deque
from dataclasses import dataclass, field
from typing import Any, Callable, Literal

logger = logging.getLogger(__name__)

QueueKind = Literal["steer", "follow_up"]
QueueMode = Literal["one-at-a-time", "all"]

QUEUE_KINDS: tuple[str, ...] = ("steer", "follow_up")
QUEUE_MODES: tuple[str, ...] = ("one-at-a-time", "all")

# additional_kwargs marker on injected HumanMessages.
QUEUE_MESSAGE_MARKER = "aegis_queue"
SKIPPED_TOOL_RESULT_TEXT = "Skipped due to queued user message."
# Config key used to hand the run's queue to graph nodes (non-primitive -> never
# copied into checkpoint metadata by LangGraph).
CONFIG_QUEUE_KEY = "aegis_message_queue"

STATE_ACTIVE = "active"
STATE_PAUSED = "paused"
STATE_CLOSED = "closed"


class MessageQueueError(Exception):
    """Enqueue/clear rejected. ``code`` maps to an HTTP status in the router."""

    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code
        self.message = message


@dataclass(frozen=True)
class QueueOwner:
    user_id: str = ""
    tenant_id: str = ""

    @classmethod
    def from_any(cls, value: Any) -> "QueueOwner | None":
        if value is None:
            return None
        if isinstance(value, QueueOwner):
            return value
        if isinstance(value, dict):
            uid = value.get("userId", value.get("user_id"))
            tid = value.get("tenantId", value.get("tenant_id"))
            if uid in (None, "") and tid in (None, ""):
                return None
            return cls(str(uid or ""), str(tid or ""))
        return None

    def matches(self, other: "QueueOwner | None") -> bool:
        if other is None:
            return False
        return self.user_id == other.user_id and self.tenant_id == other.tenant_id

    def to_dict(self) -> dict[str, str]:
        return {"userId": self.user_id, "tenantId": self.tenant_id}


@dataclass
class QueuedMessage:
    id: str
    kind: str
    content: str
    enqueued_at: float = field(default_factory=time.time)
    # Agent node id this message is pinned to (None = first agent to reach a delivery point).
    target: str | None = None

    def to_dict(self) -> dict[str, Any]:
        return {
            "id": self.id,
            "kind": self.kind,
            "content": self.content,
            "enqueuedAt": self.enqueued_at,
            "target": self.target,
        }

    def deliverable_to(self, node_id: str | None) -> bool:
        return self.target is None or node_id is None or self.target == node_id


@dataclass(frozen=True)
class MessageQueueSettings:
    enabled: bool = True
    steering_mode: str = "one-at-a-time"
    follow_up_mode: str = "one-at-a-time"
    max_pending_per_kind: int = 8
    max_message_chars: int = 4000
    max_deliveries_per_run: int = 16
    skip_remaining_tools: bool = False  # P4-11: pi HEAD batch semantics (decision D1: kept)
    max_threads: int = 1000

    def __post_init__(self) -> None:
        if self.steering_mode not in QUEUE_MODES:
            raise ValueError(f"invalid steering_mode {self.steering_mode!r}")
        if self.follow_up_mode not in QUEUE_MODES:
            raise ValueError(f"invalid follow_up_mode {self.follow_up_mode!r}")
        if self.max_pending_per_kind < 1:
            raise ValueError("max_pending_per_kind must be >= 1")

    @classmethod
    def from_app_settings(cls, settings: Any) -> "MessageQueueSettings":
        return cls(
            enabled=bool(getattr(settings, "message_queue_enabled", True)),
            steering_mode=str(getattr(settings, "message_queue_steering_mode", "one-at-a-time")),
            follow_up_mode=str(getattr(settings, "message_queue_follow_up_mode", "one-at-a-time")),
            max_pending_per_kind=int(getattr(settings, "message_queue_max_pending", 8)),
            max_message_chars=int(getattr(settings, "message_queue_max_chars", 4000)),
            max_deliveries_per_run=int(getattr(settings, "message_queue_max_deliveries", 16)),
            skip_remaining_tools=bool(getattr(settings, "message_queue_skip_remaining_tools", False)),
            max_threads=int(getattr(settings, "message_queue_max_threads", 1000)),
        )


Listener = Callable[[dict[str, Any]], None]


class ThreadMessageQueue:
    """Steering + follow-up queues for a single run/thread."""

    def __init__(self, thread_id: str, owner: QueueOwner | None, settings: MessageQueueSettings):
        self.thread_id = thread_id
        self.owner = owner
        self.settings = settings
        self._lock = threading.Lock()
        self._queues: dict[str, deque[QueuedMessage]] = {k: deque() for k in QUEUE_KINDS}
        self._modes: dict[str, str] = {
            "steer": settings.steering_mode,
            "follow_up": settings.follow_up_mode,
        }
        self._listeners: list[Listener] = []
        self.state = STATE_ACTIVE
        self.delivered = 0
        self.skipped_tool_calls = 0
        # P4-11: which agent took which queued message (lets Java write answers back to chat).
        self.deliveries: list[dict[str, Any]] = []
        self.undelivered: list[QueuedMessage] = []
        self.close_reason: str | None = None
        # Agent node ids that can drain this run's messages (None = unknown, accept any).
        self._receivers: frozenset[str] | None = None
        self._done_agents: set[str] = set()

    # ── listeners ──────────────────────────────────────────────────────
    def subscribe(self, listener: Listener) -> Callable[[], None]:
        with self._lock:
            self._listeners.append(listener)

        def _unsubscribe() -> None:
            with self._lock:
                if listener in self._listeners:
                    self._listeners.remove(listener)

        return _unsubscribe

    def emit(self, event_type: str, data: dict[str, Any]) -> None:
        payload = {"type": event_type, "threadId": self.thread_id, **data}
        with self._lock:
            listeners = list(self._listeners)
        for listener in listeners:
            try:
                listener(payload)
            except Exception as exc:  # pragma: no cover - listener bugs must not break runs
                logger.warning("[QUEUE] listener failed for %s: %s", event_type, exc)

    # ── producer side ──────────────────────────────────────────────────
    def check_owner(self, owner: QueueOwner | None) -> None:
        if self.owner is None:
            # Fail closed: a run started without an owner cannot be steered by anyone.
            raise MessageQueueError("OWNER_MISMATCH", "run has no owner; queueing not allowed")
        if not self.owner.matches(owner):
            raise MessageQueueError("OWNER_MISMATCH", "caller does not own this run")

    def enqueue(self, kind: str, content: str, owner: QueueOwner | None,
                message_id: str | None = None, target: str | None = None) -> dict[str, Any]:
        target = (str(target).strip() or None) if target is not None else None
        if kind not in QUEUE_KINDS:
            raise MessageQueueError("INVALID_KIND", f"kind must be one of {QUEUE_KINDS}")
        text = (content or "").strip()
        if not text:
            raise MessageQueueError("EMPTY_MESSAGE", "message must not be empty")
        if len(text) > self.settings.max_message_chars:
            raise MessageQueueError(
                "MESSAGE_TOO_LONG", f"message exceeds {self.settings.max_message_chars} characters"
            )
        self.check_owner(owner)
        with self._lock:
            if self.state == STATE_CLOSED:
                raise MessageQueueError("NOT_ACTIVE", "run is no longer active")
            if self.state == STATE_PAUSED:
                raise MessageQueueError(
                    "RUN_PAUSED", "run is paused for approval; approve or reject first"
                )
            self._check_receiver_locked(target)
            q = self._queues[kind]
            if len(q) >= self.settings.max_pending_per_kind:
                raise MessageQueueError(
                    "QUEUE_FULL", f"{kind} queue is full ({self.settings.max_pending_per_kind})"
                )
            pending_total = sum(len(v) for v in self._queues.values())
            if self.delivered + pending_total >= self.settings.max_deliveries_per_run:
                raise MessageQueueError(
                    "DELIVERY_LIMIT",
                    f"run accepts at most {self.settings.max_deliveries_per_run} queued messages",
                )
            msg = QueuedMessage(
                id=message_id or f"q_{uuid.uuid4().hex[:12]}", kind=kind, content=text, target=target
            )
            q.append(msg)
            position = len(q)
            snapshot = self._pending_counts_locked()
        self.emit(
            "message_queued",
            {"message": msg.to_dict(), "position": position, "pending": snapshot,
             "mode": self._modes[kind]},
        )
        return {"message": msg.to_dict(), "position": position, "pending": snapshot,
                "mode": self._modes[kind], "state": self.state}

    def clear(self, owner: QueueOwner | None, reason: str = "cleared") -> list[QueuedMessage]:
        self.check_owner(owner)
        with self._lock:
            dropped = [m for k in QUEUE_KINDS for m in self._queues[k]]
            for k in QUEUE_KINDS:
                self._queues[k].clear()
        if dropped:
            self.emit("message_queue_cleared", {"reason": reason,
                                                "dropped": [m.to_dict() for m in dropped]})
        return dropped

    def _check_receiver_locked(self, target: str | None) -> None:
        if self._receivers is None:
            return
        if target is not None:
            if target not in self._receivers:
                raise MessageQueueError("INVALID_TARGET", f"no agent {target!r} in this run")
            if target in self._done_agents:
                raise MessageQueueError("TARGET_FINISHED", f"agent {target!r} already finished")
            return
        if not (self._receivers - self._done_agents):
            raise MessageQueueError("NO_RECEIVER", "no agent in this run can still take a message")

    # ── receivers (engine side) ────────────────────────────────────────
    def set_receivers(self, agent_ids: Any) -> None:
        with self._lock:
            self._receivers = frozenset(str(a) for a in agent_ids)

    def mark_agent_done(self, agent_id: str) -> None:
        with self._lock:
            self._done_agents.add(str(agent_id))

    def active_receivers(self) -> list[str] | None:
        with self._lock:
            if self._receivers is None:
                return None
            return sorted(self._receivers - self._done_agents)

    # ── consumer side (graph nodes) ────────────────────────────────────
    def has(self, kind: str, node_id: str | None = None) -> bool:
        with self._lock:
            return any(m.deliverable_to(node_id) for m in self._queues[kind])

    def has_steering(self, node_id: str | None = None) -> bool:
        return self.has("steer", node_id)

    def has_follow_up(self, node_id: str | None = None) -> bool:
        return self.has("follow_up", node_id)

    def has_pending(self, node_id: str | None = None) -> bool:
        return self.has_steering(node_id) or self.has_follow_up(node_id)

    def peek(self) -> dict[str, list[dict[str, Any]]]:
        with self._lock:
            return {k: [m.to_dict() for m in self._queues[k]] for k in QUEUE_KINDS}

    def drain(self, kind: str, *, node_id: str | None = None) -> list[QueuedMessage]:
        """pi ``PendingMessageQueue.drain``: oldest only or everything, per mode."""
        with self._lock:
            q = self._queues[kind]
            eligible = [m for m in q if m.deliverable_to(node_id)]
            if not eligible:
                return []
            out = eligible if self._modes[kind] == "all" else eligible[:1]
            taken = {id(m) for m in out}
            kept = [m for m in q if id(m) not in taken]
            q.clear()
            q.extend(kept)
            self.delivered += len(out)
            for m in out:
                self.deliveries.append({"id": m.id, "kind": m.kind, "nodeId": node_id})
            remaining = self._pending_counts_locked()
        self.emit(
            "message_dequeued",
            {"kind": kind, "nodeId": node_id, "messages": [m.to_dict() for m in out],
             "pending": remaining},
        )
        return out

    def record_skipped_tool(self, *, node_id: str, tool_call: dict[str, Any],
                            reason: str = "steering") -> None:
        with self._lock:
            self.skipped_tool_calls += 1
        self.emit(
            "tool_skipped",
            {"nodeId": node_id, "toolCallId": tool_call.get("id"),
             "toolName": tool_call.get("name"), "reason": reason,
             "result": SKIPPED_TOOL_RESULT_TEXT},
        )

    # ── lifecycle ──────────────────────────────────────────────────────
    def pause(self) -> None:
        with self._lock:
            if self.state == STATE_ACTIVE:
                self.state = STATE_PAUSED

    def reactivate(self, owner: QueueOwner | None = None) -> None:
        with self._lock:
            if self.state != STATE_CLOSED:
                self.state = STATE_ACTIVE
            if owner is not None and self.owner is None:
                self.owner = owner

    def close(self, reason: str) -> list[QueuedMessage]:
        with self._lock:
            if self.state == STATE_CLOSED:
                return list(self.undelivered)
            self.state = STATE_CLOSED
            self.close_reason = reason
            self.undelivered = [m for k in QUEUE_KINDS for m in self._queues[k]]
            for k in QUEUE_KINDS:
                self._queues[k].clear()
        self.emit("message_queue_closed", {"reason": reason,
                                           "undelivered": [m.to_dict() for m in self.undelivered],
                                           "delivered": self.delivered})
        return list(self.undelivered)

    def summary(self) -> dict[str, Any]:
        with self._lock:
            return {
                "state": self.state,
                "modes": {"steer": self._modes["steer"], "followUp": self._modes["follow_up"]},
                "pending": self._pending_counts_locked(),
                "delivered": self.delivered,
                "skippedToolCalls": self.skipped_tool_calls,
                "deliveries": list(self.deliveries),
                "undelivered": [m.to_dict() for m in self.undelivered],
                "closeReason": self.close_reason,
                "receivers": None if self._receivers is None
                else sorted(self._receivers - self._done_agents),
            }

    def _pending_counts_locked(self) -> dict[str, int]:
        return {"steer": len(self._queues["steer"]), "followUp": len(self._queues["follow_up"])}


class MessageQueueRegistry:
    """Bounded registry of per-thread queues (thread_id == Java runId)."""

    def __init__(self, settings: MessageQueueSettings | None = None):
        self.settings = settings or MessageQueueSettings()
        self._lock = threading.Lock()
        self._queues: "OrderedDict[str, ThreadMessageQueue]" = OrderedDict()

    @property
    def enabled(self) -> bool:
        return self.settings.enabled

    def open_run(self, thread_id: str, owner: QueueOwner | None) -> ThreadMessageQueue | None:
        """Register (or reactivate on resume) the queue for a run."""
        if not self.enabled or not thread_id:
            return None
        with self._lock:
            existing = self._queues.get(thread_id)
            if existing is not None and existing.state != STATE_CLOSED:
                self._queues.move_to_end(thread_id)
                q = existing
            else:
                q = ThreadMessageQueue(thread_id, owner, self.settings)
                self._queues[thread_id] = q
                self._queues.move_to_end(thread_id)
                self._evict_locked()
        if q is existing:
            q.reactivate(owner)
        return q

    def get(self, thread_id: str) -> ThreadMessageQueue | None:
        with self._lock:
            return self._queues.get(thread_id)

    def enqueue(self, thread_id: str, kind: str, content: str, owner: QueueOwner | None,
                message_id: str | None = None, target: str | None = None) -> dict[str, Any]:
        if not self.enabled:
            raise MessageQueueError("DISABLED", "message queue is disabled")
        q = self.get(thread_id)
        if q is None or q.state == STATE_CLOSED:
            raise MessageQueueError("NOT_ACTIVE", "no active run for this thread")
        return q.enqueue(kind, content, owner, message_id=message_id, target=target)

    def finish_run(self, thread_id: str, reason: str) -> list[QueuedMessage]:
        q = self.get(thread_id)
        if q is None:
            return []
        return q.close(reason)

    def _evict_locked(self) -> None:
        # Prefer evicting closed queues; never evict more than needed.
        while len(self._queues) > self.settings.max_threads:
            victim = next((k for k, v in self._queues.items() if v.state == STATE_CLOSED), None)
            if victim is None:
                victim = next(iter(self._queues))
                self._queues[victim].close("evicted")
            self._queues.pop(victim, None)


def queue_from_config(config: Any) -> ThreadMessageQueue | None:
    if not isinstance(config, dict):
        return None
    configurable = config.get("configurable") or {}
    q = configurable.get(CONFIG_QUEUE_KEY)
    return q if isinstance(q, ThreadMessageQueue) else None


def is_queued_human_message(msg: Any) -> bool:
    kwargs = getattr(msg, "additional_kwargs", None)
    return isinstance(kwargs, dict) and QUEUE_MESSAGE_MARKER in kwargs


def to_human_messages(messages: list[QueuedMessage]) -> list[Any]:
    from langchain_core.messages import HumanMessage

    return [
        HumanMessage(
            content=m.content,
            additional_kwargs={QUEUE_MESSAGE_MARKER: {"kind": m.kind, "id": m.id}},
        )
        for m in messages
    ]
