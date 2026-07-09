"""Published research multi-specialist workflow layout builders.

Pure data builders for workflow templates (nodes + edges dict). No LangGraph
compile is performed here — layouts are compatible with Node/Edge models and
WorkflowEngine.build_graph when converted.
"""

from __future__ import annotations

from typing import Any


def _node(
    node_id: str,
    *,
    handler: str,
    label: str,
    prompt: str | None = None,
    agent_id: str | None = None,
    node_type: str | None = None,
) -> dict[str, Any]:
    data: dict[str, Any] = {
        "handler": handler,
        "label": label,
    }
    if prompt is not None:
        data["prompt"] = prompt
    if agent_id is not None:
        data["agentId"] = agent_id
    if node_type is not None:
        data["nodeType"] = node_type
    return {"id": node_id, "data": data}


def _edge(source: str, target: str) -> dict[str, str]:
    return {"source": source, "target": target}


def _chain_edges(node_ids: list[str]) -> list[dict[str, str]]:
    return [_edge(node_ids[i], node_ids[i + 1]) for i in range(len(node_ids) - 1)]


def _subject_prompt(template: str, subject_placeholder: str) -> str:
    """Fill {subject} in a prompt template with a publish-time placeholder."""
    return template.replace("{subject}", subject_placeholder)


def build_single_name_research_layout(
    *,
    subject_placeholder: str = "{subject}",
    include_portfolio: bool = False,
    require_approval: bool = False,
) -> dict[str, Any]:
    """Build a sequential multi-specialist single-name research layout.

    Topology (sequential DAG — engine-friendly equivalent of fan-in to aggregate)::

        start
          → fundamentals (finance.fundamental_analysis)
          → news (finance.industry_news)
          → valuation (finance.valuation_analysis)
          → risk (finance.risk_assessment)
          → [optional portfolio_context via general.agent]
          → aggregate (finance.stock_recommendation_aggregate)
          → end

    Returns a plain dict suitable as a published workflow template.
    """
    specialists = [
        "fundamentals",
        "news",
        "valuation",
        "risk",
        "critique_aggregate",
    ]

    nodes: list[dict[str, Any]] = [
        _node("start", handler="start", label="Start", node_type="start"),
        _node(
            "fundamentals",
            handler="finance.fundamental_analysis",
            label="Fundamentals",
            prompt=_subject_prompt(
                "Perform deep fundamental analysis for {subject}. "
                "Cover revenue, margins, cash generation, and balance sheet strength. "
                "Use only numbers from context. Output in Chinese.",
                subject_placeholder,
            ),
            agent_id="specialist_fundamentals",
        ),
        _node(
            "news",
            handler="finance.industry_news",
            label="Industry News",
            prompt=_subject_prompt(
                "Summarize recent industry and company news affecting {subject}. "
                "Cover regulatory changes, deals, and market developments. "
                "Use only data from context. Output in Chinese.",
                subject_placeholder,
            ),
            agent_id="specialist_news",
        ),
        _node(
            "valuation",
            handler="finance.valuation_analysis",
            label="Valuation",
            prompt=_subject_prompt(
                "Perform valuation analysis for {subject}. "
                "Cover multiples, peer context, and fair value considerations. "
                "Use only numbers from context. Output in Chinese.",
                subject_placeholder,
            ),
            agent_id="specialist_valuation",
        ),
        _node(
            "risk",
            handler="finance.risk_assessment",
            label="Risk Assessment",
            prompt=_subject_prompt(
                "Assess comprehensive risks for {subject}. "
                "Cover market, industry, company-specific, and liquidity risks. "
                "Use only data from context. Output in Chinese.",
                subject_placeholder,
            ),
            agent_id="specialist_risk",
        ),
    ]

    ordered_ids = ["start", "fundamentals", "news", "valuation", "risk"]

    if include_portfolio:
        specialists.insert(-1, "portfolio_context")
        nodes.append(
            _node(
                "portfolio_context",
                handler="general.agent",
                label="Portfolio Context",
                prompt=_subject_prompt(
                    "You are a portfolio context specialist. "
                    "Given holdings and portfolio summary for the desk, "
                    "summarize how {subject} fits existing exposure, "
                    "concentration, and risk contribution. "
                    "Do not invent positions. Output in Chinese.",
                    subject_placeholder,
                ),
                agent_id="specialist_portfolio",
            )
        )
        ordered_ids.append("portfolio_context")

    nodes.append(
        _node(
            "aggregate",
            handler="finance.stock_recommendation_aggregate",
            label="Recommendation Aggregate",
            prompt=_subject_prompt(
                "Generate an investment research report for {subject} by synthesizing "
                "upstream specialist outputs (fundamentals, news, valuation, risk"
                + (", portfolio context" if include_portfolio else "")
                + "). "
                "One of: BUY | HOLD | SELL | INSUFFICIENT_DATA. "
                "If key data is missing, use INSUFFICIENT_DATA. "
                "Do not invent prices or financials. Output narrative in Chinese.",
                subject_placeholder,
            ),
            agent_id="critique_aggregate",
        )
    )
    nodes.append(_node("end", handler="end", label="End", node_type="end"))
    ordered_ids.extend(["aggregate", "end"])

    return {
        "workflowKey": "stock_analysis_v2",
        "engine": "langgraph",
        "nodes": nodes,
        "edges": _chain_edges(ordered_ids),
        "metadata": {
            "specialists": specialists,
            "pattern": "fan_in_aggregate",
            "subjectPlaceholder": subject_placeholder,
            "requireApproval": require_approval,
            "includePortfolio": include_portfolio,
        },
    }


def build_earnings_reaction_layout(
    *,
    subject_placeholder: str = "{subject}",
    require_approval: bool = False,
) -> dict[str, Any]:
    """Build an earnings-reaction research layout.

    Topology::

        start
          → market_analysis
          → financial_interpretation
          → industry_news
          → stock_recommendation_aggregate
          → end
    """
    ordered_ids = [
        "start",
        "market_analysis",
        "financial_interpretation",
        "industry_news",
        "aggregate",
        "end",
    ]
    nodes = [
        _node("start", handler="start", label="Start", node_type="start"),
        _node(
            "market_analysis",
            handler="finance.market_analysis",
            label="Market Reaction",
            prompt=_subject_prompt(
                "Analyze post-earnings market price reaction for {subject}. "
                "Cover price move, volume, valuation metrics, and short-term trend. "
                "Use only numbers from context. Output in Chinese.",
                subject_placeholder,
            ),
            agent_id="specialist_market",
        ),
        _node(
            "financial_interpretation",
            handler="finance.financial_interpretation",
            label="Earnings Interpretation",
            prompt=_subject_prompt(
                "Interpret the latest earnings and financial statements for {subject}. "
                "Cover revenue, margins, surprises vs expectations when present, "
                "and balance sheet quality. Use only numbers from context. Output in Chinese.",
                subject_placeholder,
            ),
            agent_id="specialist_financials",
        ),
        _node(
            "industry_news",
            handler="finance.industry_news",
            label="Industry & Guidance News",
            prompt=_subject_prompt(
                "Summarize earnings-related industry and company news for {subject}. "
                "Cover guidance, peer reactions, and regulatory items. "
                "Use only data from context. Output in Chinese.",
                subject_placeholder,
            ),
            agent_id="specialist_news",
        ),
        _node(
            "aggregate",
            handler="finance.stock_recommendation_aggregate",
            label="Earnings Recommendation",
            prompt=_subject_prompt(
                "Synthesize earnings reaction research for {subject} into a recommendation. "
                "One of: BUY | HOLD | SELL | INSUFFICIENT_DATA. "
                "If earnings or price data is missing, use INSUFFICIENT_DATA. "
                "Output narrative in Chinese.",
                subject_placeholder,
            ),
            agent_id="critique_aggregate",
        ),
        _node("end", handler="end", label="End", node_type="end"),
    ]
    return {
        "workflowKey": "earnings_reaction",
        "engine": "langgraph",
        "nodes": nodes,
        "edges": _chain_edges(ordered_ids),
        "metadata": {
            "specialists": [
                "market",
                "financials",
                "news",
                "critique_aggregate",
            ],
            "pattern": "earnings_reaction",
            "subjectPlaceholder": subject_placeholder,
            "requireApproval": require_approval,
        },
    }


def build_watchlist_digest_layout(
    *,
    subject_placeholder: str = "{subject}",
    require_approval: bool = False,
) -> dict[str, Any]:
    """Build a watchlist morning-digest layout.

    Topology::

        start
          → market_analysis
          → industry_news
          → risk_assessment
          → end

    Uses risk_assessment as a short closing summary (no full recommendation
    aggregate) so digest runs stay lightweight.
    """
    ordered_ids = [
        "start",
        "market_analysis",
        "industry_news",
        "risk_assessment",
        "end",
    ]
    nodes = [
        _node("start", handler="start", label="Start", node_type="start"),
        _node(
            "market_analysis",
            handler="finance.market_analysis",
            label="Watchlist Market Snapshot",
            prompt=_subject_prompt(
                "Provide a concise market snapshot for watchlist name {subject}. "
                "Cover price, day change, volume, and key valuation metrics. "
                "Use only numbers from context. Output in Chinese.",
                subject_placeholder,
            ),
            agent_id="specialist_market",
        ),
        _node(
            "industry_news",
            handler="finance.industry_news",
            label="Watchlist News",
            prompt=_subject_prompt(
                "Summarize overnight / morning news relevant to {subject} for a digest. "
                "Keep bullets short and evidence-based. Output in Chinese.",
                subject_placeholder,
            ),
            agent_id="specialist_news",
        ),
        _node(
            "risk_assessment",
            handler="finance.risk_assessment",
            label="Digest Risk Summary",
            prompt=_subject_prompt(
                "Produce a short risk digest for {subject} suitable for a morning watchlist. "
                "Highlight only material risks and changes. Output in Chinese.",
                subject_placeholder,
            ),
            agent_id="specialist_risk",
        ),
        _node("end", handler="end", label="End", node_type="end"),
    ]
    return {
        "workflowKey": "watchlist_digest",
        "engine": "langgraph",
        "nodes": nodes,
        "edges": _chain_edges(ordered_ids),
        "metadata": {
            "specialists": ["market", "news", "risk"],
            "pattern": "watchlist_digest",
            "subjectPlaceholder": subject_placeholder,
            "requireApproval": require_approval,
        },
    }
