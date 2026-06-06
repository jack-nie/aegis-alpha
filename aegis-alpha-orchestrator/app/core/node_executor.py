"""Workflow node executor."""

from __future__ import annotations

import asyncio
import json
import logging
from datetime import datetime, timezone
from typing import Any

from ..config import Settings
from ..models.workflow import Node, AgentTemplate
from ..models.responses import NodeResult
from ..prompts import PromptManager
from .llm_client import LLMClient
from .market_data import MarketDataService

logger = logging.getLogger(__name__)

# Business handlers
HANDLERS = {
    "general.agent",
    "finance.market_analysis",
    "finance.industry_share",
    "finance.sentiment_monitor",
    "finance.tech_breakthrough",
    "finance.industry_news",
    "general.web_search",
    "finance.financial_interpretation",
    "finance.stock_recommendation_aggregate",
    "finance.fundamental_analysis",
    "finance.technical_analysis",
    "finance.valuation_analysis",
    "finance.money_flow_analysis",
    "finance.risk_assessment",
    "finance.peer_comparison",
    "finance.catalyst_analysis",
    "finance.thesis_builder",
    "finance.risk_reward_analysis",
    "finance.entry_strategy",
}

# Control flow handlers
FALLBACK_HANDLERS = {"start", "end", "condition", "logic"}


class NodeExecutor:
    """Executes individual workflow nodes."""

    def __init__(self, config: Settings, llm_client: LLMClient, market_data: MarketDataService):
        self._config = config
        self._llm_client = llm_client
        self._market_data = market_data

    async def execute(
        self,
        node: Node,
        state: dict[str, Any],
        subject: str = "",
        agent: AgentTemplate | None = None,
        api_key: str | None = None,
        base_url: str | None = None,
        provider: str | None = None,
        model: str | None = None,
    ) -> NodeResult:
        """Execute a single workflow node."""
        started_at = datetime.now(timezone.utc).isoformat()
        handler = self._resolve_handler(node)
        node_type = self._resolve_node_type(node)
        node_label = node.data.label or node.data.title or node.id
        logger.info(f"[THINKING] Node started: id={node.id} handler={handler} label={node_label} subject={subject}")

        effective_provider = provider or self._config.provider
        if effective_provider not in ("openai", "deepseek"):
            return self._unsupported_provider_result(node, handler, subject, started_at)

        # Check mock mode or deterministic tool
        effective_api_key = api_key or self._config.effective_api_key
        is_mock = self._config.is_mock_mode and not effective_api_key

        if is_mock or self._is_control_flow(handler, node_type):
            result = self._mock_result(node, handler, subject, state, started_at)
            logger.info(f"[THINKING] Node mock/control: id={node.id} handler={handler} label={node_label} status={result.status}")
            return result

        market_context = await self._market_data.hydrate(handler, state, subject, node.model_dump())
        if market_context:
            logger.info(f"[THINKING] Market data hydrated for node id={node.id} handler={handler} data_keys={list(market_context.keys())}")
            state = {**state, "marketDataContext": market_context}

        # Invoke LLM with retry
        max_retries = 2
        last_error = None
        for attempt in range(max_retries + 1):
            try:
                result = await self._invoke_llm(
                    node=node,
                    handler=handler,
                    state=state,
                    subject=subject,
                    agent=agent,
                    api_key=effective_api_key,
                    base_url=base_url or self._config.effective_base_url,
                    model=model,
                    started_at=started_at,
                )
                logger.info(f"[THINKING] Node invoke succeeded: id={node.id} handler={handler} attempt={attempt + 1}")
                return result
            except Exception as e:
                last_error = e
                if not self._is_retryable(e):
                    logger.warning(f"[THINKING] Node invoke failed (non-retryable): id={node.id} handler={handler} error={e}")
                    break
                if attempt < max_retries:
                    delay = 2 ** attempt
                    logger.warning(f"[THINKING] Node {node.id} LLM call failed (attempt {attempt + 1}/{max_retries + 1}), retrying in {delay}s: {e}")
                    await asyncio.sleep(delay)

        logger.error(f"[THINKING] Node execution failed after all retries: id={node.id} handler={handler} label={node_label} error={last_error}")
        fallback = self._fallback_result(node, handler, subject, state, started_at, str(last_error))

    @staticmethod
    def _normalize_signal(s) -> dict:
        """Normalize signal to dict with 'name' field."""
        if isinstance(s, dict):
            if "name" not in s and "type" in s:
                return {**s, "name": s["type"]}
            return s
        return {"name": str(s), "value": s, "weight": 0.5}

    def _resolve_handler(self, node: Node) -> str:
        """Resolve handler name from node data."""
        data = node.data
        return (data.handler or data.function_name or "logic").strip()

    def _resolve_node_type(self, node: Node) -> str:
        """Resolve node type from node data."""
        data = node.data
        return (data.node_type or data.type or "").strip()

    def _is_control_flow(self, handler: str, node_type: str) -> bool:
        """Check if node is a control flow node (start/end/condition) that needs no LLM."""
        if node_type in {"start", "end", "condition"}:
            return True
        if handler in FALLBACK_HANDLERS and handler not in HANDLERS:
            return True
        return False

    @staticmethod
    def _is_retryable(error: Exception) -> bool:
        error_str = str(error).lower()
        non_retryable = ["401", "403", "authentication", "unauthorized", "forbidden", "invalid api key"]
        if any(term in error_str for term in non_retryable):
            return False
        retryable = ["timeout", "rate limit", "429", "500", "502", "503", "504", "connection", "temporary"]
        return any(term in error_str for term in retryable)

    async def _invoke_llm(
        self,
        node: Node,
        handler: str,
        state: dict[str, Any],
        subject: str,
        agent: AgentTemplate | None,
        api_key: str,
        base_url: str,
        model: str | None,
        started_at: str,
    ) -> NodeResult:
        """Invoke LLM for node execution."""
        data = node.data

        # Build agent name
        agent_name = (
            (agent.name if agent else None)
            or data.label
            or data.title
            or "Aegis Alpha Agent"
        )

        # Get prompt
        prompt = (
            data.prompt
            or (agent.prompt if agent else None)
            or PromptManager.get_prompt(handler)
        ).format(
            subject=subject,
            agent_name=agent_name,
        )

        # Build context
        is_aggregate = handler == "finance.stock_recommendation_aggregate"
        max_context = 30000 if is_aggregate else 12000
        full_context = json.dumps(state, ensure_ascii=False, default=str)
        if len(full_context) <= max_context:
            context = full_context
        else:
            truncated = full_context[:max_context]
            last_brace = truncated.rfind('}')
            context = truncated[:last_brace + 1] if last_brace > 0 else truncated

        # Build system prompt
        if is_aggregate:
            system = (
                f"You are {agent_name}. Generate a comprehensive investment research report. "
                "Use markdown formatting with clear sections. "
                "Include actual data values from the context. "
                "Do NOT say 'data insufficient' or 'unable to analyze'."
            )
        else:
            system = (
                f"You are {agent_name}. "
                "Return only JSON. Do not use markdown. "
                'The JSON shape is {"summary":"string","signals":[...],"sources":[...],"confidence":0.5,"data":{}}.'
            )

        # Invoke LLM
        timeout_ms = max(self._config.timeout_ms, 60000) if is_aggregate else self._config.timeout_ms
        response = await self._llm_client.invoke(
            system=system,
            prompt=prompt,
            context=context,
            model=model,
            temperature=0.2,
            timeout_ms=timeout_ms,
        )

        duration_ms = int((datetime.now(timezone.utc) - datetime.fromisoformat(started_at)).total_seconds() * 1000)
        logger.info(f"[THINKING] Node completed: id={node.id} handler={handler} label={node_label} duration={duration_ms}ms status=completed confidence={response.confidence}")
        logger.info(f"[THINKING] Node output: id={node.id} summary={response.summary[:200] if response.summary else 'N/A'}")

        return NodeResult(
            ok=True,
            status="completed",
            handler=handler,
            node_id=node.id,
            node_name=data.label or data.title or node.id,
            subject=subject,
            summary=response.summary,
            signals=[self._normalize_signal(s) for s in response.signals],
            sources=[s if isinstance(s, dict) else {"title": str(s), "url": "", "type": "llm"} for s in response.sources],
            confidence=response.confidence,
            data=response.data,
            duration_ms=duration_ms,
            content=response.content,
            provider=self._config.provider,
            model=model or self._config.model,
        )

    def _mock_result(
        self,
        node: Node,
        handler: str,
        subject: str,
        state: dict[str, Any],
        started_at: str,
    ) -> NodeResult:
        """Generate mock result for deterministic tool or mock mode."""
        data = node.data
        duration_ms = int((datetime.now(timezone.utc) - datetime.fromisoformat(started_at)).total_seconds() * 1000)

        return NodeResult(
            ok=True,
            status="tool-mock",
            handler=handler,
            node_id=node.id,
            node_name=data.label or data.title or node.id,
            subject=subject,
            summary=f"Mock {handler} result for {subject}",
            signals=[{"name": "handler", "value": handler, "weight": 0.5}],
            sources=[{"title": "Mock data", "url": "", "type": "mock"}],
            confidence=0.66,
            data={"mode": "deterministic_tool", "reason": "Mock mode"},
            duration_ms=duration_ms,
            degraded=True,
            provider="mock",
            model="mock",
        )

    def _fallback_result(
        self,
        node: Node,
        handler: str,
        subject: str,
        state: dict[str, Any],
        started_at: str,
        error: str,
    ) -> NodeResult:
        """Generate fallback result when LLM fails."""
        data = node.data
        is_aggregate = handler == "finance.stock_recommendation_aggregate"
        duration_ms = int((datetime.now(timezone.utc) - datetime.fromisoformat(started_at)).total_seconds() * 1000)

        if is_aggregate:
            return NodeResult(
                ok=False,
                status="model_failed",
                handler=handler,
                node_id=node.id,
                node_name=data.label or data.title or node.id,
                subject=subject,
                summary=f"LLM call failed for {handler}: {error}",
                signals=[],
                sources=[],
                confidence=0.0,
                data={"error": error, "fallback_policy": "fail_closed"},
                duration_ms=duration_ms,
                degraded=True,
                provider=self._config.provider,
                model=self._config.model,
            )

        return NodeResult(
            ok=True,
            status="degraded",
            handler=handler,
            node_id=node.id,
            node_name=data.label or data.title or node.id,
            subject=subject,
            summary=f"Degraded {handler} result for {subject}",
            signals=[{"name": "handler", "value": handler, "weight": 0.5}],
            sources=[{"title": "Fallback", "url": "", "type": "local"}],
            confidence=0.58,
            data={"error": error, "fallback_policy": "deterministic_degraded"},
            duration_ms=duration_ms,
            degraded=True,
            provider=self._config.provider,
            model=self._config.model,
        )

    def _unsupported_provider_result(
        self,
        node: Node,
        handler: str,
        subject: str,
        started_at: str,
    ) -> NodeResult:
        """Generate result for unsupported provider."""
        data = node.data
        duration_ms = int((datetime.now(timezone.utc) - datetime.fromisoformat(started_at)).total_seconds() * 1000)

        return NodeResult(
            ok=False,
            status="unsupported_provider",
            handler=handler,
            node_id=node.id,
            node_name=data.label or data.title or node.id,
            subject=subject,
            summary=f"Provider not supported: {self._config.provider}",
            signals=[],
            sources=[],
            confidence=0.0,
            data={"reason": "Only openai and deepseek providers are supported"},
            duration_ms=duration_ms,
            degraded=True,
            provider=self._config.provider,
            model=self._config.model,
        )
