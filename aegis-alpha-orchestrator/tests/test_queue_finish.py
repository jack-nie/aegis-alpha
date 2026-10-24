"""P4-11: message-queue finish — concurrent tool batch by default, steering before the first LLM call."""

from __future__ import annotations

import asyncio
import time
from unittest.mock import AsyncMock

import pytest
from langchain_core.messages import AIMessage, HumanMessage, ToolMessage
from langchain_core.tools import tool

from app.config import Settings
from app.core.llm_client import AgentToolResult, LLMResponse
from app.core.message_queue import (
    MessageQueueRegistry,
    MessageQueueSettings,
    QueueOwner,
    is_queued_human_message,
)
from app.core.workflow_engine import WorkflowEngine
from app.models.workflow import Edge, Node, NodeData

OWNER = {"userId": "u1", "tenantId": "t1"}
TID = "run-p4-11"


def _layout():
    nodes = [
        Node(id="agent", data=NodeData(handler="general.agent", prompt="analyse")),
        Node(id="end", data=NodeData(handler="end", nodeType="end")),
    ]
    return nodes, [Edge(source="agent", target="end")]


def _final(text: str) -> AgentToolResult:
    return AgentToolResult(
        AIMessage(content=text), has_tool_calls=False,
        llm_response=LLMResponse(summary=text, confidence=0.9, content=text),
    )


def test_skip_remaining_tools_defaults_to_false():
    assert MessageQueueSettings().skip_remaining_tools is False
    assert Settings().message_queue_skip_remaining_tools is False
    assert MessageQueueSettings.from_app_settings(Settings()).skip_remaining_tools is False


@pytest.mark.asyncio
async def test_default_config_runs_tool_batch_concurrently(mock_node_executor):
    @tool
    async def slow_a() -> str:
        """Slow tool A."""
        await asyncio.sleep(0.5)
        return "a"

    @tool
    async def slow_b() -> str:
        """Slow tool B."""
        await asyncio.sleep(0.5)
        return "b"

    @tool
    async def slow_c() -> str:
        """Slow tool C."""
        await asyncio.sleep(0.5)
        return "c"

    seen: list = []

    async def fake(node, state=None, subject="", tools=None, prior_messages=None, force_final=False, **_):
        if prior_messages is None:
            ai = AIMessage(content="", tool_calls=[
                {"name": n, "args": {}, "id": f"c_{n}"} for n in ("slow_a", "slow_b", "slow_c")
            ])
            return AgentToolResult(ai, has_tool_calls=True, seed_human=HumanMessage(content="analyse"))
        seen.append(list(prior_messages))
        return _final("done")

    WorkflowEngine._graph_cache.clear()
    mock_node_executor.invoke_agent_with_tools = AsyncMock(side_effect=fake)
    engine = WorkflowEngine(
        mock_node_executor, tools=[slow_a, slow_b, slow_c],
        message_queues=MessageQueueRegistry(MessageQueueSettings()),
    )
    nodes, edges = _layout()
    started = time.monotonic()
    events = [e async for e in engine.stream_workflow(nodes, edges, {}, "s", thread_id=TID, owner=OWNER)]
    elapsed = time.monotonic() - started

    assert events[-1].event == "workflow_complete"
    # sequential would be >= 1.5s; the batch runs concurrently (pi HEAD semantics)
    assert elapsed < 1.0, elapsed
    assert len([m for m in seen[0] if isinstance(m, ToolMessage)]) == 3
    assert "tool_skipped" not in [e.event for e in events]


@pytest.mark.asyncio
async def test_steering_queued_before_first_llm_call_is_in_first_request(mock_node_executor):
    calls: list[dict] = []

    async def fake(node, state=None, subject="", tools=None, prior_messages=None, force_final=False,
                   leading_messages=None, **_):
        calls.append({"prior": prior_messages, "leading": list(leading_messages or [])})
        return _final("ok")

    WorkflowEngine._graph_cache.clear()
    mock_node_executor.invoke_agent_with_tools = AsyncMock(side_effect=fake)

    @tool
    def quote() -> str:
        """Quote."""
        return "q"

    registry = MessageQueueRegistry(MessageQueueSettings())
    original_open = registry.open_run

    def open_and_steer(thread_id, owner, *args, **kwargs):
        queue = original_open(thread_id, owner, *args, **kwargs)
        # the user typed while the run was starting: before the agent's first LLM call
        registry.enqueue(thread_id, "steer", "只看风险", QueueOwner.from_any(OWNER))
        return queue

    registry.open_run = open_and_steer
    engine = WorkflowEngine(mock_node_executor, tools=[quote], message_queues=registry)
    nodes, edges = _layout()
    events = [e async for e in engine.stream_workflow(nodes, edges, {}, "s", thread_id=TID, owner=OWNER)]

    assert len(calls) == 1, "steering reached the first request; no extra inbox round-trip"
    first = calls[0]
    assert first["prior"] is None
    assert [m.content for m in first["leading"]] == ["只看风险"]
    assert all(is_queued_human_message(m) for m in first["leading"])
    complete = events[-1]
    assert complete.event == "workflow_complete"
    assert complete.data["messageQueue"]["delivered"] == 1


@pytest.mark.asyncio
async def test_llm_client_puts_leading_messages_after_seed():
    from app.core.llm_client import LLMClient
    from app.core.message_queue import QueuedMessage, to_human_messages

    captured: list = []

    class _Client:
        def bind_tools(self, tools):
            return self

        async def ainvoke(self, messages):
            captured.extend(messages)
            return AIMessage(content='{"summary":"x","confidence":0.5}')

    client = LLMClient.__new__(LLMClient)
    client._config = type("C", (), {"timeout_ms": 1000})()
    client._resolve_model = lambda m: "m"
    client._build_client = lambda *a, **k: _Client()
    client._context_policy = lambda m: None
    steer = to_human_messages([QueuedMessage(id="m1", kind="steer", content="focus risk")])
    await client.invoke_agent_with_tools(system="sys", prompt="task", leading_messages=steer)
    kinds = [type(m).__name__ for m in captured]
    assert kinds == ["SystemMessage", "HumanMessage", "HumanMessage"]
    assert captured[-1].content == "focus risk" and is_queued_human_message(captured[-1])


def test_queue_summary_lists_deliveries_per_node():
    registry = MessageQueueRegistry(MessageQueueSettings())
    queue = registry.open_run("run-d", QueueOwner.from_any(OWNER))
    accepted = registry.enqueue("run-d", "follow_up", "再总结一句", QueueOwner.from_any(OWNER))
    queue.drain("follow_up", node_id="agent")
    deliveries = queue.summary()["deliveries"]
    assert deliveries == [{"id": accepted["message"]["id"], "kind": "follow_up", "nodeId": "agent"}]
