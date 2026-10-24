"""Agent-loop message queue: steering skips remaining tools, follow-up re-enters (pi dual loop)."""

from __future__ import annotations

from unittest.mock import AsyncMock

import pytest
from langchain_core.messages import AIMessage, HumanMessage, ToolMessage
from langchain_core.tools import tool

from app.core.llm_client import AgentToolResult, LLMResponse
from app.core.message_queue import (
    SKIPPED_TOOL_RESULT_TEXT,
    MessageQueueError,
    MessageQueueRegistry,
    MessageQueueSettings,
    QueueOwner,
    is_queued_human_message,
)
from app.core.workflow_engine import WorkflowEngine, _messages_for_tool_continuation
from app.models.responses import NodeResult
from app.models.workflow import Edge, Node, NodeData

OWNER = {"userId": "u1", "tenantId": "t1"}
TID = "run-mq-1"


def _final(text: str) -> AgentToolResult:
    return AgentToolResult(
        AIMessage(content=f'{{"summary": "{text}", "confidence": 0.9}}'),
        has_tool_calls=False,
        llm_response=LLMResponse(summary=text, confidence=0.9, content=text),
    )


def _tool_calls(*names: str, round_no: int = 1) -> AgentToolResult:
    ai = AIMessage(
        content="",
        tool_calls=[{"name": n, "args": {}, "id": f"call_{n}_{round_no}"} for n in names],
    )
    return AgentToolResult(ai, has_tool_calls=True, seed_human=HumanMessage(content="analyse"))


def _layout(with_next: bool = False):
    nodes = [Node(id="agent", data=NodeData(handler="general.agent", prompt="analyse"))]
    edges = []
    if with_next:
        nodes.append(Node(id="after", data=NodeData(handler="logic", label="After")))
        edges.append(Edge(source="agent", target="after"))
    nodes.append(Node(id="end", data=NodeData(handler="end", nodeType="end")))
    edges.append(Edge(source="after" if with_next else "agent", target="end"))
    return nodes, edges


def _engine(mock_node_executor, tools, fake, **settings) -> WorkflowEngine:
    WorkflowEngine._graph_cache.clear()
    mock_node_executor.invoke_agent_with_tools = AsyncMock(side_effect=fake)
    registry = MessageQueueRegistry(MessageQueueSettings(**settings))
    return WorkflowEngine(mock_node_executor, tools=tools, message_queues=registry)


def _enqueue(engine, kind, text, owner=OWNER):
    return engine.message_queues.enqueue(TID, kind, text, QueueOwner.from_any(owner))


async def _collect(gen):
    return [e async for e in gen]


# ── steering ────────────────────────────────────────────────────────────

@pytest.mark.asyncio
async def test_steering_skips_remaining_tool_calls_and_injects_before_next_llm(mock_node_executor):
    executed: list[str] = []
    engine_ref: dict = {}

    @tool
    def quote() -> str:
        """Fetch a quote."""
        executed.append("quote")
        # user types while the first tool is running
        _enqueue(engine_ref["e"], "steer", "只看风险，别查新闻")
        return "quote=100"

    @tool
    def news() -> str:
        """Fetch news."""
        executed.append("news")
        return "news"

    @tool
    def filings() -> str:
        """Fetch filings."""
        executed.append("filings")
        return "filings"

    seen_prior: list = []

    async def fake(node, state, subject="", tools=None, prior_messages=None, force_final=False, **_):
        if prior_messages is None:
            return _tool_calls("quote", "news", "filings")
        seen_prior.append(list(prior_messages))
        return _final("risk only")

    engine = _engine(mock_node_executor, [quote, news, filings], fake)
    engine_ref["e"] = engine
    nodes, edges = _layout()
    events = await _collect(
        engine.stream_workflow(nodes, edges, {}, "s", thread_id=TID, owner=OWNER)
    )

    assert executed == ["quote"]  # news/filings never ran
    prior = seen_prior[0]
    tool_msgs = [m for m in prior if isinstance(m, ToolMessage)]
    assert [m.content for m in tool_msgs] == [
        "quote=100", SKIPPED_TOOL_RESULT_TEXT, SKIPPED_TOOL_RESULT_TEXT,
    ]
    assert [m.status for m in tool_msgs[1:]] == ["error", "error"]
    assert {m.tool_call_id for m in tool_msgs} == {"call_quote_1", "call_news_1", "call_filings_1"}
    # steering message is the last thing the LLM sees, after all tool results
    assert is_queued_human_message(prior[-1]) and prior[-1].content == "只看风险，别查新闻"
    assert mock_node_executor.invoke_agent_with_tools.await_count == 2

    names = [e.event for e in events]
    assert names.count("tool_skipped") == 2
    assert "message_queued" in names and "message_dequeued" in names
    assert names.index("message_queued") < names.index("tool_skipped") < names.index("message_dequeued")
    skipped = [e.data for e in events if e.event == "tool_skipped"]
    assert [s["toolName"] for s in skipped] == ["news", "filings"]
    assert names[-2:] == ["message_queue_closed", "workflow_complete"]
    complete = events[-1].data
    assert complete["messageQueue"]["skippedToolCalls"] == 2
    assert complete["messageQueue"]["delivered"] == 1


@pytest.mark.asyncio
async def test_steering_without_skip_runs_whole_batch_then_injects(mock_node_executor):
    """skip_remaining_tools=False == pi HEAD (208a2cc1): steering after the full tool batch."""
    executed: list[str] = []
    engine_ref: dict = {}

    @tool
    def quote() -> str:
        """Fetch a quote."""
        executed.append("quote")
        _enqueue(engine_ref["e"], "steer", "steer me")
        return "q"

    @tool
    def news() -> str:
        """Fetch news."""
        executed.append("news")
        return "n"

    seen_prior: list = []

    async def fake(node, state, subject="", tools=None, prior_messages=None, force_final=False, **_):
        if prior_messages is None:
            return _tool_calls("quote", "news")
        seen_prior.append(list(prior_messages))
        return _final("done")

    engine = _engine(mock_node_executor, [quote, news], fake, skip_remaining_tools=False)
    engine_ref["e"] = engine
    nodes, edges = _layout()
    result = await engine.execute_workflow(nodes, edges, {}, "s", thread_id=TID, owner=OWNER)

    assert result.ok is True
    assert sorted(executed) == ["news", "quote"]
    prior = seen_prior[0]
    assert SKIPPED_TOOL_RESULT_TEXT not in [m.content for m in prior if isinstance(m, ToolMessage)]
    assert prior[-1].content == "steer me"
    assert result.final_state["messageQueue"]["skippedToolCalls"] == 0


@pytest.mark.asyncio
async def test_steering_after_final_answer_reenters_agent(mock_node_executor):
    """Steering that arrives during the final LLM call is delivered before the loop ends."""
    calls = {"n": 0}
    engine_ref: dict = {}

    @tool
    def quote() -> str:
        """Fetch a quote."""
        return "q"

    async def fake(node, state, subject="", tools=None, prior_messages=None, force_final=False, **_):
        calls["n"] += 1
        if calls["n"] == 1:
            _enqueue(engine_ref["e"], "follow_up", "follow")
            _enqueue(engine_ref["e"], "steer", "steer")
            return _final("first")
        last = prior_messages[-1]
        return _final(f"answered:{last.content}")

    engine = _engine(mock_node_executor, [quote], fake)
    engine_ref["e"] = engine
    nodes, edges = _layout()
    result = await engine.execute_workflow(nodes, edges, {}, "s", thread_id=TID, owner=OWNER)
    # steering first (pi polls steering before follow-ups), then the follow-up turn
    assert calls["n"] == 3
    assert result.node_outputs["agent"]["summary"] == "answered:follow"
    assert result.final_state["messageQueue"]["delivered"] == 2


# ── follow-up (outer loop) ─────────────────────────────────────────────

@pytest.mark.asyncio
async def test_follow_up_reenters_agent_then_continues_graph(mock_node_executor):
    engine_ref: dict = {}

    @tool
    def quote() -> str:
        """Fetch a quote."""
        return "q=1"

    turns: list = []

    async def fake(node, state, subject="", tools=None, prior_messages=None, force_final=False, **_):
        turns.append(prior_messages)
        if prior_messages is None:
            _enqueue(engine_ref["e"], "follow_up", "再比较一下同行")
            return _tool_calls("quote")
        if not is_queued_human_message(prior_messages[-1]):
            # follow-up must NOT be injected while tools are still in flight
            assert all(not is_queued_human_message(m) for m in prior_messages)
            return _final("first answer")
        return _final("peer comparison")

    engine = _engine(mock_node_executor, [quote], fake)
    engine_ref["e"] = engine
    mock_node_executor.execute = AsyncMock(
        return_value=NodeResult(ok=True, status="completed", handler="logic", node_id="after",
                                node_name="After", subject="s", summary="after ran", duration_ms=1)
    )
    nodes, edges = _layout(with_next=True)
    events = await _collect(engine.stream_workflow(nodes, edges, {}, "s", thread_id=TID, owner=OWNER))

    assert len(turns) == 3  # tools turn, final, follow-up turn
    follow_prior = turns[2]
    assert follow_prior[0].content == "analyse"  # same slice: seed human kept
    assert follow_prior[-1].content == "再比较一下同行"
    assert any(isinstance(m, AIMessage) and "first answer" in str(m.content) for m in follow_prior)
    node_updates = [e.data["nodeId"] for e in events if e.event == "node_update"]
    assert "after" in node_updates
    # the follow-up turn happens before the graph moves on to the successor
    assert node_updates.index("after") > max(i for i, n in enumerate(node_updates) if n == "agent")
    assert not any(str(n).startswith("inbox__") for n in node_updates)
    dequeued = [e.data for e in events if e.event == "message_dequeued"]
    assert [d["kind"] for d in dequeued] == ["follow_up"]
    final = [e for e in events if e.event == "workflow_complete"][0].data
    assert final["messageQueue"]["delivered"] == 1


@pytest.mark.asyncio
@pytest.mark.parametrize("mode,expected_llm_calls", [("one-at-a-time", 3), ("all", 2)])
async def test_follow_up_modes(mock_node_executor, mode, expected_llm_calls):
    engine_ref: dict = {}
    batches: list[list[str]] = []

    @tool
    def quote() -> str:
        """Fetch a quote."""
        return "q"

    async def fake(node, state, subject="", tools=None, prior_messages=None, force_final=False, **_):
        if prior_messages is None:
            _enqueue(engine_ref["e"], "follow_up", "f1")
            _enqueue(engine_ref["e"], "follow_up", "f2")
            return _final("first")
        tail = []
        for m in reversed(prior_messages):
            if not is_queued_human_message(m):
                break
            tail.insert(0, m.content)
        batches.append(tail)
        return _final("next")

    engine = _engine(mock_node_executor, [quote], fake, follow_up_mode=mode)
    engine_ref["e"] = engine
    nodes, edges = _layout()
    result = await engine.execute_workflow(nodes, edges, {}, "s", thread_id=TID, owner=OWNER)
    assert mock_node_executor.invoke_agent_with_tools.await_count == expected_llm_calls
    if mode == "all":
        assert batches == [["f1", "f2"]]
    else:
        assert batches == [["f1"], ["f2"]]
    assert result.final_state["messageQueue"]["delivered"] == 2


@pytest.mark.asyncio
async def test_no_queue_activity_keeps_final_state_unchanged(mock_node_executor):
    @tool
    def quote() -> str:
        """Fetch a quote."""
        return "q"

    async def fake(node, state, subject="", tools=None, prior_messages=None, force_final=False, **_):
        return _tool_calls("quote") if prior_messages is None else _final("ok")

    engine = _engine(mock_node_executor, [quote], fake)
    nodes, edges = _layout()
    result = await engine.execute_workflow(nodes, edges, {}, "s", thread_id=TID, owner=OWNER)
    assert result.ok is True
    assert "messageQueue" not in result.final_state
    # the run is over: further messages are not accepted (Java starts a new turn)
    with pytest.raises(MessageQueueError) as exc:
        _enqueue(engine, "follow_up", "late")
    assert exc.value.code == "NOT_ACTIVE"


@pytest.mark.asyncio
async def test_disabled_queue_builds_plain_graph(mock_node_executor):
    @tool
    def quote() -> str:
        """Fetch a quote."""
        return "q"

    async def fake(node, state, subject="", tools=None, prior_messages=None, force_final=False, **_):
        return _tool_calls("quote") if prior_messages is None else _final("ok")

    engine = _engine(mock_node_executor, [quote], fake, enabled=False)
    nodes, edges = _layout()
    graph = engine.build_graph(nodes, edges)
    assert not any(n.startswith("inbox__") for n in graph.get_graph().nodes)
    result = await engine.execute_workflow(nodes, edges, {}, "s", thread_id=TID, owner=OWNER)
    assert result.ok is True


# ── abort / ownership / approval gate ──────────────────────────────────

@pytest.mark.asyncio
async def test_client_abort_closes_queue_and_discards_pending(mock_node_executor):
    engine_ref: dict = {}

    @tool
    def quote() -> str:
        """Fetch a quote."""
        return "q"

    async def fake(node, state, subject="", tools=None, prior_messages=None, force_final=False, **_):
        _enqueue(engine_ref["e"], "follow_up", "never delivered")
        return _tool_calls("quote", round_no=1 if prior_messages is None else 2)

    engine = _engine(mock_node_executor, [quote], fake, max_pending_per_kind=8)
    engine_ref["e"] = engine
    nodes, edges = _layout()
    gen = engine.stream_workflow(nodes, edges, {}, "s", thread_id=TID, owner=OWNER)
    first = await gen.__anext__()
    assert first.event == "message_queued"
    await gen.aclose()  # client disconnect

    queue = engine.message_queues.get(TID)
    summary = queue.summary()
    assert summary["state"] == "closed" and summary["closeReason"] == "aborted"
    assert [m["content"] for m in summary["undelivered"]] == ["never delivered"]
    with pytest.raises(MessageQueueError) as exc:
        _enqueue(engine, "steer", "too late")
    assert exc.value.code == "NOT_ACTIVE"


@pytest.mark.asyncio
async def test_foreign_owner_cannot_steer_running_agent(mock_node_executor):
    engine_ref: dict = {}
    errors: list[str] = []

    @tool
    def quote() -> str:
        """Fetch a quote."""
        return "q"

    async def fake(node, state, subject="", tools=None, prior_messages=None, force_final=False, **_):
        try:
            _enqueue(engine_ref["e"], "steer", "hijack", owner={"userId": "u2", "tenantId": "t1"})
        except MessageQueueError as e:
            errors.append(e.code)
        return _final("ok")

    engine = _engine(mock_node_executor, [quote], fake)
    engine_ref["e"] = engine
    nodes, edges = _layout()
    result = await engine.execute_workflow(nodes, edges, {}, "s", thread_id=TID, owner=OWNER)
    assert errors == ["OWNER_MISMATCH"]
    assert mock_node_executor.invoke_agent_with_tools.await_count == 1
    assert "messageQueue" not in result.final_state


@pytest.mark.asyncio
async def test_queue_cannot_bypass_approval_gate(mock_node_executor):
    from langgraph.checkpoint.memory import MemorySaver

    @tool
    def quote() -> str:
        """Fetch a quote."""
        return "q"

    async def fake(node, state, subject="", tools=None, prior_messages=None, force_final=False, **_):
        return _final("analysis")

    WorkflowEngine._graph_cache.clear()
    mock_node_executor.invoke_agent_with_tools = AsyncMock(side_effect=fake)
    mock_node_executor.execute = AsyncMock(
        return_value=NodeResult(ok=True, status="completed", handler="finance.stock_recommendation_aggregate",
                                node_id="aggregate", node_name="Report", subject="s", summary="report",
                                duration_ms=1)
    )
    engine = WorkflowEngine(
        mock_node_executor, tools=[quote], checkpointer=MemorySaver(),
        message_queues=MessageQueueRegistry(MessageQueueSettings()),
    )
    nodes = [
        Node(id="agent", data=NodeData(handler="general.agent")),
        Node(id="aggregate", data=NodeData(handler="finance.stock_recommendation_aggregate")),
    ]
    edges = [Edge(source="agent", target="aggregate")]
    events = await _collect(engine.stream_workflow(
        nodes, edges, {}, "s", require_approval=True, thread_id=TID, owner=OWNER))
    assert events[-1].event == "human_interrupt"

    queue = engine.message_queues.get(TID)
    assert queue.state == "paused"
    with pytest.raises(MessageQueueError) as exc:
        _enqueue(engine, "follow_up", "approved, go ahead")
    assert exc.value.code == "RUN_PAUSED"
    # graph is still waiting on the gate; the aggregate never ran
    snapshot = await engine.build_graph(nodes, edges, require_approval=True).aget_state(
        {"configurable": {"thread_id": TID}})
    assert "approval_gate" in snapshot.next
    assert mock_node_executor.execute.await_count == 0

    resumed = await engine.resume_workflow(TID, {"approved": True})
    assert resumed.ok is True
    assert mock_node_executor.execute.await_count == 1
    assert queue.state == "closed" and queue.close_reason == "completed"


@pytest.mark.asyncio
async def test_execute_interrupt_pauses_queue_instead_of_closing(mock_node_executor):
    from langgraph.checkpoint.memory import MemorySaver

    @tool
    def quote() -> str:
        """Fetch a quote."""
        return "q"

    async def fake(node, state, subject="", tools=None, prior_messages=None, force_final=False, **_):
        return _final("analysis")

    WorkflowEngine._graph_cache.clear()
    mock_node_executor.invoke_agent_with_tools = AsyncMock(side_effect=fake)
    mock_node_executor.execute = AsyncMock(
        return_value=NodeResult(ok=True, status="completed", handler="finance.stock_recommendation_aggregate",
                                node_id="aggregate", node_name="Report", subject="s", summary="report",
                                duration_ms=1)
    )
    engine = WorkflowEngine(
        mock_node_executor, tools=[quote], checkpointer=MemorySaver(),
        message_queues=MessageQueueRegistry(MessageQueueSettings()),
    )
    nodes = [
        Node(id="agent", data=NodeData(handler="general.agent")),
        Node(id="aggregate", data=NodeData(handler="finance.stock_recommendation_aggregate")),
    ]
    edges = [Edge(source="agent", target="aggregate")]
    result = await engine.execute_workflow(
        nodes, edges, {}, "s", require_approval=True, thread_id=TID, owner=OWNER)
    assert result.final_state.get("interrupted") is True
    assert engine.message_queues.get(TID).state == "paused"
    done = await engine.resume_workflow(TID, {"approved": True})
    assert done.ok is True and engine.message_queues.get(TID).state == "closed"


# ── helpers ────────────────────────────────────────────────────────────

def test_continuation_slice_accepts_trailing_queued_messages():
    from app.core.message_queue import QueuedMessage, to_human_messages

    [queued] = to_human_messages([QueuedMessage(id="q1", kind="follow_up", content="more")])
    msgs = [
        HumanMessage(content="old"),
        AIMessage(content="old answer"),
        HumanMessage(content="seed"),
        AIMessage(content="", tool_calls=[{"name": "ping", "args": {}, "id": "c1"}]),
        ToolMessage(content="pong", tool_call_id="c1"),
        AIMessage(content="final"),
        queued,
    ]
    cont = _messages_for_tool_continuation(msgs)
    assert cont[0].content == "seed"
    assert cont[-1] is queued
    # an un-marked trailing human message is not a continuation
    assert _messages_for_tool_continuation(msgs[:-1] + [HumanMessage(content="x")]) == []
