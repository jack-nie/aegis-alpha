"""FastAPI application entry point."""

from __future__ import annotations

import logging
import uuid
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware

from .config import settings
from .routers import health, workflow, intent

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
    yield
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
