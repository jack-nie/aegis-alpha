"""P4-15: AgentEnvelope + ``convert_to_llm`` (pi ``AgentMessage`` / ``convertToLlm``).

Agent-loop entries carry semantics LangChain messages cannot express (a queued steer vs. the task,
a skipped or refused tool call, an elided/compacted tool result, a display-only notice). An
envelope keeps ``kind`` / ``payload`` / ``display`` for audit and UI next to the ``llm`` message (or
``None`` for entries the model must never see). ``convert_to_llm`` is the single LLM boundary.

Migration (checkpoint compatibility): graph state still stores plain LangChain messages, so old
threads need nothing. Every reader goes through :func:`envelope_of`, which accepts all three
shapes: an ``AgentEnvelope``, its ``to_dict()`` form (``{"__aegis_envelope": 1, ...}``), and a legacy
LangChain message classified from its existing markers. Switching the stored form to envelope
dicts later only needs writers to change.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Iterable

from langchain_core.messages import (
    AIMessage,
    BaseMessage,
    HumanMessage,
    SystemMessage,
    ToolMessage,
    message_to_dict,
    messages_from_dict,
)

from .agent_context import ELIDED_PREFIX
from .message_queue import QUEUE_MESSAGE_MARKER, SKIPPED_TOOL_RESULT_TEXT

ENVELOPE_MARKER = "__aegis_envelope"
KIND_MESSAGE = "message"
KIND_QUEUED_STEER = "queued_steer"
KIND_QUEUED_FOLLOW_UP = "queued_follow_up"
KIND_TOOL_CALLS = "tool_calls"
KIND_TOOL_RESULT = "tool_result"
KIND_TOOL_SKIPPED = "tool_skipped"
KIND_TOOL_REFUSED = "tool_refused"
KIND_COMPACTION_SUMMARY = "compaction_summary"
KIND_NOTICE = "notice"  # display/audit only; never sent to the model
KINDS = frozenset({
    KIND_MESSAGE, KIND_QUEUED_STEER, KIND_QUEUED_FOLLOW_UP, KIND_TOOL_CALLS, KIND_TOOL_RESULT,
    KIND_TOOL_SKIPPED, KIND_TOOL_REFUSED, KIND_COMPACTION_SUMMARY, KIND_NOTICE,
})
_DISPLAY_CHARS = 200


@dataclass
class AgentEnvelope:
    kind: str
    payload: dict[str, Any] = field(default_factory=dict)
    display: str = ""
    llm: BaseMessage | None = None

    def __post_init__(self) -> None:
        if self.kind not in KINDS:
            raise ValueError(f"unknown envelope kind: {self.kind}")

    def to_dict(self) -> dict[str, Any]:
        return {
            ENVELOPE_MARKER: 1,
            "kind": self.kind,
            "payload": dict(self.payload),
            "display": self.display,
            "llm": message_to_dict(self.llm) if self.llm is not None else None,
        }

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "AgentEnvelope":
        raw = data.get("llm")
        llm = messages_from_dict([raw])[0] if isinstance(raw, dict) else None
        kind = str(data.get("kind") or KIND_MESSAGE)
        return cls(kind=kind if kind in KINDS else KIND_MESSAGE, payload=dict(data.get("payload") or {}),
                   display=str(data.get("display") or ""), llm=llm)


def notice(text: str, **payload: Any) -> AgentEnvelope:
    """A display/audit-only entry (e.g. "run resumed after approval")."""
    return AgentEnvelope(KIND_NOTICE, payload=payload, display=str(text), llm=None)


def _text(message: Any) -> str:
    content = getattr(message, "content", "")
    return content if isinstance(content, str) else str(content)


def _clip(text: str) -> str:
    return text if len(text) <= _DISPLAY_CHARS else text[:_DISPLAY_CHARS] + "…"


def _classify(message: BaseMessage) -> AgentEnvelope:
    text = _text(message)
    kwargs = getattr(message, "additional_kwargs", None) or {}
    if isinstance(message, HumanMessage) and isinstance(kwargs.get(QUEUE_MESSAGE_MARKER), dict):
        info = kwargs[QUEUE_MESSAGE_MARKER]
        kind = KIND_QUEUED_STEER if info.get("kind") == "steer" else KIND_QUEUED_FOLLOW_UP
        return AgentEnvelope(kind, payload={"id": info.get("id")}, display=_clip(text), llm=message)
    if isinstance(message, ToolMessage):
        artifact = getattr(message, "artifact", None) or {}
        artifact = artifact if isinstance(artifact, dict) else {}
        payload = {"toolCallId": message.tool_call_id, "toolName": message.name,
                   "isError": bool(artifact.get("isError", getattr(message, "status", "") == "error"))}
        if text == SKIPPED_TOOL_RESULT_TEXT:
            return AgentEnvelope(KIND_TOOL_SKIPPED, payload=payload, display=f"skipped {message.name}", llm=message)
        if artifact.get("refused"):
            payload["reason"] = artifact.get("refused")
            return AgentEnvelope(KIND_TOOL_REFUSED, payload=payload, display=f"refused {message.name}", llm=message)
        if text.startswith(ELIDED_PREFIX):
            return AgentEnvelope(KIND_COMPACTION_SUMMARY, payload=payload, display=_clip(text), llm=message)
        payload["truncated"] = bool(artifact.get("truncated"))
        return AgentEnvelope(KIND_TOOL_RESULT, payload=payload, display=f"{message.name} result", llm=message)
    if isinstance(message, AIMessage) and getattr(message, "tool_calls", None):
        names = [str(c.get("name")) for c in message.tool_calls]
        return AgentEnvelope(KIND_TOOL_CALLS, payload={"tools": names}, display="calls " + ", ".join(names),
                             llm=message)
    return AgentEnvelope(KIND_MESSAGE, payload={"role": getattr(message, "type", "")}, display=_clip(text),
                         llm=message)


def envelope_of(item: Any) -> AgentEnvelope:
    """Read any stored shape (envelope, envelope dict, LangChain message dict, legacy message)."""
    if isinstance(item, AgentEnvelope):
        return item
    if isinstance(item, dict):
        if item.get(ENVELOPE_MARKER):
            return AgentEnvelope.from_dict(item)
        if "type" in item and "data" in item:  # langchain message_to_dict form
            return _classify(messages_from_dict([item])[0])
        raise TypeError("unsupported agent message dict")
    if isinstance(item, BaseMessage):
        return _classify(item)
    raise TypeError(f"unsupported agent message: {type(item).__name__}")


def convert_to_llm(items: Iterable[Any]) -> list[BaseMessage]:
    """The only conversion to provider messages: drops display-only entries and system smuggling.

    A ``SystemMessage`` inside the conversation history is never forwarded: the system prompt is
    built by the caller (P4-14), and a system turn inside stored history would outrank it.
    """
    out: list[BaseMessage] = []
    for item in items or []:
        envelope = envelope_of(item)
        message = envelope.llm
        if message is None or isinstance(message, SystemMessage):
            continue
        out.append(message)
    return out


def audit_view(items: Iterable[Any]) -> list[dict[str, Any]]:
    """Kind + display + payload per entry, for audit / UI (never message bodies beyond a clip)."""
    return [
        {"kind": e.kind, "display": e.display, **({"payload": e.payload} if e.payload else {})}
        for e in (envelope_of(i) for i in (items or []))
    ]


def conversation_kinds(items: Iterable[Any]) -> dict[str, int]:
    """Count of entries per kind (trace-safe summary of an agent loop)."""
    counts: dict[str, int] = {}
    for item in items or []:
        try:
            kind = envelope_of(item).kind
        except TypeError:
            continue
        counts[kind] = counts.get(kind, 0) + 1
    return counts
