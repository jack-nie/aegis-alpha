"""Budgeted model-context assembly.

Protected blocks (market snapshot, latest tool results, memory snippets) are
always rendered in full. Upstream node outputs are walked newest-to-oldest
and either included whole or replaced with a short summary plus ``[compressed]``.
The result is never a character prefix of ``json.dumps(state)``.
"""

from __future__ import annotations

import json
from typing import Any

DEFAULT_TOKEN_BUDGET = 2000
AGGREGATE_TOKEN_BUDGET = 8000

_SKIP_NODE_KEYS = frozenset(
    {
        "marketDataContext",
        "memoryContext",
        "latestToolResults",
        "toolResults",
        "ticker",
        "symbol",
        "userId",
        "user_id",
        "subject",
        "tool_rounds",
        "last_agent_id",
        "approval_status",
    }
)

_INPUT_TOKEN_KEYS = ("inputTokens", "input_tokens")
_OUTPUT_TOKEN_KEYS = ("outputTokens", "output_tokens")
_TOTAL_TOKEN_KEYS = ("totalTokens", "total_tokens")
_SOURCE_ID_FIELDS = ("evidenceId", "id", "sourceId")
_CLAIM_ID_FIELDS = ("evidenceId", "evidence_id")


def estimate_tokens(text: str) -> int:
    """Rough token estimate: 4 characters per token, minimum 1 for non-empty text."""
    if not text:
        return 0
    return max(1, len(text) // 4)


def assemble_model_context(state: dict | None, *, token_budget: int = DEFAULT_TOKEN_BUDGET) -> str:
    """Assemble a prompt context string under ``token_budget``.

    Protected sections are never truncated. Node sections that do not fit are
    compressed (summary clipped to 80 characters plus the ``[compressed]`` marker)
    rather than dropped, so the model still sees that the node ran.
    """
    if not isinstance(state, dict):
        state = {}

    protected_parts = [
        part
        for part in (
            _render_market(state),
            _render_latest_tools(state),
            _render_memory(state),
        )
        if part
    ]
    protected = "\n\n".join(protected_parts)
    parts: list[str] = [protected] if protected else []
    protected_over_budget = estimate_tokens(protected) > token_budget

    nodes = _upstream_nodes(state)
    for key, node in reversed(nodes):
        if protected_over_budget:
            parts.append(_render_compressed_node(key, node))
            continue
        full = _render_full_node(key, node)
        candidate = "\n\n".join([*parts, full])
        if estimate_tokens(candidate) <= token_budget:
            parts.append(full)
        else:
            parts.append(_render_compressed_node(key, node))

    return "\n\n".join(parts)


def apply_trace_usage(trace_entry: dict, result_data: dict | None) -> dict:
    """Copy normalized ``tokenUsage`` from a node result onto a trace entry.

    Returns the same ``trace_entry`` object. Missing usage leaves the entry unchanged.
    """
    if not isinstance(trace_entry, dict):
        return trace_entry
    if not isinstance(result_data, dict):
        return trace_entry
    raw_usage = result_data.get("tokenUsage")
    if not isinstance(raw_usage, dict):
        return trace_entry
    normalized = normalize_token_usage(raw_usage)
    if normalized is not None:
        trace_entry["tokenUsage"] = normalized
    return trace_entry


def memory_hit_count(state: dict | None) -> int:
    """Return ``state['memoryContext']['hitCount']`` when present, else 0."""
    if not isinstance(state, dict):
        return 0
    memory = state.get("memoryContext")
    if not isinstance(memory, dict) or "hitCount" not in memory:
        return 0
    return _as_int(memory.get("hitCount"))


def normalize_token_usage(usage: Any) -> dict | None:
    """Normalize provider usage to ``{inputTokens, outputTokens, totalTokens}``.

    Accepts camelCase or snake_case. Missing total is the sum of input and output.
    Returns None when no usage fields are present.
    """
    mapping = _as_mapping(usage)
    if not mapping:
        return None

    seen = False

    def take(keys: tuple[str, ...]) -> int:
        nonlocal seen
        for key in keys:
            if key in mapping and mapping[key] is not None:
                seen = True
                return _as_int(mapping[key])
        return 0

    input_tokens = take(_INPUT_TOKEN_KEYS)
    output_tokens = take(_OUTPUT_TOKEN_KEYS)
    total_tokens: int | None = None
    for key in _TOTAL_TOKEN_KEYS:
        if key in mapping and mapping[key] is not None:
            seen = True
            total_tokens = _as_int(mapping[key])
            break
    if not seen:
        return None
    if total_tokens is None:
        total_tokens = input_tokens + output_tokens
    return {
        "inputTokens": input_tokens,
        "outputTokens": output_tokens,
        "totalTokens": total_tokens,
    }


def _as_mapping(usage: Any) -> dict | None:
    if usage is None:
        return None
    if isinstance(usage, dict):
        return usage
    if hasattr(usage, "model_dump"):
        dumped = usage.model_dump()
        if isinstance(dumped, dict):
            return dumped
    input_tokens = getattr(usage, "input_tokens", None)
    if input_tokens is None:
        input_tokens = getattr(usage, "inputTokens", None)
    output_tokens = getattr(usage, "output_tokens", None)
    if output_tokens is None:
        output_tokens = getattr(usage, "outputTokens", None)
    total_tokens = getattr(usage, "total_tokens", None)
    if total_tokens is None:
        total_tokens = getattr(usage, "totalTokens", None)
    if input_tokens is None and output_tokens is None and total_tokens is None:
        return None
    return {
        "input_tokens": input_tokens,
        "output_tokens": output_tokens,
        "total_tokens": total_tokens,
    }


def _as_int(value: Any) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return 0


def _render_market(state: dict) -> str | None:
    if "marketDataContext" not in state or state["marketDataContext"] is None:
        return None
    body = json.dumps(state["marketDataContext"], ensure_ascii=False, default=str)
    return f"## marketDataContext\n{body}"


def _render_latest_tools(state: dict) -> str | None:
    latest = _select_latest_tool_results(state)
    if latest is None:
        return None
    if isinstance(latest, str):
        body = latest
    else:
        body = json.dumps(latest, ensure_ascii=False, default=str)
    return f"## latestToolResults\n{body}"


def _select_latest_tool_results(state: dict) -> Any:
    if "latestToolResults" in state and state["latestToolResults"] is not None:
        return state["latestToolResults"]
    tool_results = state.get("toolResults")
    if isinstance(tool_results, list) and tool_results:
        return tool_results[-1]
    return None


def _render_memory(state: dict) -> str | None:
    if memory_hit_count(state) <= 0:
        return None
    memory = state.get("memoryContext")
    if not isinstance(memory, dict):
        return None
    snippets = memory.get("snippets")
    if not isinstance(snippets, list) or not snippets:
        return None
    lines = ["## memory"]
    for snippet in snippets:
        if isinstance(snippet, dict):
            text = snippet.get("text")
            if text is None:
                continue
            lines.append(str(text))
        elif isinstance(snippet, str) and snippet:
            lines.append(snippet)
    if len(lines) == 1:
        return None
    return "\n".join(lines)


def _upstream_nodes(state: dict) -> list[tuple[str, dict]]:
    nodes: list[tuple[str, dict]] = []
    for key, value in state.items():
        if key in _SKIP_NODE_KEYS:
            continue
        if isinstance(value, dict) and "summary" in value:
            nodes.append((key, value))
    return nodes


def _summary_text(node: dict) -> str:
    summary = node.get("summary", "")
    if isinstance(summary, str):
        return summary
    return json.dumps(summary, ensure_ascii=False, default=str)


def _collect_evidence_ids(node: dict) -> list[str]:
    found: list[str] = []
    seen: set[str] = set()

    def add(value: Any) -> None:
        if value is None or value is False:
            return
        text = str(value).strip()
        if not text or text in seen:
            return
        seen.add(text)
        found.append(text)

    sources = node.get("sources")
    if isinstance(sources, list):
        for source in sources:
            if not isinstance(source, dict):
                continue
            for field in _SOURCE_ID_FIELDS:
                if field in source:
                    add(source.get(field))

    data = node.get("data")
    claims = data.get("claims") if isinstance(data, dict) else None
    if isinstance(claims, list):
        for claim in claims:
            if not isinstance(claim, dict):
                continue
            for field in _CLAIM_ID_FIELDS:
                if field in claim:
                    add(claim.get(field))
    return found


def _render_full_node(key: str, node: dict) -> str:
    summary = _summary_text(node)
    signals = json.dumps(node.get("signals", []), ensure_ascii=False, default=str)
    evidence = json.dumps(_collect_evidence_ids(node), ensure_ascii=False)
    # Whole node payload so large extra fields count toward the budget.
    payload = json.dumps(node, ensure_ascii=False, default=str)
    return (
        f"## node:{key}\n"
        f"summary: {summary}\n"
        f"signals: {signals}\n"
        f"evidence: {evidence}\n"
        f"{payload}"
    )


def _render_compressed_node(key: str, node: dict) -> str:
    short = _summary_text(node)[:80]
    return f"## node:{key}\n{short}\n[compressed]"
