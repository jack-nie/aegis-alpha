"""P2-14: parallel fan-out → deferred fan-in, and one market-data fetch per symbol per run."""
import asyncio

import pytest
from langchain_core.messages import AIMessage, HumanMessage, ToolMessage
from langchain_core.tools import tool
from langgraph.checkpoint.memory import MemorySaver

from app.core.market_data import MarketDataService, run_scoped_market_cache
from app.core.research_graph import build_single_name_research_layout
from app.core.workflow_engine import WorkflowEngine
from app.models.responses import NodeResult
from app.models.workflow import Edge, Node


@tool
def lookup(tag: str) -> str:
    """Look something up."""
    return f"looked:{tag}"


@pytest.fixture(autouse=True)
def _clear_cache():
    WorkflowEngine._graph_cache.clear()
    yield
    WorkflowEngine._graph_cache.clear()


def _layout(**kwargs):
    layout = build_single_name_research_layout(**kwargs)
    return (
        [Node.model_validate(n) for n in layout["nodes"]],
        [Edge.model_validate(e) for e in layout["edges"]],
    )


def _install_recording_executor(executor, order: list, market: MarketDataService | None = None):
    from app.core.llm_client import AgentToolResult, LLMResponse

    async def execute(node, state, subject="", *args, **kwargs):
        if market is not None and node.data.handler.startswith("finance.") and "aggregate" not in node.data.handler:
            await market.hydrate(node.data.handler, state, subject, node.model_dump())
        await asyncio.sleep(0.01)
        order.append(node.id)
        return NodeResult(ok=True, status="completed", handler=node.data.handler, node_id=node.id,
                          summary=f"s-{node.id}")

    async def invoke_agent(node, state, subject="", tools=None, prior_messages=None, force_final=False, **_):
        if not prior_messages:
            order.append(f"{node.id}:tool_call")
            return AgentToolResult(
                AIMessage(content="", tool_calls=[{"name": "lookup", "args": {"tag": node.id}, "id": "c1"}]),
                has_tool_calls=True,
                seed_human=HumanMessage(content="go"),
            )
        assert any(isinstance(m, ToolMessage) for m in prior_messages)
        order.append(node.id)
        return AgentToolResult(AIMessage(content="done"), has_tool_calls=False,
                               llm_response=LLMResponse(summary=f"s-{node.id}", confidence=0.6))

    executor.execute = execute
    executor.invoke_agent_with_tools = invoke_agent


@pytest.mark.asyncio
async def test_aggregate_runs_once_after_all_specialists_including_tool_loop(mock_node_executor):
    order: list = []
    _install_recording_executor(mock_node_executor, order)
    engine = WorkflowEngine(mock_node_executor, tools=[lookup], checkpointer=MemorySaver())
    nodes, edges = _layout(include_portfolio=True)

    result = await engine.execute_workflow(nodes, edges, {"ticker": "AAPL"}, "AAPL")

    assert result.ok, result.error
    assert order.count("aggregate") == 1
    specialists = ["fundamentals", "news", "valuation", "risk", "portfolio_context"]
    for specialist in specialists:
        assert order.index(specialist) < order.index("aggregate"), order
    # The four analysts ran in the first parallel superstep (before the agent's second turn).
    assert order[0] == "start"
    assert set(order[1:6]) == {"fundamentals", "news", "valuation", "risk", "portfolio_context:tool_call"}
    assert order[-2:] == ["aggregate", "end"]


@pytest.mark.asyncio
async def test_parallel_layout_with_approval_interrupts_once_then_aggregates(mock_node_executor):
    order: list = []
    _install_recording_executor(mock_node_executor, order)
    engine = WorkflowEngine(mock_node_executor, tools=[lookup], checkpointer=MemorySaver())
    nodes, edges = _layout(include_portfolio=True)

    paused = await engine.execute_workflow(nodes, edges, {"ticker": "AAPL"}, "AAPL",
                                           require_approval=True, thread_id="par-1")
    assert paused.ok and paused.final_state.get("interrupted") is True
    assert "aggregate" not in order
    assert "portfolio_context" in order

    resumed = await engine.resume_workflow("par-1", {"approved": True})
    assert resumed.ok, resumed.error
    assert order.count("aggregate") == 1


@pytest.mark.asyncio
async def test_market_data_fetched_once_per_symbol_per_run(mock_node_executor, mock_settings):
    market = MarketDataService(mock_settings)
    calls: list = []

    async def fake_fetch(ticker, resolved, node):
        calls.append((ticker, node.get("id")))
        await asyncio.sleep(0.02)
        return {"ok": True, "price": 1.0}

    market._fetch_quote = fake_fetch
    order: list = []
    _install_recording_executor(mock_node_executor, order, market=market)
    engine = WorkflowEngine(mock_node_executor, checkpointer=MemorySaver())
    nodes, edges = _layout()

    result = await engine.execute_workflow(nodes, edges, {"ticker": "AAPL"}, "AAPL")
    assert result.ok, result.error
    assert len(calls) == 1, calls  # four parallel specialists share one fetch

    events = [e async for e in engine.stream_workflow(nodes, edges, {"ticker": "AAPL"}, "AAPL")]
    assert events[-1].event == "workflow_complete"
    assert len(calls) == 2  # a new run fetches again


@pytest.mark.asyncio
async def test_run_cache_scope_rules(mock_settings):
    market = MarketDataService(mock_settings)
    results = iter([{"ok": False, "status": "unavailable"}, {"ok": True, "price": 2.0}, {"ok": True, "price": 3.0}])
    calls: list = []

    async def fake_fetch(ticker, resolved, node):
        calls.append(ticker)
        return next(results)

    market._fetch_quote = fake_fetch
    state = {"ticker": "AAPL"}
    with run_scoped_market_cache():
        first = await market.hydrate("finance.market_analysis", state, "AAPL", {"id": "a"})
        second = await market.hydrate("finance.market_analysis", state, "AAPL", {"id": "b"})
        third = await market.hydrate("finance.market_analysis", state, "AAPL", {"id": "c"})
        second["price"] = 99  # callers get copies
        fourth = await market.hydrate("finance.market_analysis", state, "AAPL", {"id": "d"})
    assert first["ok"] is False  # failures are not pinned for the run
    assert third["price"] == 2.0 and fourth["price"] == 2.0
    assert len(calls) == 2
    outside = await market.hydrate("finance.market_analysis", state, "AAPL", {"id": "e"})
    assert outside["price"] == 3.0 and len(calls) == 3
