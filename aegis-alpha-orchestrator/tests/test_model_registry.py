"""P4-6: model registry — node/agent model takes effect, fallbacks are explicit and traced."""
from pathlib import Path
from unittest.mock import AsyncMock, MagicMock

import pytest
from langchain_core.messages import AIMessage
from langchain_core.tools import tool

from app.core.llm_client import AgentToolResult, LLMResponse
from app.core.model_registry import ModelRegistry, get_model_registry
from app.models.workflow import AgentTemplate, Edge, Node, NodeData

DEEPSEEK_URL = "https://api.deepseek.com/v1"


@tool
def get_stock_quote(symbol: str) -> dict:
    """Quote."""
    return {"symbol": symbol, "price": 1}


def _registry(**prices):
    return ModelRegistry.from_mapping(
        {
            "allowUnlisted": False,
            "endpointDefaults": {"deepseek": "deepseek-v4-flash"},
            "models": {
                "deepseek-v4-flash": {"endpoint": "deepseek", "pricing": prices},
                "deepseek-v4-pro": {"endpoint": "deepseek"},
                "gpt-x": {"endpoint": "openai"},
            },
        }
    )


def test_packaged_registry_prices_come_from_documented_source():
    """D7: peak prices from https://api-docs.deepseek.com/quick_start/pricing (accessed 2026-10-24)."""
    registry = get_model_registry()
    assert registry.loaded
    assert {"deepseek-v4-flash", "deepseek-v4-pro"} <= set(registry.names())
    usage = {"inputTokens": 1000, "outputTokens": 100}
    assert registry.estimate_cost("deepseek-v4-flash", usage) == pytest.approx(1000 * 0.3e-6 + 100 * 1.2e-6)
    cached = {"inputTokens": 1_000_000, "outputTokens": 0, "cacheReadTokens": 1_000_000}
    assert registry.estimate_cost("deepseek-v4-pro", cached) == pytest.approx(0.044)
    source = (Path(__file__).resolve().parents[1] / "app" / "core" / "models.yaml").read_text(encoding="utf-8")
    assert "https://api-docs.deepseek.com/quick_start/pricing" in source
    assert "accessed 2026-10-24" in source


def test_resolve_rules():
    reg = _registry()
    assert reg.resolve(None, default="deepseek-v4-flash", base_url="").as_trace() == {
        "requestedModel": None, "effectiveModel": "deepseek-v4-flash",
    }
    assert reg.resolve("deepseek-v4-pro", default="deepseek-v4-flash", base_url=DEEPSEEK_URL).effective == "deepseek-v4-pro"
    unknown = reg.resolve("made-up", default="deepseek-v4-flash", base_url=DEEPSEEK_URL)
    assert (unknown.effective, unknown.fallback_reason) == ("deepseek-v4-flash", "unknown_model")
    mismatch = reg.resolve("gpt-x", default="deepseek-v4-flash", base_url=DEEPSEEK_URL)
    assert (mismatch.effective, mismatch.fallback_reason) == ("deepseek-v4-flash", "endpoint_mismatch")
    # Custom / proxy endpoint: every listed model is accepted.
    assert reg.resolve("gpt-x", default="deepseek-v4-flash", base_url="https://proxy.internal/v1").effective == "gpt-x"


def test_unfit_default_on_known_endpoint_uses_family_default():
    res = _registry().resolve(None, default="gpt-4", base_url=DEEPSEEK_URL)
    assert res.effective == "deepseek-v4-flash"
    assert res.fallback_reason == "default_endpoint_mismatch"


def test_estimate_cost_only_with_configured_prices():
    reg = _registry(inputPerMTok=1.0, outputPerMTok=2.0)
    assert reg.estimate_cost("deepseek-v4-flash", {"inputTokens": 1_000_000, "outputTokens": 500_000}) == pytest.approx(2.0)
    # cache reads without a cache-read price: unknown, not guessed
    assert reg.estimate_cost("deepseek-v4-flash", {"inputTokens": 10, "outputTokens": 1, "cacheReadTokens": 5}) is None
    reg2 = _registry(inputPerMTok=1.0, outputPerMTok=2.0, cacheReadPerMTok=0.1)
    cost = reg2.estimate_cost("deepseek-v4-flash", {"inputTokens": 1_000_000, "outputTokens": 0, "cacheReadTokens": 500_000})
    assert cost == pytest.approx(0.5 + 0.05)
    assert reg.estimate_cost("unknown", {"inputTokens": 1}) is None


def test_missing_registry_file_keeps_legacy_endpoint_rule(tmp_path):
    reg = ModelRegistry.load(tmp_path / "missing.yaml")
    assert not reg.loaded
    assert reg.resolve("gpt-4", default="gpt-4", base_url="").effective == "gpt-4"
    res = reg.resolve("gpt-4", default="deepseek-v4-flash", base_url=DEEPSEEK_URL)
    assert res.effective == "deepseek-v4-flash" and res.fallback_reason == "endpoint_mismatch"


def test_requested_model_priority(mock_node_executor):
    node = Node(id="n", data=NodeData(handler="general.agent", modelName="deepseek-v4-pro"))
    agent = AgentTemplate(agent_id="a", model_name="deepseek-v4-flash")
    assert mock_node_executor.requested_model_for(node, agent, "explicit") == "explicit"
    assert mock_node_executor.requested_model_for(node, agent) == "deepseek-v4-pro"
    assert mock_node_executor.requested_model_for(Node(id="m"), agent) == "deepseek-v4-flash"
    assert mock_node_executor.requested_model_for(Node(id="m")) is None


@pytest.mark.asyncio
async def test_execute_uses_node_model_and_records_resolution(mock_node_executor):
    mock_node_executor._llm_client.invoke = AsyncMock(return_value=LLMResponse(summary="ok", confidence=0.6))
    node = Node(id="n", data=NodeData(handler="finance.market_analysis", label="M", modelName="deepseek-v4-pro"))
    result = await mock_node_executor.execute(node=node, state={}, subject="AAPL")
    assert mock_node_executor._llm_client.invoke.await_args.kwargs["model"] == "deepseek-v4-pro"
    assert result.model == "deepseek-v4-pro"
    assert result.data["modelResolution"] == {"requestedModel": "deepseek-v4-pro", "effectiveModel": "deepseek-v4-pro"}


@pytest.mark.asyncio
async def test_execute_unknown_model_falls_back_visibly(mock_node_executor):
    mock_node_executor._llm_client.invoke = AsyncMock(return_value=LLMResponse(summary="ok", confidence=0.6))
    node = Node(id="n", data=NodeData(handler="finance.market_analysis", modelName="not-a-model"))
    result = await mock_node_executor.execute(node=node, state={}, subject="AAPL")
    default = mock_node_executor._config.model
    assert mock_node_executor._llm_client.invoke.await_args.kwargs["model"] == default
    assert result.data["modelResolution"]["modelFallbackReason"] == "unknown_model"
    assert result.data["modelResolution"]["requestedModel"] == "not-a-model"


@pytest.mark.asyncio
async def test_agent_tool_loop_uses_node_model_and_trace_shows_it(mock_node_executor):
    from langgraph.checkpoint.memory import MemorySaver

    from app.core.workflow_engine import WorkflowEngine

    llm = MagicMock()
    llm.invoke_agent_with_tools = AsyncMock(
        return_value=AgentToolResult(AIMessage(content="done"), llm_response=LLMResponse(summary="done", confidence=0.7))
    )
    mock_node_executor._llm_client = llm
    nodes = [
        Node(id="start", data=NodeData(handler="start", nodeType="start")),
        Node(id="agent", data=NodeData(handler="general.agent", nodeType="agent", label="A", modelName="deepseek-v4-pro")),
        Node(id="end", data=NodeData(handler="end", nodeType="end")),
    ]
    edges = [Edge(source="start", target="agent"), Edge(source="agent", target="end")]
    WorkflowEngine._graph_cache.clear()
    try:
        engine = WorkflowEngine(mock_node_executor, tools=[get_stock_quote], checkpointer=MemorySaver())
        result = await engine.execute_workflow(nodes, edges, {"ticker": "AAPL"}, "s")
    finally:
        WorkflowEngine._graph_cache.clear()
    assert result.ok, result.error
    assert llm.invoke_agent_with_tools.await_args.kwargs["model"] == "deepseek-v4-pro"
    assert result.node_outputs["agent"]["model"] == "deepseek-v4-pro"
    entry = [t for t in result.trace if t.node_id == "agent"][-1].model_dump(by_alias=True)
    assert entry["requestedModel"] == "deepseek-v4-pro" and entry["effectiveModel"] == "deepseek-v4-pro"
