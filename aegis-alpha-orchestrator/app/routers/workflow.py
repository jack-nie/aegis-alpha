"""Workflow execution router."""

from contextlib import nullcontext
from fastapi import APIRouter, Request
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field
from typing import Any

from ..models.requests import WorkflowRequest, NodeRequest
from ..models.responses import WorkflowResult, NodeResult
from ..dependencies import workflow_engine, node_executor
from ..core.tools import use_authorization

router = APIRouter(tags=["workflow"])


class ResumeRequest(BaseModel):
    thread_id: str = Field(..., alias="threadId")
    resume_value: dict[str, Any] | None = Field(default=None, alias="resumeValue")

    class Config:
        populate_by_name = True


def _authorization_context(delegated_token: str | None):
    """Scope tool Authorization to a run-scoped delegated token when present."""
    if not delegated_token or not str(delegated_token).strip():
        return nullcontext()
    return use_authorization(str(delegated_token).strip())


@router.post("/resume-workflow")
async def resume_workflow(body: ResumeRequest):
    """Resume a workflow that was interrupted by an approval gate."""
    try:
        result = await workflow_engine.resume_workflow(
            thread_id=body.thread_id,
            resume_value=body.resume_value or {"approved": True},
        )
        return result
    except Exception as e:
        return WorkflowResult(
            ok=False,
            error=str(e),
            final_state={},
        )


@router.post("/stream-workflow")
async def stream_workflow(request: Request, body: WorkflowRequest):
    """SSE streaming workflow execution."""
    import json

    async def event_generator():
        # use_authorization resets override on context exit (including generator teardown)
        with _authorization_context(body.delegated_token):
            async for event in workflow_engine.stream_workflow(
                nodes=body.nodes,
                edges=body.edges,
                state=body.state,
                subject=body.subject,
                require_approval=body.require_approval,
                thread_id=body.thread_id,
            ):
                yield f"event: {event.event}\ndata: {json.dumps(event.data)}\n\n"

    return StreamingResponse(
        event_generator(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            "X-Accel-Buffering": "no",
        },
    )


@router.post("/stream-workflow-tokens")
async def stream_workflow_tokens(request: Request, body: WorkflowRequest):
    import json

    async def event_generator():
        with _authorization_context(body.delegated_token):
            async for event in workflow_engine.stream_workflow_tokens(
                nodes=body.nodes,
                edges=body.edges,
                state=body.state,
                subject=body.subject,
                require_approval=body.require_approval,
                thread_id=body.thread_id,
            ):
                yield f"event: {event.event}\ndata: {json.dumps(event.data)}\n\n"

    return StreamingResponse(
        event_generator(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            "X-Accel-Buffering": "no",
        },
    )


@router.post("/execute-workflow", response_model=WorkflowResult)
async def execute_workflow(request: Request, body: WorkflowRequest) -> WorkflowResult:
    """Non-streaming workflow execution."""
    try:
        with _authorization_context(body.delegated_token):
            result = await workflow_engine.execute_workflow(
                nodes=body.nodes,
                edges=body.edges,
                state=body.state,
                subject=body.subject,
                require_approval=body.require_approval,
                thread_id=body.thread_id,
            )
        return result
    except Exception as e:
        return WorkflowResult(
            ok=False,
            error=str(e),
            final_state=body.state,
        )


@router.post("/execute-node", response_model=NodeResult)
async def execute_node(body: NodeRequest) -> NodeResult:
    """Execute a single workflow node."""
    try:
        return await node_executor.execute(
            node=body.node,
            state=body.state,
            subject=body.subject or "",
            agent=body.agent,
            api_key=body.api_key,
            base_url=body.base_url,
            provider=body.provider,
            model=body.model,
        )
    except Exception as e:
        return NodeResult(
            ok=False,
            status="error",
            handler="logic",
            node_id=body.node.id,
            node_name=body.node.id,
            summary=str(e),
        )


@router.post("/execute-agent", response_model=NodeResult)
async def execute_agent(body: NodeRequest) -> NodeResult:
    """Execute a single agent node."""
    try:
        result = await node_executor.execute(
            node=body.node,
            state=body.state,
            subject=body.subject or "",
            agent=body.agent,
            api_key=body.api_key,
            base_url=body.base_url,
            provider=body.provider,
            model=body.model,
        )
        # Add agent metadata
        if body.agent:
            result.data["agentId"] = body.agent.agent_id
            result.data["agentName"] = body.agent.name
        result.data["stateSize"] = len(body.state)
        return result
    except Exception as e:
        return NodeResult(
            ok=False,
            status="error",
            handler="logic",
            node_id=body.node.id,
            node_name=body.node.id,
            summary=str(e),
        )
