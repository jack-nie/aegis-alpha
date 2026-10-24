"""P4-8: agent-loop context transform (pi ``transformContext`` analogue).

Applied to the agent's prior messages right before every LLM call; graph state (``agent_messages``)
is never rewritten, so checkpoints keep the full tool results.

1. The most recent ``keep_recent_tool_results`` ToolMessages are sent verbatim; older ones are
   replaced by a one-line marker that keeps the tool name, the original size and every evidence id.
2. If the estimate still exceeds ``max_agent_tokens``, a deterministic compaction pass runs: every
   ToolMessage except the latest one is reduced to its marker and older AI texts are clipped.
   (Adjusted from the review: no extra summarising LLM call — 待确认 whether an LLM summary is wanted.)
3. A provider context-overflow error triggers one forced compaction and a single retry
   (``is_context_overflow`` + ``compact``), handled by ``LLMClient.invoke_agent_with_tools``.

Message order and ``tool_call_id`` pairing are always preserved (providers reject orphan tool calls).
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from langchain_core.messages import AIMessage, ToolMessage

from .context_assembly import estimate_tokens
from .tool_pipeline import extract_evidence_ids, unfence_tool_result

ELIDED_PREFIX = "[earlier tool result elided"

_OVERFLOW_MARKERS = (
    "context_length_exceeded",
    "maximum context length",
    "context length",
    "context window",
    "too many tokens",
    "prompt is too long",
    "input is too long",
    "reduce the length",
)


@dataclass(frozen=True)
class ContextPolicy:
    keep_recent_tool_results: int = 4
    elided_tool_result_chars: int = 400
    max_agent_tokens: int = 12000
    compacted_ai_text_chars: int = 600

    @classmethod
    def from_settings(cls, settings: Any, context_window: int | None = None) -> "ContextPolicy":
        """Settings-driven policy (all defaults 待确认). A registry context window caps the budget at 60%."""
        keep = _int(getattr(settings, "agent_keep_recent_tool_results", 4), 4, minimum=1)
        budget = _int(getattr(settings, "agent_max_context_tokens", 12000), 12000, minimum=1000)
        if context_window:
            budget = min(budget, max(1000, int(context_window * 0.6)))
        return cls(keep_recent_tool_results=keep, max_agent_tokens=budget)


def _int(value: Any, default: int, *, minimum: int) -> int:
    try:
        return max(minimum, int(value))
    except (TypeError, ValueError):
        return default


def is_context_overflow(error: BaseException) -> bool:
    text = f"{type(error).__name__} {error}".lower()
    return any(marker in text for marker in _OVERFLOW_MARKERS)


def estimate_messages_tokens(messages: list[Any]) -> int:
    total = 0
    for message in messages:
        content = getattr(message, "content", "")
        total += estimate_tokens(content if isinstance(content, str) else str(content))
        for call in getattr(message, "tool_calls", None) or []:
            total += estimate_tokens(str(call.get("args") if isinstance(call, dict) else call))
    return total


def _evidence_of(message: ToolMessage) -> list[str]:
    artifact = getattr(message, "artifact", None)
    if isinstance(artifact, dict) and isinstance(artifact.get("evidenceIds"), list) and artifact["evidenceIds"]:
        return [str(x) for x in artifact["evidenceIds"]]
    return extract_evidence_ids(unfence_tool_result(message.content))


def _elide(message: ToolMessage) -> ToolMessage:
    content = message.content if isinstance(message.content, str) else str(message.content)
    if content.startswith(ELIDED_PREFIX):
        return message
    evidence = _evidence_of(message)
    marker = f"{ELIDED_PREFIX}: tool={message.name or 'tool'}, {len(content)} chars"
    if evidence:
        marker += f"; evidenceIds={','.join(evidence)}"
    marker += "]"
    return ToolMessage(
        content=marker,
        tool_call_id=message.tool_call_id,
        name=message.name,
        status=getattr(message, "status", "success"),
        artifact=getattr(message, "artifact", None),
        id=message.id,
    )


def _clip_ai(message: AIMessage, max_chars: int) -> AIMessage:
    content = message.content
    if not isinstance(content, str) or len(content) <= max_chars:
        return message
    return message.model_copy(update={"content": content[:max_chars] + " …[clipped]"})


def _elide_older_tools(messages: list[Any], keep: int) -> tuple[list[Any], int]:
    tool_positions = [i for i, m in enumerate(messages) if isinstance(m, ToolMessage)]
    old = set(tool_positions[:-keep]) if keep > 0 else set(tool_positions)
    out, elided = [], 0
    for index, message in enumerate(messages):
        if index in old:
            replacement = _elide(message)
            elided += replacement is not message
            out.append(replacement)
        else:
            out.append(message)
    return out, elided


def compact(messages: list[Any], policy: ContextPolicy) -> list[Any]:
    """Forced compaction: only the latest ToolMessage stays verbatim; older AI texts are clipped."""
    out, _ = _elide_older_tools(list(messages), 1)
    last_ai = max((i for i, m in enumerate(out) if isinstance(m, AIMessage)), default=-1)
    return [
        _clip_ai(m, policy.compacted_ai_text_chars) if isinstance(m, AIMessage) and i != last_ai else m
        for i, m in enumerate(out)
    ]


def transform_context(messages: list[Any], policy: ContextPolicy | None = None) -> tuple[list[Any], dict[str, Any]]:
    """Return (messages to send, stats). Never mutates the input list or its messages."""
    policy = policy or ContextPolicy()
    out, elided = _elide_older_tools(list(messages or []), policy.keep_recent_tool_results)
    stats: dict[str, Any] = {"elidedToolResults": elided, "compacted": False}
    tokens = estimate_messages_tokens(out)
    if tokens > policy.max_agent_tokens:
        out = compact(out, policy)
        stats["compacted"] = True
        stats["elidedToolResults"] = sum(
            1 for m in out if isinstance(m, ToolMessage) and str(m.content).startswith(ELIDED_PREFIX)
        )
        tokens = estimate_messages_tokens(out)
    stats["estimatedTokens"] = tokens
    return out, stats
