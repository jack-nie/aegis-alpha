"""P2-12: recursion limit, whole-run timeout, shared streaming, bounded thread meta, checkpoint cleanup."""
import asyncio
from unittest.mock import AsyncMock, MagicMock

import pytest
from langgraph.checkpoint.memory import MemorySaver

from app.core.workflow_engine import GraphGuardrails, WorkflowEngine
from app.models.responses import NodeResult
from app.models.workflow import Edge, Node, NodeData


def _chain(n: int):
    nodes = [Node(id="start", data=NodeData(handler="start", nodeType="start"))]
    edges = []
    prev = "start"
    for i in range(n):
        nid = f"n{i}"
        nodes.append(Node(id=nid, data=NodeData(handler="finance.market_analysis", label=nid)))
        edges.append(Edge(source=prev, target=nid))
        prev = nid
    return nodes, edges


def _executor(delay: float = 0.0):
    executor = MagicMock()

    async def execute(node, *args, **kwargs):
        if delay:
            await asyncio.sleep(delay)
        return NodeResult(ok=True, status="completed", handler="logic", node_id=node.id, summary=f"s-{node.id}")

    executor.execute = AsyncMock(side_effect=execute)
    return executor


@pytest.fixture(autouse=True)
def _clear_cache():
    WorkflowEngine._graph_cache.clear()
    yield
    WorkflowEngine._graph_cache.clear()


@pytest.mark.asyncio
async def test_execute_workflow_times_out_with_error():
    engine = WorkflowEngine(_executor(delay=1.0), checkpointer=MemorySaver(),
                            guardrails=GraphGuardrails(timeout_seconds=0.05))
    nodes, edges = _chain(1)
    started = asyncio.get_running_loop().time()
    result = await engine.execute_workflow(nodes, edges, {"ticker": "AAPL"}, "t")
    assert asyncio.get_running_loop().time() - started < 0.8
    assert result.ok is False
    assert "timed out" in (result.error or "")


@pytest.mark.asyncio
@pytest.mark.parametrize("method", ["stream_workflow", "stream_workflow_tokens"])
async def test_streams_emit_timeout_event(method):
    engine = WorkflowEngine(_executor(delay=1.0), checkpointer=MemorySaver(),
                            guardrails=GraphGuardrails(timeout_seconds=0.05))
    nodes, edges = _chain(2)
    events = [e async for e in getattr(engine, method)(nodes, edges, {"ticker": "AAPL"}, "t")]
    assert events[-1].event == "error"
    assert events[-1].data["code"] == "WORKFLOW_TIMEOUT"
    assert not any(e.event == "workflow_complete" for e in events)


@pytest.mark.asyncio
@pytest.mark.parametrize("method", ["stream_workflow", "stream_workflow_tokens"])
async def test_streams_share_event_shape_on_success(method):
    engine = WorkflowEngine(_executor(), checkpointer=MemorySaver())
    nodes, edges = _chain(2)
    events = [e async for e in getattr(engine, method)(nodes, edges, {"ticker": "AAPL"}, "t")]
    assert [e.event for e in events if e.event == "node_update"]
    assert events[-1].event == "workflow_complete"
    assert events[-1].data["ok"] is True
    assert "s-n1" in str(events[-1].data["final_state"])


@pytest.mark.asyncio
async def test_recursion_limit_is_applied():
    engine = WorkflowEngine(_executor(), checkpointer=MemorySaver(),
                            guardrails=GraphGuardrails(recursion_limit=2))
    assert engine._run_config("t")["recursion_limit"] == 2
    nodes, edges = _chain(4)
    result = await engine.execute_workflow(nodes, edges, {"ticker": "AAPL"}, "t")
    assert result.ok is False
    assert "recursion" in (result.error or "").lower()


def test_thread_meta_is_bounded_lru():
    engine = WorkflowEngine(_executor(), checkpointer=MemorySaver(),
                            guardrails=GraphGuardrails(thread_meta_max=3))
    nodes, edges = _chain(1)
    for i in range(5):
        engine._remember_thread(f"t{i}", require_approval=False, nodes=nodes, edges=edges)
    assert list(engine._thread_meta) == ["t2", "t3", "t4"]
    engine._remember_thread("t2", require_approval=True, nodes=nodes, edges=edges)
    engine._remember_thread("t5", require_approval=False, nodes=nodes, edges=edges)
    assert list(engine._thread_meta) == ["t4", "t2", "t5"]


@pytest.mark.asyncio
@pytest.mark.parametrize("cleanup", [True, False])
async def test_checkpoint_cleanup_after_terminal_state_is_configurable(cleanup):
    saver = MemorySaver()
    engine = WorkflowEngine(_executor(), checkpointer=saver,
                            guardrails=GraphGuardrails(cleanup_checkpoints=cleanup))
    nodes, edges = _chain(1)
    result = await engine.execute_workflow(nodes, edges, {"ticker": "AAPL"}, "t", thread_id="run-x")
    assert result.ok
    tuple_ = await saver.aget_tuple({"configurable": {"thread_id": "run-x"}})
    assert (tuple_ is None) is cleanup
    assert ("run-x" in engine._thread_meta) is (not cleanup)


def test_guardrails_from_settings():
    from app.config import Settings

    g = GraphGuardrails.from_settings(Settings())
    assert g == GraphGuardrails(recursion_limit=100, timeout_seconds=900.0, thread_meta_max=1000,
                                cleanup_checkpoints=False)
