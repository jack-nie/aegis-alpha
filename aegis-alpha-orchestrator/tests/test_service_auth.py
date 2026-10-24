"""Service token auth smoke tests for orchestrator HTTP middleware."""

from __future__ import annotations

from unittest.mock import AsyncMock, patch

import pytest
from fastapi.testclient import TestClient

from app.config import settings
from app.main import app


@pytest.fixture
def client():
    """TestClient with lifespan side effects stubbed (no real network/DB work)."""
    with (
        patch("app.main.market_data") as market_data,
        patch("app.main.memory_store_manager") as memory_store_manager,
        patch("app.main.get_backend_client") as get_backend_client,
        patch.object(settings, "node_execution_token", "test-service-token"),
    ):
        market_data.start = AsyncMock()
        market_data.close = AsyncMock()
        memory_store_manager.initialize = AsyncMock()
        memory_store_manager.cleanup = AsyncMock(return_value=0)
        memory_store_manager.close = AsyncMock()
        backend = AsyncMock()
        backend.close = AsyncMock()
        get_backend_client.return_value = backend

        with TestClient(app) as test_client:
            yield test_client


def test_health_works_without_token(client):
    response = client.get("/health")
    assert response.status_code == 200
    body = response.json()
    assert body.get("ok") is True


def test_classify_intent_without_token_returns_401(client):
    response = client.post("/classify-intent", json={})
    assert response.status_code == 401
    body = response.json()
    assert body.get("code") == "AUTHZ_DENIED"


def test_classify_intent_with_bearer_token_not_unauthorized(client):
    response = client.post(
        "/classify-intent",
        json={},
        headers={"Authorization": "Bearer test-service-token"},
    )
    # Auth must pass; body may be accepted (defaults) or validation may yield 422
    assert response.status_code != 401
    assert response.status_code != 503


def test_classify_intent_with_x_service_token_not_unauthorized(client):
    response = client.post(
        "/classify-intent",
        json={},
        headers={"X-Service-Token": "test-service-token"},
    )
    assert response.status_code != 401
    assert response.status_code != 503


def test_classify_intent_with_wrong_token_returns_401(client):
    response = client.post(
        "/classify-intent",
        json={},
        headers={"Authorization": "Bearer wrong-token"},
    )
    assert response.status_code == 401


# ---------------- P1-11: no default token; example token only in explicit dev/mock ----------------


@pytest.fixture
def make_client():
    """TestClient factory with the service token / dev / mock flags patched per test."""
    from contextlib import ExitStack

    stacks: list[ExitStack] = []

    def _make(token: str, *, dev: bool = False, mock: bool = False) -> TestClient:
        stack = ExitStack()
        stacks.append(stack)
        market_data = stack.enter_context(patch("app.main.market_data"))
        memory_store_manager = stack.enter_context(patch("app.main.memory_store_manager"))
        get_backend_client = stack.enter_context(patch("app.main.get_backend_client"))
        stack.enter_context(patch.object(settings, "node_execution_token", token))
        stack.enter_context(patch.object(settings, "dev_mode", dev))
        stack.enter_context(patch.object(settings, "mock_mode", mock))
        market_data.start = AsyncMock()
        market_data.close = AsyncMock()
        memory_store_manager.initialize = AsyncMock()
        memory_store_manager.cleanup = AsyncMock(return_value=0)
        memory_store_manager.close = AsyncMock()
        backend = AsyncMock()
        backend.close = AsyncMock()
        get_backend_client.return_value = backend
        return stack.enter_context(TestClient(app))

    yield _make
    for stack in reversed(stacks):
        stack.close()


def test_default_service_token_is_empty():
    from app.config import Settings

    assert Settings.model_fields["node_execution_token"].default == ""


def test_example_token_rejected_outside_dev_mode(make_client):
    client = make_client("local-workflow-node-token")
    response = client.post(
        "/classify-intent", json={}, headers={"Authorization": "Bearer local-workflow-node-token"}
    )
    assert response.status_code == 503
    assert response.json()["error"] == "service_token_is_example_value"
    # Public routes stay available.
    assert client.get("/health").status_code == 200


def test_example_token_allowed_with_explicit_dev_flag(make_client):
    client = make_client("local-workflow-node-token", dev=True)
    response = client.post(
        "/classify-intent", json={}, headers={"Authorization": "Bearer local-workflow-node-token"}
    )
    assert response.status_code not in (401, 503)


def test_example_token_allowed_with_explicit_mock_flag(make_client):
    client = make_client("local-workflow-node-token", mock=True)
    response = client.post(
        "/classify-intent", json={}, headers={"Authorization": "Bearer local-workflow-node-token"}
    )
    assert response.status_code not in (401, 503)


def test_empty_token_rejects_even_empty_bearer(make_client):
    client = make_client("", dev=True)
    response = client.post("/classify-intent", json={}, headers={"Authorization": "Bearer "})
    assert response.status_code == 503
    assert response.json()["error"] == "service_token_not_configured"


def test_cors_allow_list_has_no_wildcard_and_is_env_driven():
    from app.config import Settings

    default = Settings(_env_file=None)
    assert "*" not in default.cors_origins
    assert "http://127.0.0.1:5174" in default.cors_origins
    custom = Settings(_env_file=None, AEGIS_ALPHA_CORS_ALLOWED_ORIGINS="https://a.example, *, https://b.example")
    assert custom.cors_origins == ["https://a.example", "https://b.example"]


def test_cors_preflight_only_echoes_allowed_origin(make_client):
    client = make_client("test-service-token")
    allowed = client.options(
        "/health",
        headers={"Origin": "http://127.0.0.1:5174", "Access-Control-Request-Method": "GET"},
    )
    assert allowed.headers.get("access-control-allow-origin") == "http://127.0.0.1:5174"
    denied = client.options(
        "/health",
        headers={"Origin": "https://evil.example", "Access-Control-Request-Method": "GET"},
    )
    assert denied.headers.get("access-control-allow-origin") is None
