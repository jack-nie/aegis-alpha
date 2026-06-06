"""Workflow domain models."""

from __future__ import annotations

from typing import Any
from pydantic import BaseModel, Field


class NodeData(BaseModel):
    """Node data container."""

    label: str = ""
    title: str = ""
    handler: str = ""
    function_name: str = Field(default="", alias="functionName")
    node_type: str = Field(default="", alias="nodeType")
    type: str = ""
    prompt: str = ""
    agent_id: str | None = Field(default="", alias="agentId")
    agent_name: str | None = Field(default="", alias="agentName")

    class Config:
        extra = "allow"
        populate_by_name = True


class Node(BaseModel):
    """Workflow node."""

    id: str
    data: NodeData = Field(default_factory=NodeData)

    class Config:
        extra = "allow"


class Edge(BaseModel):
    """Workflow edge connecting two nodes."""

    source: str
    target: str
    condition: dict[str, Any] | None = None

    class Config:
        extra = "allow"


class AgentTemplate(BaseModel):
    """Agent template configuration."""

    agent_id: str = ""
    name: str = ""
    model_name: str = ""
    prompt: str = ""

    class Config:
        extra = "allow"
        populate_by_name = True


class Signal(BaseModel):
    """Analysis signal."""

    name: str = ""
    value: Any = None
    weight: float = 0.5

    class Config:
        extra = "allow"

    def __init__(self, **data):
        # Normalize: use 'type' as 'name' if 'name' missing
        if not data.get("name") and data.get("type"):
            data["name"] = data["type"]
        super().__init__(**data)


class Source(BaseModel):
    """Data source reference."""

    title: str
    url: str = ""
    type: str = ""


class TraceEntry(BaseModel):
    """Node execution trace entry."""

    node_id: str
    node_name: str
    handler: str = ""
    status: str = ""
    ok: bool = False
    degraded: bool = False
    started_at: str = ""
    completed_at: str = ""
    duration_ms: int = 0
