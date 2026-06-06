"""Request DTOs."""

from __future__ import annotations

from typing import Any
from pydantic import BaseModel, Field

from .workflow import Node, Edge, AgentTemplate


class WorkflowRequest(BaseModel):
    """Request for workflow execution."""

    nodes: list[Node] = Field(default_factory=list)
    edges: list[Edge] = Field(default_factory=list)
    state: dict[str, Any] = Field(default_factory=dict)
    subject: str = "Aegis Alpha workflow"
    require_approval: bool = False
    thread_id: str | None = Field(default=None, alias="threadId")
    api_key: str | None = Field(default=None, alias="apiKey")
    base_url: str | None = Field(default=None, alias="baseUrl")
    provider: str | None = None
    model: str | None = None

    class Config:
        populate_by_name = True


class NodeRequest(BaseModel):
    """Request for single node execution."""

    node: Node = Field(default_factory=lambda: Node(id="inline"))
    state: dict[str, Any] = Field(default_factory=dict)
    agent: AgentTemplate | None = None
    subject: str | None = None
    api_key: str | None = Field(default=None, alias="apiKey")
    base_url: str | None = Field(default=None, alias="baseUrl")
    provider: str | None = None
    model: str | None = None

    class Config:
        populate_by_name = True


class IntentRequest(BaseModel):
    """Request for intent classification."""

    message: str = ""
    workflows: list[dict[str, Any]] = Field(default_factory=list)
    api_key: str | None = Field(default=None, alias="apiKey")
    base_url: str | None = Field(default=None, alias="baseUrl")
    model: str | None = None

    class Config:
        populate_by_name = True
