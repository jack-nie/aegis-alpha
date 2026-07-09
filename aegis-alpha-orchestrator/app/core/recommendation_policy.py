"""Policy for research recommendation drafts (evidence + insufficient-data gates)."""

from __future__ import annotations

from typing import Any


ALLOWED_LABELS = frozenset({"BUY", "HOLD", "SELL", "WATCH", "INSUFFICIENT_DATA"})
ACTIONABLE_LABELS = frozenset({"BUY", "SELL"})


def _as_dict(value: Any) -> dict[str, Any]:
    return value if isinstance(value, dict) else {}


def _as_list(value: Any) -> list[Any]:
    return value if isinstance(value, list) else []


def extract_market_facts(state: dict[str, Any]) -> dict[str, Any]:
    """Pull quote/financial facts from hydrated marketDataContext if present."""
    ctx = state.get("marketDataContext") or {}
    if not isinstance(ctx, dict):
        return {}
    quote = ctx.get("quote") if isinstance(ctx.get("quote"), dict) else ctx
    financials = ctx.get("financials") if isinstance(ctx.get("financials"), dict) else {}
    return {
        "has_quote": bool(
            quote
            and (
                quote.get("price") is not None
                or quote.get("last") is not None
                or quote.get("ok") is True
                or quote.get("symbol")
            )
        ),
        "has_financials": bool(
            financials
            and (
                financials.get("revenue") is not None
                or financials.get("netIncome") is not None
                or financials.get("metrics")
                or financials.get("ok") is True
            )
        ),
        "quote_as_of": quote.get("asOf") or quote.get("as_of") or quote.get("timestamp"),
        "provider": quote.get("provider") or financials.get("provider"),
        "degraded_source": bool(quote.get("degraded") or financials.get("degraded")),
    }


def build_claims_from_state(state: dict[str, Any], evidence_ids: list[str] | None = None) -> list[dict[str, Any]]:
    """Create claim stubs from market context for traceability."""
    ctx = state.get("marketDataContext") or {}
    if not isinstance(ctx, dict):
        return []
    claims: list[dict[str, Any]] = []
    evidence_id = (evidence_ids or ["market-context"])[0]
    quote = ctx.get("quote") if isinstance(ctx.get("quote"), dict) else ctx
    if isinstance(quote, dict):
        price = quote.get("price", quote.get("last"))
        if price is not None:
            claims.append(
                {
                    "claimId": "c_last_price",
                    "field": "last_price",
                    "value": price,
                    "unit": quote.get("currency") or "",
                    "asOf": quote.get("asOf") or quote.get("as_of"),
                    "evidenceId": evidence_id,
                }
            )
    financials = ctx.get("financials") if isinstance(ctx.get("financials"), dict) else {}
    if isinstance(financials, dict):
        for field in ("revenue", "netIncome", "eps"):
            if financials.get(field) is not None:
                claims.append(
                    {
                        "claimId": f"c_{field}",
                        "field": field,
                        "value": financials.get(field),
                        "unit": "",
                        "asOf": financials.get("asOf") or financials.get("period"),
                        "evidenceId": evidence_id,
                    }
                )
    return claims


def enforce_recommendation_policy(
    *,
    label: str | None,
    confidence: float | None,
    state: dict[str, Any] | None = None,
    claims: list[dict[str, Any]] | None = None,
    missing_data: list[Any] | None = None,
    degraded: bool = False,
    sources: list[Any] | None = None,
) -> dict[str, Any]:
    """
    Normalize recommendation draft fields per research DoD.

    - BUY/SELL require quote + financial facts (or claims covering them) and sources/claims.
    - Unbound actionable labels become INSUFFICIENT_DATA.
    - confidence is capped when evidence is weak.
    """
    state = state or {}
    missing = [str(m) for m in _as_list(missing_data)]
    claims_list = [c for c in _as_list(claims) if isinstance(c, dict)]
    sources_list = _as_list(sources)
    facts = extract_market_facts(state)

    if not claims_list:
        claims_list = build_claims_from_state(state)

    raw_label = (label or "").strip().upper().replace(" ", "_")
    if raw_label in ("STRONG_BUY", "STRONGBUY"):
        raw_label = "BUY"
    if raw_label not in ALLOWED_LABELS:
        # Heuristic from free text
        upper = (label or "").upper()
        if "INSUFFICIENT" in upper or "数据不足" in (label or ""):
            raw_label = "INSUFFICIENT_DATA"
        elif "BUY" in upper or "买入" in (label or ""):
            raw_label = "BUY"
        elif "SELL" in upper or "卖出" in (label or ""):
            raw_label = "SELL"
        elif "HOLD" in upper or "持有" in (label or ""):
            raw_label = "HOLD"
        elif "WATCH" in upper:
            raw_label = "WATCH"
        else:
            raw_label = "INSUFFICIENT_DATA"

    has_price_claim = any(c.get("field") in ("last_price", "price") and c.get("evidenceId") for c in claims_list)
    has_fin_claim = any(
        c.get("field") in ("revenue", "netIncome", "eps", "financials") and c.get("evidenceId")
        for c in claims_list
    )
    has_quote = facts["has_quote"] or has_price_claim
    has_financials = facts["has_financials"] or has_fin_claim
    has_evidence = bool(sources_list) or bool(claims_list)

    if not has_quote:
        missing.append("missing_quote")
    if not has_financials:
        missing.append("missing_financials")
    if not has_evidence:
        missing.append("missing_evidence")
    if facts.get("degraded_source") or degraded:
        missing.append("degraded_source")

    # Dedupe missing while preserving order
    seen: set[str] = set()
    missing_unique: list[str] = []
    for item in missing:
        if item not in seen:
            seen.add(item)
            missing_unique.append(item)

    policy_degraded = degraded or facts.get("degraded_source") or bool(missing_unique)
    forced_insufficient = False

    if raw_label in ACTIONABLE_LABELS:
        if not has_quote or not has_financials or not has_evidence:
            raw_label = "INSUFFICIENT_DATA"
            forced_insufficient = True
            policy_degraded = True

    # Unbound numeric claims (no evidenceId) are stripped
    clean_claims: list[dict[str, Any]] = []
    stripped = 0
    for claim in claims_list:
        if claim.get("evidenceId"):
            clean_claims.append(claim)
        else:
            stripped += 1
    if stripped and raw_label in ACTIONABLE_LABELS:
        raw_label = "INSUFFICIENT_DATA"
        forced_insufficient = True
        policy_degraded = True
        missing_unique.append("claims_without_evidence")

    conf = float(confidence) if confidence is not None else 0.0
    if conf < 0:
        conf = 0.0
    if conf > 1:
        conf = 1.0
    if not has_evidence or raw_label == "INSUFFICIENT_DATA":
        conf = min(conf, 0.3)
    if policy_degraded:
        conf = min(conf, 0.5)

    return {
        "recommendation": raw_label,
        "confidence": conf,
        "claims": clean_claims,
        "missingData": missing_unique,
        "degraded": policy_degraded,
        "forcedInsufficient": forced_insufficient,
        "hasQuote": has_quote,
        "hasFinancials": has_financials,
        "hasEvidence": has_evidence,
    }
