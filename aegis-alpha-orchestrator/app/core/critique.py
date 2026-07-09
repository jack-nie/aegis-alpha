"""Critique gate for research recommendation drafts (Phase 0.5 pure rules, no LLM)."""

from __future__ import annotations

from typing import Any

from .recommendation_policy import (
    ACTIONABLE_LABELS,
    ALLOWED_LABELS,
    enforce_recommendation_policy,
)


def _as_dict(value: Any) -> dict[str, Any]:
    return value if isinstance(value, dict) else {}


def _as_list(value: Any) -> list[Any]:
    return value if isinstance(value, list) else []


def _normalize_label(label: Any) -> str:
    raw = (str(label) if label is not None else "").strip().upper().replace(" ", "_")
    if raw in ("STRONG_BUY", "STRONGBUY"):
        return "BUY"
    if raw in ALLOWED_LABELS:
        return raw
    upper = (str(label) if label is not None else "").upper()
    text = str(label) if label is not None else ""
    if "INSUFFICIENT" in upper or "数据不足" in text:
        return "INSUFFICIENT_DATA"
    if "BUY" in upper or "买入" in text:
        return "BUY"
    if "SELL" in upper or "卖出" in text:
        return "SELL"
    if "HOLD" in upper or "持有" in text:
        return "HOLD"
    if "WATCH" in upper:
        return "WATCH"
    return raw or "INSUFFICIENT_DATA"


def _collect_evidence_ids(claims: list[dict[str, Any]], sources: list[Any]) -> list[str]:
    ids: list[str] = []
    seen: set[str] = set()
    for claim in claims:
        eid = claim.get("evidenceId") or claim.get("evidence_id")
        if eid is None:
            continue
        text = str(eid).strip()
        if text and text not in seen:
            seen.add(text)
            ids.append(text)
    for source in sources:
        if not isinstance(source, dict):
            continue
        eid = source.get("evidenceId") or source.get("id") or source.get("sourceId")
        if eid is None:
            continue
        text = str(eid).strip()
        if text and text not in seen:
            seen.add(text)
            ids.append(text)
    return ids


def critique_recommendation_draft(state: dict, draft: dict) -> dict:
    """
    Pure critique of a recommendation draft against evidence gates.

    ok is False only when the draft proposes actionable BUY/SELL that fails policy gates.
    """
    state_dict = _as_dict(state)
    draft_dict = _as_dict(draft)

    requested_label = _normalize_label(
        draft_dict.get("recommendation") or draft_dict.get("label")
    )
    confidence = draft_dict.get("confidence")
    claims = draft_dict.get("claims")
    missing_in = draft_dict.get("missingData")
    if missing_in is None:
        missing_in = draft_dict.get("missing_data")
    sources = draft_dict.get("sources")
    degraded = bool(draft_dict.get("degraded"))

    policy = enforce_recommendation_policy(
        label=requested_label,
        confidence=float(confidence) if confidence is not None else None,
        state=state_dict,
        claims=claims if isinstance(claims, list) else None,
        missing_data=missing_in if isinstance(missing_in, list) else None,
        degraded=degraded,
        sources=sources if isinstance(sources, list) else None,
    )

    missing_data = list(policy.get("missingData") or [])
    evidence_ids = _collect_evidence_ids(
        [c for c in _as_list(policy.get("claims")) if isinstance(c, dict)],
        _as_list(sources),
    )

    conflicts: list[str] = []
    for item in _as_list(draft_dict.get("conflicts")):
        text = str(item).strip()
        if text and text not in conflicts:
            conflicts.append(text)

    notes: list[str] = []
    final_label = policy.get("recommendation") or "INSUFFICIENT_DATA"

    if requested_label in ACTIONABLE_LABELS and (
        policy.get("forcedInsufficient") or final_label != requested_label
    ):
        conflicts.append(f"label_gate:{requested_label}->{final_label}")
        notes.append("actionable_label_failed_evidence_gates")

    if policy.get("degraded"):
        notes.append("policy_degraded")
    if not policy.get("hasQuote"):
        notes.append("missing_quote")
    if not policy.get("hasFinancials"):
        notes.append("missing_financials")
    if not policy.get("hasEvidence"):
        notes.append("missing_evidence")

    # Dedupe notes
    seen_notes: set[str] = set()
    notes_unique: list[str] = []
    for note in notes:
        if note not in seen_notes:
            seen_notes.add(note)
            notes_unique.append(note)

    actionable_failed = requested_label in ACTIONABLE_LABELS and (
        bool(policy.get("forcedInsufficient")) or final_label == "INSUFFICIENT_DATA"
    )
    ok = not actionable_failed

    return {
        "ok": ok,
        "missing_data": missing_data,
        "conflicts": conflicts,
        "evidence_ids": evidence_ids,
        "notes": notes_unique,
    }
