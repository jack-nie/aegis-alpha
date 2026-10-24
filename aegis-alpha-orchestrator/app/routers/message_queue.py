"""Message-queue router: steer / follow-up an active agent run.

Called by the Java API (service token required, see ``main.service_auth_middleware``)
when a user sends a chat message while their run is still executing. The owner
({userId, tenantId}) is checked against the owner registered when the run started.
"""

from __future__ import annotations

from fastapi import APIRouter
from fastapi.responses import JSONResponse

from ..core.message_queue import MessageQueueError, QueueOwner
from ..dependencies import message_queues
from ..models.requests import QueueMessageRequest, QueueOwnerRequest

router = APIRouter(tags=["message-queue"])

_STATUS_BY_CODE = {
    "NOT_ACTIVE": 409,
    "RUN_PAUSED": 409,
    "DISABLED": 409,
    "TARGET_FINISHED": 409,
    "NO_RECEIVER": 409,
    "OWNER_MISMATCH": 403,
    "QUEUE_FULL": 429,
    "DELIVERY_LIMIT": 429,
    "INVALID_KIND": 422,
    "EMPTY_MESSAGE": 422,
    "MESSAGE_TOO_LONG": 422,
    "INVALID_TARGET": 422,
}

_KIND_ALIASES = {"steer": "steer", "steering": "steer", "follow_up": "follow_up",
                 "followup": "follow_up", "follow-up": "follow_up", "followUp": "follow_up"}


def _error(exc: MessageQueueError) -> JSONResponse:
    return JSONResponse(
        status_code=_STATUS_BY_CODE.get(exc.code, 400),
        content={"ok": False, "code": exc.code, "error": exc.message},
    )


@router.post("/message-queue/{thread_id}/messages", status_code=202)
async def enqueue_message(thread_id: str, body: QueueMessageRequest):
    kind = _KIND_ALIASES.get((body.kind or "").strip(), (body.kind or "").strip())
    try:
        result = message_queues.enqueue(
            thread_id,
            kind,
            body.message,
            QueueOwner.from_any(body.owner),
            message_id=body.message_id,
            target=body.agent_id,
        )
    except MessageQueueError as exc:
        return _error(exc)
    return JSONResponse(status_code=202, content={"ok": True, "threadId": thread_id, **result})


@router.post("/message-queue/{thread_id}/status")
async def queue_status(thread_id: str, body: QueueOwnerRequest):
    queue = message_queues.get(thread_id)
    if queue is None:
        return _error(MessageQueueError("NOT_ACTIVE", "no run queue for this thread"))
    try:
        queue.check_owner(QueueOwner.from_any(body.owner))
    except MessageQueueError as exc:
        return _error(exc)
    return {"ok": True, "threadId": thread_id, **queue.summary(), "queued": queue.peek()}


@router.post("/message-queue/{thread_id}/clear")
async def clear_queue(thread_id: str, body: QueueOwnerRequest):
    """Drop pending messages (used when the user cancels the run)."""
    queue = message_queues.get(thread_id)
    if queue is None:
        return {"ok": True, "threadId": thread_id, "dropped": []}
    try:
        dropped = queue.clear(QueueOwner.from_any(body.owner), reason="cleared")
    except MessageQueueError as exc:
        return _error(exc)
    return {"ok": True, "threadId": thread_id, "dropped": [m.to_dict() for m in dropped]}
