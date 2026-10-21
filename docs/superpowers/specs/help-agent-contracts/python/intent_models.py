"""Design contracts: extended intent models for Help / Support routing.

Normative for P0 implementation. Not imported by production runtime.
"""

from __future__ import annotations

from enum import Enum
from typing import Any

from pydantic import BaseModel, ConfigDict, Field


class IntentDomain(str, Enum):
    RESEARCH = "RESEARCH"
    PRODUCT_HELP = "PRODUCT_HELP"
    RUN_DIAGNOSIS = "RUN_DIAGNOSIS"
    CHITCHAT = "CHITCHAT"


class Complexity(str, Enum):
    """L0=playbook, L1=vanilla RAG, L2=help_graph agent."""

    L0 = "L0"
    L1 = "L1"
    L2 = "L2"


class RouteAction(str, Enum):
    DISPATCH_WORKFLOW = "DISPATCH_WORKFLOW"
    PLAYBOOK = "PLAYBOOK"
    KNOWLEDGE_RAG = "KNOWLEDGE_RAG"
    HELP_GRAPH = "HELP_GRAPH"
    COPILOT = "COPILOT"
    CLARIFY = "CLARIFY"


class PageContext(BaseModel):
    """UI-injected context from the current console page."""

    model_config = ConfigDict(extra="ignore")

    path: str | None = None
    run_id: str | None = Field(default=None, alias="runId")
    workflow_key: str | None = Field(default=None, alias="workflowKey")
    ticker: str | None = None
    recommendation_id: str | None = Field(default=None, alias="recommendationId")
    portfolio_id: str | None = Field(default=None, alias="portfolioId")


class IntentEntities(BaseModel):
    model_config = ConfigDict(populate_by_name=True, serialize_by_alias=True)

    run_id: str | None = Field(default=None, alias="runId")
    ticker: str | None = None
    path: str | None = None
    portfolio_id: str | None = Field(default=None, alias="portfolioId")
    recommendation_id: str | None = Field(default=None, alias="recommendationId")


class ExtendedIntentResult(BaseModel):
    """Backward-compatible extension of orchestrator IntentResult."""

    model_config = ConfigDict(populate_by_name=True, serialize_by_alias=True)

    # Existing fields (Java IntentRouterService)
    workflow_key: str | None = Field(default=None, alias="workflowKey")
    ticker: str | None = None
    confidence: float = 0.0
    source: str = ""
    reason: str | None = None
    error: str | None = None
    data: dict[str, Any] = Field(default_factory=dict)

    # New fields
    domain: IntentDomain = IntentDomain.CHITCHAT
    complexity: Complexity = Complexity.L0
    route_action: RouteAction = Field(default=RouteAction.COPILOT, alias="routeAction")
    needs_clarification: bool = Field(default=False, alias="needsClarification")
    clarification_prompt: str | None = Field(default=None, alias="clarificationPrompt")
    entities: IntentEntities = Field(default_factory=IntentEntities)
    cacheable: bool = False

    def to_java_map(self) -> dict[str, Any]:
        """Shape expected by ChatService / IntentRouterService consumers."""
        return self.model_dump(by_alias=True, exclude_none=False)


class SourceRef(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    type: str  # playbook | chunk | run | doc
    id: str
    title: str | None = None
    path: str | None = None


class ChatMessageResponse(BaseModel):
    """Unified POST /_backend/chat/messages response envelope."""

    model_config = ConfigDict(populate_by_name=True, serialize_by_alias=True)

    thread_id: str = Field(alias="threadId")
    content: str
    message: str
    ok: bool = True
    domain: IntentDomain = IntentDomain.CHITCHAT
    complexity: Complexity = Complexity.L0
    route_action: RouteAction = Field(default=RouteAction.COPILOT, alias="routeAction")
    provider: str = ""
    routed_to_workflow: bool = Field(default=False, alias="routedToWorkflow")
    run_id: str | None = Field(default=None, alias="runId")
    workflow_key: str | None = Field(default=None, alias="workflowKey")
    run_status: str | None = Field(default=None, alias="runStatus")
    needs_clarification: bool = Field(default=False, alias="needsClarification")
    clarification_prompt: str | None = Field(default=None, alias="clarificationPrompt")
    escalate_human: bool = Field(default=False, alias="escalateHuman")
    source_refs: list[SourceRef] = Field(default_factory=list, alias="sourceRefs")
    cache_hit: bool = Field(default=False, alias="cacheHit")
    disclaimer: str | None = None
