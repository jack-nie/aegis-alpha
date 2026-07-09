"""Workflow node executor."""

from __future__ import annotations

import asyncio
import json
import logging
from datetime import datetime, timezone
from typing import Any, AsyncIterator

from ..config import Settings
from ..models.workflow import Node, AgentTemplate
from ..models.responses import NodeResult
from ..prompts import PromptManager
from .critique import critique_recommendation_draft
from .llm_client import AgentToolResult, LLMClient, LLMResponse
from .market_data import MarketDataService
from .recommendation_policy import enforce_recommendation_policy

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
                    node_label=node_label,
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
        return self._fallback_result(node, handler, subject, state, started_at, str(last_error))

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
        node_label: str | None = None,
    ) -> NodeResult:
        """Invoke LLM for node execution."""
        data = node.data
        if not node_label:
            node_label = data.label or data.title or node.id

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
                f"You are {agent_name}. Generate an evidence-based investment research report. "
                "Use markdown formatting with clear sections. "
                "Use only numbers present in marketDataContext / tool outputs. "
                "If critical market or financial data is missing, recommend INSUFFICIENT_DATA "
                "and list missing fields. Never invent prices, multiples, or target prices."
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

        sources = [
            s if isinstance(s, dict) else {"title": str(s), "url": "", "type": "llm"}
            for s in response.sources
        ]
        # Prefer market hydration as a structured source when present
        market_ctx = state.get("marketDataContext")
        if isinstance(market_ctx, dict) and market_ctx:
            sources = [
                {
                    "title": "marketDataContext",
                    "url": "",
                    "type": "market",
                    "sourceType": "market",
                    "summary": "Hydrated quote/financials/news context",
                },
                *sources,
            ]

        result_data = dict(response.data or {})
        summary = response.summary
        confidence = response.confidence
        degraded = False
        content = response.content

        if is_aggregate:
            draft_label = None
            if isinstance(result_data, dict):
                draft_label = result_data.get("recommendation") or result_data.get("label")
            if not draft_label and content:
                draft_label = self._extract_recommendation_label(content)
            if not draft_label:
                draft_label = summary
            policy = enforce_recommendation_policy(
                label=str(draft_label) if draft_label is not None else None,
                confidence=confidence,
                state=state,
                claims=result_data.get("claims") if isinstance(result_data, dict) else None,
                missing_data=result_data.get("missingData") if isinstance(result_data, dict) else None,
                degraded=bool(result_data.get("degraded")) if isinstance(result_data, dict) else False,
                sources=sources,
            )
            draft_for_critique = {
                "recommendation": str(draft_label) if draft_label is not None else None,
                "confidence": confidence,
                "claims": result_data.get("claims") if isinstance(result_data, dict) else None,
                "missingData": result_data.get("missingData") if isinstance(result_data, dict) else None,
                "degraded": bool(result_data.get("degraded")) if isinstance(result_data, dict) else False,
                "sources": sources,
                "conflicts": result_data.get("conflicts") if isinstance(result_data, dict) else None,
            }
            critique = critique_recommendation_draft(state, draft_for_critique)
            # Policy already maps failing BUY/SELL → INSUFFICIENT_DATA; critique records the gate failure.
            if not critique.get("ok") and policy["recommendation"] in ("BUY", "SELL"):
                # Safety net: never leave actionable labels when critique fails.
                policy = {
                    **policy,
                    "recommendation": "INSUFFICIENT_DATA",
                    "forcedInsufficient": True,
                    "degraded": True,
                }
            result_data = {
                **result_data,
                "recommendation": policy["recommendation"],
                "confidence": policy["confidence"],
                "claims": policy["claims"],
                "missingData": policy["missingData"],
                "degraded": policy["degraded"],
                "policy": {
                    "forcedInsufficient": policy["forcedInsufficient"],
                    "hasQuote": policy["hasQuote"],
                    "hasFinancials": policy["hasFinancials"],
                    "hasEvidence": policy["hasEvidence"],
                },
                "critique": critique,
            }
            confidence = policy["confidence"]
            degraded = policy["degraded"]
            # Optional policy source summarizing critique outcome
            critique_notes = critique.get("notes") or []
            critique_missing = critique.get("missing_data") or []
            sources = [
                *sources,
                {
                    "title": "recommendation_critique",
                    "url": "",
                    "type": "policy",
                    "sourceType": "policy",
                    "summary": (
                        f"ok={critique.get('ok')} "
                        f"missing={len(critique_missing)} "
                        f"conflicts={len(critique.get('conflicts') or [])} "
                        f"notes={','.join(str(n) for n in critique_notes[:5])}"
                    ).strip(),
                },
            ]
            # Surface machine-readable label in summary prefix for Java materializer
            summary = f"[{policy['recommendation']}] {summary or ''}".strip()

        return NodeResult(
            ok=True,
            status="completed",
            handler=handler,
            node_id=node.id,
            node_name=data.label or data.title or node.id,
            subject=subject,
            summary=summary,
            signals=[self._normalize_signal(s) for s in response.signals],
            sources=sources,
            confidence=confidence,
            data=result_data,
            duration_ms=duration_ms,
            content=content,
            degraded=degraded,
            provider=self._config.provider,
            model=model or self._config.model,
        )

    @staticmethod
    def _extract_recommendation_label(text: str) -> str | None:
        """Best-effort parse of recommendation enum from model text/JSON fence."""
        if not text:
            return None
        import re  # local import keeps module import surface small

        # JSON fence recommendation field
        match = re.search(
            r'"recommendation"\s*:\s*"(BUY|HOLD|SELL|WATCH|INSUFFICIENT_DATA)"',
            text,
            re.IGNORECASE,
        )
        if match:
            return match.group(1).upper()
        upper = text.upper()
        for label in ("INSUFFICIENT_DATA", "STRONG BUY", "BUY", "SELL", "HOLD", "WATCH"):
            if label in upper:
                return label.replace(" ", "_") if label != "STRONG BUY" else "BUY"
        return None

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

    async def invoke_agent_with_tools(
        self,
        node: Node,
        state: dict[str, Any],
        subject: str = "",
        tools: list | None = None,
        prior_messages: list | None = None,
        force_final: bool = False,
        agent: AgentTemplate | None = None,
        model: str | None = None,
    ) -> AgentToolResult:
        """One general.agent turn with bound tools (true tool-calling loop).

        Does not run finance.* hydrate paths beyond optional market context for
        general.agent. Returns AgentToolResult with either tool_calls or a final
        LLMResponse suitable for NodeResult conversion.
        """
        started_at = datetime.now(timezone.utc).isoformat()
        handler = self._resolve_handler(node)
        data = node.data
        node_label = data.label or data.title or node.id

        effective_provider = self._config.provider
        if effective_provider not in ("openai", "deepseek"):
            # Unsupported provider: synthesize a final degraded text result.
            mock = self._unsupported_provider_result(node, handler, subject, started_at)
            return AgentToolResult(
                None,
                has_tool_calls=False,
                llm_response=LLMResponse(
                    summary=mock.summary,
                    confidence=mock.confidence,
                    data=mock.data,
                    content=mock.summary,
                ),
            )

        effective_api_key = self._config.effective_api_key
        is_mock = self._config.is_mock_mode and not effective_api_key
        if is_mock:
            mock = self._mock_result(node, handler, subject, state, started_at)
            return AgentToolResult(
                None,
                has_tool_calls=False,
                llm_response=LLMResponse(
                    summary=mock.summary,
                    signals=[],
                    sources=[],
                    confidence=mock.confidence,
                    data=mock.data or {},
                    content=mock.summary,
                ),
            )

        agent_name = (
            (agent.name if agent else None)
            or data.label
            or data.title
            or "Aegis Alpha Agent"
        )
        prompt = (
            data.prompt
            or (agent.prompt if agent else None)
            or PromptManager.get_prompt(handler)
        ).format(
            subject=subject,
            agent_name=agent_name,
        )

        max_context = 12000
        full_context = json.dumps(state, ensure_ascii=False, default=str)
        if len(full_context) <= max_context:
            context = full_context
        else:
            truncated = full_context[:max_context]
            last_brace = truncated.rfind("}")
            context = truncated[: last_brace + 1] if last_brace > 0 else truncated

        system = (
            f"You are {agent_name}. "
            "You may call tools when you need live market, financial, news, or portfolio data. "
            "When you have enough evidence, return only JSON (no markdown) with shape "
            '{"summary":"string","signals":[...],"sources":[...],"confidence":0.5,"data":{}}.'
        )
        if force_final:
            system += (
                " You have reached the tool-call limit. Do not call tools. "
                "Answer now using the tool results already in the conversation."
            )

        logger.info(
            f"[THINKING] Agent tool-loop turn: id={node.id} label={node_label} "
            f"force_final={force_final} tools={len(tools or [])} "
            f"prior_messages={len(prior_messages or [])}"
        )

        return await self._llm_client.invoke_agent_with_tools(
            system=system,
            prompt=prompt,
            context=context,
            tools=tools if not force_final else None,
            prior_messages=prior_messages,
            model=model,
            temperature=0.2,
            timeout_ms=self._config.timeout_ms,
            force_final=force_final,
        )

    def agent_tool_result_to_node_result(
        self,
        node: Node,
        subject: str,
        agent_result: AgentToolResult,
        started_at: str,
        model: str | None = None,
    ) -> NodeResult:
        """Convert a final (no tool_calls) AgentToolResult into NodeResult."""
        handler = self._resolve_handler(node)
        data = node.data
        response = agent_result.llm_response or LLMResponse(
            summary="No response from agent",
            confidence=0.5,
        )
        duration_ms = int(
            (datetime.now(timezone.utc) - datetime.fromisoformat(started_at)).total_seconds()
            * 1000
        )
        sources = [
            s if isinstance(s, dict) else {"title": str(s), "url": "", "type": "llm"}
            for s in response.sources
        ]
        return NodeResult(
            ok=True,
            status="completed",
            handler=handler,
            node_id=node.id,
            node_name=data.label or data.title or node.id,
            subject=subject,
            summary=response.summary,
            signals=[self._normalize_signal(s) for s in response.signals],
            sources=sources,
            confidence=response.confidence,
            data=dict(response.data or {}),
            duration_ms=duration_ms,
            content=response.content,
            degraded=False,
            provider=self._config.provider,
            model=model or self._config.model,
        )

    async def invoke_stream(
        self,
        node: Node,
        state: dict[str, Any],
        subject: str = "",
        agent: AgentTemplate | None = None,
        api_key: str | None = None,
        base_url: str | None = None,
        provider: str | None = None,
        model: str | None = None,
    ) -> AsyncIterator[str]:
        """Stream LLM response token by token for aggregate nodes."""
        effective_api_key = api_key or self._config.effective_api_key
        effective_provider = provider or self._config.provider
        if effective_provider not in ("openai", "deepseek"):
            yield self._mock_result(node, self._resolve_handler(node), subject, state, datetime.now(timezone.utc).isoformat()).content or ""
            return

        data = node.data
        handler = self._resolve_handler(node)
        is_aggregate = handler == "finance.stock_recommendation_aggregate"
        if not is_aggregate:
            result = await self.execute(node, state, subject, agent, api_key, base_url, provider, model)
            yield result.content or result.summary or ""
            return

        agent_name = (
            (agent.name if agent else None)
            or data.label
            or data.title
            or "Aegis Alpha Agent"
        )
        prompt = (
            data.prompt
            or (agent.prompt if agent else None)
            or PromptManager.get_prompt(handler)
        ).format(
            subject=subject,
            agent_name=agent_name,
        )

        max_context = 30000
        full_context = json.dumps(state, ensure_ascii=False, default=str)
        if len(full_context) <= max_context:
            context = full_context
        else:
            truncated = full_context[:max_context]
            last_brace = truncated.rfind('}')
            context = truncated[:last_brace + 1] if last_brace > 0 else truncated

        system = (
            f"You are {agent_name}. Generate a comprehensive investment research report. "
            "Use markdown formatting with clear sections. "
            "Include actual data values from the context. "
            "Do NOT say 'data insufficient' or 'unable to analyze'."
        )

        effective_model = model or self._config.model
        effective_base = base_url or self._config.effective_base_url
        effective_key = api_key or self._config.effective_api_key
        timeout_ms = max(self._config.timeout_ms, 60000)

        async for token in self._llm_client.invoke_stream(
            system=system, prompt=prompt, context=context,
            model=effective_model, temperature=0.2, timeout_ms=timeout_ms,
        ):
            yield token

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
