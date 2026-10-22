"""P2-15: an approved, policy-clean recommendation is written to approved_conclusion; nothing else is."""
import os
import tempfile
from unittest.mock import AsyncMock, MagicMock

import pytest
from langgraph.checkpoint.memory import MemorySaver

from app.core.memory_selector import load_run_memory, read_store_value
from app.core.persistent_store import PersistentStore
from app.core.workflow_engine import WorkflowEngine
from app.models.responses import NodeResult
from app.models.workflow import Edge, Node, NodeData

GOOD = {
    "recommendation": "HOLD",
    "confidence": 0.55,
    "claims": [{"text": "price 190", "evidenceId": "ev-1"}],
    "missingData": [],
    "degraded": False,
    "policy": {"forcedInsufficient": False, "hasQuote": True, "hasFinancials": True, "hasEvidence": True},
}


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


def _executor(aggregate_data: dict, *, ok: bool = True):
    executor = MagicMock()

    async def execute(node, *args, **kwargs):
        data = aggregate_data if node.id == "aggregate" else {}
        summary = f"[{aggregate_data.get('recommendation')}] steady" if node.id == "aggregate" else "s"
        return NodeResult(ok=ok if node.id == "aggregate" else True, status="completed",
                          handler=node.data.handler, node_id=node.id, summary=summary, data=data,
                          degraded=bool(data.get("degraded")))

    executor.execute = AsyncMock(side_effect=execute)
    return executor


@pytest.fixture
def store():
    tmp = tempfile.TemporaryDirectory()
    s = PersistentStore(os.path.join(tmp.name, "store.db"))
    yield s
    tmp.cleanup()


@pytest.fixture(autouse=True)
def _clear_cache():
    WorkflowEngine._graph_cache.clear()
    yield
    WorkflowEngine._graph_cache.clear()


async def _run(store, aggregate_data, approved: bool, thread: str, *, ok: bool = True):
    engine = WorkflowEngine(_executor(aggregate_data, ok=ok), store=store, checkpointer=MemorySaver())
    nodes, edges = _layout()
    paused = await engine.execute_workflow(nodes, edges, {"ticker": "AAPL"}, "AAPL",
                                           require_approval=True, thread_id=thread)
    assert paused.final_state.get("interrupted") is True
    return await engine.resume_workflow(thread, {"approved": approved})


@pytest.mark.asyncio
async def test_approval_writes_policy_checked_conclusion(store):
    result = await _run(store, GOOD, True, "t-ok")
    assert result.ok, result.error
    stored = await read_store_value(store, ("ticker", "AAPL"), "approved_conclusion")
    assert stored["recommendation"] == "HOLD"
    assert stored["claims"] == [{"text": "price 190", "evidenceId": "ev-1"}]
    assert stored["threadId"] == "t-ok"
    assert stored["summary"].startswith("[HOLD]")
    memory = await load_run_memory(store, user_id=None, symbol="AAPL")
    assert any(s["kind"] == "approved_conclusion" for s in memory["snippets"])


@pytest.mark.asyncio
async def test_rejection_writes_nothing(store):
    result = await _run(store, GOOD, False, "t-rej")
    assert result.ok
    assert await read_store_value(store, ("ticker", "AAPL"), "approved_conclusion") is None


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "data,ok",
    [
        ({**GOOD, "recommendation": "INSUFFICIENT_DATA"}, True),
        ({**GOOD, "degraded": True}, True),
        ({**GOOD, "claims": [{"text": "no evidence"}]}, True),
        ({k: v for k, v in GOOD.items() if k != "policy"}, True),
        (GOOD, False),
    ],
    ids=["insufficient", "degraded", "unbound-claims", "no-policy", "aggregate-failed"],
)
async def test_policy_failing_approval_writes_nothing(store, data, ok):
    await _run(store, data, True, f"t-{id(data)}", ok=ok)
    assert await read_store_value(store, ("ticker", "AAPL"), "approved_conclusion") is None
