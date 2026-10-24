"""Help loop execution router.

The orchestrator holds no help corpus of its own: Java (``KnowledgeService`` /
``HelpLoopService``) is the single retrieval implementation and sends the chunks it
already selected and ACL pre-filtered. Arbitrary corpus injection is not accepted.
"""

from __future__ import annotations

import logging
from typing import Any

from fastapi import APIRouter
from pydantic import BaseModel, Field, ConfigDict

from ..core.help_loop import run_help_loop

logger = logging.getLogger(__name__)

router = APIRouter(tags=["help"])

MAX_CHUNKS = 20


class HelpExecuteRequest(BaseModel):
    message: str = ""
    roles: list[str] = Field(default_factory=list)
    # Java-selected, ACL pre-filtered chunks. Unknown fields (e.g. legacy ``corpus``) are ignored.
    chunks: list[dict[str, Any]] = Field(default_factory=list)
    intent: dict[str, Any] | None = None
    max_rounds: int = 3

    model_config = ConfigDict(populate_by_name=True, extra="ignore")


@router.post("/help/execute")
async def help_execute(body: HelpExecuteRequest) -> dict[str, Any]:
    """Draft a help answer from caller-provided chunks (retrieve/grade/≤3/escalate).

    Never invents private facts: with no chunks the loop escalates.
    """
    message = body.message or ""
    chunks = list(body.chunks or [])[:MAX_CHUNKS]
    result = run_help_loop(
        message=message,
        corpus=chunks,
        roles=body.roles,
        max_rounds=body.max_rounds or 3,
    )
    response = result.to_response()
    response["domain"] = "PRODUCT_HELP"
    response["routeAction"] = "HELP_GRAPH"
    if body.intent:
        response["intent"] = body.intent
    return response
