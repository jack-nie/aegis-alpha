"""FastAPI application entry point."""

from __future__ import annotations

import logging
import uuid
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from .config import settings
from .core.run_backends import checkpointer_factory_from_settings, replica_safety_warnings
from .dependencies import CHECKPOINT_DB_PATH, market_data, memory_store_manager, workflow_engine
from .core.tools import get_backend_client
from .routers import health, workflow, intent, help as help_router, message_queue

# Paths that do not require service token (health/docs only)
_PUBLIC_PATH_PREFIXES = (
    "/health",
    "/docs",
    "/openapi.json",
    "/redoc",
)

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s - %(name)s - %(levelname)s - %(message)s",
)
logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Application lifespan events."""
    logger.info(f"Starting Aegis Alpha Orchestrator on port {settings.port}")
    logger.info(f"Provider: {settings.provider}, Model: {settings.model}")
    logger.info(f"Mock mode: {settings.is_mock_mode}")
    # Decision D6: dev conveniences never start in production (fatal, the process exits).
    fatal = settings.production_safety_problems
    if fatal:
        logger.critical("Refusing to start in production: %s", ", ".join(fatal))
        raise RuntimeError(f"production safety check failed: {fatal}")
    problem = settings.service_token_problem
    if problem:
        logger.warning(
            "Service token unusable (%s): non-public routes will be rejected. "
            "Set AEGIS_ALPHA_NODE_EXECUTION_TOKEN (example value only with AEGIS_ALPHA_DEV_MODE=true "
            "or AEGIS_ALPHA_LANGCHAIN_MOCK=true).",
            problem,
        )
    await market_data.start()
    await memory_store_manager.initialize()
    # P4-16: checkpointer behind CheckpointerFactory (sqlite default; memory for demos).
    checkpointer_factory = checkpointer_factory_from_settings(settings, CHECKPOINT_DB_PATH)
    for warning in replica_safety_warnings(settings, checkpointer_factory):
        logger.warning("[REPLICAS] %s", warning)
    try:
        workflow_engine.attach_checkpointer(await checkpointer_factory.open())
        logger.info("Checkpointer attached: backend=%s durable=%s", checkpointer_factory.name,
                    checkpointer_factory.durable)
    except ImportError:
        logger.warning("langgraph-checkpoint-sqlite/aiosqlite missing; checkpoints stay in memory")
    yield
    await market_data.close()
    await get_backend_client(settings).close()
    await memory_store_manager.cleanup()
    await memory_store_manager.close()
    from langgraph.checkpoint.memory import MemorySaver

    workflow_engine.attach_checkpointer(MemorySaver())
    await checkpointer_factory.close()
    logger.info("Shutting down Aegis Alpha Orchestrator")


# Create FastAPI app
app = FastAPI(
    title="Aegis Alpha Orchestrator",
    description="LangGraph-based workflow orchestration engine",
    version="0.2.0",
    lifespan=lifespan,
)

# CORS middleware
app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.cors_origins,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


def _extract_bearer(authorization: str | None) -> str | None:
    if not authorization:
        return None
    value = authorization.strip()
    if value.lower().startswith("bearer "):
        return value[7:].strip()
    return value


@app.middleware("http")
async def service_auth_middleware(request: Request, call_next):
    """Require service token on non-public routes (fail-closed when token configured)."""
    path = request.url.path or ""
    if any(path == p or path.startswith(p + "/") for p in _PUBLIC_PATH_PREFIXES):
        return await call_next(request)

    problem = settings.service_token_problem
    if problem:
        return JSONResponse(
            status_code=503,
            content={"ok": False, "error": problem, "code": "AUTHZ_DENIED"},
        )
    expected = (settings.node_execution_token or "").strip()

    provided = _extract_bearer(request.headers.get("authorization"))
    if provided != expected:
        # Also accept X-Service-Token for mesh callers
        provided = (request.headers.get("x-service-token") or "").strip() or provided
    if provided != expected:
        return JSONResponse(
            status_code=401,
            content={"ok": False, "error": "unauthorized", "code": "AUTHZ_DENIED"},
        )
    return await call_next(request)


@app.middleware("http")
async def request_context_middleware(request: Request, call_next):
    """Inject request context (request-id, trace-id, client-ip)."""
    request_id = request.headers.get("x-request-id") or str(uuid.uuid4())
    trace_id = request.headers.get("x-trace-id") or request.headers.get("traceparent")
    xff = request.headers.get("x-forwarded-for", "")
    client_ip = xff.split(",")[0].strip() if xff else (
        request.headers.get("x-real-ip") or request.client.host if request.client else "unknown"
    )
    user_agent = request.headers.get("user-agent")

    # Store in request state
    request.state.request_id = request_id
    request.state.trace_id = trace_id
    request.state.client_ip = client_ip
    request.state.user_agent = user_agent

    response = await call_next(request)
    response.headers["X-Request-Id"] = request_id
    return response


# Include routers
app.include_router(health.router)
app.include_router(workflow.router)
app.include_router(intent.router)
app.include_router(help_router.router)
app.include_router(message_queue.router)


def start():
    """Start the application (used by CLI)."""
    import uvicorn

    uvicorn.run(
        "app.main:app",
        host=settings.host,
        port=settings.port,
        reload=False,
        log_level="info",
    )


if __name__ == "__main__":
    start()
