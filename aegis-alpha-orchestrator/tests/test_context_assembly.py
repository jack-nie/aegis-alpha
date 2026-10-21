"""Budgeted context assembly and token-usage propagation."""

import json

import pytest
from langchain_core.messages import AIMessage

from app.core.context_assembly import (
    apply_trace_usage,
    assemble_model_context,
    estimate_tokens,
    memory_hit_count,
)
from app.core.llm_client import LLMResponse
from app.models.workflow import Node, NodeData

_TOKEN_USAGE = {"inputTokens": 11, "outputTokens": 7, "totalTokens": 18}


def _node() -> Node:
    return Node(
        id="current",
        data=NodeData(
            handler="finance.market_analysis",
            label="Market Analysis",
            prompt="Analyze {subject} as {agent_name}.",
        ),
    )


def _oversized_state() -> dict:
    return {
        "ticker": "NVDA",
        "subject": "NVDA",
        "approval_status": {"summary": "META_SHOULD_BE_SKIPPED"},
        "marketDataContext": {"snapshot": "MARKET_SNAPSHOT_SENTINEL"},
        "latestToolResults": {"output": "LATEST_TOOL_SENTINEL"},
        "memoryContext": {
            "hitCount": 2,
            "snippets": [
                {"kind": "preference", "text": "PREF_SENTINEL"},
                {"kind": "conclusion", "text": "CONCLUSION_SENTINEL"},
            ],
        },
        "old_analysis": {
            "summary": "old-summary",
            "signals": [{"name": "stale", "value": "down"}],
            "blob": "OLD_BLOB_SHOULD_BE_DROPPED" + ("Z" * 40000),
        },
        "new_analysis": {
            "summary": "new-summary",
            "signals": [{"name": "momentum", "value": "up"}],
            "sources": [{"evidenceId": "ev-new-1", "title": "filing"}],
            "data": {"claims": [{"evidence_id": "ev-claim-1", "text": "revenue"}]},
        },
    }


async def _execute_recording(executor, state: dict) -> tuple[object, str]:
    recorded: dict[str, str] = {}

    async def fake_invoke(system, prompt, context="", **kwargs):
        recorded["context"] = context
        recorded["prompt"] = prompt
        return LLMResponse(summary="ok", content="ok", token_usage=dict(_TOKEN_USAGE))

    executor._llm_client.invoke = fake_invoke
    result = await executor.execute(node=_node(), state=state, subject="NVDA")
    return result, recorded["context"]


class _StructuredSuccessChat:
    """Chat model whose structured-output success carries usage_metadata."""

    def __init__(self, recorded_messages: list):
        self._recorded_messages = recorded_messages

    def bind_tools(self, tools):
        raise AssertionError("finance invoke must not bind tools before structured output")

    def with_structured_output(self, schema, include_raw=False, **kwargs):
        from app.core.llm_client import AnalystResult

        assert include_raw is True
        assert schema is AnalystResult
        recorded = self._recorded_messages

        class _Structured:
            async def ainvoke(self, messages):
                recorded.append(messages)
                parsed = AnalystResult(summary="ok", confidence=0.6, data={})
                raw = AIMessage(
                    content="ok",
                    usage_metadata={
                        "input_tokens": 11,
                        "output_tokens": 7,
                        "total_tokens": 18,
                    },
                )
                return {"raw": raw, "parsed": parsed, "parsing_error": None}

        return _Structured()

    async def ainvoke(self, messages):
        raise AssertionError("structured success must not fall through to plain ainvoke")


@pytest.mark.asyncio
async def test_invoke_llm_assembles_budgeted_context_and_token_usage(mock_node_executor):
    from app.core.llm_client import LLMClient

    recorded_messages: list = []
    client = mock_node_executor._llm_client
    assert client.invoke.__func__ is LLMClient.invoke
    client._build_client = lambda *args, **kwargs: _StructuredSuccessChat(recorded_messages)

    state = _oversized_state()
    result = await mock_node_executor.execute(node=_node(), state=state, subject="NVDA")
    assert recorded_messages, "real LLMClient.invoke did not call the chat model"
    human = recorded_messages[0][1].content
    assert "Workflow context:\n" in human
    context = human.split("Workflow context:\n", 1)[1]

    assert "new-summary" in context
    assert "momentum" in context
    assert "ev-new-1" in context
    assert 'evidence: ["ev-new-1", "ev-claim-1"]' in context
    assert "MARKET_SNAPSHOT_SENTINEL" in context
    assert "LATEST_TOOL_SENTINEL" in context
    assert "PREF_SENTINEL" in context
    assert "CONCLUSION_SENTINEL" in context
    assert "OLD_BLOB_SHOULD_BE_DROPPED" not in context
    assert "old-summary" in context
    assert "[compressed]" in context
    assert "META_SHOULD_BE_SKIPPED" not in context

    full_json = json.dumps(state)
    assert not full_json.startswith(context)
    assert context != full_json[: len(context)]

    assert result.data["tokenUsage"] == _TOKEN_USAGE
    assert result.data.get("recommendation") is None

    trace: dict = {}
    apply_trace_usage(trace, result.data)
    assert trace["tokenUsage"]["inputTokens"] == 11
    assert trace["tokenUsage"]["totalTokens"] == 18
    assert trace["tokenUsage"]["outputTokens"] == 7

    from langgraph.checkpoint.memory import MemorySaver

    from app.core.workflow_engine import WorkflowEngine

    WorkflowEngine._graph_cache.clear()
    engine = WorkflowEngine(mock_node_executor, checkpointer=MemorySaver())
    workflow_result = await engine.execute_workflow(
        nodes=[_node()],
        edges=[],
        state=state,
        subject="NVDA",
        thread_id="context-usage-thread",
    )
    assert workflow_result.ok, workflow_result.error
    run_trace = []
    for entry in workflow_result.trace:
        payload = entry.model_dump() if hasattr(entry, "model_dump") else dict(entry)
        extra = getattr(entry, "model_extra", None) or {}
        payload.update(extra)
        run_trace.append(payload)
    assert run_trace, "workflow produced no trace"
    assert run_trace[-1].get("tokenUsage") == _TOKEN_USAGE


@pytest.mark.asyncio
async def test_execute_without_memory_context_still_assembles(mock_node_executor):
    state = {
        "ticker": "MSFT",
        "subject": "MSFT",
        "marketDataContext": {"snapshot": "STILL_ASSEMBLED"},
    }
    assert memory_hit_count(state) == 0
    assert memory_hit_count(None) == 0
    assert memory_hit_count({}) == 0

    result, context = await _execute_recording(mock_node_executor, state)
    assert isinstance(context, str)
    assert "STILL_ASSEMBLED" in context
    assert "## memory" not in context
    assert result.ok is True
    assert "tokenUsage" in result.data


def test_estimate_tokens_and_direct_assembly_rules():
    assert estimate_tokens("") == 0
    assert estimate_tokens("a") == 1
    assert estimate_tokens("abcd") == 1
    assert estimate_tokens("abcdefgh") == 2

    fallback_state = {
        "toolResults": [
            {"output": "OLD_TOOL"},
            {"output": "FALLBACK_TOOL_SENTINEL"},
        ],
        "memoryContext": {
            "hitCount": 0,
            "snippets": [{"kind": "preference", "text": "HIDDEN_MEMORY"}],
        },
        "old_analysis": {
            "summary": "old-summary",
            "blob": "DROP_ME" + ("Q" * 20000),
        },
        "new_analysis": {
            "summary": "new-summary",
            "signals": [{"name": "momentum", "value": "up"}],
            "sources": [{"id": "ev-direct-1"}],
        },
    }
    assert memory_hit_count(fallback_state) == 0
    text = assemble_model_context(fallback_state)
    assert "FALLBACK_TOOL_SENTINEL" in text
    assert "OLD_TOOL" not in text
    assert "HIDDEN_MEMORY" not in text
    assert "## memory" not in text
    assert "new-summary" in text
    assert "momentum" in text
    assert "ev-direct-1" in text
    assert "old-summary" in text
    assert "[compressed]" in text
    assert "DROP_ME" not in text
    assert not json.dumps(fallback_state).startswith(text)

    huge_market = {"snapshot": "MARKET_KEEP", "pad": "M" * 8000}
    forced = assemble_model_context(
        {
            "marketDataContext": huge_market,
            "solo": {
                "summary": "keep-summary-but-compress",
                "signals": [{"name": "should_hide_signal", "value": "up"}],
            },
        },
        token_budget=50,
    )
    assert "MARKET_KEEP" in forced
    assert "M" * 8000 in forced
    assert "[compressed]" in forced
    assert "keep-summary-but-compress" in forced
    assert "should_hide_signal" not in forced


def test_apply_trace_usage_normalizes_aliases_and_ignores_missing():
    trace = {"nodeId": "n1"}
    apply_trace_usage(trace, {"tokenUsage": {"input_tokens": 3, "output_tokens": 4}})
    assert trace["tokenUsage"] == {
        "inputTokens": 3,
        "outputTokens": 4,
        "totalTokens": 7,
    }

    untouched = {"nodeId": "n2"}
    assert apply_trace_usage(untouched, {}) is untouched
    assert "tokenUsage" not in untouched
    assert apply_trace_usage(untouched, None) is untouched
