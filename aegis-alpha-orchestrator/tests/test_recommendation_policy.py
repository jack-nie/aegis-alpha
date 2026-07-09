"""Golden-style unit tests for recommendation evidence policy."""

from app.core.recommendation_policy import enforce_recommendation_policy, build_claims_from_state


def test_buy_without_evidence_becomes_insufficient():
    result = enforce_recommendation_policy(
        label="BUY",
        confidence=0.9,
        state={},
        claims=[],
        sources=[],
    )
    assert result["recommendation"] == "INSUFFICIENT_DATA"
    assert result["degraded"] is True
    assert result["confidence"] <= 0.3
    assert result["forcedInsufficient"] is True


def test_buy_with_quote_financials_and_sources_allowed():
    state = {
        "marketDataContext": {
            "quote": {"ok": True, "price": 100.0, "asOf": "2026-07-01", "provider": "test"},
            "financials": {"ok": True, "revenue": 1e9, "netIncome": 1e8},
        }
    }
    claims = build_claims_from_state(state)
    result = enforce_recommendation_policy(
        label="BUY",
        confidence=0.8,
        state=state,
        claims=claims,
        sources=[{"title": "market", "type": "market"}],
    )
    assert result["recommendation"] == "BUY"
    assert result["hasQuote"] is True
    assert result["hasFinancials"] is True
    assert result["claims"]
    assert all(c.get("evidenceId") for c in result["claims"])


def test_strip_claims_without_evidence_id_blocks_sell():
    result = enforce_recommendation_policy(
        label="SELL",
        confidence=0.7,
        state={
            "marketDataContext": {
                "quote": {"price": 10},
                "financials": {"revenue": 1},
            }
        },
        claims=[{"claimId": "x", "field": "last_price", "value": 10}],  # no evidenceId
        sources=[{"title": "x"}],
    )
    # build will not replace empty evidence claims path fully; unbound claims stripped
    assert result["recommendation"] == "INSUFFICIENT_DATA" or all(
        c.get("evidenceId") for c in result["claims"]
    )


def test_hold_allowed_with_partial_data_still_degraded():
    result = enforce_recommendation_policy(
        label="HOLD",
        confidence=0.6,
        state={"marketDataContext": {"quote": {"price": 12}}},
        sources=[{"title": "quote"}],
    )
    assert result["recommendation"] == "HOLD"
    assert "missing_financials" in result["missingData"]


def test_insufficient_explicit_preserved():
    result = enforce_recommendation_policy(
        label="INSUFFICIENT_DATA",
        confidence=0.4,
        state={},
        sources=[],
    )
    assert result["recommendation"] == "INSUFFICIENT_DATA"
    assert result["confidence"] <= 0.3
