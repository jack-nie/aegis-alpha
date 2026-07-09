"""Tests for tool registry and role allowlists."""

from app.config import Settings
from app.core.tools import create_tools
from app.core.tool_registry import (
    TOOL_ROLES,
    TOOL_SPECS,
    create_tool_registry,
    resolve_agent_roles,
    tool_names_for_roles,
    tools_for_roles,
)
from app.models.workflow import Node, NodeData


def _settings() -> Settings:
    return Settings(AEGIS_ALPHA_LANGCHAIN_API_KEY="test")


def test_create_tools_still_returns_six_tools():
    tools = create_tools(_settings())
    assert isinstance(tools, list)
    assert len(tools) == 6


def test_create_tool_registry_maps_all_tools():
    registry = create_tool_registry(_settings())
    assert len(registry) == 6
    for name in TOOL_SPECS:
        assert name in registry


def test_fundamentals_role_excludes_portfolio_tools():
    registry = create_tool_registry(_settings())
    tools = tools_for_roles(registry, ["fundamentals"])
    names = {t.name for t in tools}

    assert "get_stock_quote" in names
    assert "get_financials" in names
    assert "get_company_overview" in names
    assert "get_portfolio_positions" not in names
    assert "get_portfolio_summary" not in names
    assert "get_news" not in names


def test_portfolio_role_includes_portfolio_tools():
    registry = create_tool_registry(_settings())
    tools = tools_for_roles(registry, ["portfolio"])
    names = {t.name for t in tools}

    assert "get_portfolio_positions" in names
    assert "get_portfolio_summary" in names
    assert "get_stock_quote" not in names
    assert "get_financials" not in names


def test_news_role_only_news():
    names = set(tool_names_for_roles(["news"]))
    assert names == {"get_news"}


def test_supervisor_gets_all_phase1_tools():
    names = set(tool_names_for_roles(["supervisor"]))
    assert names == set(TOOL_SPECS.keys())


def test_general_excludes_portfolio():
    names = set(tool_names_for_roles(["general"]))
    assert "get_news" in names
    assert "get_stock_quote" in names
    assert "get_portfolio_positions" not in names


def test_tool_specs_sandbox_classes():
    assert TOOL_SPECS["get_stock_quote"].sandbox_class == "research"
    assert TOOL_SPECS["get_portfolio_positions"].sandbox_class == "portfolio_read"
    assert TOOL_SPECS["get_portfolio_summary"].side_effect == "read"


def test_tool_roles_constant_covers_phase1():
    expected = {
        "fundamentals",
        "news",
        "valuation",
        "risk",
        "portfolio",
        "general",
        "supervisor",
    }
    assert expected <= set(TOOL_ROLES)


def test_resolve_agent_roles_from_fundamentals_agent_id():
    node = Node(
        id="fundamentals",
        data=NodeData(handler="general.agent", agentId="specialist_fundamentals"),
    )
    assert resolve_agent_roles(node) == ["fundamentals"]


def test_resolve_agent_roles_from_handler_heuristics():
    assert resolve_agent_roles(
        Node(id="n", data=NodeData(handler="finance.industry_news"))
    ) == ["news"]
    assert resolve_agent_roles(
        Node(id="n", data=NodeData(handler="finance.fundamental_analysis"))
    ) == ["fundamentals"]
    assert resolve_agent_roles(
        Node(id="n", data=NodeData(handler="finance.valuation_analysis"))
    ) == ["valuation"]
    assert resolve_agent_roles(
        Node(id="n", data=NodeData(handler="finance.risk_assessment"))
    ) == ["risk"]
    assert resolve_agent_roles(
        Node(id="n", data=NodeData(handler="general.agent"))
    ) == ["general"]


def test_tools_for_roles_news_excludes_portfolio():
    registry = create_tool_registry(_settings())
    tools = tools_for_roles(registry, ["news"])
    names = {t.name for t in tools}
    assert "get_news" in names
    assert "get_portfolio_positions" not in names
    assert "get_portfolio_summary" not in names
    assert "get_stock_quote" not in names
