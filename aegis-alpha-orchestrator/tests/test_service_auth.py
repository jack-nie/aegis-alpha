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
