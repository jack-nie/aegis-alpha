"""Decision D5: checkpoints are deleted for terminal runs, never for paused / aborted / failed resumes."""
import asyncio
from unittest.mock import AsyncMock, MagicMock

import pytest
from langgraph.checkpoint.memory import MemorySaver

from app.config import Settings
from app.core.workflow_engine import (
    GraphGuardrails,
    WorkflowEngine,
    cleanup_kind,
    parse_cleanup_outcomes,
)
from app.models.responses import NodeResult
from app.models.workflow import Edge, Node, NodeData


def _chain():
    nodes = [Node(id="start", data=NodeData(handler="start", nodeType="start")),
             Node(id="n0", data=NodeData(handler="finance.market_analysis", label="n0"))]
    return nodes, [Edge(source="start", target="n0")]


def _approval_layout():
    nodes = [Node(id="start", data=NodeData(handler="start", nodeType="start")),
             Node(id="market", data=NodeData(handler="finance.market_analysis")),
             Node(id="aggregate", data=NodeData(handler="finance.stock_recommendation_aggregate")),
             Node(id="end", data=NodeData(handler="end", nodeType="end"))]
    edges = [Edge(source="start", target="market"), Edge(source="market", target="aggregate"),
             Edge(source="aggregate", target="end")]
    return nodes, edges


def _executor(*, fail=False, delay=0.0):
    executor = MagicMock()

    async def execute(node, *args, **kwargs):
        if delay:
            await asyncio.sleep(delay)
        if fail:
            raise RuntimeError("boom")
        return NodeResult(ok=True, status="completed", handler="logic", node_id=node.id, summary="s")

    executor.execute = AsyncMock(side_effect=execute)
    return executor


@pytest.fixture(autouse=True)
def _clear_cache():
    WorkflowEngine._graph_cache.clear()
    yield
    WorkflowEngine._graph_cache.clear()


async def _has_checkpoint(saver, tid):
    return (await saver.aget_tuple({"configurable": {"thread_id": tid}})) is not None


def test_cleanup_kind_rules():
    assert cleanup_kind("completed", resumed=False) == "completed"
    assert cleanup_kind("completed", resumed=True) == "completed"  # approved or rejected
    assert cleanup_kind("cancelled", resumed=False) == "cancelled"
    assert cleanup_kind("error", resumed=False) == cleanup_kind("timeout", resumed=False) == "failed"
    for outcome in ("paused", "aborted", "running"):
        assert cleanup_kind(outcome, resumed=False) is None
    # Failed / cancelled resume: Java returns the run to HUMAN_INTERRUPT -> keep the checkpoint.
    assert cleanup_kind("error", resumed=True) is None
    assert cleanup_kind("cancelled", resumed=True) is None


def test_outcomes_are_configurable_and_validated():
    assert parse_cleanup_outcomes(None) == frozenset({"completed", "cancelled", "failed"})
    assert parse_cleanup_outcomes("completed") == frozenset({"completed"})
    with pytest.raises(ValueError):
        parse_cleanup_outcomes("completed,paused")
    g = GraphGuardrails.from_settings(Settings(AEGIS_ALPHA_WORKFLOW_CLEANUP_OUTCOMES="completed,failed"))
    assert g.cleanup_checkpoints and g.cleanup_outcomes == frozenset({"completed", "failed"})
    assert not GraphGuardrails.from_settings(
        Settings(AEGIS_ALPHA_WORKFLOW_CLEANUP_CHECKPOINTS="false")).cleanup_checkpoints


@pytest.mark.asyncio
async def test_failed_run_checkpoint_is_deleted_by_default():
    saver = MemorySaver()
    engine = WorkflowEngine(_executor(fail=True), checkpointer=saver)
    nodes, edges = _chain()
    await engine.execute_workflow(nodes, edges, {"ticker": "AAPL"}, "t", thread_id="run-fail")
    assert not await _has_checkpoint(saver, "run-fail")


@pytest.mark.asyncio
async def test_timed_out_run_respects_outcome_config():
    saver = MemorySaver()
    engine = WorkflowEngine(_executor(delay=1.0), checkpointer=saver,
                            guardrails=GraphGuardrails(timeout_seconds=0.05, cleanup_outcomes=frozenset({"completed"})))
    nodes, edges = _chain()
    result = await engine.execute_workflow(nodes, edges, {"ticker": "AAPL"}, "t", thread_id="run-slow")
    assert result.ok is False
    # "failed" not configured -> kept (whatever the graph had written so far stays readable).
    assert "run-slow" in engine._thread_meta


@pytest.mark.asyncio
async def test_paused_run_keeps_checkpoint_and_completed_resume_deletes_it():
    saver = MemorySaver()
    engine = WorkflowEngine(_executor(), checkpointer=saver)
    nodes, edges = _approval_layout()
    paused = await engine.execute_workflow(nodes, edges, {"ticker": "AAPL"}, "t",
                                           require_approval=True, thread_id="run-hi")
    assert paused.final_state.get("interrupted")
    assert await _has_checkpoint(saver, "run-hi")  # HUMAN_INTERRUPT: never cleaned

    done = await engine.resume_workflow("run-hi", {"approved": False})
    assert not done.final_state.get("interrupted")
    assert not await _has_checkpoint(saver, "run-hi")  # rejected = terminal


@pytest.mark.asyncio
async def test_failed_resume_keeps_checkpoint_for_retry():
    saver = MemorySaver()
    engine = WorkflowEngine(_executor(), checkpointer=saver)
    nodes, edges = _approval_layout()
    await engine.execute_workflow(nodes, edges, {"ticker": "AAPL"}, "t", require_approval=True, thread_id="run-r")
    assert await _has_checkpoint(saver, "run-r")
    await engine._cleanup_thread("run-r", "error", resumed=True)
    await engine._cleanup_thread("run-r", "cancelled", resumed=True)
    await engine._cleanup_thread("run-r", "paused")
    await engine._cleanup_thread("run-r", "aborted")
    assert await _has_checkpoint(saver, "run-r")
