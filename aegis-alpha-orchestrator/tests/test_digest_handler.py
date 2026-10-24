"""P3-16: the digest closer of non-single-ticker workflows skips the recommendation policy."""

from __future__ import annotations

from datetime import datetime, timezone
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from app.core.llm_client import LLMResponse
from app.core.market_data import SKIP_HYDRATION_HANDLERS
from app.core.node_executor import DIGEST_HANDLER, HANDLERS
from app.models.workflow import Node, NodeData
from app.prompts.finance_prompts import PromptManager


def test_digest_handler_is_registered_with_its_own_prompt():
    assert DIGEST_HANDLER == "finance.digest_aggregate"
    assert DIGEST_HANDLER in HANDLERS
    assert PromptManager.has_handler(DIGEST_HANDLER)
    assert "BUY/SELL" in PromptManager.get_prompt(DIGEST_HANDLER)
    assert DIGEST_HANDLER in SKIP_HYDRATION_HANDLERS


async def _invoke(executor, handler: str, response: LLMResponse):
    executor._llm_client.invoke = AsyncMock(return_value=response)
    node = Node(id="daily_summary", data=NodeData(handler=handler, label="Daily Briefing"))
    return await executor._invoke_llm(
        node=node,
        handler=handler,
        state={"nodes": {"market_overview": {"summary": "indices up"}}},
        subject="global market daily",
        agent=None,
        api_key="k",
        base_url="",
        model=None,
        started_at=datetime.now(timezone.utc).isoformat(),
    )


@pytest.mark.asyncio
async def test_daily_digest_node_does_not_trigger_recommendation_policy(mock_node_executor):
    policy = MagicMock(side_effect=AssertionError("policy must not run for digests"))
    critique = MagicMock(side_effect=AssertionError("critique must not run for digests"))
    response = LLMResponse(
        summary="SELL-side flows dominated; markets mixed",
        confidence=0.6,
        data={"recommendation": "SELL", "highlights": ["a"]},
    )
    with patch("app.core.node_executor.enforce_recommendation_policy", policy), \
            patch("app.core.node_executor.critique_recommendation_draft", critique):
        result = await _invoke(mock_node_executor, DIGEST_HANDLER, response)

    policy.assert_not_called()
    critique.assert_not_called()
    assert result.ok is True
    assert "recommendation" not in result.data
    assert result.data["digest"] is True
    assert not result.summary.startswith("[")  # no machine label prefix for the Java materializer
    kwargs = mock_node_executor._llm_client.invoke.call_args.kwargs
    assert kwargs["structured"] is True
    assert "Do not output a BUY/SELL/HOLD rating" in kwargs["system"]


@pytest.mark.asyncio
async def test_stock_aggregate_still_runs_recommendation_policy(mock_node_executor):
    response = LLMResponse(summary="report", content='report\n```json\n{"recommendation":"HOLD"}\n```')
    from app.core import node_executor as module

    with patch.object(module, "enforce_recommendation_policy", wraps=module.enforce_recommendation_policy) as policy:
        result = await _invoke(mock_node_executor, "finance.stock_recommendation_aggregate", response)
    policy.assert_called_once()
    assert result.data["recommendation"] in ("HOLD", "INSUFFICIENT_DATA")
