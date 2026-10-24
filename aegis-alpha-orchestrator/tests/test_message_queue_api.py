"""HTTP/SSE integration: enqueue via /message-queue while /stream-workflow runs."""

from __future__ import annotations

import json
from unittest.mock import AsyncMock, patch

import httpx
import pytest
from langchain_core.messages import AIMessage, HumanMessage
from langchain_core.tools import tool

from app.config import settings
from app.core.llm_client import AgentToolResult, LLMResponse
from app.core.message_queue import MessageQueueRegistry, MessageQueueSettings, is_queued_human_message
from app.core.workflow_engine import WorkflowEngine
from app.main import app

TOKEN = "test-service-token"
HEADERS = {"Authorization": f"Bearer {TOKEN}"}
OWNER = {"userId": "u1", "tenantId": "t1"}
RUN = "run-api-1"
LAYOUT = {
    "nodes": [
        {"id": "agent", "data": {"handler": "general.agent", "prompt": "analyse"}},
        {"id": "end", "data": {"handler": "end", "nodeType": "end"}},
    ],
    "edges": [{"source": "agent", "target": "end"}],
}


def _parse_sse(text: str) -> list[tuple[str, dict]]:
    events = []
    for block in text.strip().split("\n\n"):
        name, data = None, None
        for line in block.splitlines():
            if line.startswith("event: "):
                name = line[7:]
            elif line.startswith("data: "):
                data = json.loads(line[6:])
        if name:
            events.append((name, data))
    return events


@pytest.fixture
def wired(mock_node_executor):
    """App with a test engine/registry; lifespan side effects stubbed."""
    WorkflowEngine._graph_cache.clear()
    registry = MessageQueueRegistry(MessageQueueSettings())
    ctx: dict = {"client": None, "responses": []}

    @tool
    def quote() -> str:
        """Fetch a quote."""
        return "q=1"

    @tool
    def news() -> str:
        """Fetch news."""
        return "news"

    async def http_enqueue(kind, message, owner):
        resp = await ctx["client"].post(
            f"/message-queue/{RUN}/messages",
            json={"kind": kind, "message": message, "owner": owner},
            headers=HEADERS,
        )
        ctx["responses"].append((resp.status_code, resp.json()))

    async def fake(node, state=None, subject="", tools=None, prior_messages=None, force_final=False, **_):
        if prior_messages is None:
            # foreign user is rejected, owner is accepted (steer + follow-up)
            await http_enqueue("steer", "hijack", {"userId": "u2", "tenantId": "t1"})
            await http_enqueue("steer", "只看风险", OWNER)
            await http_enqueue("followUp", "再总结一句", OWNER)
            ai = AIMessage(content="", tool_calls=[
                {"name": "quote", "args": {}, "id": "c_quote"},
                {"name": "news", "args": {}, "id": "c_news"},
            ])
            return AgentToolResult(ai, has_tool_calls=True, seed_human=HumanMessage(content="analyse"))
        tail = prior_messages[-1]
        text = tail.content if is_queued_human_message(tail) else "?"
        return AgentToolResult(
            AIMessage(content=text), has_tool_calls=False,
            llm_response=LLMResponse(summary=f"answer:{text}", confidence=0.8, content=text),
        )

    mock_node_executor.invoke_agent_with_tools = AsyncMock(side_effect=fake)
    engine = WorkflowEngine(mock_node_executor, tools=[quote, news], message_queues=registry)
    with (
        patch("app.main.market_data"),
        patch("app.main.memory_store_manager"),
        patch("app.main.get_backend_client"),
        patch.object(settings, "node_execution_token", TOKEN),
        patch("app.routers.workflow.workflow_engine", engine),
        patch("app.routers.message_queue.message_queues", registry),
    ):
        yield ctx, registry


@pytest.mark.asyncio
async def test_stream_workflow_with_steering_and_follow_up_over_http(wired):
    state, registry = wired
    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://orch") as client:
        state["client"] = client
        resp = await client.post(
            "/stream-workflow",
            json={**LAYOUT, "subject": "AAPL", "threadId": RUN, "owner": OWNER},
            headers=HEADERS,
        )
        assert resp.status_code == 200
        events = _parse_sse(resp.text)

        codes = [(c, b.get("code")) for c, b in state["responses"]]
        assert codes == [(403, "OWNER_MISMATCH"), (202, None), (202, None)]
        accepted = state["responses"][1][1]
        assert accepted["message"]["kind"] == "steer" and accepted["mode"] == "one-at-a-time"

        names = [n for n, _ in events]
        assert names.count("message_queued") == 2
        assert names.count("tool_skipped") == 1
        skipped = dict(events)["tool_skipped"]
        assert skipped["toolName"] == "news" and skipped["threadId"] == RUN
        dequeued = [d for n, d in events if n == "message_dequeued"]
        assert [d["kind"] for d in dequeued] == ["steer", "follow_up"]
        assert names[-2:] == ["message_queue_closed", "workflow_complete"]
        complete = events[-1][1]
        assert complete["messageQueue"]["delivered"] == 2
        assert complete["final_state"]["nodes"]["agent"]["summary"] == "answer:再总结一句"

        # run finished → enqueue is refused so Java can start a normal turn
        late = await client.post(
            f"/message-queue/{RUN}/messages",
            json={"kind": "steer", "message": "late", "owner": OWNER},
            headers=HEADERS,
        )
        assert late.status_code == 409 and late.json()["code"] == "NOT_ACTIVE"

        status = await client.post(f"/message-queue/{RUN}/status", json={"owner": OWNER}, headers=HEADERS)
        assert status.status_code == 200 and status.json()["state"] == "closed"
        foreign = await client.post(
            f"/message-queue/{RUN}/status", json={"owner": {"userId": "u2", "tenantId": "t1"}},
            headers=HEADERS,
        )
        assert foreign.status_code == 403


@pytest.mark.asyncio
async def test_message_queue_routes_require_service_token(wired):
    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://orch") as client:
        resp = await client.post(
            f"/message-queue/{RUN}/messages", json={"kind": "steer", "message": "x", "owner": OWNER}
        )
        assert resp.status_code == 401


@pytest.mark.asyncio
async def test_enqueue_validation_and_unknown_run(wired):
    _, registry = wired
    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://orch") as client:
        unknown = await client.post(
            "/message-queue/nope/messages", json={"kind": "steer", "message": "x", "owner": OWNER},
            headers=HEADERS,
        )
        assert unknown.status_code == 409 and unknown.json()["code"] == "NOT_ACTIVE"

        from app.core.message_queue import QueueOwner
        registry.open_run("live", QueueOwner.from_any(OWNER))
        bad_kind = await client.post(
            "/message-queue/live/messages", json={"kind": "shout", "message": "x", "owner": OWNER},
            headers=HEADERS,
        )
        assert bad_kind.status_code == 422 and bad_kind.json()["code"] == "INVALID_KIND"
        for _ in range(8):
            ok = await client.post(
                "/message-queue/live/messages", json={"kind": "steer", "message": "x", "owner": OWNER},
                headers=HEADERS,
            )
            assert ok.status_code == 202
        full = await client.post(
            "/message-queue/live/messages", json={"kind": "steer", "message": "x", "owner": OWNER},
            headers=HEADERS,
        )
        assert full.status_code == 429 and full.json()["code"] == "QUEUE_FULL"
        cleared = await client.post("/message-queue/live/clear", json={"owner": OWNER}, headers=HEADERS)
        assert cleared.status_code == 200 and len(cleared.json()["dropped"]) == 8


@pytest.mark.asyncio
async def test_enqueue_agent_id_targets_one_agent_and_maps_receiver_codes(wired):
    _, registry = wired
    from app.core.message_queue import QueueOwner

    queue = registry.open_run("par", QueueOwner.from_any(OWNER))
    queue.set_receivers(["fund", "val"])
    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://orch") as client:
        async def post(body):
            return await client.post("/message-queue/par/messages",
                                     json={"message": "x", "owner": OWNER, **body}, headers=HEADERS)

        ok = await post({"kind": "steer", "agentId": "val"})
        assert ok.status_code == 202 and ok.json()["message"]["target"] == "val"
        ghost = await post({"kind": "steer", "agentId": "ghost"})
        assert ghost.status_code == 422 and ghost.json()["code"] == "INVALID_TARGET"
        queue.mark_agent_done("fund")
        done = await post({"kind": "follow_up", "agentId": "fund"})
        assert done.status_code == 409 and done.json()["code"] == "TARGET_FINISHED"
        queue.mark_agent_done("val")
        none_left = await post({"kind": "follow_up"})
        assert none_left.status_code == 409 and none_left.json()["code"] == "NO_RECEIVER"
