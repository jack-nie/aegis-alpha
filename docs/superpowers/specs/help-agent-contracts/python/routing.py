"""Design contracts: adaptive routing (workflow-first).

Pure functions for unit tests when ported to production.
P0 Java port should mirror these rules in IntentRouterService / HelpRouter.
"""

from __future__ import annotations

import re
from typing import Any, Iterable

from intent_models import (
    Complexity,
    ExtendedIntentResult,
    IntentDomain,
    IntentEntities,
    PageContext,
    RouteAction,
)

# --- keyword tables (P0; DB-driven keywords remain for RESEARCH workflows) ---

RESEARCH_HINTS = (
    "日报",
    "晨报",
    "深度分析",
    "深度研究",
    "个股分析",
    "分析一下",
    "分析股票",
    "止损",
    "止盈",
    "投资组合",
    "持仓",
    "板块",
    "行业分析",
    "财报",
    "业绩",
    "自选股",
    "早报",
    "回测",
    "daily",
    "deep dive",
    "stock analysis",
    "portfolio",
    "earnings",
    "watchlist",
)

HELP_HINTS = (
    "怎么",
    "如何",
    "怎样",
    "在哪",
    "是什么",
    "什么是",
    "如何打开",
    "如何使用",
    "帮助",
    "教程",
    "操作步骤",
    "如何审批",
    "如何登录",
    "copilot",
    "工作流怎么",
    "如何运行",
    "degraded 是什么",
    "什么是 degraded",
)

DIAGNOSIS_HINTS = (
    "失败",
    "报错",
    "为什么失败",
    "为什么 degraded",
    "degraded",
    "超时",
    "卡住",
    "节点失败",
    "run 失败",
    "执行失败",
    "failed",
    "error",
    "diagnose",
)

PRIVATE_ENTITY_PATTERNS = (
    re.compile(r"\brun[_-]?id\b", re.I),
    re.compile(r"\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b", re.I),
    re.compile(r"\bportfolio\b", re.I),
    re.compile(r"持仓|组合明细|我的仓位"),
)

RUN_ID_PATTERN = re.compile(
    r"\b([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\b",
    re.I,
)
TICKER_PATTERN = re.compile(r"\b([A-Z]{1,5}(?:\.[A-Z]{2})?)\b")
TICKER_STOPWORDS = frozenset(
    {"AI", "API", "CPI", "ETF", "GDP", "IPO", "LLM", "SEC", "USA", "USD", "CEO", "CFO", "ESG"}
)


def _contains_any(text: str, hints: Iterable[str]) -> bool:
    lower = text.lower()
    return any(h.lower() in lower for h in hints)


def extract_entities(message: str, page: PageContext | None = None) -> IntentEntities:
    page = page or PageContext()
    run_id = page.run_id
    m = RUN_ID_PATTERN.search(message or "")
    if m:
        run_id = m.group(1)

    ticker = page.ticker
    for match in TICKER_PATTERN.finditer(message or ""):
        cand = match.group(1)
        if cand.upper() not in TICKER_STOPWORDS:
            ticker = cand
            break

    return IntentEntities(
        run_id=run_id,
        ticker=ticker,
        path=page.path,
        portfolio_id=page.portfolio_id,
        recommendation_id=page.recommendation_id,
    )


def is_cacheable(message: str, entities: IntentEntities) -> bool:
    if entities.run_id or entities.portfolio_id or entities.recommendation_id:
        return False
    text = message or ""
    for pat in PRIVATE_ENTITY_PATTERNS:
        if pat.search(text):
            return False
    return True


def estimate_complexity(message: str, domain: IntentDomain) -> Complexity:
    if domain == IntentDomain.RUN_DIAGNOSIS:
        return Complexity.L2
    text = message or ""
    # Multi-constraint / cross-object → L2
    if ("并且" in text or "同时" in text or "如果" in text) and (
        "还" in text or "是否" in text or "会不会" in text
    ):
        return Complexity.L2
    if len(text) > 80 and _contains_any(text, HELP_HINTS):
        return Complexity.L1
    if _contains_any(text, ("是什么", "在哪", "如何打开", "什么是")):
        return Complexity.L0
    if _contains_any(text, HELP_HINTS):
        return Complexity.L0
    return Complexity.L1


def route(
    message: str,
    *,
    page: PageContext | None = None,
    research_workflow_key: str | None = None,
    research_confidence: float = 0.0,
    playbook_id: str | None = None,
) -> ExtendedIntentResult:
    """Adaptive router: RESEARCH wins over HELP when workflow matched."""

    entities = extract_entities(message, page)
    cacheable = is_cacheable(message, entities)
    text = message or ""

    # 1) Research / published workflow (caller supplies LLM|keyword match)
    if research_workflow_key and research_confidence >= 0.6:
        return ExtendedIntentResult(
            workflow_key=research_workflow_key,
            ticker=entities.ticker,
            confidence=research_confidence,
            source="research_priority",
            domain=IntentDomain.RESEARCH,
            complexity=Complexity.L2,
            route_action=RouteAction.DISPATCH_WORKFLOW,
            entities=entities,
            cacheable=False,
            reason="Published workflow match takes priority",
        )

    # Soft research hints without workflow → still prefer copilot research path
    if _contains_any(text, RESEARCH_HINTS) and not _contains_any(text, HELP_HINTS):
        return ExtendedIntentResult(
            ticker=entities.ticker,
            confidence=0.4,
            source="research_hint",
            domain=IntentDomain.RESEARCH,
            complexity=Complexity.L1,
            route_action=RouteAction.COPILOT,
            entities=entities,
            cacheable=False,
            reason="Research-flavored chitchat → copilot",
        )

    # 2) Run diagnosis
    if entities.run_id or _contains_any(text, DIAGNOSIS_HINTS):
        if not entities.run_id:
            return ExtendedIntentResult(
                confidence=0.8,
                source="diagnosis_clarify",
                domain=IntentDomain.RUN_DIAGNOSIS,
                complexity=Complexity.L2,
                route_action=RouteAction.CLARIFY,
                needs_clarification=True,
                clarification_prompt="请提供运行 ID（runId），或从「运行中心」打开对应 run 后再问我。",
                entities=entities,
                cacheable=False,
                reason="Diagnosis without runId",
            )
        return ExtendedIntentResult(
            confidence=0.85,
            source="diagnosis",
            domain=IntentDomain.RUN_DIAGNOSIS,
            complexity=Complexity.L2,
            route_action=RouteAction.HELP_GRAPH,
            entities=entities,
            cacheable=False,
            reason="Run diagnosis with runId",
        )

    # 3) Product help
    if _contains_any(text, HELP_HINTS):
        complexity = estimate_complexity(text, IntentDomain.PRODUCT_HELP)
        if complexity == Complexity.L0 and playbook_id:
            return ExtendedIntentResult(
                confidence=0.9,
                source="playbook",
                domain=IntentDomain.PRODUCT_HELP,
                complexity=Complexity.L0,
                route_action=RouteAction.PLAYBOOK,
                entities=entities,
                cacheable=cacheable,
                reason=f"Playbook hit: {playbook_id}",
                data={"playbookId": playbook_id},
            )
        if complexity == Complexity.L0:
            # No playbook → still PLAYBOOK engine may fuzzy-miss; fallback COPILOT later
            return ExtendedIntentResult(
                confidence=0.7,
                source="help_l0",
                domain=IntentDomain.PRODUCT_HELP,
                complexity=Complexity.L0,
                route_action=RouteAction.PLAYBOOK,
                entities=entities,
                cacheable=cacheable,
                reason="Product help L0",
            )
        if complexity == Complexity.L1:
            return ExtendedIntentResult(
                confidence=0.75,
                source="help_l1",
                domain=IntentDomain.PRODUCT_HELP,
                complexity=Complexity.L1,
                route_action=RouteAction.KNOWLEDGE_RAG,
                entities=entities,
                cacheable=cacheable,
                reason="Product help L1 RAG",
            )
        return ExtendedIntentResult(
            confidence=0.75,
            source="help_l2",
            domain=IntentDomain.PRODUCT_HELP,
            complexity=Complexity.L2,
            route_action=RouteAction.HELP_GRAPH,
            entities=entities,
            cacheable=False,
            reason="Product help L2 agent",
        )

    # 4) Default chitchat → existing research copilot
    return ExtendedIntentResult(
        ticker=entities.ticker,
        confidence=0.2,
        source="default_copilot",
        domain=IntentDomain.CHITCHAT,
        complexity=Complexity.L0,
        route_action=RouteAction.COPILOT,
        entities=entities,
        cacheable=False,
        reason="Fallback copilot",
    )


def match_playbook_id(message: str, catalog: dict[str, list[str]]) -> str | None:
    """catalog: playbook_id -> keywords."""
    lower = (message or "").lower()
    best_id: str | None = None
    best_len = 0
    for pid, keywords in catalog.items():
        for kw in keywords:
            k = kw.strip().lower()
            if k and k in lower and len(k) > best_len:
                best_len = len(k)
                best_id = pid
    return best_id
