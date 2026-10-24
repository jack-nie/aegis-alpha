"""P4-16: RunQueueBackend / CheckpointerFactory seams (no new dependencies)."""
import pytest

from app.config import Settings
from app.core.message_queue import MessageQueueRegistry, QueueOwner
from app.core.run_backends import (
    BackendUnavailable,
    CheckpointerFactory,
    MemoryCheckpointerFactory,
    RunQueueBackend,
    SqliteCheckpointerFactory,
    ReplicaConfigError,
    checkpointer_factory_from_settings,
    enforce_replica_safety,
    replica_safety_warnings,
    run_queue_backend_from_settings,
)


def _settings(**kw):
    return Settings(AEGIS_ALPHA_DATA_DIR="/tmp/aegis-test-data", **kw)


def test_defaults_keep_current_behaviour():
    s = _settings()
    factory = checkpointer_factory_from_settings(s, "/tmp/aegis-test-data/cp.db")
    assert isinstance(factory, SqliteCheckpointerFactory) and factory.durable and not factory.shared
    backend = run_queue_backend_from_settings(s)
    assert isinstance(backend, MessageQueueRegistry)
    assert replica_safety_warnings(s, factory) == []


def test_current_implementations_satisfy_the_protocols():
    assert isinstance(MessageQueueRegistry(), RunQueueBackend)
    assert isinstance(SqliteCheckpointerFactory("x.db"), CheckpointerFactory)
    assert isinstance(MemoryCheckpointerFactory(), CheckpointerFactory)


def test_queue_backend_contract_owner_and_closed_run():
    backend: RunQueueBackend = run_queue_backend_from_settings(_settings())
    alice = QueueOwner.from_any({"userId": "a", "tenantId": "t"})
    assert backend.open_run("run-1", alice) is backend.open_run("run-1", alice)  # idempotent
    with pytest.raises(Exception) as foreign:
        backend.enqueue("run-1", "steer", "x", QueueOwner.from_any({"userId": "b", "tenantId": "t"}))
    assert "OWNER" in str(getattr(foreign.value, "code", foreign.value)).upper()
    backend.enqueue("run-1", "follow_up", "later", alice)
    left = backend.finish_run("run-1", "completed")
    assert [m.content for m in left] == ["later"]
    with pytest.raises(Exception):
        backend.enqueue("run-1", "steer", "x", alice)


@pytest.mark.parametrize("name", ["postgres", "redis"])
def test_planned_backends_fail_loudly_not_silently(name):
    with pytest.raises(BackendUnavailable):
        checkpointer_factory_from_settings(_settings(AEGIS_ALPHA_CHECKPOINTER_BACKEND=name), "x.db")
    with pytest.raises(BackendUnavailable):
        run_queue_backend_from_settings(_settings(AEGIS_ALPHA_RUN_QUEUE_BACKEND=name))


def test_unknown_backend_rejected():
    with pytest.raises(ValueError):
        checkpointer_factory_from_settings(_settings(AEGIS_ALPHA_CHECKPOINTER_BACKEND="mongo"), "x.db")


def test_multi_replica_on_local_backends_warns():
    s = _settings(AEGIS_ALPHA_REPLICAS=3)
    warnings = replica_safety_warnings(s, checkpointer_factory_from_settings(s, "x.db"))
    assert len(warnings) == 2 and any("sticky" in w for w in warnings)


def test_multi_replica_on_local_backends_fails_fast_without_override():
    s = _settings(AEGIS_ALPHA_REPLICAS=2)
    with pytest.raises(ReplicaConfigError, match="AEGIS_ALPHA_ALLOW_LOCAL_BACKENDS_MULTI_REPLICA"):
        enforce_replica_safety(s, checkpointer_factory_from_settings(s, "x.db"))


def test_multi_replica_override_downgrades_to_warnings():
    s = _settings(AEGIS_ALPHA_REPLICAS=2, AEGIS_ALPHA_ALLOW_LOCAL_BACKENDS_MULTI_REPLICA="true")
    warnings = enforce_replica_safety(s, checkpointer_factory_from_settings(s, "x.db"))
    assert len(warnings) == 2 and all("allowed by" in w for w in warnings)


def test_single_replica_needs_no_override():
    s = _settings()
    assert enforce_replica_safety(s, checkpointer_factory_from_settings(s, "x.db")) == []


def test_lifespan_refuses_unsafe_multi_replica():
    from unittest.mock import AsyncMock, patch

    from fastapi.testclient import TestClient

    from app.config import settings
    from app.main import app

    with (
        patch("app.main.market_data") as market_data,
        patch("app.main.memory_store_manager"),
        patch("app.main.get_backend_client"),
        patch.object(settings, "node_execution_token", "test-service-token"),
        patch.object(settings, "replicas", 2),
    ):
        market_data.start = AsyncMock()
        with pytest.raises(ReplicaConfigError):
            with TestClient(app):
                pass
        market_data.start.assert_not_called()


@pytest.mark.asyncio
async def test_memory_factory_open_close():
    factory = MemoryCheckpointerFactory()
    saver = await factory.open()
    assert saver is await factory.open()
    await factory.close()
    assert await factory.open() is not saver


@pytest.mark.asyncio
async def test_sqlite_factory_round_trip(tmp_path):
    factory = SqliteCheckpointerFactory(str(tmp_path / "cp.db"))
    try:
        saver = await factory.open()
    except ImportError:
        pytest.skip("langgraph-checkpoint-sqlite not installed")
    assert hasattr(saver, "aget_tuple")
    await factory.close()
