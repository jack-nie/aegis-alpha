"""Response DTOs."""

from __future__ import annotations

from typing import Any
from pydantic import BaseModel, ConfigDict, Field

from .workflow import Signal, Source, TraceEntry


class NodeResult(BaseModel):
    """Standardized node execution result."""

    ok: bool = True
    status: str = "completed"
    handler: str = "logic"
    node_id: str = ""
    node_name: str = ""
    subject: str = ""
    summary: str = ""
    signals: list[Signal] = Field(default_factory=list)
    sources: list[Source] = Field(default_factory=list)
    confidence: float = 0.5
    data: dict[str, Any] = Field(default_factory=dict)
    duration_ms: int = 0
    degraded: bool = False
    content: str | None = None
    message: str | None = None
    provider: str = ""
    model: str = ""


class WorkflowResult(BaseModel):
    """Workflow execution result."""

    ok: bool = True
    final_state: dict[str, Any] = Field(default_factory=dict)
    trace: list[TraceEntry] = Field(default_factory=list)
    node_outputs: dict[str, Any] = Field(default_factory=dict)
    state: dict[str, Any] = Field(default_factory=dict)
    request_id: str | None = None
    trace_id: str | None = None
    error: str | None = None


class IntentResult(BaseModel):
    """Intent classification result.

    Serializes workflow_key as workflowKey for Java IntentRouterService.
    """

    model_config = ConfigDict(populate_by_name=True, serialize_by_alias=True)

    workflow_key: str | None = Field(default=None, alias="workflowKey")
    ticker: str | None = None
    confidence: float = 0.0
    source: str = ""
    reason: str | None = None
    error: str | None = None
    # Optional extras (e.g. normalized symbol); ignored by older Java consumers
    data: dict[str, Any] = Field(default_factory=dict)


class HealthResponse(BaseModel):
    """Health check response."""

    ok: bool = True
    engine: str = "langgraph"
    port: int = 8787
    provider: str = ""
    model: str = ""
    has_api_key: bool = False
    base_url_configured: bool = False
    mock: bool = False


class SSEEvent(BaseModel):
    """Server-Sent Event."""

    event: str
    data: dict[str, Any] = Field(default_factory=dict)
