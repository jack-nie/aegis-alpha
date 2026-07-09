"""FastAPI application entry point."""

from __future__ import annotations

import logging
import uuid
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from .config import settings
from .dependencies import market_data, memory_store_manager
from .core.tools import get_backend_client
from .routers import health, workflow, intent

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
    await market_data.start()
    await memory_store_manager.initialize()
    yield
    await market_data.close()
    await get_backend_client(settings).close()
    await memory_store_manager.cleanup()
    await memory_store_manager.close()
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
    allow_origins=["*"],
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

    expected = (settings.node_execution_token or "").strip()
    if not expected:
        return JSONResponse(
            status_code=503,
            content={"ok": False, "error": "service_token_not_configured", "code": "AUTHZ_DENIED"},
        )

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
