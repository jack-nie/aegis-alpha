"""Intent classification service."""

from __future__ import annotations

import logging
import re
from typing import Any

from ..models.responses import IntentResult
from ..utils.symbol_normalize import try_normalize_symbol
from .llm_client import LLMClient

logger = logging.getLogger(__name__)

# Used when the model calls a function without a usable confidence argument.
DEFAULT_LLM_CONFIDENCE = 0.5

# P2-3: identical copy of aegis-alpha-api/src/main/resources/routing/routing-rules.json
# (tests/test_routing_rules_sync.py fails if they drift). Orchestrator and API deploy separately,
# so the data is mirrored rather than read across services at runtime.
TICKER_STOPWORDS: frozenset[str] = frozenset({
    "AI", "API", "CPI", "ETF", "GDP", "IPO", "LLM", "SEC", "USA", "USD", "CEO", "CFO", "ESG",
})
REGEX_RULES: tuple[tuple[str, str, bool], ...] = (
    ("earnings_reaction", r"财报|业绩|季报|earnings reaction|earnings report", True),
    ("watchlist_digest", r"早报|自选|watchlist|morning digest|digest", True),
    ("stock_analysis", r"分析.{0,20}(?:股票|个股)|(?:股票|个股).{0,10}分析|分析一下.{0,20}", True),
    ("sector-analyst-workflow", r"分析.{0,20}(?:板块|行业)|(?:板块|行业).{0,10}分析", False),
)
_COMPILED_REGEX_RULES = tuple((key, re.compile(pattern), ticker) for key, pattern, ticker in REGEX_RULES)
# P2-7: function the model calls for requests unrelated to research / the product.
OUT_OF_SCOPE_FUNCTION = "out_of_scope"
OUT_OF_SCOPE_DOMAIN = "OUT_OF_SCOPE"
_TICKER_RE = re.compile(r"\b([A-Z]{1,5}(?:\.[A-Z]{2})?)\b")


def extract_ticker(message: str) -> str | None:
    """First ticker-shaped token that is not a routing stopword (AI, ETF, ...)."""
    for match in _TICKER_RE.finditer(message or ""):
        if match.group(1).upper() not in TICKER_STOPWORDS:
            return match.group(1)
    return None


class IntentClassifier:
    """Classifies user intent to match workflow."""

    def __init__(self, llm_client: LLMClient):
        self._llm_client = llm_client

    @staticmethod
    def _attach_normalized_symbol(ticker: str | None) -> dict[str, Any]:
        """If ticker looks normalizable, attach structured symbol metadata."""
        if not ticker or not str(ticker).strip():
            return {}
        normalized = try_normalize_symbol(str(ticker).strip())
        if normalized.get("ok"):
            return {
                "normalizedSymbol": normalized.get("symbol"),
                "market": normalized.get("market"),
                "symbolNormalizeOk": True,
            }
        return {
            "symbolNormalizeOk": False,
            "symbolNormalizeError": normalized.get("error"),
            "rawSymbol": ticker,
        }

    async def classify(
        self, message: str, workflows: list[dict[str, Any]]
    ) -> IntentResult:
        """Classify user intent using LLM function calling."""
        if not message or not workflows:
            return IntentResult(
                workflow_key=None,
                ticker=None,
                confidence=0,
                reason="Missing message or workflows",
            )

        # Build tools for function calling (and map fn name -> original workflowKey)
        tools, function_name_map = self._build_tools(workflows)

        try:
            result = await self._llm_client.classify_intent(message, tools)
            if result:
                if result.get("argsError"):
                    logger.warning("Intent classifier arguments unparsable; using keyword fallback")
                    return self._keyword_fallback(message, workflows)
                function_name = result.get("function", "")
                ticker = result.get("ticker", "") or None
                if ticker is not None and not str(ticker).strip():
                    ticker = None

                if function_name == OUT_OF_SCOPE_FUNCTION and OUT_OF_SCOPE_FUNCTION not in function_name_map:
                    return IntentResult(
                        workflow_key=None,
                        ticker=None,
                        confidence=self._model_confidence(result.get("confidence")),
                        source="llm_out_of_scope",
                        domain=OUT_OF_SCOPE_DOMAIN,
                        data={"domain": OUT_OF_SCOPE_DOMAIN},
                    )

                # Look up original key; never reverse-munge underscores/hyphens
                workflow_key = function_name_map.get(function_name)
                if workflow_key is None:
                    logger.warning("Intent classifier called an unknown function; using keyword fallback")
                    return self._keyword_fallback(message, workflows)

                return IntentResult(
                    workflow_key=workflow_key,
                    ticker=ticker,
                    confidence=self._model_confidence(result.get("confidence")),
                    source="llm_function_calling",
                    data=self._attach_normalized_symbol(ticker),
                )

            # No function call - try keyword fallback
            return self._keyword_fallback(message, workflows)

        except Exception as e:
            logger.warning("Intent classification failed: %s", type(e).__name__)
            return self._keyword_fallback(message, workflows)

    @staticmethod
    def _model_confidence(value: Any) -> float:
        """Model-reported confidence clamped to [0, 1]; missing/invalid -> 0.5 (uncalibrated)."""
        if value is None or isinstance(value, bool):
            return DEFAULT_LLM_CONFIDENCE
        try:
            number = float(value)
        except (TypeError, ValueError):
            return DEFAULT_LLM_CONFIDENCE
        if number != number:  # NaN
            return DEFAULT_LLM_CONFIDENCE
        return max(0.0, min(1.0, number))

    def _build_tools(
        self, workflows: list[dict[str, Any]]
    ) -> tuple[list[dict[str, Any]], dict[str, str]]:
        """Build OpenAI function calling tools and function_name -> workflowKey map."""
        tools: list[dict[str, Any]] = []
        function_name_map: dict[str, str] = {}
        for wf in workflows:
            key = wf.get("workflowKey", "unknown")
            fn_name = "run_" + str(key).replace("-", "_")
            if fn_name in function_name_map:
                existing = function_name_map[fn_name]
                if existing != key:
                    logger.warning(
                        "Function name collision for %s: keeping %s, ignoring %s",
                        fn_name,
                        existing,
                        key,
                    )
            else:
                function_name_map[fn_name] = key
            tools.append(
                {
                    "type": "function",
                    "function": {
                        "name": fn_name,
                        "description": wf.get("routingDescription") or wf.get("name", "Execute workflow"),
                        "parameters": {
                            "type": "object",
                            "properties": {
                                "ticker": {
                                    "type": "string",
                                    "description": (
                                        "Stock ticker or symbol mentioned by the user "
                                        "(e.g. AAPL, 600519.SH). Empty string if not applicable."
                                    ),
                                },
                                "confidence": {
                                    "type": "number",
                                    "minimum": 0,
                                    "maximum": 1,
                                    "description": (
                                        "How confident you are (0-1) that the user wants to RUN "
                                        "this workflow now."
                                    ),
                                },
                            },
                            "required": ["ticker", "confidence"],
                        },
                    },
                }
            )
        tools.append(
            {
                "type": "function",
                "function": {
                    "name": OUT_OF_SCOPE_FUNCTION,
                    "description": (
                        "Call when the request is unrelated to investment research, workflows or this "
                        "product (e.g. poems, jokes, weather, recipes). Do not call for market questions."
                    ),
                    "parameters": {
                        "type": "object",
                        "properties": {
                            "confidence": {"type": "number", "minimum": 0, "maximum": 1},
                        },
                        "required": ["confidence"],
                    },
                },
            }
        )
        return tools, function_name_map

    def _keyword_fallback(
        self, message: str, workflows: list[dict[str, Any]]
    ) -> IntentResult:
        """Fallback: match workflow by keywords."""
        message_lower = message.lower()

        ticker = extract_ticker(message)

        # Shared regex rules first (same order and data as the Java RoutingRules)
        available = {wf.get("workflowKey") for wf in workflows}
        for key, pattern, wants_ticker in _COMPILED_REGEX_RULES:
            if key in available and pattern.search(message_lower):
                rule_ticker = ticker if wants_ticker else None
                return IntentResult(
                    workflow_key=key,
                    ticker=rule_ticker,
                    confidence=0.7,
                    source="keyword_regex",
                    data=self._attach_normalized_symbol(rule_ticker),
                )

        # Score workflows by keyword match
        best_score = 0
        best_wf = None

        for wf in workflows:
            keywords = wf.get("triggerKeywords", "") or ""
            description = wf.get("routingDescription", "") or ""
            name = wf.get("name", "") or ""

            # Build searchable text
            search_text = f"{keywords} {description} {name}".lower()

            # Count keyword matches
            score = 0
            for word in message_lower.split():
                if len(word) > 2 and word in search_text:
                    score += 1

            if score > best_score:
                best_score = score
                best_wf = wf

        symbol_data = self._attach_normalized_symbol(ticker)

        if best_wf and best_score > 0:
            return IntentResult(
                workflow_key=best_wf.get("workflowKey"),
                ticker=ticker,
                confidence=min(0.5 + best_score * 0.1, 0.8),
                source="keyword_fallback",
                data=symbol_data,
            )

        return IntentResult(
            workflow_key=None,
            ticker=ticker,
            confidence=0,
            source="no_match",
            data=symbol_data,
        )
