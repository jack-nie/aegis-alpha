"""Workflow node executor."""

from __future__ import annotations

import asyncio
import hashlib
import json
import logging
import random
import re
import time
from datetime import datetime, timezone
from typing import Any

from ..config import Settings
from ..models.workflow import Node, AgentTemplate
from ..models.responses import NodeResult
from ..prompts import PromptManager
from .context_assembly import (
    AGGREGATE_TOKEN_BUDGET,
    DEFAULT_TOKEN_BUDGET,
    assemble_model_context,
)
from .prompt_safety import USER_INPUT_TAG, fence
from .critique import critique_recommendation_draft
from .llm_client import AgentToolResult, LLMClient, LLMResponse
from .market_data import MarketDataService
from .recommendation_policy import enforce_recommendation_policy

logger = logging.getLogger(__name__)


def _assemble_context(state: dict[str, Any], *, aggregate: bool = False) -> str:
    """Budgeted prompt context. Aggregate keeps a larger budget; never a prefix cut."""
    budget = AGGREGATE_TOKEN_BUDGET if aggregate else DEFAULT_TOKEN_BUDGET
    return assemble_model_context(state, token_budget=budget)


AGGREGATE_HANDLER = "finance.stock_recommendation_aggregate"
# P3-16: closer for non-single-ticker workflows (daily / portfolio / exit / sector). Large context
# budget like the aggregate, but no recommendation policy, critique or BUY/SELL label.
DIGEST_HANDLER = "finance.digest_aggregate"


def digest_system_prompt(agent_name: str) -> str:
    return (
        f"You are {agent_name}. Summarize the upstream workflow outputs into a digest. "
        "Use only facts present in the context and state missing data explicitly. "
        "Do not output a BUY/SELL/HOLD rating or target price. "
        "Return only JSON. Do not use markdown. "
        'The JSON shape is {"summary":"string","signals":[...],"sources":[...],"confidence":0.5,"data":{}}.'
    )


def render_prompt(template: str | None, *, subject: str, agent_name: str) -> str:
    """Fill ``{subject}`` / ``{agent_name}`` only.

    ``str.format`` raised ``KeyError('"recommendation"')`` on templates that contain literal JSON
    (the default aggregate prompt ends with a JSON example), so the aggregate node always failed;
    user-authored prompts with braces failed the same way.
    """
    text = template or ""
    # P2-9: the subject is end-user input, so it is substituted as a labelled inline block.
    subject_block = fence(USER_INPUT_TAG, str(subject or ""), inline=True)
    return text.replace("{agent_name}", str(agent_name or "")).replace("{subject}", subject_block)


def aggregate_system_prompt(agent_name: str) -> str:
    """Single output protocol for the aggregate node (streaming and non-streaming).

    Markdown report followed by one fenced ``json`` trailer the policy layer can parse.
    """
    return (
        f"You are {agent_name}. Generate an evidence-based investment research report. "
        "Use markdown formatting with clear sections. "
        "Use only numbers present in marketDataContext / tool outputs. "
        "If critical market or financial data is missing, recommend INSUFFICIENT_DATA "
        "and list missing fields. Never invent prices, multiples, or target prices. "
        "End the report with exactly one fenced ```json block of the shape "
        '{"recommendation":"BUY|HOLD|SELL|WATCH|INSUFFICIENT_DATA","confidence":0.0,'
        '"claims":[{"text":"...","evidenceId":"..."}],"missingData":["..."]} '
        "where every evidenceId is copied from the context."
    )


_JSON_TRAILER = re.compile(r"```json\s*(\{.*?\})\s*```", re.DOTALL | re.IGNORECASE)


def parse_aggregate_trailer(content: str | None) -> dict[str, Any]:
    """Parse the last fenced json trailer of an aggregate report; ``{}`` when absent/invalid."""
    if not content:
        return {}
    matches = _JSON_TRAILER.findall(content)
    for raw in reversed(matches):
        try:
            parsed = json.loads(raw)
        except (TypeError, ValueError):
            continue
        if isinstance(parsed, dict):
            return parsed
    return {}


def register_market_evidence(market_context: dict[str, Any]) -> tuple[dict[str, Any], dict[str, Any]]:
    """Give a hydrated snapshot a server-generated evidence id (content hash, never model text).

    Returns the context carrying ``evidenceId`` and the registry entry for ``final_state.evidence``.
    """
    if not isinstance(market_context, dict) or market_context.get("ok") is False:
        return market_context, {}
    payload = json.dumps(
        {k: v for k, v in market_context.items() if k != "evidenceId"},
        sort_keys=True, ensure_ascii=False, default=str,
    )
    evidence_id = "ev-market-" + hashlib.sha256(payload.encode("utf-8")).hexdigest()[:12]
    quote = market_context.get("quote") if isinstance(market_context.get("quote"), dict) else market_context
    entry = {
        "evidenceId": evidence_id,
        "kind": "market_snapshot",
        "symbol": market_context.get("symbol") or (quote.get("symbol") if isinstance(quote, dict) else None),
        "asOf": (quote.get("asOf") or quote.get("as_of") or quote.get("timestamp")) if isinstance(quote, dict) else None,
    }
    return {**market_context, "evidenceId": evidence_id}, {evidence_id: entry}


def _with_token_usage(result_data: dict[str, Any], response: LLMResponse) -> dict[str, Any]:
    """Attach provider token usage without dropping existing result data keys."""
    data = dict(result_data or {})
    if response.token_usage is not None:
        data["tokenUsage"] = response.token_usage
    return data


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
    "finance.digest_aggregate",
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

_LABEL_ALTERNATION = r"INSUFFICIENT[_ ]DATA|STRONG[_ ]BUY|STRONG[_ ]SELL|BUY|SELL|HOLD|WATCH"
_JSON_RECOMMENDATION_RE = re.compile(
    r'"recommendation"\s*:\s*"(' + _LABEL_ALTERNATION + r')"', re.IGNORECASE
)
_LABELED_RECOMMENDATION_RE = re.compile(
    r"(?:recommendation|rating|建议|评级|结论)\s*[:：]\s*\**\s*(" + _LABEL_ALTERNATION + r")\b",
    re.IGNORECASE,
)
# Bare tokens must be upper-case words: prose like "shareholder", "buyback" or "hold on" never matches.
_BARE_LABEL_RE = re.compile(r"(?<![A-Za-z0-9_])(" + _LABEL_ALTERNATION + r")(?![A-Za-z0-9_])")


_LABEL_PRIORITY = ("INSUFFICIENT_DATA", "BUY", "SELL", "HOLD", "WATCH")


def _normalize_label(raw: str) -> str:
    label = raw.strip().upper().replace(" ", "_")
    if label == "STRONG_BUY":
        return "BUY"
    if label == "STRONG_SELL":
        return "SELL"
    return label

class NodeExecutor:
    """Executes individual workflow nodes."""

    def __init__(self, config: Settings, llm_client: LLMClient, market_data: MarketDataService):
        self._config = config
        self._llm_client = llm_client
        self._market_data = market_data
        # Injectable for tests: retry backoff sleep, jitter source and monotonic clock.
        self._sleep = asyncio.sleep
        self._random = random.random
        self._clock = time.monotonic

    def _retry_budget(self) -> tuple[int, float]:
        attempts = getattr(self._config, "node_retry_max_attempts", 3)
        deadline_ms = getattr(self._config, "node_retry_deadline_ms", 120000)
        try:
            attempts = max(1, int(attempts))
        except (TypeError, ValueError):
            attempts = 3
        try:
            deadline_s = max(0.0, float(deadline_ms) / 1000.0)
        except (TypeError, ValueError):
            deadline_s = 120.0
        return attempts, deadline_s

    def _backoff_delay(self, attempt: int) -> float:
        """Exponential backoff (1s, 2s, ...) with ±50% jitter so parallel nodes do not retry in lockstep."""
        base = float(2 ** attempt)
        return base * (0.5 + self._random())

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
        evidence_entries: dict[str, Any] = {}
        if market_context:
            logger.info(f"[THINKING] Market data hydrated for node id={node.id} handler={handler} data_keys={list(market_context.keys())}")
            market_context, evidence_entries = register_market_evidence(market_context)
            known = dict(state.get("evidence") or {}) if isinstance(state.get("evidence"), dict) else {}
            known.update(evidence_entries)
            state = {**state, "marketDataContext": market_context, "evidence": known}

        # Invoke LLM with retry: bounded attempts, jittered backoff and a total deadline.
        max_attempts, deadline_s = self._retry_budget()
        retry_started = self._clock()
        last_error = None
        for attempt in range(max_attempts):
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
                if evidence_entries:
                    # Engine merges data.evidence into final_state.evidence (server-issued ids).
                    result.data = {**(result.data or {}), "evidence": evidence_entries}
                return result
            except Exception as e:
                last_error = e
                if not self._is_retryable(e):
                    logger.warning(f"[THINKING] Node invoke failed (non-retryable): id={node.id} handler={handler} error={e}")
                    break
                if attempt + 1 < max_attempts:
                    delay = self._backoff_delay(attempt)
                    elapsed = self._clock() - retry_started
                    if elapsed + delay >= deadline_s:
                        logger.warning(
                            f"[THINKING] Node {node.id} retry deadline reached "
                            f"(elapsed={elapsed:.1f}s, deadline={deadline_s:.1f}s); giving up"
                        )
                        break
                    logger.warning(f"[THINKING] Node {node.id} LLM call failed (attempt {attempt + 1}/{max_attempts}), retrying in {delay:.2f}s: {type(e).__name__}")
                    await self._sleep(delay)

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
        prompt = render_prompt(
            data.prompt
            or (agent.prompt if agent else None)
            or PromptManager.get_prompt(handler),
            subject=subject,
            agent_name=agent_name,
        )

        # Build context (budgeted assembly, not a JSON prefix cut).
        is_aggregate = handler == "finance.stock_recommendation_aggregate"
        is_digest = handler == DIGEST_HANDLER
        context = _assemble_context(state, aggregate=is_aggregate or is_digest)

        # Build system prompt
        if is_aggregate:
            system = aggregate_system_prompt(agent_name)
        elif is_digest:
            system = digest_system_prompt(agent_name)
        else:
            system = (
                f"You are {agent_name}. "
                "Return only JSON. Do not use markdown. "
                'The JSON shape is {"summary":"string","signals":[...],"sources":[...],"confidence":0.5,"data":{}}.'
            )

        # Invoke LLM
        timeout_ms = max(self._config.timeout_ms, 60000) if (is_aggregate or is_digest) else self._config.timeout_ms
        response = await self._llm_client.invoke(
            system=system,
            prompt=prompt,
            context=context,
            model=model,
            temperature=0.2,
            timeout_ms=timeout_ms,
            # Explicit protocol: analysts → AnalystResult; aggregate → markdown + json trailer.
            structured=not is_aggregate,
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

        result_data = _with_token_usage(response.data or {}, response)
        if is_digest:
            # A digest never carries an actionable label into stream summaries / Java materialization.
            result_data.pop("recommendation", None)
            result_data["digest"] = True
        if is_aggregate:
            trailer = parse_aggregate_trailer(response.content)
            for key in ("recommendation", "claims", "missingData", "conflicts"):
                if key in trailer and key not in result_data:
                    result_data[key] = trailer[key]
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
            # Fail closed on evidence ids: without a registry nothing the model cites is known.
            policy_state = state if isinstance(state.get("evidence"), (dict, list)) else {**state, "evidence": {}}
            policy = enforce_recommendation_policy(
                label=str(draft_label) if draft_label is not None else None,
                confidence=confidence,
                state=policy_state,
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
            critique = critique_recommendation_draft(policy_state, draft_for_critique)
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
        """Best-effort parse of the recommendation enum from model text (P3-4).

        Order: JSON ``"recommendation"`` field, then an explicit ``Recommendation:/Rating:/建议:/评级:``
        line, then a standalone upper-case token with word boundaries (so ``SHAREHOLDER`` is not HOLD
        and ``BUYBACK`` is not BUY). Structured response fields are preferred by the caller.
        """
        if not text:
            return None
        match = _JSON_RECOMMENDATION_RE.search(text)
        if match:
            return _normalize_label(match.group(1))
        match = _LABELED_RECOMMENDATION_RE.search(text)
        if match:
            return _normalize_label(match.group(1))
        found = {_normalize_label(m.group(1)) for m in _BARE_LABEL_RE.finditer(text)}
        for label in _LABEL_PRIORITY:  # same precedence as the previous substring scan
            if label in found:
                return label
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
        prompt = render_prompt(
            data.prompt
            or (agent.prompt if agent else None)
            or PromptManager.get_prompt(handler),
            subject=subject,
            agent_name=agent_name,
        )

        context = _assemble_context(state, aggregate=False)

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
            data=_with_token_usage(response.data or {}, response),
            duration_ms=duration_ms,
            content=response.content,
            degraded=False,
            provider=self._config.provider,
            model=model or self._config.model,
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
