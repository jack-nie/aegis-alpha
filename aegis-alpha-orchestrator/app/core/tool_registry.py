"""Tool registry with role-based allowlists for Phase 1 specialists."""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

from ..config import Settings

# Phase 1 specialist / agent roles that may bind tools.
TOOL_ROLES: frozenset[str] = frozenset(
    {
        "fundamentals",
        "news",
        "valuation",
        "risk",
        "portfolio",
        "general",
        "supervisor",
    }
)


@dataclass(frozen=True)
class ToolSpec:
    """Metadata for a registered tool (name, side effects, sandbox, roles)."""

    name: str
    side_effect: str = "read"
    sandbox_class: str = "research"  # research | portfolio_read
    roles: frozenset[str] = field(default_factory=frozenset)


# Default role map for Phase 1. Portfolio tools are not on fundamentals-only agents.
TOOL_SPECS: dict[str, ToolSpec] = {
    "get_stock_quote": ToolSpec(
        name="get_stock_quote",
        side_effect="read",
        sandbox_class="research",
        roles=frozenset({"fundamentals", "valuation", "general", "supervisor"}),
    ),
    "get_financials": ToolSpec(
        name="get_financials",
        side_effect="read",
        sandbox_class="research",
        roles=frozenset({"fundamentals", "valuation", "general", "supervisor"}),
    ),
    "get_company_overview": ToolSpec(
        name="get_company_overview",
        side_effect="read",
        sandbox_class="research",
        roles=frozenset({"fundamentals", "valuation", "general", "supervisor"}),
    ),
    "get_news": ToolSpec(
        name="get_news",
        side_effect="read",
        sandbox_class="research",
        roles=frozenset({"news", "general", "supervisor"}),
    ),
    "get_portfolio_positions": ToolSpec(
        name="get_portfolio_positions",
        side_effect="read",
        sandbox_class="portfolio_read",
        roles=frozenset({"portfolio", "supervisor"}),
    ),
    "get_portfolio_summary": ToolSpec(
        name="get_portfolio_summary",
        side_effect="read",
        sandbox_class="portfolio_read",
        roles=frozenset({"portfolio", "supervisor"}),
    ),
}


def create_tool_registry(config: Settings) -> dict[str, Any]:
    """Build name -> LangChain tool mapping for all registered tools."""
    from .tools import create_tools

    tools = create_tools(config)
    return {tool.name: tool for tool in tools}


def tools_for_roles(registry: dict[str, Any], roles: list[str]) -> list:
    """Return tools whose allowlist intersects the requested roles (stable order by TOOL_SPECS)."""
    role_set = set(roles)
    selected: list = []
    for name, spec in TOOL_SPECS.items():
        if name not in registry:
            continue
        if spec.roles & role_set:
            selected.append(registry[name])
    return selected


def tool_names_for_roles(roles: list[str]) -> list[str]:
    """Return tool names allowed for the given roles (no live tool instances)."""
    role_set = set(roles)
    return [name for name, spec in TOOL_SPECS.items() if spec.roles & role_set]
