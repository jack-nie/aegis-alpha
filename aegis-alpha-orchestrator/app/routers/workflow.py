"""Workflow execution router."""

from fastapi import APIRouter, Request
from fastapi.responses import StreamingResponse

from ..models.requests import WorkflowRequest, NodeRequest
from ..models.responses import WorkflowResult, NodeResult
from ..core.workflow_engine import WorkflowEngine
from ..core.node_executor import NodeExecutor
from ..core.llm_client import LLMClient
from ..core.market_data import MarketDataService
from ..config import settings

router = APIRouter(tags=["workflow"])

# Dependencies
_llm_client = LLMClient(settings)
_market_data = MarketDataService(settings)
_node_executor = NodeExecutor(settings, _llm_client, _market_data)
_workflow_engine = WorkflowEngine(_node_executor)


@router.post("/stream-workflow")
async def stream_workflow(request: Request, body: WorkflowRequest):
    """SSE streaming workflow execution."""
    import json

    async def event_generator():
        async for event in _workflow_engine.stream_workflow(
            nodes=body.nodes,
            edges=body.edges,
            state=body.state,
            subject=body.subject,
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
        result = await _workflow_engine.execute_workflow(
            nodes=body.nodes,
            edges=body.edges,
            state=body.state,
            subject=body.subject,
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
        return await _node_executor.execute(
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
        result = await _node_executor.execute(
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
