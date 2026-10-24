"""P4-3: RunRegistry + POST /cancel-run — cooperative cancellation distinct from timeout."""
from __future__ import annotations

import asyncio
import json
from unittest.mock import patch

import httpx
import pytest
from langchain_core.messages import AIMessage
from langgraph.checkpoint.memory import MemorySaver

from app.config import settings
from app.core.llm_client import AgentToolResult, LLMResponse
from app.core.message_queue import MessageQueueRegistry, MessageQueueSettings
from app.core.run_registry import RunCancelled, RunRegistry, RunRegistryError, await_or_cancel
from app.core.workflow_engine import WorkflowEngine
from app.models.workflow import Edge, Node, NodeData

OWNER = {"userId": "u1", "tenantId": "t1"}
TOKEN = "test-service-token"
HEADERS = {"Authorization": f"Bearer {TOKEN}"}


def _layout():
    nodes = [
        Node(id="start", data=NodeData(handler="start", nodeType="start")),
        Node(id="agent", data=NodeData(handler="general.agent", nodeType="agent")),
        Node(id="end", data=NodeData(handler="end", nodeType="end")),
    ]
    return nodes, [Edge(source="start", target="agent"), Edge(source="agent", target="end")]


def _slow_agent(executor, started: asyncio.Event, observed: dict):
    async def fake(node, state, subject="", tools=None, prior_messages=None, force_final=False, **_):
        started.set()
        try:
            await asyncio.sleep(30)
        except asyncio.CancelledError:
            observed["node_cancelled"] = True
            raise
        return AgentToolResult(AIMessage(content="late"), llm_response=LLMResponse(summary="late"))

    executor.invoke_agent_with_tools = fake


def _engine(executor):
    from langchain_core.tools import tool

    @tool
    def quote(symbol: str) -> str:
        """Quote."""
        return "q"

    WorkflowEngine._graph_cache.clear()
    return WorkflowEngine(
        executor, tools=[quote], checkpointer=MemorySaver(),
        message_queues=MessageQueueRegistry(MessageQueueSettings()),
    )


def test_registry_owner_checks():
    registry = RunRegistry()
    with pytest.raises(RunRegistryError) as e:
        registry.cancel("none", OWNER)
    assert e.value.code == "NOT_ACTIVE"
    registry.open("t", None)
    with pytest.raises(RunRegistryError) as e:
        registry.cancel("t", OWNER)  # ownerless run: fail closed
    assert e.value.code == "OWNER_MISMATCH"
    handle = registry.open("t2", OWNER)
    with pytest.raises(RunRegistryError):
        registry.cancel("t2", {"userId": "u2", "tenantId": "t1"})
    assert registry.cancel("t2", OWNER) == {"threadId": "t2", "cancelled": True, "alreadyCancelled": False}
    assert handle.is_cancelled
    registry.close(handle)
    assert registry.get("t2") is None


@pytest.mark.asyncio
async def test_await_or_cancel_distinguishes_cancel_and_timeout():
    registry = RunRegistry()
    handle = registry.open("x", OWNER)
    with pytest.raises(asyncio.TimeoutError):
        await await_or_cancel(asyncio.sleep(5), handle, 0.05)
    task = asyncio.ensure_future(await_or_cancel(asyncio.sleep(5), handle, 10))
    await asyncio.sleep(0.01)
    handle.request_cancel("user")
    with pytest.raises(RunCancelled) as info:
        await task
    assert info.value.reason == "user"
    assert await await_or_cancel(asyncio.sleep(0, result=7), None, 1) == 7


@pytest.mark.asyncio
async def test_execute_workflow_cancel(mock_node_executor):
    started, observed = asyncio.Event(), {}
    _slow_agent(mock_node_executor, started, observed)
    engine = _engine(mock_node_executor)
    nodes, edges = _layout()
    task = asyncio.ensure_future(
        engine.execute_workflow(nodes, edges, {}, "s", thread_id="run-c1", owner=OWNER)
    )
    await asyncio.wait_for(started.wait(), 5)
    assert engine.cancel_run("run-c1", OWNER)["cancelled"] is True
    result = await asyncio.wait_for(task, 5)
    assert result.ok is False and "cancelled" in (result.error or "")
    assert result.final_state.get("cancelled") is True
    assert observed.get("node_cancelled") is True
    assert engine.runs.get("run-c1") is None
    assert engine.message_queues.get("run-c1") is None or engine.message_queues.get("run-c1").close_reason == "cancelled"
    WorkflowEngine._graph_cache.clear()


@pytest.mark.asyncio
async def test_stream_workflow_cancel_emits_cancelled_and_closes_queue(mock_node_executor):
    started, observed = asyncio.Event(), {}
    _slow_agent(mock_node_executor, started, observed)
    engine = _engine(mock_node_executor)
    nodes, edges = _layout()
    events: list = []

    async def consume():
        async for ev in engine.stream_workflow(nodes, edges, {}, "s", thread_id="run-c2", owner=OWNER):
            events.append(ev)

    task = asyncio.ensure_future(consume())
    await asyncio.wait_for(started.wait(), 5)
    engine.cancel_run("run-c2", OWNER)
    await asyncio.wait_for(task, 5)
    names = [e.event for e in events]
    assert names[-2:] == ["message_queue_closed", "cancelled"]
    assert events[-2].data["reason"] == "cancelled"
    assert events[-1].data["code"] == "WORKFLOW_CANCELLED"
    assert "workflow_complete" not in names
    assert observed.get("node_cancelled") is True
    assert engine.runs.get("run-c2") is None
    WorkflowEngine._graph_cache.clear()


@pytest.mark.asyncio
async def test_cancel_run_http_requires_token_and_owner(mock_node_executor):
    from app.main import app

    started, observed = asyncio.Event(), {}
    _slow_agent(mock_node_executor, started, observed)
    engine = _engine(mock_node_executor)
    with (
        patch("app.main.market_data"),
        patch("app.main.memory_store_manager"),
        patch("app.main.get_backend_client"),
        patch.object(settings, "node_execution_token", TOKEN),
        patch("app.routers.workflow.workflow_engine", engine),
    ):
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://orch") as client:
            layout = {
                "nodes": [n.model_dump(by_alias=True) for n in _layout()[0]],
                "edges": [e.model_dump() for e in _layout()[1]],
            }
            run = asyncio.ensure_future(client.post(
                "/stream-workflow", json={**layout, "subject": "s", "threadId": "run-h", "owner": OWNER},
                headers=HEADERS,
            ))
            await asyncio.wait_for(started.wait(), 5)
            assert (await client.post("/cancel-run", json={"threadId": "run-h", "owner": OWNER})).status_code == 401
            foreign = await client.post(
                "/cancel-run", json={"threadId": "run-h", "owner": {"userId": "u2", "tenantId": "t1"}},
                headers=HEADERS,
            )
            assert foreign.status_code == 403 and foreign.json()["code"] == "OWNER_MISMATCH"
            ok = await client.post("/cancel-run", json={"threadId": "run-h", "owner": OWNER}, headers=HEADERS)
            assert ok.status_code == 200 and ok.json()["cancelled"] is True
            resp = await asyncio.wait_for(run, 5)
            assert "event: cancelled" in resp.text
            gone = await client.post("/cancel-run", json={"threadId": "run-h", "owner": OWNER}, headers=HEADERS)
            assert gone.status_code == 404 and gone.json()["code"] == "NOT_ACTIVE"
    WorkflowEngine._graph_cache.clear()
