"""P2-16: AsyncSqliteSaver lifecycle; durable approval resume across a restart."""
import os
import tempfile
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from langgraph.checkpoint.memory import MemorySaver
from langgraph.checkpoint.sqlite.aio import AsyncSqliteSaver

from app.core.checkpointer_lifecycle import close_async_checkpointer, open_async_checkpointer
from app.core.workflow_engine import WorkflowEngine
from app.models.responses import NodeResult
from app.models.workflow import Edge, Node, NodeData


def _layout():
    nodes = [
        Node(id="start", data=NodeData(handler="start", nodeType="start")),
        Node(id="market", data=NodeData(handler="finance.market_analysis")),
        Node(id="aggregate", data=NodeData(handler="finance.stock_recommendation_aggregate")),
        Node(id="end", data=NodeData(handler="end", nodeType="end")),
    ]
    edges = [Edge(source="start", target="market"), Edge(source="market", target="aggregate"),
             Edge(source="aggregate", target="end")]
    return nodes, edges


def _executor(calls: list):
    executor = MagicMock()

    async def execute(node, *args, **kwargs):
        calls.append(node.id)
        return NodeResult(ok=True, status="completed", handler=node.data.handler, node_id=node.id, summary="s")

    executor.execute = AsyncMock(side_effect=execute)
    return executor


@pytest.mark.asyncio
async def test_async_sqlite_saver_resumes_approval_after_restart():
    calls: list = []
    with tempfile.TemporaryDirectory() as tmp:
        db = os.path.join(tmp, "nested", "checkpoints.db")
        saver = await open_async_checkpointer(db)
        try:
            assert isinstance(saver, AsyncSqliteSaver)
            assert await open_async_checkpointer(db) is saver  # one per process
            nodes, edges = _layout()
            engine = WorkflowEngine(_executor(calls), checkpointer=saver)
            paused = await engine.execute_workflow(nodes, edges, {"ticker": "AAPL"}, "AAPL",
                                                   require_approval=True, thread_id="durable-async")
            assert paused.ok and paused.final_state.get("interrupted") is True
            assert "aggregate" not in calls
        finally:
            await close_async_checkpointer()

        # "Restart": new connection, new engine, layout passed explicitly (no store).
        reopened = await open_async_checkpointer(db)
        try:
            assert reopened is not saver
            engine2 = WorkflowEngine(_executor(calls), checkpointer=reopened)
            nodes, edges = _layout()
            resumed = await engine2.resume_workflow("durable-async", {"approved": True}, nodes=nodes, edges=edges)
            assert resumed.ok, resumed.error
            assert resumed.final_state.get("approval_status") == "approved"
            assert calls.count("aggregate") == 1 and calls.count("market") == 1
        finally:
            await close_async_checkpointer()


def test_lifespan_attaches_async_saver_and_detaches_on_shutdown(monkeypatch):
    from app import config as config_mod
    from app import main as main_mod

    monkeypatch.setattr(config_mod.settings, "node_execution_token", "ci-token")
    with tempfile.TemporaryDirectory() as tmp:
        monkeypatch.setattr(main_mod, "CHECKPOINT_DB_PATH", os.path.join(tmp, "cp.db"))
        with patch.object(main_mod, "market_data") as md, patch.object(main_mod, "memory_store_manager") as msm:
            md.start = AsyncMock()
            md.close = AsyncMock()
            msm.initialize = AsyncMock()
            msm.cleanup = AsyncMock()
            msm.close = AsyncMock()
            from fastapi.testclient import TestClient

            with TestClient(main_mod.app):
                assert isinstance(main_mod.workflow_engine._checkpointer, AsyncSqliteSaver)
            assert isinstance(main_mod.workflow_engine._checkpointer, MemorySaver)


def test_compiled_graphs_are_not_shared_between_engines():
    nodes, edges = _layout()
    a = WorkflowEngine(_executor([]), checkpointer=MemorySaver())
    b = WorkflowEngine(_executor([]), checkpointer=MemorySaver())
    assert a.build_graph(nodes, edges) is a.build_graph(nodes, edges)
    assert a.build_graph(nodes, edges) is not b.build_graph(nodes, edges)
    before = a.build_graph(nodes, edges)
    a.attach_checkpointer(MemorySaver())
    assert a.build_graph(nodes, edges) is not before
