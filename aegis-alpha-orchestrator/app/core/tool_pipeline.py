"""P4-1: tool execution pipeline (validate → execute → classify → truncate → fence).

Modeled on pi's ``prepareToolCall`` / ``executePreparedToolCall`` / ``afterToolCall``:

* invalid arguments or unknown tools become an error ``ToolMessage`` (the backend is never called);
* exceptions raised by a tool become an error ``ToolMessage`` (the run keeps going and the model sees
  the error on its next turn) — ``asyncio.CancelledError`` is never swallowed;
* backend payloads with ``status`` in {error, unavailable, denied} are mapped to ``status="error"``;
* results are truncated (list items, then characters) with an explicit marker; evidence ids found in
  the full payload are extracted *before* truncation and kept in the artifact;
* the content is wrapped in a ``<tool_result>`` block (``prompt_safety.fence``), so text returned by
  external sources (news bodies etc.) cannot close the block or pose as instructions;
* ``artifact`` carries ``durationMs`` / ``truncated`` / ``isError`` / ``evidenceIds``.

Every call reports ``tool_execution_start`` / ``tool_execution_end`` through ``emit_tool_event``
(LangGraph custom stream when available, see P4-9).
"""

from __future__ import annotations

import asyncio
import json
import logging
import re
import time
from dataclasses import dataclass, field
from typing import Any, Awaitable, Callable

from langchain_core.messages import ToolMessage

from .prompt_safety import TOOL_RESULT_TAG, fence

logger = logging.getLogger(__name__)

ERROR_STATUSES = frozenset({"error", "unavailable", "denied"})
SYMBOL_RE = re.compile(r"^[A-Za-z0-9.\-]{1,15}$")
_EVIDENCE_TOKEN_RE = re.compile(r"\bev-[A-Za-z0-9][A-Za-z0-9\-]{2,63}\b")
_EVIDENCE_KEYS = ("evidenceId", "evidence_id")
_ARG_PREVIEW_CHARS = 64

Validator = Callable[[dict[str, Any]], "str | None"]


def validate_symbol(args: dict[str, Any]) -> str | None:
    symbol = args.get("symbol")
    if not isinstance(symbol, str) or not SYMBOL_RE.match(symbol.strip()):
        return "symbol must be 1-15 characters of letters, digits, '.' or '-'"
    return None


# Validators keyed by *argument name*: applied to every tool whose schema declares that argument.
# (``portfolio_id`` is validated by ``tools.portfolio_read`` itself, which answers ``denied``.)
DEFAULT_ARG_VALIDATORS: dict[str, Validator] = {
    "symbol": validate_symbol,
}


def _declared_args(tool: Any) -> set[str]:
    try:
        return set((getattr(tool, "args", None) or {}).keys())
    except Exception:  # noqa: BLE001
        return set()


@dataclass
class ToolPolicy:
    max_result_chars: int = 6000
    max_list_items: int = 20
    arg_validators: dict[str, Validator] = field(default_factory=lambda: dict(DEFAULT_ARG_VALIDATORS))

    @classmethod
    def from_settings(cls, settings: Any) -> "ToolPolicy":
        return cls(
            max_result_chars=max(200, int(getattr(settings, "tool_result_max_chars", 6000) or 6000)),
            max_list_items=max(1, int(getattr(settings, "tool_result_max_items", 20) or 20)),
        )


# ── payload helpers ──────────────────────────────────────────────────────────

def parse_tool_content(content: Any) -> Any:
    """ToolMessage content → python value (JSON when possible, else the text)."""
    if isinstance(content, (dict, list)) and not _is_block_list(content):
        return content
    if isinstance(content, list):  # content blocks
        content = "\n".join(str(b.get("text", "")) if isinstance(b, dict) else str(b) for b in content)
    text = "" if content is None else str(content)
    stripped = text.strip()
    if stripped[:1] in ("{", "["):
        try:
            return json.loads(stripped)
        except (TypeError, ValueError):
            return text
    return text


def _is_block_list(value: Any) -> bool:
    return isinstance(value, list) and bool(value) and all(isinstance(x, dict) and "type" in x for x in value)


def is_error_payload(payload: Any) -> bool:
    return isinstance(payload, dict) and str(payload.get("status") or "").lower() in ERROR_STATUSES


def extract_evidence_ids(payload: Any, *, limit: int = 50) -> list[str]:
    """Evidence ids anywhere in the full payload (keys ``evidenceId`` or ``ev-…`` tokens)."""
    found: list[str] = []

    def add(value: str) -> None:
        if value and value not in found and len(found) < limit:
            found.append(value)

    def walk(node: Any) -> None:
        if len(found) >= limit:
            return
        if isinstance(node, dict):
            for key, value in node.items():
                if key in _EVIDENCE_KEYS and isinstance(value, (str, int)):
                    add(str(value))
                else:
                    walk(value)
        elif isinstance(node, list):
            for item in node:
                walk(item)
        elif isinstance(node, str):
            for match in _EVIDENCE_TOKEN_RE.findall(node):
                add(match)

    walk(payload)
    return found


def _cap_lists(value: Any, max_items: int) -> tuple[Any, int]:
    """Cap every list to ``max_items``; returns (value, number of dropped items)."""
    if isinstance(value, list):
        dropped = max(0, len(value) - max_items)
        kept: list[Any] = []
        for item in value[:max_items]:
            capped, sub = _cap_lists(item, max_items)
            dropped += sub
            kept.append(capped)
        if len(value) > max_items:
            kept.append(f"[truncated {len(value) - max_items} items]")
        return kept, dropped
    if isinstance(value, dict):
        out: dict[str, Any] = {}
        dropped = 0
        for key, item in value.items():
            out[key], sub = _cap_lists(item, max_items)
            dropped += sub
        return out, dropped
    return value, 0


def truncate_payload(payload: Any, policy: ToolPolicy) -> tuple[str, bool]:
    """Serialize a payload within ``policy`` limits. Returns (text, truncated)."""
    capped, dropped = _cap_lists(payload, policy.max_list_items)
    text = capped if isinstance(capped, str) else json.dumps(capped, ensure_ascii=False, default=str)
    truncated = dropped > 0
    if len(text) > policy.max_result_chars:
        omitted = len(text) - policy.max_result_chars
        marker = f"\n[truncated: {omitted} chars omitted]"
        text = text[: max(0, policy.max_result_chars - len(marker))] + marker
        truncated = True
    return text, truncated


def redact_args(args: Any) -> dict[str, Any]:
    """Short, display-safe preview of tool arguments for events (no long free text)."""
    if not isinstance(args, dict):
        return {}
    out: dict[str, Any] = {}
    for key, value in list(args.items())[:10]:
        text = value if isinstance(value, (int, float, bool)) or value is None else str(value)
        if isinstance(text, str) and len(text) > _ARG_PREVIEW_CHARS:
            text = text[:_ARG_PREVIEW_CHARS] + "…"
        out[str(key)] = text
    return out


# ── events ───────────────────────────────────────────────────────────────────

def emit_tool_event(event: str, payload: dict[str, Any]) -> None:
    """Best-effort LangGraph custom-stream event (no-op outside a graph run)."""
    try:
        from langgraph.config import get_stream_writer

        writer = get_stream_writer()
    except Exception:  # noqa: BLE001 - not inside a runnable context
        return
    try:
        writer({"event": event, **payload})
    except Exception:  # noqa: BLE001
        logger.debug("tool event %s not delivered", event)


# ── wrapper ──────────────────────────────────────────────────────────────────

def _error_message(call: dict[str, Any], text: str, artifact: dict[str, Any]) -> ToolMessage:
    body = json.dumps({"error": text, "status": "error"}, ensure_ascii=False)
    return ToolMessage(
        content=fence(TOOL_RESULT_TAG, body),
        tool_call_id=str(call.get("id") or ""),
        name=call.get("name"),
        status="error",
        artifact=artifact,
    )


def make_guarded_tool_call(
    policy: ToolPolicy | None = None,
    *,
    node_id_of: Callable[[Any], str | None] | None = None,
) -> Callable[[Any, Callable[[Any], Awaitable[Any]]], Awaitable[Any]]:
    """Build the ``awrap_tool_call`` hook for ``ToolNode``."""
    policy = policy or ToolPolicy()

    async def guarded_tool_call(request: Any, execute: Callable[[Any], Awaitable[Any]]) -> Any:
        call = dict(getattr(request, "tool_call", None) or {})
        name = str(call.get("name") or "")
        call_id = str(call.get("id") or "")
        args = call.get("args") if isinstance(call.get("args"), dict) else {}
        node_id = node_id_of(request) if node_id_of else None
        started = time.monotonic()
        base = {"toolCallId": call_id, "toolName": name, "nodeId": node_id}
        emit_tool_event("tool_execution_start", {**base, "args": redact_args(args)})

        def finish(message: ToolMessage, *, is_error: bool, truncated: bool, evidence: list[str]) -> ToolMessage:
            artifact = {
                "durationMs": int((time.monotonic() - started) * 1000),
                "truncated": truncated,
                "isError": is_error,
                "evidenceIds": evidence,
            }
            message.artifact = artifact
            emit_tool_event(
                "tool_execution_end",
                {**base, "isError": is_error, "durationMs": artifact["durationMs"], "truncated": truncated},
            )
            return message

        if getattr(request, "tool", None) is None:
            return finish(_error_message(call, f"Unknown tool: {name}", {}), is_error=True, truncated=False, evidence=[])
        error = None
        for arg_name in sorted(_declared_args(request.tool)):
            validator = policy.arg_validators.get(arg_name)
            error = validator(args) if validator else None
            if error:
                break
        if error:
            return finish(
                _error_message(call, f"Invalid arguments: {error}", {}), is_error=True, truncated=False, evidence=[]
            )
        try:
            result = await execute(request)
        except asyncio.CancelledError:
            raise
        except Exception as exc:  # noqa: BLE001 - pi: tool errors are results, not run failures
            logger.warning("[TOOL] %s failed: %s", name, type(exc).__name__)
            return finish(
                _error_message(call, f"Tool failed: {type(exc).__name__}", {}), is_error=True, truncated=False, evidence=[]
            )
        if not isinstance(result, ToolMessage):
            return result  # Command-style output: leave untouched
        payload = parse_tool_content(result.content)
        is_error = result.status == "error" or is_error_payload(payload)
        evidence = extract_evidence_ids(payload)
        text, truncated = truncate_payload(payload, policy)
        result.content = fence(TOOL_RESULT_TAG, text)
        result.status = "error" if is_error else "success"
        return finish(result, is_error=is_error, truncated=truncated, evidence=evidence)

    return guarded_tool_call


def build_tool_node(tools: list, policy: ToolPolicy | None = None) -> Any:
    """``ToolNode`` with error handling and the guarded wrapper (P4-1)."""
    from langgraph.prebuilt import ToolNode

    return ToolNode(tools, handle_tool_errors=True, awrap_tool_call=make_guarded_tool_call(policy))


_FENCE_RE = re.compile(r"^\s*<" + TOOL_RESULT_TAG + r">\n?(.*?)\n?</" + TOOL_RESULT_TAG + r">\s*$", re.DOTALL)


def unfence_tool_result(content: Any) -> str:
    """Inner text of a ``<tool_result>`` block (or the content unchanged)."""
    text = "" if content is None else str(content)
    match = _FENCE_RE.match(text)
    return match.group(1) if match else text


def count_tool_errors(messages: list[Any]) -> int:
    """Number of error ToolMessages in a conversation (P4-1 / P4-2 degraded accounting)."""
    return sum(
        1
        for m in messages or []
        if type(m).__name__ == "ToolMessage" and getattr(m, "status", "success") == "error"
    )
