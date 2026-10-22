"""Bounded help loop: retrieve → grade → rewrite/re-retrieve (≤3) → draft or escalate.

Designed for mock-friendly unit tests without live LLM or vector DB.
Knowledge chunks are supplied by the caller (e.g. from Java KnowledgeService proxy
or local fixture docs). The loop never invents runId/portfolio facts.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Any

MAX_RETRIEVAL_ROUNDS = 3
DEFAULT_DISCLAIMER = (
    "根据现有资料无法确定完整答案。建议打开运行中心查看节点详情，或联系 Desk Lead / 平台支持。"
)


@dataclass
class Grade:
    ok: bool
    coverage: float = 0.0
    density: float = 0.0
    reason: str = ""

    def to_dict(self) -> dict[str, Any]:
        return {
            "ok": self.ok,
            "coverage": self.coverage,
            "density": self.density,
            "reason": self.reason,
        }


@dataclass
class HelpLoopResult:
    ok: bool
    content: str
    escalate: bool = False
    disclaimer: str | None = None
    retrieval_rounds: int = 0
    grade: Grade | None = None
    chunks: list[dict[str, Any]] = field(default_factory=list)
    applied_filter: dict[str, Any] = field(default_factory=dict)
    provider: str = "help-loop"

    def to_response(self) -> dict[str, Any]:
        source_refs = []
        for c in self.chunks:
            source_refs.append(
                {
                    "type": "chunk",
                    "id": str(c.get("id") or ""),
                    "title": str(c.get("title") or c.get("id") or ""),
                    "path": c.get("path"),
                }
            )
        return {
            "ok": self.ok,
            "content": self.content,
            "message": self.content,
            "escalate": self.escalate,
            "escalateHuman": self.escalate,
            "disclaimer": self.disclaimer,
            "provider": self.provider,
            "sourceRefs": source_refs,
            "helpLoop": {
                "retrievalRounds": self.retrieval_rounds,
                "maxRounds": MAX_RETRIEVAL_ROUNDS,
                "grade": self.grade.to_dict() if self.grade else None,
                "chunkCount": len(self.chunks),
                "appliedFilter": self.applied_filter,
                "preFiltered": True,
                "escalate": self.escalate,
            },
        }


def tokenize(text: str) -> list[str]:
    lower = (text or "").lower()
    tokens: list[str] = []
    tokens.extend(re.findall(r"[a-z0-9_]{2,}", lower))
    # CJK bigrams
    cjk = re.findall(r"[\u4e00-\u9fff]{2,}", lower)
    for span in cjk:
        if len(span) == 2:
            tokens.append(span)
        else:
            for i in range(len(span) - 1):
                tokens.append(span[i : i + 2])
    return tokens


def grade_chunks(message: str, chunks: list[dict[str, Any]]) -> Grade:
    if not chunks:
        return Grade(ok=False, reason="no_chunks")
    tokens = tokenize(message or "")
    if not tokens:
        return Grade(ok=True, coverage=1.0, density=1.0, reason="empty_query_tokens")
    blob_parts: list[str] = []
    for c in chunks:
        blob_parts.append(str(c.get("excerpt") or ""))
        blob_parts.append(str(c.get("text") or ""))
        blob_parts.append(str(c.get("title") or ""))
        blob_parts.append(str(c.get("body") or ""))
    blob = " ".join(blob_parts).lower()
    hits = sum(1 for t in tokens if t in blob)
    coverage = hits / max(len(tokens), 1)
    density = min(len(blob) / 200.0, 1.0)
    # High coverage can pass with shorter excerpts; density required when coverage is marginal.
    ok = coverage >= 0.5 or (coverage >= 0.35 and density >= 0.15)
    if ok:
        return Grade(ok=True, coverage=coverage, density=density, reason="pass")
    reason = "low_coverage" if coverage < 0.35 else "low_density"
    return Grade(ok=False, coverage=coverage, density=density, reason=reason)


def rewrite_query(original: str, previous: str, round_index: int) -> str:
    base = (original or "").strip()
    if round_index == 1:
        return f"{base} 操作 步骤 说明"
    if round_index == 2:
        return f"{base} workflow 运行 中心 审批"
    return previous or base


def _acl_prefilter(
    corpus: list[dict[str, Any]], roles: list[str] | None
) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    """Pre-filter by visibility/roles BEFORE ranking (not post-only)."""
    principal_roles = list(roles or [])
    applied = {
        "visibilityIn": ["public"],
        "orRolesIntersectPrincipal": True,
        "principalRoles": principal_roles,
        "preFiltered": True,
    }
    candidates: list[dict[str, Any]] = []
    for doc in corpus:
        vis = str(doc.get("visibility") or "public").strip().lower()
        if vis == "public":
            candidates.append(doc)
            continue
        if vis in ("internal", "role"):
            allowed = doc.get("allowedRoles") or doc.get("allowed_roles") or []
            if isinstance(allowed, str):
                allowed = [a.strip() for a in allowed.split(",") if a.strip()]
            if any(r in principal_roles for r in allowed):
                candidates.append(doc)
    return candidates, applied


def _score(doc: dict[str, Any], query_lower: str) -> int:
    score = 0
    title = str(doc.get("title") or "").lower()
    body = str(doc.get("body") or doc.get("text") or doc.get("excerpt") or "").lower()
    keywords = doc.get("keywords") or []
    if isinstance(keywords, str):
        keywords = [k.strip() for k in keywords.split(",") if k.strip()]
    for kw in keywords:
        if kw and kw.lower() in query_lower:
            score += 5 + min(len(kw), 20)
    for tok in tokenize(query_lower):
        if tok in title:
            score += 3
        if tok in body:
            score += 1
    return score


def retrieve(
    query: str,
    corpus: list[dict[str, Any]],
    roles: list[str] | None,
    limit: int = 5,
) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    candidates, applied = _acl_prefilter(corpus, roles)
    q = (query or "").lower()
    scored: list[tuple[int, dict[str, Any]]] = []
    for doc in candidates:
        s = _score(doc, q)
        if s > 0:
            scored.append((s, doc))
    scored.sort(key=lambda x: x[0], reverse=True)
    hits: list[dict[str, Any]] = []
    for s, doc in scored[: max(1, limit)]:
        hit = dict(doc)
        hit["score"] = s
        if "excerpt" not in hit:
            text = str(hit.get("body") or hit.get("text") or "")
            hit["excerpt"] = text[:400] + ("…" if len(text) > 400 else "")
        hits.append(hit)
    return hits, applied


def run_help_loop(
    message: str,
    corpus: list[dict[str, Any]],
    roles: list[str] | None = None,
    max_rounds: int = MAX_RETRIEVAL_ROUNDS,
) -> HelpLoopResult:
    """Execute retrieve-grade-rewrite loop with hard round cap."""
    cap = min(max(1, max_rounds), MAX_RETRIEVAL_ROUNDS)
    query = (message or "").strip()
    merged: list[dict[str, Any]] = []
    seen: set[str] = set()
    applied: dict[str, Any] = {}
    current = query
    last_grade = Grade(ok=False, reason="no_rounds")

    for round_i in range(1, cap + 1):
        hits, applied = retrieve(current, corpus, roles, limit=5)
        for h in hits:
            hid = str(h.get("id") or id(h))
            if hid not in seen:
                seen.add(hid)
                merged.append(h)
        last_grade = grade_chunks(query, merged)
        if last_grade.ok:
            return _draft_success(query, merged, last_grade, round_i, applied)
        if round_i < cap:
            current = rewrite_query(query, current, round_i)

    return HelpLoopResult(
        ok=False,
        content="根据现有公开资料无法给出可靠回答。\n\n" + DEFAULT_DISCLAIMER,
        escalate=True,
        disclaimer=DEFAULT_DISCLAIMER,
        retrieval_rounds=cap,
        grade=last_grade,
        chunks=merged,
        applied_filter=applied,
        provider="help-loop",
    )


def _draft_success(
    query: str,
    chunks: list[dict[str, Any]],
    grade: Grade,
    rounds: int,
    applied: dict[str, Any],
) -> HelpLoopResult:
    lines = ["## 知识库回答", "", f"根据公开产品文档整理如下（共检索 {rounds} 轮）：", ""]
    for i, c in enumerate(chunks[:3], start=1):
        lines.append(f"### {i}. {c.get('title') or c.get('id')}")
        lines.append("")
        lines.append(str(c.get("excerpt") or c.get("text") or c.get("body") or ""))
        lines.append("")
    lines.append("_来源均经 ACL 预过滤后的公开/授权文档。私有 run/组合数据不会出现在知识索引中。_")
    return HelpLoopResult(
        ok=True,
        content="\n".join(lines),
        escalate=False,
        disclaimer=None,
        retrieval_rounds=rounds,
        grade=grade,
        chunks=chunks,
        applied_filter=applied,
        provider="help-loop",
    )


# Test fixture only. Production help answers use Java-provided chunks (see routers/help.py);
# this list is NOT served by /help/execute.
DEFAULT_CORPUS: list[dict[str, Any]] = [
    {
        "id": "workflow-ops",
        "title": "工作流运行与发布操作说明",
        "visibility": "public",
        "keywords": ["工作流", "workflow", "发布", "published", "运行工作流", "如何运行", "操作步骤"],
        "body": (
            "仅 published 工作流可被对话路由。在 Copilot 用自然语言触发。"
            "系统创建 workflow_run 返回 runId；运行中心查看节点输出。"
        ),
    },
    {
        "id": "run-center-guide",
        "title": "运行中心使用指南",
        "visibility": "public",
        "keywords": ["运行中心", "节点输出", "run 详情", "查看进度"],
        "body": "路径 /runs 与 /runs/{runId}。可查看 QUEUED RUNNING COMPLETED FAILED DEGRADED 与节点错误。",
    },
    {
        "id": "approval-governance",
        "title": "推荐审批与治理边界",
        "visibility": "public",
        "keywords": ["审批", "治理", "PENDING_REVIEW", "证据", "evidence", "如何审批"],
        "body": "推荐进入 PENDING_REVIEW 后人工审批。助手不能代为审批或驳回。证据不足应重跑或拒绝。",
    },
    {
        "id": "internal-ops",
        "title": "内部运维笔记",
        "visibility": "internal",
        "allowedRoles": ["desk", "admin"],
        "keywords": ["运维", "内部", "orchestrator"],
        "body": "仅 desk/admin 可见的内部运维说明。",
    },
]
