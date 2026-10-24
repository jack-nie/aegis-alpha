"""Unit tests for the per-thread steering / follow-up queue (pi PendingMessageQueue semantics)."""

from __future__ import annotations

import threading

import pytest

from app.core.message_queue import (
    MessageQueueError,
    MessageQueueRegistry,
    MessageQueueSettings,
    QueueOwner,
    is_queued_human_message,
    to_human_messages,
)

OWNER = {"userId": "u1", "tenantId": "t1"}


def _registry(**kw) -> MessageQueueRegistry:
    return MessageQueueRegistry(MessageQueueSettings(**kw))


def _open(reg, tid="run-1", owner=OWNER):
    return reg.open_run(tid, QueueOwner.from_any(owner))


def test_defaults_match_pi_one_at_a_time():
    s = MessageQueueSettings()
    assert s.steering_mode == "one-at-a-time"
    assert s.follow_up_mode == "one-at-a-time"
    assert s.skip_remaining_tools is True
    assert s.max_pending_per_kind == 8


def test_invalid_mode_rejected():
    with pytest.raises(ValueError):
        MessageQueueSettings(steering_mode="burst")


def test_one_at_a_time_drains_oldest_only():
    reg = _registry()
    q = _open(reg)
    for text in ("a", "b", "c"):
        reg.enqueue("run-1", "follow_up", text, QueueOwner.from_any(OWNER))
    assert [m.content for m in q.drain("follow_up")] == ["a"]
    assert [m.content for m in q.drain("follow_up")] == ["b"]
    assert q.summary()["pending"] == {"steer": 0, "followUp": 1}
    assert q.summary()["delivered"] == 2


def test_all_mode_drains_everything():
    reg = _registry(steering_mode="all", follow_up_mode="all")
    q = _open(reg)
    for text in ("a", "b"):
        reg.enqueue("run-1", "steer", text, QueueOwner.from_any(OWNER))
    assert [m.content for m in q.drain("steer")] == ["a", "b"]
    assert q.drain("steer") == []


def test_kinds_are_independent():
    reg = _registry()
    q = _open(reg)
    reg.enqueue("run-1", "steer", "s", QueueOwner.from_any(OWNER))
    reg.enqueue("run-1", "follow_up", "f", QueueOwner.from_any(OWNER))
    assert q.has_steering() and q.has_follow_up()
    assert [m.kind for m in q.drain("steer")] == ["steer"]
    assert q.has_follow_up()


def test_bounded_per_kind():
    reg = _registry(max_pending_per_kind=2)
    _open(reg)
    owner = QueueOwner.from_any(OWNER)
    reg.enqueue("run-1", "steer", "1", owner)
    reg.enqueue("run-1", "steer", "2", owner)
    with pytest.raises(MessageQueueError) as exc:
        reg.enqueue("run-1", "steer", "3", owner)
    assert exc.value.code == "QUEUE_FULL"
    # other kind still has room
    reg.enqueue("run-1", "follow_up", "f", owner)


def test_delivery_limit_per_run():
    reg = _registry(max_deliveries_per_run=2)
    q = _open(reg)
    owner = QueueOwner.from_any(OWNER)
    reg.enqueue("run-1", "steer", "1", owner)
    q.drain("steer")
    reg.enqueue("run-1", "steer", "2", owner)
    with pytest.raises(MessageQueueError) as exc:
        reg.enqueue("run-1", "follow_up", "3", owner)
    assert exc.value.code == "DELIVERY_LIMIT"


def test_concurrent_enqueue_respects_bound():
    reg = _registry(max_pending_per_kind=5, max_deliveries_per_run=100)
    q = _open(reg)
    owner = QueueOwner.from_any(OWNER)
    accepted, rejected = [], []

    def worker(i):
        try:
            reg.enqueue("run-1", "follow_up", f"m{i}", owner)
            accepted.append(i)
        except MessageQueueError as e:
            rejected.append(e.code)

    threads = [threading.Thread(target=worker, args=(i,)) for i in range(40)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    assert len(accepted) == 5
    assert set(rejected) == {"QUEUE_FULL"}
    assert q.summary()["pending"]["followUp"] == 5


@pytest.mark.parametrize(
    "owner",
    [None, {"userId": "u2", "tenantId": "t1"}, {"userId": "u1", "tenantId": "t2"}],
)
def test_owner_mismatch_rejected(owner):
    reg = _registry()
    _open(reg)
    with pytest.raises(MessageQueueError) as exc:
        reg.enqueue("run-1", "steer", "hi", QueueOwner.from_any(owner))
    assert exc.value.code == "OWNER_MISMATCH"


def test_ownerless_run_fails_closed():
    reg = _registry()
    reg.open_run("run-x", None)
    with pytest.raises(MessageQueueError) as exc:
        reg.enqueue("run-x", "steer", "hi", QueueOwner.from_any(OWNER))
    assert exc.value.code == "OWNER_MISMATCH"


@pytest.mark.parametrize(
    "kind,text,code",
    [("shout", "x", "INVALID_KIND"), ("steer", "   ", "EMPTY_MESSAGE"), ("steer", "x" * 11, "MESSAGE_TOO_LONG")],
)
def test_validation(kind, text, code):
    reg = _registry(max_message_chars=10)
    _open(reg)
    with pytest.raises(MessageQueueError) as exc:
        reg.enqueue("run-1", kind, text, QueueOwner.from_any(OWNER))
    assert exc.value.code == code


def test_unknown_thread_is_not_active():
    reg = _registry()
    with pytest.raises(MessageQueueError) as exc:
        reg.enqueue("nope", "steer", "hi", QueueOwner.from_any(OWNER))
    assert exc.value.code == "NOT_ACTIVE"


def test_disabled_registry():
    reg = _registry(enabled=False)
    assert reg.open_run("run-1", QueueOwner.from_any(OWNER)) is None
    with pytest.raises(MessageQueueError) as exc:
        reg.enqueue("run-1", "steer", "hi", QueueOwner.from_any(OWNER))
    assert exc.value.code == "DISABLED"


def test_pause_keeps_pending_and_rejects_new_then_resume_reactivates():
    reg = _registry()
    q = _open(reg)
    owner = QueueOwner.from_any(OWNER)
    reg.enqueue("run-1", "follow_up", "kept", owner)
    q.pause()
    with pytest.raises(MessageQueueError) as exc:
        reg.enqueue("run-1", "follow_up", "approve", owner)
    assert exc.value.code == "RUN_PAUSED"
    again = reg.open_run("run-1", None)  # resume path
    assert again is q and q.state == "active"
    assert [m.content for m in q.drain("follow_up")] == ["kept"]


def test_close_discards_and_reports_undelivered():
    reg = _registry()
    q = _open(reg)
    events = []
    q.subscribe(events.append)
    reg.enqueue("run-1", "steer", "late", QueueOwner.from_any(OWNER))
    undelivered = reg.finish_run("run-1", "aborted")
    assert [m.content for m in undelivered] == ["late"]
    assert q.summary()["closeReason"] == "aborted"
    assert [e["type"] for e in events] == ["message_queued", "message_queue_closed"]
    with pytest.raises(MessageQueueError) as exc:
        reg.enqueue("run-1", "steer", "after", QueueOwner.from_any(OWNER))
    assert exc.value.code == "NOT_ACTIVE"
    # a new run on the same thread id gets a fresh queue
    fresh = _open(reg)
    assert fresh is not q and fresh.state == "active"


def test_clear_requires_owner():
    reg = _registry()
    q = _open(reg)
    reg.enqueue("run-1", "steer", "x", QueueOwner.from_any(OWNER))
    with pytest.raises(MessageQueueError):
        q.clear(QueueOwner.from_any({"userId": "evil", "tenantId": "t1"}))
    dropped = q.clear(QueueOwner.from_any(OWNER))
    assert [m.content for m in dropped] == ["x"]
    assert not q.has_steering()


def test_registry_is_bounded_and_evicts_closed_first():
    reg = _registry(max_threads=2)
    a = _open(reg, "a")
    _open(reg, "b")
    a.close("completed")
    _open(reg, "c")
    assert reg.get("a") is None and reg.get("b") is not None and reg.get("c") is not None
    _open(reg, "d")  # no closed queue left: oldest active is closed + evicted
    assert reg.get("b") is None


def test_human_message_marker():
    reg = _registry()
    q = _open(reg)
    reg.enqueue("run-1", "steer", "focus on risk", QueueOwner.from_any(OWNER))
    [msg] = to_human_messages(q.drain("steer"))
    assert msg.content == "focus on risk"
    assert is_queued_human_message(msg)
    assert msg.additional_kwargs["aegis_queue"]["kind"] == "steer"
