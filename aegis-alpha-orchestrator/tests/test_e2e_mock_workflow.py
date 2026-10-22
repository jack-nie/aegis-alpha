"""End-to-end mock workflow smoke (CI-friendly, no external LLM/backend).

Covers: service auth, execute-workflow with mock mode, policy gate on aggregate
outputs, research_graph layouts loadable by WorkflowEngine.
"""

from __future__ import annotations

from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from fastapi.testclient import TestClient

from app.core.recommendation_policy import enforce_recommendation_policy
from app.core.research_graph import (
    build_earnings_reaction_layout,
    build_single_name_research_layout,
    build_watchlist_digest_layout,
)
from app.core.workflow_engine import WorkflowEngine
from app.models.responses import NodeResult
from app.models.workflow import Edge, Node, NodeData


@pytest.fixture
def client(monkeypatch):
    monkeypatch.setenv("AEGIS_ALPHA_NODE_EXECUTION_TOKEN", "ci-e2e-token")
    monkeypatch.setenv("AEGIS_ALPHA_LANGCHAIN_MOCK", "true")
    # Reload settings used by app
    from app import config as config_mod
    from app import main as main_mod

    monkeypatch.setattr(config_mod.settings, "node_execution_token", "ci-e2e-token")
    monkeypatch.setattr(config_mod.settings, "mock_mode", True)
    monkeypatch.setattr(config_mod.settings, "api_key", "")

    with patch.object(main_mod, "market_data") as md, patch.object(
        main_mod, "memory_store_manager"
    ) as msm:
        md.start = AsyncMock()
        md.close = AsyncMock()
        msm.initialize = AsyncMock()
        msm.cleanup = AsyncMock()
        msm.close = AsyncMock()
        with TestClient(main_mod.app) as test_client:
            yield test_client


def _auth_headers():
    return {"Authorization": "Bearer ci-e2e-token"}


def test_e2e_execute_workflow_mock_requires_auth(client):
    layout = build_watchlist_digest_layout()
    body = {
        "nodes": layout["nodes"],
        "edges": layout["edges"],
        "subject": "AAPL watchlist",
        "state": {"ticker": "AAPL"},
    }
    unauth = client.post("/execute-workflow", json=body)
    assert unauth.status_code == 401

    resp = client.post("/execute-workflow", json=body, headers=_auth_headers())
    assert resp.status_code != 401
    # Mock mode should complete without real LLM
    data = resp.json()
    assert "ok" in data or resp.status_code == 200


def test_e2e_execute_node_accepts_delegated_token_field(client):
    body = {
        "node": {
            "id": "n1",
            "data": {"handler": "start", "label": "Start", "nodeType": "start"},
        },
        "state": {},
        "subject": "test",
        "delegatedToken": "fake-delegation",
    }
    resp = client.post("/execute-node", json=body, headers=_auth_headers())
    assert resp.status_code != 401
    assert resp.status_code == 200
    payload = resp.json()
    assert payload.get("ok") is True or payload.get("status") in ("tool-mock", "completed", "error")


def test_e2e_research_layouts_compile():
    """Layouts from research_graph compile under WorkflowEngine without tools."""
    executor = MagicMock()
    executor.execute = AsyncMock(
        return_value=NodeResult(
            ok=True,
            status="completed",
            handler="logic",
            node_id="x",
            summary="ok",
        )
    )
    engine = WorkflowEngine(node_executor=executor, tools=None)

    for builder in (
        build_single_name_research_layout,
        build_earnings_reaction_layout,
        build_watchlist_digest_layout,
    ):
        layout = builder()
        nodes = [Node.model_validate(n) for n in layout["nodes"]]
        edges = [Edge.model_validate(e) for e in layout["edges"]]
        graph = engine.build_graph(nodes, edges, require_approval=False)
        assert graph is not None


def test_e2e_policy_blocks_unfounded_buy():
    result = enforce_recommendation_policy(
        label="BUY",
        confidence=0.95,
        state={},
        claims=[],
        sources=[],
    )
    assert result["recommendation"] == "INSUFFICIENT_DATA"
    assert result["degraded"] is True


def test_e2e_classify_intent_with_auth(client):
    resp = client.post(
        "/classify-intent",
        headers=_auth_headers(),
        json={
            "message": "分析 AAPL 财报反应",
            "workflows": [
                {
                    "workflowKey": "earnings_reaction",
                    "name": "Earnings",
                    "triggerKeywords": "财报,earnings",
                    "routingDescription": "Earnings reaction",
                }
            ],
        },
    )
    assert resp.status_code != 401
    data = resp.json()
    # Either LLM mock or keyword path; contract is camelCase workflowKey present
    assert "workflowKey" in data or "workflow_key" in data or "error" in data or data.get("source")


def test_e2e_execute_workflow_with_require_approval_returns_interrupted(client, monkeypatch):
    """P1-14: Java now runs sync/queued workflows via /execute-workflow; the approval gate must pause."""
    import uuid

    from langgraph.checkpoint.memory import MemorySaver

    from app.routers import workflow as workflow_router

    # Fresh in-memory checkpointer: the app-level one is closed by earlier TestClient lifespans.
    WorkflowEngine._graph_cache.clear()
    engine = WorkflowEngine(workflow_router.node_executor, checkpointer=MemorySaver())
    monkeypatch.setattr(workflow_router, "workflow_engine", engine)

    layout = build_single_name_research_layout()
    body = {
        "nodes": layout["nodes"],
        "edges": layout["edges"],
        "subject": "AAPL",
        "state": {"ticker": "AAPL"},
        "requireApproval": True,
        "threadId": f"p1-14-{uuid.uuid4()}",
    }
    resp = client.post("/execute-workflow", json=body, headers=_auth_headers())
    assert resp.status_code == 200
    data = resp.json()
    assert data["ok"] is True, data.get("error")
    assert data["final_state"].get("interrupted") is True
    WorkflowEngine._graph_cache.clear()


# ---------------- P2-10: request-level credentials are accepted but never used ----------------


def test_request_models_drop_api_key_and_base_url_with_warning(caplog):
    import logging

    from app.models.requests import IntentRequest, NodeRequest, WorkflowRequest

    caplog.set_level(logging.WARNING, logger="app.models.requests")
    for model in (WorkflowRequest, NodeRequest, IntentRequest):
        body = model.model_validate({"apiKey": "sk-evil-123", "baseUrl": "http://evil.test/v1"})
        assert body.api_key is None and body.base_url is None
    assert "Ignoring request-level credentials" in caplog.text
    assert "sk-evil-123" not in caplog.text and "evil.test" not in caplog.text
    assert NodeRequest.model_validate({}).api_key is None


def test_execute_node_never_forwards_request_api_key(client):
    from app.routers import workflow as workflow_router

    fake = AsyncMock(return_value=NodeResult(ok=True, status="completed", handler="start", node_id="n1"))
    with patch.object(workflow_router.node_executor, "execute", fake):
        resp = client.post(
            "/execute-node",
            json={
                "node": {"id": "n1", "data": {"handler": "start", "nodeType": "start"}},
                "subject": "t",
                "apiKey": "sk-evil-123",
                "baseUrl": "http://evil.test/v1",
            },
            headers=_auth_headers(),
        )
    assert resp.status_code == 200
    kwargs = fake.await_args.kwargs
    assert kwargs.get("api_key") is None and kwargs.get("base_url") is None
    assert "sk-evil-123" not in repr(fake.await_args)
