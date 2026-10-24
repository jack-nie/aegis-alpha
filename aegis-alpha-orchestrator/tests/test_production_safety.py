"""Decision D6: DEV_MODE / sample service token refuse to start when a production marker is active."""
from unittest.mock import AsyncMock, patch

import pytest
from fastapi.testclient import TestClient

from app.config import EXAMPLE_SERVICE_TOKEN, Settings, settings


def _s(monkeypatch, profiles="", **kw):
    monkeypatch.setenv("SPRING_PROFILES_ACTIVE", profiles)
    return Settings(**kw)


def test_local_dev_mode_still_allowed(monkeypatch):
    s = _s(monkeypatch, AEGIS_ALPHA_DEV_MODE="true", AEGIS_ALPHA_NODE_EXECUTION_TOKEN=EXAMPLE_SERVICE_TOKEN)
    assert not s.is_production and s.production_safety_problems == []
    assert s.service_token_problem is None


@pytest.mark.parametrize("marker", [{"AEGIS_ALPHA_ENV": "production"}, {"AEGIS_ALPHA_ENV": "PROD"}])
def test_dev_mode_in_production_is_fatal(monkeypatch, marker):
    s = _s(monkeypatch, AEGIS_ALPHA_DEV_MODE="true", AEGIS_ALPHA_NODE_EXECUTION_TOKEN="real", **marker)
    assert s.production_safety_problems == ["dev_mode_in_production"]


def test_spring_profile_marks_production_and_sample_token_is_fatal_even_in_mock(monkeypatch):
    s = _s(monkeypatch, profiles="default,prod", AEGIS_ALPHA_LANGCHAIN_MOCK="true",
           AEGIS_ALPHA_NODE_EXECUTION_TOKEN=EXAMPLE_SERVICE_TOKEN)
    assert s.is_production
    assert s.production_safety_problems == ["example_service_token_in_production"]
    assert s.service_token_problem == "service_token_is_example_value"


def test_lifespan_refuses_to_start(monkeypatch):
    monkeypatch.setenv("SPRING_PROFILES_ACTIVE", "")
    from app.main import app

    with (
        patch("app.main.market_data") as market_data,
        patch("app.main.memory_store_manager"),
        patch("app.main.get_backend_client"),
        patch.object(settings, "deployment_env", "production"),
        patch.object(settings, "dev_mode", True),
    ):
        market_data.start = AsyncMock()
        with pytest.raises(RuntimeError, match="production safety"):
            with TestClient(app):
                pass
        market_data.start.assert_not_called()
