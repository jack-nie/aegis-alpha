"""Golden-10 fixtures: actionable BUY/SELL without evidence must fail critique/policy gates."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import pytest

from app.core.critique import critique_recommendation_draft
from app.core.recommendation_policy import ACTIONABLE_LABELS, enforce_recommendation_policy

FIXTURES_DIR = Path(__file__).resolve().parent / "fixtures" / "golden10"


def _load_fixtures() -> list[dict[str, Any]]:
    paths = sorted(FIXTURES_DIR.glob("*.json"))
    fixtures: list[dict[str, Any]] = []
    for path in paths:
        with path.open(encoding="utf-8") as handle:
            data = json.load(handle)
        if not isinstance(data, dict):
            raise AssertionError(f"Fixture must be an object: {path.name}")
        data.setdefault("id", path.stem)
        fixtures.append(data)
    return fixtures


def _normalize_label(label: Any) -> str:
    raw = (str(label) if label is not None else "").strip().upper().replace(" ", "_")
    if raw in ("STRONG_BUY", "STRONGBUY"):
        return "BUY"
    return raw


def _evaluate(fixture: dict[str, Any]) -> tuple[dict[str, Any], dict[str, Any]]:
    draft = fixture.get("draft") if isinstance(fixture.get("draft"), dict) else {}
    state = fixture.get("state") if isinstance(fixture.get("state"), dict) else {}
    missing_in = draft.get("missingData")
    if missing_in is None:
        missing_in = draft.get("missing_data")

    policy = enforce_recommendation_policy(
        label=draft.get("recommendation") or draft.get("label"),
        confidence=float(draft["confidence"]) if draft.get("confidence") is not None else None,
        state=state,
        claims=draft.get("claims") if isinstance(draft.get("claims"), list) else None,
        missing_data=missing_in if isinstance(missing_in, list) else None,
        degraded=bool(draft.get("degraded")),
        sources=draft.get("sources") if isinstance(draft.get("sources"), list) else None,
    )
    critique = critique_recommendation_draft(state=state, draft=draft)
    return policy, critique


def _evidence_is_empty(draft: dict[str, Any], state: dict[str, Any]) -> bool:
    """True when draft has no claims/sources and state has no usable market facts."""
    claims = draft.get("claims") if isinstance(draft.get("claims"), list) else []
    sources = draft.get("sources") if isinstance(draft.get("sources"), list) else []
    if claims or sources:
        return False
    ctx = state.get("marketDataContext") if isinstance(state.get("marketDataContext"), dict) else {}
    quote = ctx.get("quote") if isinstance(ctx.get("quote"), dict) else ctx
    financials = ctx.get("financials") if isinstance(ctx.get("financials"), dict) else {}
    has_quote_fact = bool(
        isinstance(quote, dict)
        and (
            quote.get("price") is not None
            or quote.get("last") is not None
            or quote.get("ok") is True
            or quote.get("symbol")
        )
    )
    has_fin_fact = bool(
        isinstance(financials, dict)
        and (
            financials.get("revenue") is not None
            or financials.get("netIncome") is not None
            or financials.get("metrics")
            or financials.get("ok") is True
        )
    )
    return not has_quote_fact and not has_fin_fact


FIXTURES = _load_fixtures()
FIXTURE_IDS = [str(f.get("id") or f"fixture_{i}") for i, f in enumerate(FIXTURES)]


def test_golden10_fixture_count():
    # g11 (P1-13: forged evidenceId) extends the original golden-10 set.
    assert len(FIXTURES) == 11, f"Expected 11 golden fixtures, found {len(FIXTURES)} in {FIXTURES_DIR}"


@pytest.mark.parametrize("fixture", FIXTURES, ids=FIXTURE_IDS)
def test_golden10_fixture(fixture: dict[str, Any]):
    expect = fixture.get("expect") if isinstance(fixture.get("expect"), dict) else {}
    policy, critique = _evaluate(fixture)

    if "recommendation" in expect:
        assert policy["recommendation"] == expect["recommendation"], (
            f"{fixture.get('id')}: recommendation {policy['recommendation']!r} != {expect['recommendation']!r}"
        )
    if "ok" in expect:
        assert critique["ok"] is expect["ok"], (
            f"{fixture.get('id')}: critique ok {critique['ok']!r} != {expect['ok']!r}"
        )
    if "degraded" in expect:
        assert policy["degraded"] is expect["degraded"], (
            f"{fixture.get('id')}: degraded {policy['degraded']!r} != {expect['degraded']!r}"
        )
    if "forcedInsufficient" in expect:
        assert policy["forcedInsufficient"] is expect["forcedInsufficient"], (
            f"{fixture.get('id')}: forcedInsufficient mismatch"
        )

    for key in expect.get("missing_data_contains") or []:
        assert key in policy["missingData"] or key in critique["missing_data"], (
            f"{fixture.get('id')}: missing_data should contain {key!r}; "
            f"policy={policy['missingData']}, critique={critique['missing_data']}"
        )

    for key in expect.get("notes_contains") or []:
        assert key in critique["notes"], (
            f"{fixture.get('id')}: notes should contain {key!r}; notes={critique['notes']}"
        )

    for key in expect.get("conflicts_contains") or []:
        assert key in critique["conflicts"], (
            f"{fixture.get('id')}: conflicts should contain {key!r}; conflicts={critique['conflicts']}"
        )

    # Actionable labels that policy forces to INSUFFICIENT_DATA must fail critique.
    requested = _normalize_label(
        (fixture.get("draft") or {}).get("recommendation")
        or (fixture.get("draft") or {}).get("label")
    )
    if requested in ACTIONABLE_LABELS and policy["recommendation"] == "INSUFFICIENT_DATA":
        assert critique["ok"] is False


def test_golden10_no_actionable_empty_evidence_remains_actionable():
    """
    Hard CI gate: no fixture may leave BUY/SELL when evidence is empty.

    Empty evidence = no claims, no sources, and no usable quote/financial facts in state.
    """
    violations: list[str] = []
    for fixture in FIXTURES:
        draft = fixture.get("draft") if isinstance(fixture.get("draft"), dict) else {}
        state = fixture.get("state") if isinstance(fixture.get("state"), dict) else {}
        requested = _normalize_label(draft.get("recommendation") or draft.get("label"))
        if requested not in ACTIONABLE_LABELS:
            continue
        if not _evidence_is_empty(draft, state):
            continue
        policy, critique = _evaluate(fixture)
        final = policy["recommendation"]
        if final in ACTIONABLE_LABELS:
            violations.append(
                f"{fixture.get('id')}: requested {requested} empty-evidence stayed {final}"
            )
        if critique["ok"] is True:
            violations.append(
                f"{fixture.get('id')}: requested {requested} empty-evidence critique ok=True"
            )
        if final != "INSUFFICIENT_DATA":
            violations.append(
                f"{fixture.get('id')}: expected INSUFFICIENT_DATA for empty evidence, got {final}"
            )
    assert not violations, "Actionable empty-evidence gate failed:\n" + "\n".join(violations)


def test_golden10_actionable_without_full_evidence_cannot_pass():
    """
    Stronger gate across all fixtures: BUY/SELL that lack quote+financials+evidence
    must not remain BUY/SELL and must have critique ok=False.
    """
    for fixture in FIXTURES:
        draft = fixture.get("draft") if isinstance(fixture.get("draft"), dict) else {}
        requested = _normalize_label(draft.get("recommendation") or draft.get("label"))
        if requested not in ACTIONABLE_LABELS:
            continue
        policy, critique = _evaluate(fixture)
        claims = [c for c in (policy.get("claims") or []) if isinstance(c, dict)]
        full_evidence = (
            bool(policy.get("hasQuote"))
            and bool(policy.get("hasFinancials"))
            and bool(policy.get("hasEvidence"))
            and bool(claims)
            and all(c.get("evidenceId") for c in claims)
            and not policy.get("forcedInsufficient")
        )
        if full_evidence:
            continue
        assert policy["recommendation"] not in ACTIONABLE_LABELS, (
            f"{fixture.get('id')}: incomplete evidence left actionable {policy['recommendation']}"
        )
        assert critique["ok"] is False, f"{fixture.get('id')}: incomplete evidence critique ok=True"


def test_known_evidence_id_still_allows_buy():
    """A BUY citing the server-issued id with quote + financial facts keeps its label."""
    state = {
        "symbol": "MSFT",
        "evidence": {"ev-market-abc": {"kind": "market_snapshot"}},
        "marketDataContext": {
            "evidenceId": "ev-market-abc",
            "quote": {"ok": True, "price": 100.0},
            "financials": {"ok": True, "revenue": 1, "netIncome": 1},
        },
    }
    claims = [
        {"field": "last_price", "value": 100.0, "evidenceId": "ev-market-abc"},
        {"field": "revenue", "value": 1, "evidenceId": "ev-market-abc"},
    ]
    policy = enforce_recommendation_policy(
        label="BUY", confidence=0.8, state=state, claims=claims, sources=[{"id": "ev-market-abc"}]
    )
    assert policy["recommendation"] == "BUY"
    assert policy["forcedInsufficient"] is False
