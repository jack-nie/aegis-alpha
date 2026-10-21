"""Tests for workflow engine."""

import pytest
from unittest.mock import AsyncMock, MagicMock, patch

from app.models.workflow import Node, NodeData, Edge


def test_topology_hash(mock_node_executor):
    from app.core.workflow_engine import WorkflowEngine
    engine = WorkflowEngine(mock_node_executor)
    nodes = [
        Node(id="start", data=NodeData(handler="start")),
        Node(id="end", data=NodeData(handler="end")),
    ]
    edges = [Edge(source="start", target="end")]
    h1 = engine._topology_hash(nodes, edges)
    h2 = engine._topology_hash(nodes, edges)
    assert h1 == h2


def test_topology_hash_different(mock_node_executor):
    from app.core.workflow_engine import WorkflowEngine
    engine = WorkflowEngine(mock_node_executor)
    nodes1 = [Node(id="start", data=NodeData(handler="start"))]
    nodes2 = [Node(id="start", data=NodeData(handler="start")), Node(id="end", data=NodeData(handler="end"))]
    h1 = engine._topology_hash(nodes1, [])
    h2 = engine._topology_hash(nodes2, [])
    assert h1 != h2


def test_topology_hash_changes_when_prompt_changes(mock_node_executor):
    from app.core.workflow_engine import WorkflowEngine
    engine = WorkflowEngine(mock_node_executor)
    nodes_a = [Node(id="agent", data=NodeData(handler="general.agent", prompt="analyze A"))]
    nodes_b = [Node(id="agent", data=NodeData(handler="general.agent", prompt="analyze B"))]
    edges = []
    assert engine._topology_hash(nodes_a, edges) != engine._topology_hash(nodes_b, edges)


def test_topology_hash_changes_when_handler_changes(mock_node_executor):
    from app.core.workflow_engine import WorkflowEngine
    engine = WorkflowEngine(mock_node_executor)
    nodes_a = [Node(id="n1", data=NodeData(handler="finance.market_analysis"))]
    nodes_b = [Node(id="n1", data=NodeData(handler="finance.technical_analysis"))]
    edges = []
    assert engine._topology_hash(nodes_a, edges) != engine._topology_hash(nodes_b, edges)


def test_topology_hash_changes_when_agent_id_changes(mock_node_executor):
    from app.core.workflow_engine import WorkflowEngine
    engine = WorkflowEngine(mock_node_executor)
    nodes_a = [Node(id="n1", data=NodeData(handler="general.agent", agentId="agent-a"))]
    nodes_b = [Node(id="n1", data=NodeData(handler="general.agent", agentId="agent-b"))]
    assert engine._topology_hash(nodes_a, []) != engine._topology_hash(nodes_b, [])


def test_graph_cache_hit(mock_node_executor):
    from app.core.workflow_engine import WorkflowEngine
    engine = WorkflowEngine(mock_node_executor)
    nodes = [
        Node(id="start", data=NodeData(handler="start", nodeType="start")),
        Node(id="end", data=NodeData(handler="end", nodeType="end")),
    ]
    edges = [Edge(source="start", target="end")]
    g1 = engine.build_graph(nodes, edges)
    g2 = engine.build_graph(nodes, edges)
    assert g1 is g2


def test_graph_cache_miss_on_prompt_change(mock_node_executor):
    from app.core.workflow_engine import WorkflowEngine
    engine = WorkflowEngine(mock_node_executor)
    nodes_a = [
        Node(id="start", data=NodeData(handler="start", nodeType="start")),
        Node(id="agent", data=NodeData(handler="general.agent", prompt="v1")),
    ]
    nodes_b = [
        Node(id="start", data=NodeData(handler="start", nodeType="start")),
        Node(id="agent", data=NodeData(handler="general.agent", prompt="v2")),
    ]
    edges = [Edge(source="start", target="agent")]
    g1 = engine.build_graph(nodes_a, edges)
    g2 = engine.build_graph(nodes_b, edges)
    assert g1 is not g2


def test_build_graph_linear(mock_node_executor):
    from app.core.workflow_engine import WorkflowEngine
    engine = WorkflowEngine(mock_node_executor)
    nodes = [
        Node(id="start", data=NodeData(handler="start", nodeType="start")),
        Node(id="end", data=NodeData(handler="end", nodeType="end")),
    ]
    edges = [Edge(source="start", target="end")]
    graph = engine.build_graph(nodes, edges)
    assert graph is not None


def test_build_graph_with_condition(mock_node_executor):
    from app.core.workflow_engine import WorkflowEngine
    engine = WorkflowEngine(mock_node_executor)
    nodes = [
        Node(id="start", data=NodeData(handler="start", nodeType="start")),
        Node(id="market", data=NodeData(handler="finance.market_analysis")),
        Node(id="end", data=NodeData(handler="end", nodeType="end")),
    ]
    edges = [
        Edge(source="start", target="market", condition={"field": "trend", "value": "bullish"}),
        Edge(source="market", target="end"),
    ]
    graph = engine.build_graph(nodes, edges)
    assert graph is not None


def test_build_graph_with_approval(mock_node_executor):
    from app.core.workflow_engine import WorkflowEngine
    engine = WorkflowEngine(mock_node_executor)
    nodes = [
        Node(id="market", data=NodeData(handler="finance.market_analysis")),
        Node(id="aggregate", data=NodeData(handler="finance.stock_recommendation_aggregate")),
    ]
    edges = [Edge(source="market", target="aggregate")]
    graph = engine.build_graph(nodes, edges, require_approval=True)
    assert graph is not None


def test_agent_single_successor_routing_map_includes_next(mock_node_executor):
    """Single-successor agent must register agent__next in the conditional path map."""
    from app.core.workflow_engine import WorkflowEngine
    from langgraph.graph import StateGraph
    from langchain_core.tools import tool

    @tool
    def ping() -> str:
        """Ping tool for routing tests."""
        return "pong"

    # Avoid class-level graph cache hiding the build path under test.
    WorkflowEngine._graph_cache.clear()

    captured = []
    original = StateGraph.add_conditional_edges

    def wrapper(self, source, path, path_map=None, **kwargs):
        if path_map is not None:
            captured.append({"source": source, "path_map": dict(path_map)})
            return original(self, source, path, path_map, **kwargs)
        return original(self, source, path, **kwargs)

    engine = WorkflowEngine(mock_node_executor, tools=[ping])
    nodes = [
        Node(id="agent", data=NodeData(handler="general.agent", nodeType="agent")),
        Node(id="next_node", data=NodeData(handler="end", nodeType="end")),
    ]
    edges = [Edge(source="agent", target="next_node")]

    with patch.object(StateGraph, "add_conditional_edges", wrapper):
        graph = engine.build_graph(nodes, edges)
        assert graph is not None

    agent_entries = [c for c in captured if c["source"] == "agent"]
    assert len(agent_entries) == 1
    path_map = agent_entries[0]["path_map"]
    assert path_map.get("tools") == "tools"
    assert path_map.get("agent__next") == "next_node"


def test_build_graph_with_general_agent_and_registry(mock_node_executor):
    """Per-role registry mode: general.agent builds without crashing."""
    from app.config import Settings
    from app.core.tool_registry import create_tool_registry
    from app.core.workflow_engine import WorkflowEngine
    from langgraph.graph import StateGraph

    WorkflowEngine._graph_cache.clear()
    registry = create_tool_registry(Settings(AEGIS_ALPHA_LANGCHAIN_API_KEY="test"))

    captured = []
    original = StateGraph.add_conditional_edges

    def wrapper(self, source, path, path_map=None, **kwargs):
        if path_map is not None:
            captured.append({"source": source, "path_map": dict(path_map)})
            return original(self, source, path, path_map, **kwargs)
        return original(self, source, path, **kwargs)

    engine = WorkflowEngine(mock_node_executor, tool_registry=registry)
    nodes = [
        Node(
            id="agent",
            data=NodeData(
                handler="general.agent",
                nodeType="agent",
                agentId="specialist_fundamentals",
            ),
        ),
        Node(id="next_node", data=NodeData(handler="end", nodeType="end")),
    ]
    edges = [Edge(source="agent", target="next_node")]

    with patch.object(StateGraph, "add_conditional_edges", wrapper):
        graph = engine.build_graph(nodes, edges)
        assert graph is not None

    agent_entries = [c for c in captured if c["source"] == "agent"]
    assert len(agent_entries) == 1
    path_map = agent_entries[0]["path_map"]
    assert path_map.get("tools__agent") == "tools__agent"
    assert path_map.get("agent__next") == "next_node"
    assert "tools" not in path_map


def test_per_agent_tools_nodes_for_multiple_specialists(mock_node_executor):
    """Two specialists get distinct tools__{id} routing targets."""
    from app.config import Settings
    from app.core.tool_registry import create_tool_registry
    from app.core.workflow_engine import WorkflowEngine
    from langgraph.graph import StateGraph

    WorkflowEngine._graph_cache.clear()
    registry = create_tool_registry(Settings(AEGIS_ALPHA_LANGCHAIN_API_KEY="test"))

    captured = []
    original = StateGraph.add_conditional_edges

    def wrapper(self, source, path, path_map=None, **kwargs):
        if path_map is not None:
            captured.append({"source": source, "path_map": dict(path_map)})
            return original(self, source, path, path_map, **kwargs)
        return original(self, source, path, **kwargs)

    engine = WorkflowEngine(mock_node_executor, tool_registry=registry)
    nodes = [
        Node(
            id="fund_agent",
            data=NodeData(handler="general.agent", agentId="specialist_fundamentals"),
        ),
        Node(
            id="news_agent",
            data=NodeData(handler="general.agent", agentId="specialist_news"),
        ),
        Node(id="end", data=NodeData(handler="end", nodeType="end")),
    ]
    edges = [
        Edge(source="fund_agent", target="news_agent"),
        Edge(source="news_agent", target="end"),
    ]

    with patch.object(StateGraph, "add_conditional_edges", wrapper):
        graph = engine.build_graph(nodes, edges)
        assert graph is not None

    fund_map = next(c["path_map"] for c in captured if c["source"] == "fund_agent")
    news_map = next(c["path_map"] for c in captured if c["source"] == "news_agent")
    assert fund_map.get("tools__fund_agent") == "tools__fund_agent"
    assert news_map.get("tools__news_agent") == "tools__news_agent"


def test_topological_sort(mock_node_executor):
    from app.core.workflow_engine import WorkflowEngine
    engine = WorkflowEngine(mock_node_executor)
    nodes = [
        Node(id="start", data=NodeData(handler="start")),
        Node(id="market", data=NodeData(handler="finance.market_analysis")),
        Node(id="end", data=NodeData(handler="end")),
    ]
    edges = [
        Edge(source="start", target="market"),
        Edge(source="market", target="end"),
    ]
    sorted_nodes = WorkflowEngine.topological_sort(nodes, edges)
    ids = [n.id for n in sorted_nodes]
    assert ids.index("start") < ids.index("market")
    assert ids.index("market") < ids.index("end")


def test_deep_merge():
    from app.core.workflow_engine import deep_merge
    result = deep_merge({"a": 1}, {"b": 2})
    assert result == {"a": 1, "b": 2}


def test_deep_merge_nested():
    from app.core.workflow_engine import deep_merge
    result = deep_merge({"a": {"x": 1}}, {"a": {"y": 2}})
    assert result == {"a": {"x": 1, "y": 2}}


def test_deep_merge_overwrite():
    from app.core.workflow_engine import deep_merge
    result = deep_merge({"a": 1}, {"a": 2})
    assert result == {"a": 2}


def test_concat_lists():
    from app.core.workflow_engine import concat_lists
    assert concat_lists([1], [2]) == [1, 2]
    assert concat_lists([], [1]) == [1]


@pytest.mark.asyncio
async def test_resume_preserves_require_approval_from_thread_meta(mock_node_executor):
    """Resume must rebuild the graph with the require_approval used at start."""
    from app.core.workflow_engine import WorkflowEngine

    engine = WorkflowEngine(mock_node_executor)
    nodes = [
        Node(id="market", data=NodeData(handler="finance.market_analysis")),
        Node(id="aggregate", data=NodeData(handler="finance.stock_recommendation_aggregate")),
    ]
    edges = [Edge(source="market", target="aggregate")]
    thread_id = "thread-approval-1"
    engine._remember_thread(thread_id, require_approval=True, nodes=nodes, edges=edges)

    mock_graph = MagicMock()
    mock_graph.ainvoke = AsyncMock(
        return_value={
            "final_state": {"approval_status": "approved"},
            "trace": [],
            "node_outputs": {},
        }
    )

    with patch.object(engine, "build_graph", return_value=mock_graph) as mock_build:
        result = await engine.resume_workflow(
            thread_id=thread_id,
            resume_value={"approved": True},
        )

    assert result.ok is True
    mock_build.assert_called_once()
    call_args = mock_build.call_args
    assert call_args.kwargs.get("require_approval") is True or (
        len(call_args.args) >= 3 and call_args.args[2] is True
    )


@pytest.mark.asyncio
async def test_resume_explicit_require_approval_overrides_meta(mock_node_executor):
    from app.core.workflow_engine import WorkflowEngine

    engine = WorkflowEngine(mock_node_executor)
    nodes = [
        Node(id="market", data=NodeData(handler="finance.market_analysis")),
        Node(id="aggregate", data=NodeData(handler="finance.stock_recommendation_aggregate")),
    ]
    edges = [Edge(source="market", target="aggregate")]
    thread_id = "thread-approval-2"
    engine._remember_thread(thread_id, require_approval=False, nodes=nodes, edges=edges)

    mock_graph = MagicMock()
    mock_graph.ainvoke = AsyncMock(
        return_value={"final_state": {}, "trace": [], "node_outputs": {}}
    )

    with patch.object(engine, "build_graph", return_value=mock_graph) as mock_build:
        result = await engine.resume_workflow(
            thread_id=thread_id,
            resume_value={"approved": True},
            require_approval=True,
        )

    assert result.ok is True
    call_args = mock_build.call_args
    assert call_args.kwargs.get("require_approval") is True or (
        len(call_args.args) >= 3 and call_args.args[2] is True
    )


@pytest.mark.asyncio
async def test_resume_defaults_require_approval_true_without_meta(mock_node_executor):
    from app.core.workflow_engine import WorkflowEngine

    engine = WorkflowEngine(mock_node_executor)
    nodes = [
        Node(id="market", data=NodeData(handler="finance.market_analysis")),
        Node(id="aggregate", data=NodeData(handler="finance.stock_recommendation_aggregate")),
    ]
    edges = [Edge(source="market", target="aggregate")]

    mock_graph = MagicMock()
    mock_graph.ainvoke = AsyncMock(
        return_value={"final_state": {}, "trace": [], "node_outputs": {}}
    )

    with patch.object(engine, "build_graph", return_value=mock_graph) as mock_build:
        result = await engine.resume_workflow(
            thread_id="unknown-thread",
            resume_value={"approved": True},
            nodes=nodes,
            edges=edges,
        )

    assert result.ok is True
    call_args = mock_build.call_args
    assert call_args.kwargs.get("require_approval") is True or (
        len(call_args.args) >= 3 and call_args.args[2] is True
    )


def test_messages_for_tool_continuation_empty():
    from app.core.workflow_engine import _messages_for_tool_continuation

    assert _messages_for_tool_continuation([]) == []
    assert _messages_for_tool_continuation([MagicMock()]) == []


def test_messages_for_tool_continuation_slices_from_human():
    from langchain_core.messages import AIMessage, HumanMessage, ToolMessage
    from app.core.workflow_engine import _messages_for_tool_continuation

    msgs = [
        HumanMessage(content="old"),
        AIMessage(content="done"),
        HumanMessage(content="new task"),
        AIMessage(
            content="",
            tool_calls=[{"name": "ping", "args": {}, "id": "c1"}],
        ),
        ToolMessage(content="pong", tool_call_id="c1"),
    ]
    cont = _messages_for_tool_continuation(msgs)
    assert len(cont) == 3
    assert cont[0].content == "new task"
    assert isinstance(cont[-1], ToolMessage)


@pytest.mark.asyncio
async def test_agent_tool_loop_calls_tool_once_then_final(mock_node_executor):
    """LLM requests a tool once; after ToolNode, agent returns final text."""
    from langchain_core.messages import AIMessage
    from langchain_core.tools import tool
    from app.core.llm_client import AgentToolResult, LLMResponse
    from app.core.workflow_engine import MAX_TOOL_ROUNDS, WorkflowEngine

    WorkflowEngine._graph_cache.clear()

    @tool
    def ping() -> str:
        """Return pong for tool-loop tests."""
        return "pong"

    call_count = {"n": 0}

    async def fake_invoke_agent_with_tools(
        node,
        state,
        subject="",
        tools=None,
        prior_messages=None,
        force_final=False,
        agent=None,
        model=None,
    ):
        from langchain_core.messages import HumanMessage

        call_count["n"] += 1
        if call_count["n"] == 1:
            assert force_final is False
            assert prior_messages in (None, [])
            ai = AIMessage(
                content="",
                tool_calls=[{"name": "ping", "args": {}, "id": "call_ping_1"}],
            )
            return AgentToolResult(
                ai,
                has_tool_calls=True,
                seed_human=HumanMessage(content="use ping"),
            )
        # Second turn (after tools) or force_final: final JSON-ish summary
        assert prior_messages is not None or force_final
        if prior_messages:
            assert any(type(m).__name__ == "ToolMessage" for m in prior_messages)
        ai = AIMessage(content='{"summary": "ping said pong", "confidence": 0.9}')
        return AgentToolResult(
            ai,
            has_tool_calls=False,
            llm_response=LLMResponse(
                summary="ping said pong",
                confidence=0.9,
                content="ping said pong",
            ),
            seed_human=None,
        )

    mock_node_executor.invoke_agent_with_tools = AsyncMock(side_effect=fake_invoke_agent_with_tools)

    engine = WorkflowEngine(mock_node_executor, tools=[ping])
    nodes = [
        Node(id="agent", data=NodeData(handler="general.agent", prompt="use ping")),
        Node(id="end", data=NodeData(handler="end", nodeType="end")),
    ]
    edges = [Edge(source="agent", target="end")]

    result = await engine.execute_workflow(
        nodes=nodes,
        edges=edges,
        state={},
        subject="tool loop test",
    )

    assert result.ok is True
    assert call_count["n"] == 2
    agent_out = (result.node_outputs or {}).get("agent") or (result.final_state or {}).get("agent")
    assert agent_out is not None
    assert "pong" in (agent_out.get("summary") or "")
    assert (result.final_state or {}).get("last_agent_id") == "agent"
    # One tool round recorded
    rounds = (result.final_state or {}).get("tool_rounds") or {}
    assert rounds.get("agent") == 1
    assert MAX_TOOL_ROUNDS == 3


@pytest.mark.asyncio
async def test_agent_tool_loop_caps_at_max_rounds(mock_node_executor):
    """Agent that always requests tools is forced to final after MAX_TOOL_ROUNDS."""
    from langchain_core.messages import AIMessage
    from langchain_core.tools import tool
    from app.core.llm_client import AgentToolResult, LLMResponse
    from app.core.workflow_engine import MAX_TOOL_ROUNDS, WorkflowEngine

    WorkflowEngine._graph_cache.clear()

    @tool
    def ping() -> str:
        """Return pong."""
        return "pong"

    call_ids = {"n": 0}

    async def always_tools(
        node,
        state,
        subject="",
        tools=None,
        prior_messages=None,
        force_final=False,
        agent=None,
        model=None,
    ):
        from langchain_core.messages import HumanMessage

        if force_final:
            ai = AIMessage(content='{"summary": "capped", "confidence": 0.5}')
            return AgentToolResult(
                ai,
                has_tool_calls=False,
                llm_response=LLMResponse(summary="capped", confidence=0.5, content="capped"),
            )
        call_ids["n"] += 1
        ai = AIMessage(
            content="",
            tool_calls=[{"name": "ping", "args": {}, "id": f"call_cap_{call_ids['n']}"}],
        )
        seed = None if prior_messages else HumanMessage(content="cap test")
        return AgentToolResult(ai, has_tool_calls=True, seed_human=seed)

    mock_node_executor.invoke_agent_with_tools = AsyncMock(side_effect=always_tools)

    engine = WorkflowEngine(mock_node_executor, tools=[ping])
    nodes = [
        Node(id="agent", data=NodeData(handler="general.agent")),
        Node(id="end", data=NodeData(handler="end", nodeType="end")),
    ]
    edges = [Edge(source="agent", target="end")]

    result = await engine.execute_workflow(
        nodes=nodes,
        edges=edges,
        state={},
        subject="cap test",
    )

    assert result.ok is True
    # MAX_TOOL_ROUNDS tool requests + 1 force_final turn
    assert mock_node_executor.invoke_agent_with_tools.await_count == MAX_TOOL_ROUNDS + 1
    force_flags = [
        c.kwargs.get("force_final")
        for c in mock_node_executor.invoke_agent_with_tools.await_args_list
    ]
    assert force_flags[-1] is True
    agent_out = (result.node_outputs or {}).get("agent") or (result.final_state or {}).get("agent")
    assert agent_out is not None
    assert "capped" in (agent_out.get("summary") or "")


@pytest.mark.asyncio
async def test_finance_handler_does_not_enter_tool_loop(mock_node_executor):
    """finance.* handlers stay on hydrate+LLM execute path (no tool loop)."""
    from langchain_core.tools import tool
    from app.core.workflow_engine import WorkflowEngine
    from app.models.responses import NodeResult

    WorkflowEngine._graph_cache.clear()

    @tool
    def ping() -> str:
        """Unused tool."""
        return "pong"

    mock_node_executor.execute = AsyncMock(
        return_value=NodeResult(
            ok=True,
            status="completed",
            handler="finance.market_analysis",
            node_id="market",
            node_name="market",
            subject="s",
            summary="finance path",
        )
    )
    mock_node_executor.invoke_agent_with_tools = AsyncMock(
        side_effect=AssertionError("tool loop must not run for finance.*")
    )

    engine = WorkflowEngine(mock_node_executor, tools=[ping])
    nodes = [
        Node(id="market", data=NodeData(handler="finance.market_analysis")),
        Node(id="end", data=NodeData(handler="end", nodeType="end")),
    ]
    edges = [Edge(source="market", target="end")]

    result = await engine.execute_workflow(
        nodes=nodes,
        edges=edges,
        state={},
        subject="finance only",
    )

    assert result.ok is True
    mock_node_executor.invoke_agent_with_tools.assert_not_awaited()
    mock_node_executor.execute.assert_awaited()


def test_workflow_request_accepts_require_approval_alias():
    from app.models.requests import WorkflowRequest

    body = WorkflowRequest.model_validate({
        "requireApproval": True,
        "threadId": "run-1",
        "nodes": [],
        "edges": [],
    })
    assert body.require_approval is True
    assert body.thread_id == "run-1"
    by_name = WorkflowRequest.model_validate({"require_approval": False})
    assert by_name.require_approval is False


@pytest.mark.asyncio
async def test_durable_approval_resume_reloads_layout_after_restart():
    """Approval pause survives a process restart: layout comes from the store, not memory."""
    import os
    import tempfile

    from app.core.checkpointer_lifecycle import close_checkpointer, open_checkpointer
    from app.core.persistent_store import PersistentStore
    from app.core.workflow_engine import WorkflowEngine
    from app.models.responses import NodeResult

    WorkflowEngine._graph_cache.clear()
    close_checkpointer()

    class FakeExecutor:
        def __init__(self):
            self.calls = []

        async def execute(self, node, state, subject="", **kwargs):
            self.calls.append(node.id)
            return NodeResult(
                ok=True,
                summary="node",
                node_id=node.id,
                handler=node.data.handler or "logic",
                node_name=node.id,
                subject=subject or "",
            )

    executor = FakeExecutor()
    nodes = [
        Node(id="start", data=NodeData(handler="start", nodeType="start")),
        Node(id="market", data=NodeData(handler="finance.market_analysis")),
        Node(id="aggregate", data=NodeData(handler="finance.stock_recommendation_aggregate")),
        Node(id="end", data=NodeData(handler="end", nodeType="end")),
    ]
    edges = [
        Edge(source="start", target="market"),
        Edge(source="market", target="aggregate"),
        Edge(source="aggregate", target="end"),
    ]
    thread_id = "durable-approval-thread"
    temp_dir = tempfile.TemporaryDirectory()
    store = None
    store_reopened = None
    try:
        checkpoint_path = os.path.join(temp_dir.name, "checkpoints.db")
        store_path = os.path.join(temp_dir.name, "store.db")
        checkpointer = open_checkpointer(checkpoint_path)
        store = PersistentStore(store_path)
        engine = WorkflowEngine(executor, store=store, checkpointer=checkpointer)

        paused = await engine.execute_workflow(
            nodes=nodes,
            edges=edges,
            state={"ticker": "AAPL"},
            subject="approval resume",
            require_approval=True,
            thread_id=thread_id,
        )

        assert paused.ok is True, paused.error
        assert "market" in executor.calls
        assert "aggregate" not in executor.calls
        assert (paused.final_state or {}).get("approval_status") is None

        calls_before_resume = list(executor.calls)
        engine._thread_meta.clear()
        WorkflowEngine._graph_cache.clear()
        await store.close()
        store = None
        close_checkpointer()

        checkpointer_reopened = open_checkpointer(checkpoint_path)
        store_reopened = PersistentStore(store_path)
        resumed_engine = WorkflowEngine(
            executor,
            store=store_reopened,
            checkpointer=checkpointer_reopened,
        )
        resumed = await resumed_engine.resume_workflow(
            thread_id,
            {"approved": True},
        )

        assert resumed.ok is True, resumed.error
        assert "aggregate" in executor.calls
        assert executor.calls.index("aggregate") >= len(calls_before_resume)
        assert (resumed.final_state or {}).get("approval_status") == "approved"
    finally:
        WorkflowEngine._graph_cache.clear()
        if store is not None:
            await store.close()
        if store_reopened is not None:
            await store_reopened.close()
        close_checkpointer()
        temp_dir.cleanup()

# ---- P1-5: approval gate is fail-closed and rejection skips aggregate ----


async def _paused_engine(thread_id):
    from langgraph.checkpoint.memory import InMemorySaver

    from app.core.workflow_engine import WorkflowEngine
    from app.models.responses import NodeResult

    WorkflowEngine._graph_cache.clear()

    class CountingExecutor:
        def __init__(self):
            self.calls = []

        async def execute(self, node, state, subject="", **kwargs):
            self.calls.append(node.id)
            return NodeResult(ok=True, summary="node", node_id=node.id,
                              handler=node.data.handler or "logic", node_name=node.id, subject=subject or "")

    executor = CountingExecutor()
    engine = WorkflowEngine(executor, checkpointer=InMemorySaver())
    nodes = [
        Node(id="start", data=NodeData(handler="start", nodeType="start")),
        Node(id="market", data=NodeData(handler="finance.market_analysis")),
        Node(id="aggregate", data=NodeData(handler="finance.stock_recommendation_aggregate")),
        Node(id="end", data=NodeData(handler="end", nodeType="end")),
    ]
    edges = [
        Edge(source="start", target="market"),
        Edge(source="market", target="aggregate"),
        Edge(source="aggregate", target="end"),
    ]
    paused = await engine.execute_workflow(
        nodes=nodes, edges=edges, state={"ticker": "AAPL"}, subject="AAPL",
        require_approval=True, thread_id=thread_id,
    )
    assert paused.ok is True, paused.error
    assert "aggregate" not in executor.calls
    return engine, executor


@pytest.mark.asyncio
async def test_rejection_does_not_run_aggregate():
    from app.core.workflow_engine import WorkflowEngine

    engine, executor = await _paused_engine("reject-thread")
    try:
        result = await engine.resume_workflow("reject-thread", {"approved": False, "comment": "no"})
        assert result.ok is True, result.error
        assert executor.calls.count("aggregate") == 0
        assert (result.final_state or {}).get("approval_status") == "rejected"
    finally:
        WorkflowEngine._graph_cache.clear()


@pytest.mark.asyncio
async def test_approval_runs_aggregate_once():
    from app.core.workflow_engine import WorkflowEngine

    engine, executor = await _paused_engine("approve-thread")
    try:
        result = await engine.resume_workflow("approve-thread", {"approved": True})
        assert result.ok is True, result.error
        assert executor.calls.count("aggregate") == 1
        assert (result.final_state or {}).get("approval_status") == "approved"
    finally:
        WorkflowEngine._graph_cache.clear()


@pytest.mark.asyncio
@pytest.mark.parametrize("resume_value", [None, {}, {"approved": "yes"}, {"comment": "lgtm"}, {"approved": 1}])
async def test_resume_without_boolean_decision_is_rejected_and_does_not_approve(resume_value):
    from app.core.workflow_engine import WorkflowEngine

    engine, executor = await _paused_engine("missing-decision-thread")
    try:
        result = await engine.resume_workflow("missing-decision-thread", resume_value)
        assert result.ok is False
        assert "approved" in (result.error or "")
        assert "aggregate" not in executor.calls
    finally:
        WorkflowEngine._graph_cache.clear()


@pytest.mark.asyncio
async def test_resume_router_has_no_default_approval(monkeypatch):
    from app.routers import workflow as workflow_router

    captured = {}

    async def fake_resume(thread_id, resume_value=None, **kwargs):
        captured["resume_value"] = resume_value
        from app.models.responses import WorkflowResult
        return WorkflowResult(ok=False, error="x", final_state={})

    monkeypatch.setattr(workflow_router.workflow_engine, "resume_workflow", fake_resume)
    body = workflow_router.ResumeRequest.model_validate({"threadId": "t-1"})
    await workflow_router.resume_workflow(body)
    assert captured["resume_value"] is None


def test_gate_helpers_are_fail_closed():
    from app.core.workflow_engine import is_explicit_approval

    assert is_explicit_approval({"approved": True}) is True
    for value in (None, {}, {"approved": False}, {"approved": "true"}, {"approved": 1}, "approved"):
        assert is_explicit_approval(value) is False


# ---------------- P1-10: node result cache key + portfolio agents not cached ----------------


def test_node_result_cache_key_binds_prompt_and_is_stable(mock_node_executor):
    from app.core.workflow_engine import WorkflowEngine

    a = Node(id="n1", data=NodeData(handler="finance.market_analysis", prompt="analyze A"))
    a2 = Node(id="n1", data=NodeData(handler="finance.market_analysis", prompt="analyze A"))
    b = Node(id="n1", data=NodeData(handler="finance.market_analysis", prompt="analyze B"))
    key_a = WorkflowEngine._node_result_cache_key(a, "finance.market_analysis")
    assert key_a == WorkflowEngine._node_result_cache_key(a2, "finance.market_analysis")
    assert key_a != WorkflowEngine._node_result_cache_key(b, "finance.market_analysis")
    assert key_a.startswith("finance.market_analysis:v3:")


def test_portfolio_agent_is_not_cacheable(mock_node_executor):
    from app.core.workflow_engine import WorkflowEngine

    class _Tool:
        def __init__(self, name):
            self.name = name

    registry = {
        "get_stock_quote": _Tool("get_stock_quote"),
        "get_portfolio_positions": _Tool("get_portfolio_positions"),
        "get_portfolio_summary": _Tool("get_portfolio_summary"),
    }
    engine = WorkflowEngine(mock_node_executor, tool_registry=registry)
    portfolio_agent = Node(id="pa", data=NodeData(handler="general.agent", agentId="specialist_portfolio"))
    research_agent = Node(id="ra", data=NodeData(handler="general.agent", agentId="specialist_fundamentals"))
    plain_node = Node(id="pn", data=NodeData(handler="finance.portfolio_review"))
    assert engine._node_reads_portfolio(portfolio_agent) is True
    assert engine._node_reads_portfolio(research_agent) is False
    assert engine._node_reads_portfolio(plain_node) is False


@pytest.mark.asyncio
async def test_same_handler_different_prompt_does_not_hit_cache_and_hit_is_not_degraded(tmp_path):
    from langgraph.checkpoint.memory import MemorySaver

    from app.config import Settings
    from app.core.memory_store import MemoryStoreManager
    from app.core.workflow_engine import WorkflowEngine
    from app.models.responses import NodeResult

    calls: list[str] = []

    async def _execute(node, state, subject="", **kwargs):
        calls.append(node.data.prompt or "")
        return NodeResult(
            ok=True, status="completed", handler=node.data.handler, node_id=node.id,
            node_name=node.id, subject=subject, summary=f"ran {node.data.prompt}",
        )

    executor = type("Executor", (), {})()
    executor.execute = AsyncMock(side_effect=_execute)
    manager = MemoryStoreManager(Settings(
        AEGIS_ALPHA_LANGCHAIN_API_KEY="test",
        AEGIS_ALPHA_DATA_DIR=str(tmp_path),
        AEGIS_ALPHA_STORE_TTL_SECONDS=3600,
    ))
    await manager.initialize()

    def _graph(prompt):
        nodes = [
            Node(id="start", data=NodeData(handler="start", nodeType="start")),
            Node(id="n1", data=NodeData(handler="finance.market_analysis", prompt=prompt)),
            Node(id="end", data=NodeData(handler="end", nodeType="end")),
        ]
        edges = [Edge(source="start", target="n1"), Edge(source="n1", target="end")]
        return nodes, edges

    try:
        WorkflowEngine._graph_cache.clear()
        engine = WorkflowEngine(executor, store=manager.store, checkpointer=MemorySaver())
        for prompt in ("prompt A", "prompt B"):
            nodes, edges = _graph(prompt)
            result = await engine.execute_workflow(nodes, edges, {"ticker": "AAPL"}, "AAPL", require_approval=False)
            assert result.ok, result.error
        assert calls.count("prompt A") == 1
        assert calls.count("prompt B") == 1

        nodes, edges = _graph("prompt A")
        result = await engine.execute_workflow(nodes, edges, {"ticker": "AAPL"}, "AAPL", require_approval=False)
        assert result.ok, result.error
        assert calls.count("prompt A") == 1, "identical node should be served from cache"
        hit = [
            {**(t.model_dump() if hasattr(t, "model_dump") else dict(t)), **(getattr(t, "model_extra", None) or {})}
            for t in result.trace
        ]
        n1 = [t for t in hit if t.get("nodeId") == "n1" or t.get("node_id") == "n1"]
        assert n1 and n1[-1].get("cacheHit") is True
        assert n1[-1].get("degraded") is False
    finally:
        await manager.close()
        WorkflowEngine._graph_cache.clear()
