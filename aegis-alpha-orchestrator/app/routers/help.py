"""Help loop execution router."""

from __future__ import annotations

from typing import Any

from fastapi import APIRouter
from pydantic import BaseModel, Field

from ..core.help_loop import DEFAULT_CORPUS, run_help_loop

router = APIRouter(tags=["help"])


class HelpExecuteRequest(BaseModel):
    message: str = ""
    roles: list[str] = Field(default_factory=list)
    corpus: list[dict[str, Any]] | None = None
    intent: dict[str, Any] | None = None
    max_rounds: int = 3

    class Config:
        populate_by_name = True


@router.post("/help/execute")
async def help_execute(body: HelpExecuteRequest) -> dict[str, Any]:
    """Run bounded help loop (retrieve/grade/≤3/escalate). Mock-friendly, no free invent of private IDs."""
    message = body.message or ""
    # Hard rule: never invent private facts when message lacks them
    corpus = body.corpus if body.corpus is not None else DEFAULT_CORPUS
    result = run_help_loop(
        message=message,
        corpus=corpus,
        roles=body.roles,
        max_rounds=body.max_rounds or 3,
    )
    response = result.to_response()
    response["domain"] = "PRODUCT_HELP"
    response["routeAction"] = "HELP_GRAPH"
    if body.intent:
        response["intent"] = body.intent
    return response
