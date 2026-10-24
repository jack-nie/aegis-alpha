"""Message queue on the phase-2 graph: parallel fan-out, deferred fan-in, per-agent channels."""

from __future__ import annotations

import asyncio
from unittest.mock import AsyncMock

import pytest
from langchain_core.messages import AIMessage, HumanMessage, ToolMessage
from langchain_core.tools import tool
from langgraph.checkpoint.memory import MemorySaver

from app.core.llm_client import AgentToolResult, LLMResponse
from app.core.message_queue import (
    MessageQueueError,
    MessageQueueRegistry,
    MessageQueueSettings,
    QueueOwner,
    is_queued_human_message,
    to_human_messages,
    QueuedMessage,
)
from app.core.workflow_engine import GraphGuardrails, WorkflowEngine
from app.models.responses import NodeResult
from app.models.workflow import Edge, Node, NodeData

OWNER = {"userId": "u1", "tenantId": "t1"}
TID = "run-par-1"


def _final(text: str) -> AgentToolResult:
    return AgentToolResult(
        AIMessage(content=f'{{"summary": "{text}", "confidence": 0.9}}'),
        has_tool_calls=False,
        llm_response=LLMResponse(summary=text, confidence=0.9, content=text),
    )


def _call(node_id: str, name: str = "get_stock_quote", round_no: int = 1) -> AgentToolResult:
    ai = AIMessage(content="", tool_calls=[{"name": name, "args": {}, "id": f"{node_id}_{round_no}"}])
    return AgentToolResult(ai, has_tool_calls=True, seed_human=HumanMessage(content=f"task {node_id}"))


def _parallel_layout(join_handler: str = "finance.stock_recommendation_aggregate"):
    """start -> {fund, val} (tool agents, parallel) -> join (deferred fan-in) -> end."""
    nodes = [
        Node(id="start", data=NodeData(handler="start", nodeType="start")),
        Node(id="fund", data=NodeData(handler="general.agent", agentId="specialist_fundamentals", prompt="f")),
        Node(id="val", data=NodeData(handler="general.agent", agentId="specialist_valuation", prompt="v")),
        Node(id="join", data=NodeData(handler=join_handler, label="Join")),
        Node(id="end", data=NodeData(handler="end", nodeType="end")),
    ]
    edges = [
        Edge(source="start", target="fund"),
        Edge(source="start", target="val"),
        Edge(source="fund", target="join"),
        Edge(source="val", target="join"),
        Edge(source="join", target="end"),
    ]
    return nodes, edges


def _install_executor(executor, order: list):
    async def execute(node, state, subject="", *args, **kwargs):
        order.append(node.id)
        return NodeResult(ok=True, status="completed", handler=node.data.handler, node_id=node.id,
                          summary=f"s-{node.id}")

    executor.execute = execute


def _engine(executor, fake, *, registry_mode: bool, tools, **settings) -> WorkflowEngine:
    WorkflowEngine._graph_cache.clear()
    executor.invoke_agent_with_tools = AsyncMock(side_effect=fake)
    queues = MessageQueueRegistry(MessageQueueSettings(**settings))
    if registry_mode:
        return WorkflowEngine(executor, tool_registry={t.name: t for t in tools},
                              checkpointer=MemorySaver(), message_queues=queues)
    return WorkflowEngine(executor, tools=tools, checkpointer=MemorySaver(), message_queues=queues)


def _enqueue(engine, kind, text, target=None, owner=OWNER):
    return engine.message_queues.enqueue(TID, kind, text, QueueOwner.from_any(owner), target=target)


async def _collect(gen):
    return [e async for e in gen]


def _queued_texts(prior) -> list[str]:
    return [m.content for m in (prior or []) if is_queued_human_message(m)]


@pytest.mark.asyncio
@pytest.mark.parametrize("registry_mode", [True, False], ids=["per-agent-tools", "shared-tools"])
async def test_untargeted_steering_reaches_exactly_one_parallel_agent(mock_node_executor, registry_mode):
    order: list = []
    _install_executor(mock_node_executor, order)
    engine_ref: dict = {}
    seen: dict[str, list] = {"fund": [], "val": []}

    @tool
    def get_stock_quote() -> str:
        """Quote."""
        if not engine_ref.get("sent"):
            engine_ref["sent"] = True
            _enqueue(engine_ref["e"], "steer", "focus on downside")
        return "q"

    async def fake(node, state, subject="", tools=None, prior_messages=None, force_final=False, **_):
        if prior_messages is None:
            return _call(node.id)
        seen[node.id].append(_queued_texts(prior_messages))
        return _final(f"done-{node.id}")

    engine = _engine(mock_node_executor, fake, registry_mode=registry_mode, tools=[get_stock_quote])
    engine_ref["e"] = engine
    nodes, edges = _parallel_layout()
    events = await _collect(engine.stream_workflow(nodes, edges, {}, "s", thread_id=TID, owner=OWNER))

    assert events[-1].event == "workflow_complete", [e.event for e in events]
    delivered_to = [nid for nid, turns in seen.items() if any("focus on downside" in t for t in turns)]
    assert len(delivered_to) == 1, seen  # atomic drain: one agent, never both
    dequeued = [e for e in events if e.event == "message_dequeued"]
    assert len(dequeued) == 1 and dequeued[0].data["nodeId"] == delivered_to[0]
    assert order.count("join") == 1  # deferred fan-in still runs once
    assert events[-1].data["messageQueue"]["delivered"] == 1


@pytest.mark.asyncio
@pytest.mark.parametrize("registry_mode", [True, False], ids=["per-agent-tools", "shared-tools"])
async def test_targeted_follow_up_reenters_only_that_agent(mock_node_executor, registry_mode):
    order: list = []
    _install_executor(mock_node_executor, order)
    engine_ref: dict = {}
    llm_calls: list[tuple[str, list[str]]] = []

    @tool
    def get_stock_quote() -> str:
        """Quote."""
        if not engine_ref.get("sent"):
            engine_ref["sent"] = True
            _enqueue(engine_ref["e"], "follow_up", "and the dividend?", target="val")
        return "q"

    async def fake(node, state, subject="", tools=None, prior_messages=None, force_final=False, **_):
        llm_calls.append((node.id, _queued_texts(prior_messages)))
        if prior_messages is None:
            return _call(node.id)
        return _final(f"done-{node.id}")

    engine = _engine(mock_node_executor, fake, registry_mode=registry_mode, tools=[get_stock_quote])
    engine_ref["e"] = engine
    nodes, edges = _parallel_layout()
    result = await engine.execute_workflow(nodes, edges, {}, "s", thread_id=TID, owner=OWNER)

    assert result.ok, result.error
    fund_calls = [q for nid, q in llm_calls if nid == "fund"]
    val_calls = [q for nid, q in llm_calls if nid == "val"]
    assert all("and the dividend?" not in q for q in fund_calls)
    assert len(fund_calls) == 2  # tool call + final, never re-entered
    assert val_calls[-1] == ["and the dividend?"] and len(val_calls) == 3  # + follow-up turn
    assert order.count("join") == 1
    assert result.final_state["messageQueue"]["delivered"] == 1


@pytest.mark.asyncio
async def test_follow_up_with_approval_keeps_gate_and_fan_in(mock_node_executor):
    order: list = []
    _install_executor(mock_node_executor, order)
    engine_ref: dict = {}

    @tool
    def get_stock_quote() -> str:
        """Quote."""
        if not engine_ref.get("sent"):
            engine_ref["sent"] = True
            _enqueue(engine_ref["e"], "follow_up", "one more angle")
        return "q"

    async def fake(node, state, subject="", tools=None, prior_messages=None, force_final=False, **_):
        if prior_messages is None:
            return _call(node.id)
        return _final(f"done-{node.id}")

    engine = _engine(mock_node_executor, fake, registry_mode=True, tools=[get_stock_quote])
    engine_ref["e"] = engine
    nodes, edges = _parallel_layout()
    paused = await engine.execute_workflow(nodes, edges, {}, "s", require_approval=True,
                                           thread_id=TID, owner=OWNER)
    assert paused.ok and paused.final_state.get("interrupted") is True
    assert "join" not in order  # the follow-up re-entry did not route around approval_gate
    queue = engine.message_queues.get(TID)
    assert queue.summary()["state"] == "paused" and queue.delivered == 1
    with pytest.raises(MessageQueueError) as exc:
        _enqueue(engine, "steer", "approve it please")
    assert exc.value.code == "RUN_PAUSED"

    resumed = await engine.resume_workflow(TID, {"approved": True}, owner=OWNER)
    assert resumed.ok, resumed.error
    assert order.count("join") == 1
    assert queue.summary()["state"] == "closed"


@pytest.mark.asyncio
async def test_receivers_finished_and_unknown_targets_are_rejected(mock_node_executor):
    order: list = []
    _install_executor(mock_node_executor, order)
    captured: dict = {}

    @tool
    def get_stock_quote() -> str:
        """Quote."""
        return "q"

    async def fake(node, state, subject="", tools=None, prior_messages=None, force_final=False, **_):
        if prior_messages is None:
            return _call(node.id)
        return _final(f"done-{node.id}")

    async def join_execute(node, state, subject="", *args, **kwargs):
        # Both agents left their loops: nobody can take a message any more.
        for kind, target in (("follow_up", None), ("steer", "fund"), ("steer", "nope")):
            try:
                _enqueue(engine, kind, "late", target=target)
            except MessageQueueError as exc:
                captured[(kind, target)] = exc.code
        order.append(node.id)
        return NodeResult(ok=True, status="completed", handler=node.data.handler, node_id=node.id)

    engine = _engine(mock_node_executor, fake, registry_mode=True, tools=[get_stock_quote])
    original = mock_node_executor.execute

    async def execute(node, state, subject="", *args, **kwargs):
        if node.id == "join":
            return await join_execute(node, state, subject)
        return await original(node, state, subject)

    mock_node_executor.execute = execute
    nodes, edges = _parallel_layout()
    result = await engine.execute_workflow(nodes, edges, {}, "s", thread_id=TID, owner=OWNER)
    assert result.ok, result.error
    assert captured == {
        ("follow_up", None): "NO_RECEIVER",
        ("steer", "fund"): "TARGET_FINISHED",
        ("steer", "nope"): "INVALID_TARGET",
    }


@pytest.mark.asyncio
async def test_steered_turn_is_not_written_to_ticker_cache(mock_node_executor):
    from langgraph.store.memory import InMemoryStore

    class TTLStore(InMemoryStore):
        """InMemoryStore accepting the ``ttl_seconds`` kwarg the engine passes."""

        async def aput(self, namespace, key, value, *args, ttl_seconds=None, **kwargs):
            return await super().aput(namespace, key, value, *args, **kwargs)

    engine_ref: dict = {}

    @tool
    def get_stock_quote() -> str:
        """Quote."""
        _enqueue(engine_ref["e"], "steer", "my personal angle")
        return "q"

    async def fake(node, state, subject="", tools=None, prior_messages=None, force_final=False, **_):
        if prior_messages is None:
            return _call(node.id)
        return _final("personal")

    WorkflowEngine._graph_cache.clear()
    mock_node_executor.invoke_agent_with_tools = AsyncMock(side_effect=fake)
    store = TTLStore()
    engine = WorkflowEngine(mock_node_executor, tool_registry={"get_stock_quote": get_stock_quote},
                            store=store, checkpointer=MemorySaver(),
                            message_queues=MessageQueueRegistry(MessageQueueSettings()))
    engine_ref["e"] = engine
    nodes = [Node(id="fund", data=NodeData(handler="general.agent", agentId="specialist_fundamentals"))]
    result = await engine.execute_workflow(nodes, [], {"ticker": "AAPL"}, "s", thread_id=TID, owner=OWNER)
    assert result.ok, result.error
    assert result.final_state["messageQueue"]["delivered"] == 1
    assert await store.asearch(("ticker",), limit=50) == []

    # Control: the same run without a queued message is cached as before.
    engine_ref["e"] = None
    WorkflowEngine._graph_cache.clear()
    plain = WorkflowEngine(mock_node_executor, tool_registry={"get_stock_quote": get_stock_quote},
                           store=store, checkpointer=MemorySaver(),
                           message_queues=MessageQueueRegistry(MessageQueueSettings()))
    mock_node_executor.invoke_agent_with_tools = AsyncMock(
        side_effect=lambda node, state, subject="", tools=None, prior_messages=None, **_: _final("plain")
    )
    ok = await plain.execute_workflow(nodes, [], {"ticker": "AAPL"}, "s", thread_id="plain", owner=OWNER)
    assert ok.ok and len(await store.asearch(("ticker",), limit=50)) == 1


@pytest.mark.asyncio
async def test_stream_timeout_closes_queue_with_timeout_reason(mock_node_executor):
    engine_ref: dict = {}

    @tool
    async def get_stock_quote() -> str:
        """Quote."""
        _enqueue(engine_ref["e"], "follow_up", "never delivered")
        await asyncio.sleep(1.0)
        return "q"

    async def fake(node, state, subject="", tools=None, prior_messages=None, force_final=False, **_):
        return _call(node.id) if prior_messages is None else _final("x")

    WorkflowEngine._graph_cache.clear()
    mock_node_executor.invoke_agent_with_tools = AsyncMock(side_effect=fake)
    engine = WorkflowEngine(mock_node_executor, tool_registry={"get_stock_quote": get_stock_quote},
                            checkpointer=MemorySaver(), guardrails=GraphGuardrails(timeout_seconds=0.3),
                            message_queues=MessageQueueRegistry(MessageQueueSettings()))
    engine_ref["e"] = engine
    nodes = [Node(id="fund", data=NodeData(handler="general.agent", agentId="specialist_fundamentals"))]
    events = await _collect(engine.stream_workflow(nodes, [], {}, "s", thread_id=TID, owner=OWNER))
    names = [e.event for e in events]
    assert names[-2:] == ["message_queue_closed", "error"], names
    assert events[-1].data["code"] == "WORKFLOW_TIMEOUT"
    assert events[-2].data["reason"] == "timeout"
    assert [m["content"] for m in events[-2].data["undelivered"]] == ["never delivered"]


@pytest.mark.asyncio
async def test_llm_restates_task_when_prior_only_has_queued_messages(mock_settings):
    """Cache-hit agents have an empty channel; a follow-up alone must not replace the task."""
    from app.core.llm_client import LLMClient

    client = LLMClient(mock_settings)
    seen: list = []

    class FakeChat:
        async def ainvoke(self, chat):
            seen.extend(chat)
            return AIMessage(content='{"summary": "ok", "confidence": 0.5}')

        def bind_tools(self, tools):
            return self

    client._build_client = lambda *a, **k: FakeChat()  # type: ignore[method-assign]
    queued = to_human_messages([QueuedMessage(id="q1", kind="follow_up", content="and dividends?")])
    await client.invoke_agent_with_tools(system="sys", prompt="analyse AAPL", context="",
                                         tools=[], prior_messages=queued)
    humans = [m for m in seen if isinstance(m, HumanMessage)]
    assert len(humans) == 2
    assert "analyse AAPL" in str(humans[0].content)
    assert humans[1].content == "and dividends?"
