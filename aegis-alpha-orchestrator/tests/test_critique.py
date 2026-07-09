"""Unit tests for recommendation draft critique (Phase 0.5 pure rules)."""

from app.core.critique import critique_recommendation_draft
from app.core.recommendation_policy import build_claims_from_state


def test_critique_buy_without_evidence_fails():
    result = critique_recommendation_draft(
        state={},
        draft={
            "recommendation": "BUY",
            "confidence": 0.9,
            "claims": [],
            "sources": [],
        },
    )
    assert result["ok"] is False
    assert "missing_quote" in result["missing_data"] or any(
        "missing" in m for m in result["missing_data"]
    )
    assert result["notes"]
    assert any("actionable" in n for n in result["notes"])


def test_critique_buy_with_full_evidence_passes():
    state = {
        "marketDataContext": {
            "quote": {"ok": True, "price": 100.0, "asOf": "2026-07-01", "provider": "test"},
            "financials": {"ok": True, "revenue": 1e9, "netIncome": 1e8},
        }
    }
    claims = build_claims_from_state(state)
    result = critique_recommendation_draft(
        state=state,
        draft={
            "recommendation": "BUY",
            "confidence": 0.8,
            "claims": claims,
            "sources": [{"title": "market", "type": "market", "id": "market-context"}],
        },
    )
    assert result["ok"] is True
    assert result["evidence_ids"]
    assert "market-context" in result["evidence_ids"] or any(result["evidence_ids"])


def test_critique_sell_missing_financials_fails():
    state = {
        "marketDataContext": {
            "quote": {"price": 50.0, "asOf": "2026-07-01"},
        }
    }
    result = critique_recommendation_draft(
        state=state,
        draft={
            "recommendation": "SELL",
            "confidence": 0.7,
            "sources": [{"title": "quote"}],
        },
    )
    assert result["ok"] is False
    assert "missing_financials" in result["missing_data"]
    assert any("label_gate" in c for c in result["conflicts"])


def test_critique_hold_partial_data_ok():
    """Non-actionable labels do not fail the critique gate on partial data."""
    result = critique_recommendation_draft(
        state={"marketDataContext": {"quote": {"price": 12}}},
        draft={
            "recommendation": "HOLD",
            "confidence": 0.6,
            "sources": [{"title": "quote"}],
        },
    )
    assert result["ok"] is True
    assert "missing_financials" in result["missing_data"]


def test_critique_insufficient_explicit_ok():
    result = critique_recommendation_draft(
        state={},
        draft={
            "recommendation": "INSUFFICIENT_DATA",
            "confidence": 0.2,
            "sources": [],
        },
    )
    assert result["ok"] is True
    assert isinstance(result["missing_data"], list)
    assert isinstance(result["conflicts"], list)
    assert isinstance(result["evidence_ids"], list)
    assert isinstance(result["notes"], list)
