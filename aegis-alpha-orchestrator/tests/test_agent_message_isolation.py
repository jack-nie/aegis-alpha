"""P2-8: tool-loop messages are isolated per general.agent; multi-successor agents fan out."""
import pytest
from langchain_core.messages import AIMessage, HumanMessage, ToolMessage
from langchain_core.tools import tool
from langgraph.checkpoint.memory import MemorySaver

from app.models.workflow import Edge, Node, NodeData


@tool
def echo(tag: str) -> str:
    """Echo a tag back."""
    return f"echo:{tag}"


def _install_fake_agent(executor, seen: dict):
    from app.core.llm_client import AgentToolResult, LLMResponse

    async def fake_invoke(node, state, subject="", tools=None, prior_messages=None, force_final=False, **_):
        if not prior_messages:
            ai = AIMessage(
                content="",
                tool_calls=[{"name": "echo", "args": {"tag": node.id}, "id": f"call-{node.id}"}],
            )
            return AgentToolResult(ai, has_tool_calls=True, seed_human=HumanMessage(content=f"task {node.id}"))
        seen[node.id] = list(prior_messages)
        tool_texts = [m.content for m in prior_messages if isinstance(m, ToolMessage)]
        return AgentToolResult(
            AIMessage(content=f"done {node.id}"),
            has_tool_calls=False,
            llm_response=LLMResponse(summary=f"{node.id} saw {tool_texts}", confidence=0.6),
        )

    executor.invoke_agent_with_tools = fake_invoke


@pytest.mark.asyncio
@pytest.mark.parametrize("mode", ["shared_tools", "registry_like_per_agent"])
async def test_parallel_agents_do_not_see_each_others_tool_calls(mock_node_executor, mode):
    from app.core.workflow_engine import WorkflowEngine

    WorkflowEngine._graph_cache.clear()
    seen: dict = {}
    _install_fake_agent(mock_node_executor, seen)
    engine = WorkflowEngine(mock_node_executor, tools=[echo], checkpointer=MemorySaver())
    if mode == "registry_like_per_agent":
        engine._tools_for_node = lambda node: [echo]
    nodes = [
        Node(id="start", data=NodeData(handler="start", nodeType="start")),
        Node(id="agent_a", data=NodeData(handler="general.agent", nodeType="agent")),
        Node(id="agent_b", data=NodeData(handler="general.agent", nodeType="agent")),
        Node(id="end", data=NodeData(handler="end", nodeType="end")),
    ]
    edges = [
        Edge(source="start", target="agent_a"),
        Edge(source="start", target="agent_b"),
        Edge(source="agent_a", target="end"),
        Edge(source="agent_b", target="end"),
    ]
    try:
        result = await engine.execute_workflow(nodes, edges, {"ticker": "AAPL"}, "isolation")
        assert result.ok, result.error
        assert set(seen) == {"agent_a", "agent_b"}
        for agent_id, other in (("agent_a", "agent_b"), ("agent_b", "agent_a")):
            tool_msgs = [m for m in seen[agent_id] if isinstance(m, ToolMessage)]
            assert [m.content for m in tool_msgs] == [f"echo:{agent_id}"]
            assert all(other not in str(m.content) for m in seen[agent_id])
        summaries = {k: v["summary"] for k, v in result.node_outputs.items() if k.startswith("agent_")}
        assert summaries == {
            "agent_a": "agent_a saw ['echo:agent_a']",
            "agent_b": "agent_b saw ['echo:agent_b']",
        }
    finally:
        WorkflowEngine._graph_cache.clear()


@pytest.mark.asyncio
async def test_agent_with_multiple_successors_reaches_all_of_them(mock_node_executor):
    from app.core.workflow_engine import WorkflowEngine

    WorkflowEngine._graph_cache.clear()
    _install_fake_agent(mock_node_executor, {})
    engine = WorkflowEngine(mock_node_executor, tools=[echo], checkpointer=MemorySaver())
    nodes = [
        Node(id="start", data=NodeData(handler="start", nodeType="start")),
        Node(id="agent", data=NodeData(handler="general.agent", nodeType="agent")),
        Node(id="left", data=NodeData(handler="end", nodeType="end")),
        Node(id="right", data=NodeData(handler="end", nodeType="end")),
    ]
    edges = [
        Edge(source="start", target="agent"),
        Edge(source="agent", target="left"),
        Edge(source="agent", target="right"),
    ]
    try:
        result = await engine.execute_workflow(nodes, edges, {"ticker": "AAPL"}, "fan-out")
        assert result.ok, result.error
        visited = {t.node_id for t in result.trace}
        assert {"left", "right"} <= visited, visited
    finally:
        WorkflowEngine._graph_cache.clear()
