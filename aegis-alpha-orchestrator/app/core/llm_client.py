"""LLM API client with OpenAI-compatible interface."""

from __future__ import annotations

import json
import logging
import re
from typing import Any, AsyncIterator

from langchain_core.messages import AIMessage, BaseMessage, HumanMessage, SystemMessage
from langchain_openai import ChatOpenAI
from pydantic import BaseModel, Field

from ..config import Settings

logger = logging.getLogger(__name__)

DEFAULT_DEEPSEEK_MODEL = "deepseek-v4-flash"
SUPPORTED_DEEPSEEK_MODELS = {"deepseek-v4-pro", "deepseek-v4-flash"}


class AgentToolResult:
    """Result of a single agent turn that may request tools."""

    def __init__(
        self,
        ai_message: Any,
        *,
        has_tool_calls: bool = False,
        llm_response: "LLMResponse | None" = None,
        seed_human: HumanMessage | None = None,
    ):
        self.ai_message = ai_message
        self.has_tool_calls = has_tool_calls
        self.llm_response = llm_response
        self.seed_human = seed_human

    @property
    def messages_to_append(self) -> list[Any]:
        """New messages to append to WorkflowState.messages (reducer is concat)."""
        out: list[Any] = []
        if self.seed_human is not None:
            out.append(self.seed_human)
        if self.ai_message is not None:
            out.append(self.ai_message)
        return out


class SignalModel(BaseModel):
    name: str = Field(default="", description="Signal name")
    value: str = Field(default="", description="Signal value")
    weight: float = Field(default=0.5, description="Signal weight 0-1")


class SourceModel(BaseModel):
    title: str = Field(default="", description="Source title")
    url: str = Field(default="", description="Source URL")
    type: str = Field(default="llm", description="Source type")


class AnalystResult(BaseModel):
    summary: str = Field(default="", description="Analysis summary")
    signals: list[SignalModel] = Field(default_factory=list, description="Trading signals")
    sources: list[SourceModel] = Field(default_factory=list, description="Data sources")
    confidence: float = Field(default=0.5, ge=0.0, le=1.0, description="Confidence score 0-1")
    data: dict[str, Any] = Field(default_factory=dict, description="Structured analysis data")


class LLMResponse:
    """Normalized LLM response."""

    def __init__(
        self,
        summary: str = "",
        signals: list[dict] | None = None,
        sources: list[dict] | None = None,
        confidence: float = 0.5,
        data: dict | None = None,
        content: str = "",
        raw: Any = None,
    ):
        self.summary = summary
        self.signals = signals or []
        self.sources = sources or []
        self.confidence = confidence
        self.data = data or {}
        self.content = content or summary
        self.raw = raw

    def to_dict(self) -> dict[str, Any]:
        return {
            "summary": self.summary,
            "signals": self.signals,
            "sources": self.sources,
            "confidence": self.confidence,
            "data": self.data,
            "content": self.content,
        }


_LLM_CLIENT_CACHE_MAX = 10


class LLMClient:
    """LLM API client supporting OpenAI-compatible endpoints."""

    def __init__(self, config: Settings):
        self._config = config
        self._client_cache: dict[str, ChatOpenAI] = {}

    def _resolve_model(self, requested: str | None = None) -> str:
        """Resolve model name with DeepSeek compatibility."""
        requested = requested or self._config.model
        base_url = self._config.effective_base_url

        if self._uses_deepseek_endpoint(base_url) and requested not in SUPPORTED_DEEPSEEK_MODELS:
            return DEFAULT_DEEPSEEK_MODEL
        return requested

    def _uses_deepseek_endpoint(self, base_url: str) -> bool:
        """Check if using DeepSeek-compatible endpoint."""
        return "deepseek" in (base_url or "").lower()

    def _build_client(
        self, model: str, temperature: float = 0.2, timeout_ms: int = 25000
    ) -> ChatOpenAI:
        """Build ChatOpenAI client instance with caching."""
        cache_key = f"{model}|{temperature}|{timeout_ms}"
        if cache_key in self._client_cache:
            return self._client_cache[cache_key]

        kwargs: dict[str, Any] = {
            "api_key": self._config.effective_api_key,
            "model": model,
            "temperature": temperature,
            "timeout": timeout_ms / 1000,
            "max_retries": 0,
        }
        base_url = self._config.effective_base_url
        if base_url:
            kwargs["openai_api_base"] = base_url
        client = ChatOpenAI(**kwargs)

        if len(self._client_cache) >= _LLM_CLIENT_CACHE_MAX:
            oldest_key = next(iter(self._client_cache))
            del self._client_cache[oldest_key]
        self._client_cache[cache_key] = client
        return client

    async def invoke(
        self,
        system: str,
        prompt: str,
        context: str = "",
        model: str | None = None,
        temperature: float = 0.2,
        timeout_ms: int | None = None,
        tools: list | None = None,
    ) -> LLMResponse:
        """Invoke LLM with system prompt and user message."""
        resolved_model = self._resolve_model(model)
        effective_timeout = timeout_ms or self._config.timeout_ms

        client = self._build_client(resolved_model, temperature, effective_timeout)

        if tools:
            client = client.bind_tools(tools)

        user_content = prompt
        if context:
            user_content = f"{prompt}\n\nWorkflow context:\n{context}"

        messages = [
            SystemMessage(content=system),
            HumanMessage(content=user_content),
        ]

        try:
            use_structured = tools is None and not self._is_aggregate_handler(system)
            if use_structured:
                try:
                    structured_llm = client.with_structured_output(AnalystResult)
                    response = await structured_llm.ainvoke(messages)
                    if isinstance(response, AnalystResult):
                        return LLMResponse(
                            summary=response.summary,
                            signals=[s.model_dump() for s in response.signals],
                            sources=[s.model_dump() for s in response.sources],
                            confidence=self._clamp_confidence(response.confidence),
                            data=response.data,
                            content=response.summary,
                        )
                except Exception as struct_err:
                    logger.warning(f"Structured output failed, falling back to text parsing: {struct_err}")

            response = await client.ainvoke(messages)
            content = response.content if hasattr(response, "content") else str(response)
            usage = getattr(response, "usage_metadata", None)

            if tools and hasattr(response, "tool_calls") and response.tool_calls:
                tool_messages = []
                for tc in response.tool_calls:
                    tool_messages.append({
                        "role": "assistant",
                        "tool_calls": [tc],
                    })
                return LLMResponse(
                    summary=f"Called tool: {tc.get('name', 'unknown')}",
                    content=str(response.content) if response.content else "",
                    data={"tool_calls": tool_messages},
                )

            return self._normalize_content(content, usage)
        except Exception as e:
            logger.error(f"LLM invocation failed: {e}")
            raise

    async def invoke_agent_with_tools(
        self,
        system: str,
        prompt: str,
        context: str = "",
        tools: list | None = None,
        prior_messages: list | None = None,
        model: str | None = None,
        temperature: float = 0.2,
        timeout_ms: int | None = None,
        force_final: bool = False,
    ) -> AgentToolResult:
        """One agent turn with optional tool binding.

        Builds [System, Human?, *prior] and invokes the chat model. When tools are
        bound and the model returns tool_calls, the raw AIMessage is returned so the
        graph can route to ToolNode. Otherwise content is normalized to LLMResponse.
        """
        resolved_model = self._resolve_model(model)
        effective_timeout = timeout_ms or self._config.timeout_ms
        client = self._build_client(resolved_model, temperature, effective_timeout)

        bindable = list(tools or [])
        if bindable and not force_final:
            client = client.bind_tools(bindable)

        user_content = prompt
        if context:
            user_content = f"{prompt}\n\nWorkflow context:\n{context}"

        prior = list(prior_messages or [])
        seed_human: HumanMessage | None = None
        chat: list[BaseMessage] = [SystemMessage(content=system)]

        if prior:
            # Continue mid-loop conversation (Human + AI(tool_calls) + ToolMessages).
            has_human = any(
                type(m).__name__ == "HumanMessage"
                or getattr(m, "type", None) in ("human", "user")
                for m in prior
            )
            if not has_human:
                # Provider-safe restatement; not appended to graph state (already mid-loop).
                chat.append(HumanMessage(content=user_content))
            chat.extend(prior)
        else:
            seed_human = HumanMessage(content=user_content)
            chat.append(seed_human)

        try:
            response = await client.ainvoke(chat)
        except Exception as e:
            logger.error(f"Agent tool-loop invocation failed: {e}")
            raise

        # Ensure we always work with an AIMessage-like object for ToolNode routing.
        if not isinstance(response, AIMessage):
            content = getattr(response, "content", None)
            tool_calls = getattr(response, "tool_calls", None) or []
            response = AIMessage(
                content=content if content is not None else str(response),
                tool_calls=list(tool_calls) if tool_calls else [],
            )

        tool_calls = getattr(response, "tool_calls", None) or []
        # Some providers put tool_calls only in additional_kwargs
        if not tool_calls:
            extra = getattr(response, "additional_kwargs", None) or {}
            raw_calls = extra.get("tool_calls") or []
            if raw_calls:
                tool_calls = raw_calls

        if tool_calls and not force_final:
            # Normalize tool_calls onto the message for route_to_tools / ToolNode.
            if not getattr(response, "tool_calls", None):
                ai_kwargs: dict[str, Any] = {
                    "content": response.content or "",
                    "tool_calls": tool_calls,
                    "additional_kwargs": getattr(response, "additional_kwargs", {}) or {},
                }
                msg_id = getattr(response, "id", None)
                if msg_id is not None:
                    ai_kwargs["id"] = msg_id
                response = AIMessage(**ai_kwargs)
            return AgentToolResult(
                response,
                has_tool_calls=True,
                seed_human=seed_human,
            )

        content = response.content if hasattr(response, "content") else str(response)
        if isinstance(content, list):
            # Multimodal content blocks → join text parts
            parts = []
            for block in content:
                if isinstance(block, str):
                    parts.append(block)
                elif isinstance(block, dict) and block.get("type") == "text":
                    parts.append(str(block.get("text", "")))
                else:
                    parts.append(str(block))
            content = "\n".join(parts)
        usage = getattr(response, "usage_metadata", None)
        llm_response = self._normalize_content(str(content or ""), usage)
        return AgentToolResult(
            response,
            has_tool_calls=False,
            llm_response=llm_response,
            seed_human=seed_human,
        )

    async def invoke_stream(
        self,
        system: str,
        prompt: str,
        context: str = "",
        model: str | None = None,
        temperature: float = 0.2,
        timeout_ms: int | None = None,
    ) -> AsyncIterator[str]:
        resolved_model = self._resolve_model(model)
        effective_timeout = timeout_ms or self._config.timeout_ms
        client = self._build_client(resolved_model, temperature, effective_timeout)

        user_content = prompt
        if context:
            user_content = f"{prompt}\n\nWorkflow context:\n{context}"

        messages = [
            SystemMessage(content=system),
            HumanMessage(content=user_content),
        ]

        try:
            async for chunk in client.astream(messages):
                if hasattr(chunk, "content") and chunk.content:
                    yield chunk.content
        except Exception as e:
            logger.error(f"LLM streaming failed: {e}")
            raise

    async def classify_intent(
        self, message: str, tools: list[dict[str, Any]]
    ) -> dict[str, Any] | None:
        """Classify intent using function calling."""
        resolved_model = self._resolve_model()
        client = self._build_client(resolved_model, temperature=0, timeout_ms=5000)

        messages = [
            SystemMessage(
                content=(
                    "You are an intent classifier. Based on the user message, call exactly ONE "
                    "function that best matches the user's intent. If no function matches, do not "
                    "call any function. Always respond with a function call or empty response."
                )
            ),
            HumanMessage(content=message),
        ]

        try:
            response = await client.ainvoke(
                messages, tools=tools, tool_choice="auto"
            )
            tool_calls = getattr(response, "additional_kwargs", {}).get("tool_calls", [])
            if tool_calls:
                call = tool_calls[0]
                fn = call.get("function", {})
                args = json.loads(fn.get("arguments", "{}"))
                return {
                    "function": fn.get("name", ""),
                    "ticker": args.get("ticker", ""),
                }
            return None
        except Exception as e:
            logger.error(f"Intent classification failed: {e}")
            return None

    def _normalize_content(self, content: str, usage: Any = None) -> LLMResponse:
        """Normalize LLM response content to structured format."""
        if not content:
            return LLMResponse(summary="No response from LLM")

        # Strip markdown code fences
        cleaned = content.strip()
        if cleaned.startswith("```"):
            lines = cleaned.split("\n")
            lines = lines[1:]  # Remove opening fence
            if lines and lines[-1].strip() == "```":
                lines = lines[:-1]
            cleaned = "\n".join(lines).strip()

        # Try direct JSON parse
        try:
            data = json.loads(cleaned)
            return self._dict_to_response(data, content)
        except json.JSONDecodeError:
            pass

        # Check if it's a markdown report
        if self._is_markdown_report(cleaned):
            return LLMResponse(
                summary=cleaned[:2000],
                content=cleaned,
                confidence=0.75,
            )

        # Try extracting embedded JSON
        match = re.search(r'\{[\s\S]*"summary"[\s\S]*\}', cleaned)
        if match:
            try:
                data = json.loads(match.group())
                return self._dict_to_response(data, content)
            except json.JSONDecodeError:
                pass

        # Fallback to plain text
        return LLMResponse(
            summary=cleaned[:2000],
            content=cleaned,
            confidence=0.5,
        )

    def _is_markdown_report(self, text: str) -> bool:
        """Check if text is a markdown report."""
        if len(text) < 200:
            return False
        markers = ["# ", "## ", "**", "| "]
        return any(marker in text for marker in markers)

    def _dict_to_response(self, data: dict, raw_content: str) -> LLMResponse:
        """Convert dict to LLMResponse."""
        return LLMResponse(
            summary=data.get("summary", ""),
            signals=data.get("signals", []),
            sources=data.get("sources", []),
            confidence=self._clamp_confidence(data.get("confidence", 0.5)),
            data=data.get("data", {}),
            content=data.get("content", data.get("summary", "")),
            raw=raw_content,
        )

    @staticmethod
    def _clamp_confidence(value: float) -> float:
        """Clamp confidence to [0, 1]."""
        try:
            return max(0.0, min(1.0, float(value)))
        except (TypeError, ValueError):
            return 0.5

    @staticmethod
    def _is_aggregate_handler(system_prompt: str) -> bool:
        """Check if the system prompt is for an aggregate/recommendation handler."""
        aggregate_keywords = ["stock_recommendation_aggregate", "comprehensive investment research report", "aggregate"]
        lower = (system_prompt or "").lower()
        return any(kw in lower for kw in aggregate_keywords)
