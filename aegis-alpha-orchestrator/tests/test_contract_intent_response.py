"""Contract tests: intent response aliases and recommendation policy gates."""

from __future__ import annotations

import json

from app.core.recommendation_policy import enforce_recommendation_policy
from app.models.responses import IntentResult


def test_intent_result_model_dump_uses_workflow_key_alias():
    result = IntentResult(
        workflow_key="stock_analysis",
        ticker="AAPL",
        confidence=0.9,
        source="llm",
    )
    data = result.model_dump()
    assert "workflowKey" in data
    assert data["workflowKey"] == "stock_analysis"
    assert "workflow_key" not in data


def test_intent_result_json_uses_workflow_key_alias():
    result = IntentResult(
        workflow_key="deep_dive",
        ticker="MSFT",
        confidence=0.75,
        source="heuristic",
    )
    payload = json.loads(result.model_dump_json())
    assert payload["workflowKey"] == "deep_dive"
    assert "workflow_key" not in payload


def test_intent_result_accepts_camel_and_snake_on_validate():
    from_camel = IntentResult.model_validate({"workflowKey": "portfolio_review", "confidence": 0.5})
    from_snake = IntentResult.model_validate({"workflow_key": "portfolio_review", "confidence": 0.5})
    assert from_camel.workflow_key == "portfolio_review"
    assert from_snake.workflow_key == "portfolio_review"


def test_policy_buy_without_evidence_is_insufficient_data():
    result = enforce_recommendation_policy(
        label="BUY",
        confidence=0.9,
        state={},
        claims=[],
        sources=[],
    )
    assert result["recommendation"] == "INSUFFICIENT_DATA"
    assert result["degraded"] is True
    assert result["forcedInsufficient"] is True
    assert result["confidence"] <= 0.3
