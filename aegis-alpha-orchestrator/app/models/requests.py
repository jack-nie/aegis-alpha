"""Request DTOs."""

from __future__ import annotations

import logging
from typing import Any
from pydantic import BaseModel, Field, model_validator

from .workflow import Node, Edge, AgentTemplate

logger = logging.getLogger(__name__)


class _IgnoresRequestCredentials(BaseModel):
    """P2-10: ``apiKey`` / ``baseUrl`` are still accepted on the wire for backward compatibility, but
    are never used: the orchestrator only uses its own configured credentials. A warning (without
    the values) is logged when a caller still sends them, then the fields are cleared."""

    @model_validator(mode="after")
    def _drop_request_credentials(self):
        sent = [name for name in ("api_key", "base_url") if getattr(self, name, None)]
        if sent:
            logger.warning(
                "Ignoring request-level credentials %s on %s; using orchestrator configuration",
                sent, type(self).__name__,
            )
            for name in sent:
                object.__setattr__(self, name, None)
        return self


class WorkflowRequest(_IgnoresRequestCredentials):
    """Request for workflow execution."""

    nodes: list[Node] = Field(default_factory=list)
    edges: list[Edge] = Field(default_factory=list)
    state: dict[str, Any] = Field(default_factory=dict)
    subject: str = "Aegis Alpha workflow"
    require_approval: bool = Field(default=False, alias="requireApproval")
    thread_id: str | None = Field(default=None, alias="threadId")
    api_key: str | None = Field(default=None, alias="apiKey")
    base_url: str | None = Field(default=None, alias="baseUrl")
    provider: str | None = None
    model: str | None = None
    # Short-lived portfolio:read delegation from Java (camelCase wire name).
    delegated_token: str | None = Field(default=None, alias="delegatedToken")

    class Config:
        populate_by_name = True


class NodeRequest(_IgnoresRequestCredentials):
    """Request for single node execution."""

    node: Node = Field(default_factory=lambda: Node(id="inline"))
    state: dict[str, Any] = Field(default_factory=dict)
    agent: AgentTemplate | None = None
    subject: str | None = None
    api_key: str | None = Field(default=None, alias="apiKey")
    base_url: str | None = Field(default=None, alias="baseUrl")
    provider: str | None = None
    model: str | None = None
    delegated_token: str | None = Field(default=None, alias="delegatedToken")

    class Config:
        populate_by_name = True


class IntentRequest(_IgnoresRequestCredentials):
    """Request for intent classification."""

    message: str = ""
    workflows: list[dict[str, Any]] = Field(default_factory=list)
    api_key: str | None = Field(default=None, alias="apiKey")
    base_url: str | None = Field(default=None, alias="baseUrl")
    model: str | None = None

    class Config:
        populate_by_name = True
