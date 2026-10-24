"""Workflow domain models."""

from __future__ import annotations

from typing import Any
from pydantic import BaseModel, Field, ConfigDict


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

    model_config = ConfigDict(extra="allow", populate_by_name=True)


class Node(BaseModel):
    """Workflow node."""

    id: str
    data: NodeData = Field(default_factory=NodeData)

    model_config = ConfigDict(extra="allow")


class Edge(BaseModel):
    """Workflow edge connecting two nodes."""

    source: str
    target: str
    condition: dict[str, Any] | None = None

    model_config = ConfigDict(extra="allow")


class AgentTemplate(BaseModel):
    """Agent template configuration."""

    agent_id: str = ""
    name: str = ""
    model_name: str = ""
    prompt: str = ""

    model_config = ConfigDict(extra="allow", populate_by_name=True)


class Signal(BaseModel):
    """Analysis signal."""

    name: str = ""
    value: Any = None
    weight: float = 0.5

    model_config = ConfigDict(extra="allow")

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
    """Node execution trace entry.

    Accepts both snake_case and camelCase keys (workflow engine emits camelCase
    for SSE / Java consumers).
    """

    node_id: str = Field(default="", alias="nodeId")
    node_name: str = Field(default="", alias="nodeName")
    handler: str = ""
    status: str = ""
    ok: bool = False
    degraded: bool = False
    started_at: str = Field(default="", alias="startedAt")
    completed_at: str = Field(default="", alias="completedAt")
    duration_ms: int = Field(default=0, alias="durationMs")

    model_config = ConfigDict(extra="allow", populate_by_name=True)
