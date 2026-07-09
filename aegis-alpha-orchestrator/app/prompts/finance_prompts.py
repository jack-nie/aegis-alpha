"""Financial analysis prompt templates."""

from __future__ import annotations


class PromptManager:
    """Manages prompt templates for different analysis handlers."""

    _prompts: dict[str, str] = {
        "finance.market_analysis": (
            "Analyze the current market price and valuation metrics for {subject}. "
            "Cover: current price, market cap, P/E ratio, P/B ratio, dividend yield, "
            "EPS, 52-week range, trading volume, and price trend. "
            "Use actual numbers from the context. Output in Chinese."
        ),
        "finance.industry_share": (
            "Analyze the industry position and competitive landscape for {subject}. "
            "Cover: market share, industry ranking, competitive advantages, "
            "key competitors, industry growth rate, and barriers to entry. "
            "Use actual data from the context. Output in Chinese."
        ),
        "finance.sentiment_monitor": (
            "Assess the current market sentiment for {subject}. "
            "Cover: analyst consensus and target prices, institutional holdings changes, "
            "retail investor sentiment, media coverage tone, and insider trading activity. "
            "Use actual data from the context. Output in Chinese."
        ),
        "finance.tech_breakthrough": (
            "Analyze recent technology or product breakthroughs for {subject}. "
            "Cover: new product launches, R&D pipeline, patent filings, "
            "technology partnerships, and competitive differentiation. "
            "Use actual data from the context. Output in Chinese."
        ),
        "finance.industry_news": (
            "Summarize recent industry news affecting {subject}. "
            "Cover: regulatory changes, industry trends, major deals, "
            "policy impacts, and market developments. "
            "Use actual data from the context. Output in Chinese."
        ),
        "general.agent": (
            "You are {agent_name}, an AI investment research agent. "
            "Analyze the provided data for {subject} and generate insights. "
            "Reference actual values from marketDataContext. "
            "Output in Chinese."
        ),
        "general.web_search": (
            "Based on the search results, provide evidence-based analysis for {subject}. "
            "Cite specific sources and data points. Output in English."
        ),
        "finance.financial_interpretation": (
            "Interpret the financial statements for {subject}. "
            "Cover: revenue growth, profit margins, ROE, cash flow, "
            "debt levels, working capital, and key financial ratios. "
            "Use actual numbers from the context. Output in Chinese."
        ),
        "finance.stock_recommendation_aggregate": (
            "Generate an investment research report for {subject}.\n\n"
            "## Required Sections\n\n"
            "### 1. Company Overview\n"
            "Brief description, industry, market cap, key metrics (only if present in context).\n\n"
            "### 2. Market & Valuation\n"
            "Current price, multiples, ranges — use only numbers from marketDataContext.\n\n"
            "### 3. Fundamental Analysis\n"
            "Revenue, earnings, margins when available in context.\n\n"
            "### 4. Bull / Bear Thesis\n"
            "Balanced bull and bear points grounded in evidence.\n\n"
            "### 5. Key Risks\n"
            "List 3-5 specific risks.\n\n"
            "### 6. Recommendation\n"
            "One of: BUY | HOLD | SELL | INSUFFICIENT_DATA. "
            "If quote or financials are missing/stale/unreliable, you MUST use INSUFFICIENT_DATA "
            "and list missing fields. Do not invent prices, PE, EPS, or target prices.\n\n"
            "End with a JSON block:\n"
            "```json\n"
            '{"recommendation":"HOLD|BUY|SELL|INSUFFICIENT_DATA","confidence":0.0,'
            '"timeHorizon":"3M|6M|12M","missingData":[],"degraded":false}\n'
            "```\n"
            "Output narrative in Chinese; keep the final JSON keys in English."
        ),
        "finance.fundamental_analysis": (
            "Perform deep fundamental analysis for {subject}. "
            "Cover: revenue breakdown, profit drivers, margin trends, "
            "return on capital, cash generation, and balance sheet strength. "
            "Use actual numbers from the context. Output in Chinese."
        ),
        "finance.technical_analysis": (
            "Analyze technical indicators for {subject}. "
            "Cover: moving averages (5/10/20/60/120/250 day), RSI, MACD, KDJ, "
            "Bollinger Bands, volume analysis, support/resistance levels. "
            "Use actual data from the context. Output in Chinese."
        ),
        "finance.valuation_analysis": (
            "Perform valuation analysis for {subject}. "
            "Cover: P/E, P/B, P/S, PEG ratio, EV/EBITDA, DCF considerations, "
            "comparison with industry averages, and fair value estimate. "
            "Use actual numbers from the context. Output in Chinese."
        ),
        "finance.money_flow_analysis": (
            "Analyze money flow patterns for {subject}. "
            "Cover: institutional vs retail flow, large order analysis, "
            "northbound capital flow, margin trading data, and volume-price relationship. "
            "Use actual data from the context. Output in Chinese."
        ),
        "finance.risk_assessment": (
            "Assess comprehensive risks for {subject}. "
            "Cover: market risk, industry risk, company-specific risk, "
            "regulatory risk, liquidity risk, and ESG risks. "
            "Use actual data from the context. Output in Chinese."
        ),
        "finance.peer_comparison": (
            "Compare {subject} with industry peers. "
            "Cover: valuation multiples, growth rates, profitability, "
            "market share, competitive position, and relative attractiveness. "
            "Use actual data from the context. Output in Chinese."
        ),
        "finance.catalyst_analysis": (
            "Identify potential catalysts for {subject}. "
            "Cover: upcoming earnings, product launches, regulatory approvals, "
            "M&A activity, industry events, and policy changes. "
            "Use actual data from the context. Output in Chinese."
        ),
        "finance.thesis_builder": (
            "Build bull and bear investment theses for {subject}. "
            "Bull case: 3-5 supporting arguments with evidence. "
            "Bear case: 3-5 risk factors with evidence. "
            "Probability-weighted outcome assessment. "
            "Use actual data from the context. Output in Chinese."
        ),
        "finance.risk_reward_analysis": (
            "Analyze risk-reward profile for {subject}. "
            "Cover: upside potential (%), downside risk (%), "
            "risk-reward ratio, key scenarios, and position sizing suggestion. "
            "Use actual data from the context. Output in Chinese."
        ),
        "finance.entry_strategy": (
            "Design entry strategy for {subject}. "
            "Cover: ideal entry price range, position sizing, "
            "stop-loss levels, take-profit targets, and timing considerations. "
            "Use actual data from the context. Output in Chinese."
        ),
    }

    @classmethod
    def get_prompt(cls, handler: str) -> str:
        """Get prompt template for handler."""
        return cls._prompts.get(handler, cls._prompts["general.agent"])

    @classmethod
    def register_prompt(cls, handler: str, prompt: str) -> None:
        """Register a new prompt template."""
        cls._prompts[handler] = prompt

    @classmethod
    def has_handler(cls, handler: str) -> bool:
        """Check if handler has a registered prompt."""
        return handler in cls._prompts
