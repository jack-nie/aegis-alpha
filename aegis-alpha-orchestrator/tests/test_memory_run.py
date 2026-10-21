"""Workflow run picks up user preference and approved conclusion from the store."""

import json
from unittest.mock import AsyncMock

import pytest


def _plain(value):
    if hasattr(value, "model_dump"):
        return _plain(value.model_dump())
    if isinstance(value, dict):
        return {str(key): _plain(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [_plain(item) for item in value]
    if isinstance(value, (str, int, float, bool)) or value is None:
        return value
    return str(value)


def _trace_dicts(trace):
    dumped = []
    for entry in trace or []:
        if isinstance(entry, dict):
            dumped.append(dict(entry))
            continue
        data = {}
        if hasattr(entry, "model_dump"):
            data.update(entry.model_dump())
        extra = getattr(entry, "model_extra", None) or {}
        data.update(extra)
        dumped.append(data)
    return dumped


def _memory_hits(trace):
    hits = []
    for entry in _trace_dicts(trace):
        if "memoryHits" in entry:
            hits.append(entry["memoryHits"])
    return hits


def _assert_memory_hits(trace, expected):
    hits = _memory_hits(trace)
    rendered = _trace_dicts(trace)
    assert hits, f"memoryHits missing from trace: {rendered!r}"
    if len(hits) == len(list(trace)):
        assert hits == [expected] * len(hits), hits
        return
    # Engine may record the injected hit count once for the whole trace.
    assert hits == [expected], f"memoryHits={hits!r} trace={rendered!r}"


def _corpus(states, result):
    return json.dumps(
        _plain(
            {
                "states": states,
                "final_state": result.final_state,
                "state": result.state,
                "trace": _trace_dicts(result.trace),
                "node_outputs": result.node_outputs,
            }
        ),
        default=str,
    )


async def _open_manager(tmp_path):
    from app.config import Settings
    from app.core.memory_store import MemoryStoreManager

    settings = Settings(
        AEGIS_ALPHA_LANGCHAIN_API_KEY="test",
        AEGIS_ALPHA_DATA_DIR=str(tmp_path),
        AEGIS_ALPHA_STORE_TTL_SECONDS=3600,
    )
    manager = MemoryStoreManager(settings)
    await manager.initialize()
    return manager


def _executor():
    states = []

    async def _execute(node, state, subject="", **kwargs):
        states.append(_plain(state))
        from app.models.responses import NodeResult

        return NodeResult(
            ok=True,
            status="completed",
            handler=node.data.handler or "logic",
            node_id=node.id,
            node_name=node.id,
            subject=subject,
            summary="ran",
        )

    executor = type("Executor", (), {})()
    executor.execute = AsyncMock(side_effect=_execute)
    executor.states = states
    return executor


def _graph():
    from app.models.workflow import Edge, Node, NodeData

    nodes = [
        Node(id="start", data=NodeData(handler="start", nodeType="start")),
        Node(id="end", data=NodeData(handler="end", nodeType="end")),
    ]
    edges = [Edge(source="start", target="end")]
    return nodes, edges


@pytest.mark.asyncio
async def test_workflow_run_injects_preference_and_approved_conclusion(tmp_path):
    from langgraph.checkpoint.memory import MemorySaver

    from app.core.persistent_store import PersistentStore
    from app.core.workflow_engine import WorkflowEngine

    user = "user-mem"
    symbol = "AAPL"
    manager = await _open_manager(tmp_path)
    try:
        assert isinstance(manager.store, PersistentStore)
        await manager.store_user_preference(user, "risk_tolerance", "conservative-pref")
        await manager.store_approved_conclusion(
            symbol,
            {"summary": "approved-conclusion-text", "recommendation": "HOLD"},
        )
        WorkflowEngine._graph_cache.clear()
        executor = _executor()
        engine = WorkflowEngine(executor, store=manager.store, checkpointer=MemorySaver())
        nodes, edges = _graph()
        result = await engine.execute_workflow(
            nodes,
            edges,
            {"userId": user, "ticker": symbol},
            "memory run",
            require_approval=False,
        )
        assert result.ok, result.error
        from app.core.context_assembly import assemble_model_context

        model_context = "\n".join(
            assemble_model_context(state) for state in executor.states
        )
        assert "conservative-pref" in model_context
        assert "approved-conclusion-text" in model_context
        _assert_memory_hits(result.trace, 2)
    finally:
        await manager.close()
        WorkflowEngine._graph_cache.clear()


@pytest.mark.asyncio
async def test_workflow_run_without_memory_has_zero_hits(tmp_path):
    from langgraph.checkpoint.memory import MemorySaver

    from app.core.workflow_engine import WorkflowEngine

    manager = await _open_manager(tmp_path / "empty")
    try:
        WorkflowEngine._graph_cache.clear()
        executor = _executor()
        engine = WorkflowEngine(executor, store=manager.store, checkpointer=MemorySaver())
        nodes, edges = _graph()
        result = await engine.execute_workflow(
            nodes,
            edges,
            {"userId": "user-empty", "ticker": "MSFT"},
            "empty memory run",
            require_approval=False,
        )
        assert result.ok, result.error
        _assert_memory_hits(result.trace, 0)
        corpus = _corpus(executor.states, result)
        assert "conservative-pref" not in corpus
        assert "approved-conclusion-text" not in corpus
    finally:
        await manager.close()
        WorkflowEngine._graph_cache.clear()
