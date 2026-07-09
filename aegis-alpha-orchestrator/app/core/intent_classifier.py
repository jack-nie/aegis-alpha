"""Intent classification service."""

from __future__ import annotations

import logging
import re
from typing import Any

from ..models.responses import IntentResult
from .llm_client import LLMClient

logger = logging.getLogger(__name__)


class IntentClassifier:
    """Classifies user intent to match workflow."""

    def __init__(self, llm_client: LLMClient):
        self._llm_client = llm_client

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
                function_name = result.get("function", "")
                ticker = result.get("ticker", "")

                # Look up original key; never reverse-munge underscores/hyphens
                workflow_key = function_name_map.get(function_name)

                # Find matching workflow to get confidence
                matched_wf = next(
                    (wf for wf in workflows if wf.get("workflowKey") == workflow_key),
                    None,
                )

                return IntentResult(
                    workflow_key=workflow_key,
                    ticker=ticker or None,
                    confidence=0.9 if matched_wf else 0.5,
                    source="llm_function_calling",
                )

            # No function call - try keyword fallback
            return self._keyword_fallback(message, workflows)

        except Exception as e:
            logger.error(f"Intent classification failed: {e}")
            return self._keyword_fallback(message, workflows)

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
                            },
                            "required": ["ticker"],
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

        # Extract ticker pattern (1-5 uppercase letters or digits with exchange suffix)
        ticker_match = re.search(
            r'\b([A-Z]{1,5}(?:\.[A-Z]{2})?)\b', message
        )
        ticker = ticker_match.group(1) if ticker_match else None

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

        if best_wf and best_score > 0:
            return IntentResult(
                workflow_key=best_wf.get("workflowKey"),
                ticker=ticker,
                confidence=min(0.5 + best_score * 0.1, 0.8),
                source="keyword_fallback",
            )

        return IntentResult(
            workflow_key=None,
            ticker=ticker,
            confidence=0,
            source="no_match",
        )
