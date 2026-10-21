"""Design contracts: help_graph state, nodes, and tool policy.

Implementation target: aegis-alpha-orchestrator/app/core/help_graph.py
"""

from __future__ import annotations

from enum import Enum
from typing import Any, Literal, TypedDict

from intent_models import Complexity, IntentDomain, IntentEntities, PageContext, SourceRef

MAX_RETRIEVAL_ROUNDS = 3

HELP_TOOL_ALLOWLIST: frozenset[str] = frozenset(
    {
        "search_product_docs",
        "get_workflow_run",
        "get_workflow_run_nodes",
        "get_workflow_run_events",
        "get_portfolio_positions",
        "get_stock_quote",
    }
)

HELP_TOOL_DENYLIST: frozenset[str] = frozenset(
    {
        "approve_recommendation",
        "reject_recommendation",
        "create_portfolio_trade",
        "execute_sql",
    }
)


class HelpNode(str, Enum):
    NORMALIZE_ENTITIES = "normalize_entities"
    CACHE_LOOKUP = "cache_lookup"
    ROUTE_COMPLEXITY = "route_complexity"
    CLARIFY_GATE = "clarify_gate"
    RETRIEVE = "retrieve"
    GRADE_EVIDENCE = "grade_evidence"
    REWRITE_QUERY = "rewrite_query"
    TOOL_GATHER = "tool_gather"
    DRAFT_ANSWER = "draft_answer"
    SELF_CHECK = "self_check"
    ESCALATE_OR_END = "escalate_or_end"


DEFAULT_DISCLAIMER = (
    "根据现有资料无法确定完整答案。建议打开运行中心查看节点详情，或联系 Desk Lead / 平台支持。"
)


class EvidenceGrade(TypedDict):
    ok: bool
    coverage: float
    density: float
    reasons: list[str]


class HelpState(TypedDict, total=False):
    message: str
    thread_id: str
    user_scope_digest: str  # non-PII hash of principal for logging only
    domain: str  # IntentDomain value
    complexity: str  # Complexity value
    entities: dict[str, Any]
    page_context: dict[str, Any]
    cacheable: bool
    cache_hit: bool
    query: str
    retrieval_rounds: int
    chunks: list[dict[str, Any]]
    tool_results: dict[str, Any]
    needs_clarification: bool
    clarification_prompt: str | None
    draft: str
    source_refs: list[dict[str, Any]]
    escalate_human: bool
    disclaimer: str | None
    ok: bool
    error: str | None
    grade: EvidenceGrade


def initial_help_state(
    message: str,
    thread_id: str,
    *,
    domain: IntentDomain = IntentDomain.PRODUCT_HELP,
    complexity: Complexity = Complexity.L2,
    entities: IntentEntities | None = None,
    page_context: PageContext | None = None,
    cacheable: bool = False,
) -> HelpState:
    ent = entities or IntentEntities()
    page = page_context or PageContext()
    return HelpState(
        message=message,
        thread_id=thread_id,
        domain=domain.value,
        complexity=complexity.value,
        entities=ent.model_dump(by_alias=True),
        page_context=page.model_dump(by_alias=True, exclude_none=True),
        cacheable=cacheable,
        cache_hit=False,
        query=message,
        retrieval_rounds=0,
        chunks=[],
        tool_results={},
        needs_clarification=False,
        clarification_prompt=None,
        draft="",
        source_refs=[],
        escalate_human=False,
        disclaimer=None,
        ok=True,
        error=None,
    )


def grade_chunks(message: str, chunks: list[dict[str, Any]]) -> EvidenceGrade:
    """Deterministic P0 grader (no LLM). Port to production for self-correction."""
    if not chunks:
        return EvidenceGrade(ok=False, coverage=0.0, density=0.0, reasons=["no_chunks"])

    text = (message or "").lower()
    tokens = [t for t in re_split_words(text) if len(t) > 1]
    if not tokens:
        return EvidenceGrade(ok=True, coverage=1.0, density=1.0, reasons=["empty_query_tokens"])

    blob = " ".join(str(c.get("text") or c.get("content") or "") for c in chunks).lower()
    hits = sum(1 for t in tokens if t in blob)
    coverage = hits / max(len(tokens), 1)
    density = min(len(blob) / 200.0, 1.0)
    ok = coverage >= 0.35 and density >= 0.2
    reasons: list[str] = []
    if coverage < 0.35:
        reasons.append("low_coverage")
    if density < 0.2:
        reasons.append("low_density")
    if ok:
        reasons.append("pass")
    return EvidenceGrade(ok=ok, coverage=coverage, density=density, reasons=reasons)


def re_split_words(text: str) -> list[str]:
    import re

    # CJK bigrams + ascii words
    ascii_words = re.findall(r"[a-z0-9_]+", text)
    cjk = re.findall(r"[\u4e00-\u9fff]{2,}", text)
    return ascii_words + cjk


def should_continue_retrieve(state: HelpState) -> Literal["retrieve", "tools", "escalate"]:
    rounds = int(state.get("retrieval_rounds") or 0)
    grade = state.get("grade") or {}
    if grade.get("ok"):
        return "tools"
    if rounds < MAX_RETRIEVAL_ROUNDS:
        return "retrieve"
    return "escalate"


def apply_escalate(state: HelpState) -> HelpState:
    state = dict(state)
    state["escalate_human"] = True
    state["ok"] = False
    state["disclaimer"] = DEFAULT_DISCLAIMER
    state["draft"] = state.get("draft") or DEFAULT_DISCLAIMER
    return HelpState(**state)  # type: ignore[misc]


def filter_tools_for_help(requested: list[str]) -> list[str]:
    out: list[str] = []
    for name in requested:
        if name in HELP_TOOL_DENYLIST:
            continue
        if name in HELP_TOOL_ALLOWLIST:
            out.append(name)
    return out


# --- Node IO contracts (documentation as call signatures) ---


def node_normalize_entities(state: HelpState) -> HelpState:
    """Merge message + page_context into entities; set cacheable flag."""
    raise NotImplementedError("design contract")


def node_cache_lookup(state: HelpState) -> HelpState:
    """If cacheable and L1 hit, set draft + cache_hit and skip graph."""
    raise NotImplementedError("design contract")


def node_clarify_gate(state: HelpState) -> HelpState:
    """If RUN_DIAGNOSIS without runId → needs_clarification."""
    raise NotImplementedError("design contract")


def node_retrieve(state: HelpState) -> HelpState:
    """Call Java search_product_docs; append chunks; increment retrieval_rounds."""
    raise NotImplementedError("design contract")


def node_grade_evidence(state: HelpState) -> HelpState:
    """Write state['grade']; graph edges use should_continue_retrieve."""
    raise NotImplementedError("design contract")


def node_tool_gather(state: HelpState) -> HelpState:
    """Allowlisted tools only; inject delegated token for portfolio reads."""
    raise NotImplementedError("design contract")


def node_draft_answer(state: HelpState) -> HelpState:
    """LLM or template; must attach source_refs."""
    raise NotImplementedError("design contract")


def node_self_check(state: HelpState) -> HelpState:
    """Refuse investment advice without evidence; mirror research critique culture."""
    raise NotImplementedError("design contract")


# Suggested LangGraph edge map (for implementers)
GRAPH_EDGES: list[tuple[str, str | dict[str, str]]] = [
    (HelpNode.NORMALIZE_ENTITIES.value, HelpNode.CACHE_LOOKUP.value),
    (HelpNode.CACHE_LOOKUP.value, HelpNode.ROUTE_COMPLEXITY.value),
    (HelpNode.ROUTE_COMPLEXITY.value, HelpNode.CLARIFY_GATE.value),
    (
        HelpNode.CLARIFY_GATE.value,
        {
            "clarify": "__end__",
            "continue": HelpNode.RETRIEVE.value,
        },
    ),
    (HelpNode.RETRIEVE.value, HelpNode.GRADE_EVIDENCE.value),
    (
        HelpNode.GRADE_EVIDENCE.value,
        {
            "retrieve": HelpNode.REWRITE_QUERY.value,
            "tools": HelpNode.TOOL_GATHER.value,
            "escalate": HelpNode.ESCALATE_OR_END.value,
        },
    ),
    (HelpNode.REWRITE_QUERY.value, HelpNode.RETRIEVE.value),
    (HelpNode.TOOL_GATHER.value, HelpNode.DRAFT_ANSWER.value),
    (HelpNode.DRAFT_ANSWER.value, HelpNode.SELF_CHECK.value),
    (HelpNode.SELF_CHECK.value, HelpNode.ESCALATE_OR_END.value),
    (HelpNode.ESCALATE_OR_END.value, "__end__"),
]
