"""Workflow domain models."""

from __future__ import annotations

from typing import Any
from pydantic import BaseModel, Field


class NodeData(BaseModel):
    """Node data container."""

    label: str = ""
    title: str = ""
    handler: str = ""
    function_name: str = ""
    node_type: str = ""
    type: str = ""
    prompt: str = ""
    agent_id: str = ""
    agent_name: str = ""

    class Config:
        extra = "allow"


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

    name: str
    value: Any = None
    weight: float = 0.5


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
