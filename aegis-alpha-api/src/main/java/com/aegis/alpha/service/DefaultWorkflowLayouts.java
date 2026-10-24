package com.aegis.alpha.service;

import com.aegis.alpha.domain.WorkflowDefinition;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * P3-10: code-default workflow layouts (system graphs) and their version metadata, extracted
 * unchanged from {@link WorkflowService}. Pure functions; no Spring wiring.
 */
final class DefaultWorkflowLayouts {

    private DefaultWorkflowLayouts() {
    }

    /** Bump when research default graphs change so stale DB layouts are skipped on read. */
    /** 3 = parallel specialist fan-out → fan-in (P2-14); older un-customised defaults are refreshed. */
    /** P3-16: 4 = non-single-ticker closers moved to {@link #DIGEST_AGGREGATE_HANDLER}. */
    static final int CURRENT_RESEARCH_LAYOUT_VERSION = 4;
    static final String RECOMMENDATION_AGGREGATE_HANDLER = "finance.stock_recommendation_aggregate";
    /** P3-16: closer for daily / portfolio / position / exit / sector workflows (no BUY/SELL policy, no backtest). */
    static final String DIGEST_AGGREGATE_HANDLER = "finance.digest_aggregate";
    static final String DIGEST_LAYOUT_PATTERN = "digest";
    private static final Set<String> RESEARCH_LAYOUT_KEYS = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
            "stock_analysis",
            "earnings_reaction",
            "watchlist_digest"
    )));
    /** P3-16: system workflows whose closing node is the digest handler; versioned like research keys. */
    private static final Set<String> DIGEST_LAYOUT_KEYS = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
            "daily",
            "exit_workflow",
            "portfolio_workflow",
            "position_workflow",
            "sector-analyst-workflow"
    )));

    static Map<String, Object> defaultLayout(WorkflowDefinition definition) {
        Map<String, Object> layout = emptyLayout(definition.getWorkflowKey());
        layout.put("name", definition.getName());
        layout.put("engine", definition.getEngine() == null ? "langgraph" : definition.getEngine());
        if ("stock_recommendation_research".equals(definition.getWorkflowKey())) {
            layout.put("nodes", stockRecommendationNodes());
            layout.put("edges", stockRecommendationEdges());
            return layout;
        }
        if ("stock_analysis".equals(definition.getWorkflowKey())) {
            layout.put("nodes", stockAnalysisNodes());
            layout.put("edges", stockAnalysisEdges());
            attachResearchLayoutMetadata(layout, "fan_in_aggregate");
            return layout;
        }
        if ("daily".equals(definition.getWorkflowKey())) {
            layout.put("nodes", dailyNodes());
            layout.put("edges", dailyEdges());
            attachResearchLayoutMetadata(layout, DIGEST_LAYOUT_PATTERN);
            return layout;
        }
        if ("deep_dive".equals(definition.getWorkflowKey())) {
            layout.put("nodes", deepDiveNodes());
            layout.put("edges", deepDiveEdges());
            return layout;
        }
        if ("exit_workflow".equals(definition.getWorkflowKey())) {
            layout.put("nodes", exitWorkflowNodes());
            layout.put("edges", exitWorkflowEdges());
            attachResearchLayoutMetadata(layout, DIGEST_LAYOUT_PATTERN);
            return layout;
        }
        if ("portfolio_workflow".equals(definition.getWorkflowKey())) {
            layout.put("nodes", portfolioWorkflowNodes());
            layout.put("edges", portfolioWorkflowEdges());
            attachResearchLayoutMetadata(layout, DIGEST_LAYOUT_PATTERN);
            return layout;
        }
        if ("position_workflow".equals(definition.getWorkflowKey())) {
            layout.put("nodes", positionWorkflowNodes());
            layout.put("edges", positionWorkflowEdges());
            attachResearchLayoutMetadata(layout, DIGEST_LAYOUT_PATTERN);
            return layout;
        }
        if ("sector-analyst-workflow".equals(definition.getWorkflowKey())) {
            layout.put("nodes", sectorAnalystNodes());
            layout.put("edges", sectorAnalystEdges());
            attachResearchLayoutMetadata(layout, DIGEST_LAYOUT_PATTERN);
            return layout;
        }
        if ("telegram_hourly_news_digest".equals(definition.getWorkflowKey())) {
            layout.put("nodes", telegramDigestNodes());
            layout.put("edges", telegramDigestEdges());
            return layout;
        }
        if ("earnings_reaction".equals(definition.getWorkflowKey())) {
            layout.put("nodes", earningsReactionNodes());
            layout.put("edges", earningsReactionEdges());
            attachResearchLayoutMetadata(layout, "earnings_reaction");
            return layout;
        }
        if ("watchlist_digest".equals(definition.getWorkflowKey())) {
            layout.put("nodes", watchlistDigestNodes());
            layout.put("edges", watchlistDigestEdges());
            attachResearchLayoutMetadata(layout, "watchlist_digest");
            return layout;
        }
        List<Map<String, Object>> nodes = new ArrayList<>();
        nodes.add(flowNode("start", "Start", "start", 80, 260, "scheduler.manual", null));
        nodes.add(flowNode("context", "Load Context", "logic", 320, 260, "portfolio.get_context", null));
        nodes.add(flowNode("analyst", "Research Agent", "agent", 590, 260, "general.agent", "agent-preset-value-investing"));
        nodes.add(flowNode("end", "End", "end", 860, 260, "workflow.end", null));
        List<Map<String, Object>> edges = new ArrayList<>();
        edges.add(edge("start", "context"));
        edges.add(edge("context", "analyst"));
        edges.add(edge("analyst", "end"));
        layout.put("nodes", nodes);
        layout.put("edges", edges);
        return layout;
    }

    static void attachResearchLayoutMetadata(Map<String, Object> layout, String pattern) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("layoutVersion", CURRENT_RESEARCH_LAYOUT_VERSION);
        metadata.put("pattern", pattern);
        metadata.put("custom", false);
        layout.put("metadata", metadata);
    }

    /** Keys whose code-default layout is versioned and refreshed on read (research + P3-16 digest keys). */
    static boolean isResearchLayoutKey(String workflowKey) {
        return workflowKey != null && (RESEARCH_LAYOUT_KEYS.contains(workflowKey.trim())
                || DIGEST_LAYOUT_KEYS.contains(workflowKey.trim()));
    }

    /**
     * Research keys without layoutVersion / with version &lt; CURRENT are treated as stale
     * code defaults. User-marked custom layouts and version-stamped saves are never auto-refreshed.
     */
    @SuppressWarnings("unchecked")
    static boolean shouldRefreshResearchLayout(String workflowKey, Map<String, Object> layout) {
        if (!isResearchLayoutKey(workflowKey) || layout == null) {
            return false;
        }
        Object metaObj = layout.get("metadata");
        if (!(metaObj instanceof Map)) {
            return true;
        }
        Map<String, Object> metadata = (Map<String, Object>) metaObj;
        if (isCustomLayout(metadata)) {
            return false;
        }
        return layoutVersionOf(metadata) < CURRENT_RESEARCH_LAYOUT_VERSION;
    }

    static boolean isCustomLayout(Map<String, Object> metadata) {
        Object custom = metadata.get("custom");
        if (custom instanceof Boolean) {
            return Boolean.TRUE.equals(custom);
        }
        return custom != null && "true".equalsIgnoreCase(String.valueOf(custom).trim());
    }

    static int layoutVersionOf(Map<String, Object> metadata) {
        Object version = metadata.get("layoutVersion");
        if (version == null) {
            return 0;
        }
        if (version instanceof Number) {
            return ((Number) version).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(version).trim());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    @SuppressWarnings("unchecked")
    static void ensureLayoutVersionMetadata(Map<String, Object> layout, String workflowKey) {
        Object metaObj = layout.get("metadata");
        Map<String, Object> metadata;
        if (metaObj instanceof Map) {
            metadata = new LinkedHashMap<>((Map<String, Object>) metaObj);
        } else {
            metadata = new LinkedHashMap<>();
        }
        metadata.put("layoutVersion", CURRENT_RESEARCH_LAYOUT_VERSION);
        if (isResearchLayoutKey(workflowKey) && !metadata.containsKey("pattern")) {
            metadata.put("pattern", researchPattern(workflowKey));
        }
        layout.put("metadata", metadata);
    }

    static String researchPattern(String workflowKey) {
        if ("stock_analysis".equals(workflowKey)) {
            return "fan_in_aggregate";
        }
        if ("earnings_reaction".equals(workflowKey)) {
            return "earnings_reaction";
        }
        if ("watchlist_digest".equals(workflowKey)) {
            return "watchlist_digest";
        }
        if (workflowKey != null && DIGEST_LAYOUT_KEYS.contains(workflowKey)) {
            return DIGEST_LAYOUT_PATTERN;
        }
        return workflowKey;
    }

    static List<Map<String, Object>> stockRecommendationNodes() {
        List<Map<String, Object>> nodes = new ArrayList<>();
        nodes.add(flowNode("start", "Start", "start", 80, 260, "scheduler.manual", null));
        nodes.add(flowNode("market_analysis", "市场分析", "agent", 320, 120, "finance.market_analysis", null));
        nodes.add(flowNode("industry_share", "行业份额", "agent", 320, 260, "finance.industry_share", null));
        nodes.add(flowNode("sentiment_monitor", "舆情监测", "agent", 320, 400, "finance.sentiment_monitor", null));
        nodes.add(flowNode("tech_breakthrough", "技术突破", "agent", 590, 120, "finance.tech_breakthrough", null));
        nodes.add(flowNode("industry_news", "行业新闻", "agent", 590, 260, "finance.industry_news", null));
        nodes.add(flowNode("web_search", "网页搜索", "agent", 590, 400, "general.web_search", null));
        nodes.add(flowNode("financial_interpretation", "财务解读", "agent", 860, 260, "finance.financial_interpretation", null));
        nodes.add(flowNode("recommendation", "股票推荐聚合", "agent", 1130, 260, "finance.stock_recommendation_aggregate", null));
        nodes.add(flowNode("end", "End", "end", 1400, 260, "workflow.end", null));
        return nodes;
    }

    static List<Map<String, Object>> stockRecommendationEdges() {
        List<Map<String, Object>> edges = new ArrayList<>();
        edges.add(edge("start", "market_analysis"));
        edges.add(edge("market_analysis", "industry_share"));
        edges.add(edge("industry_share", "sentiment_monitor"));
        edges.add(edge("sentiment_monitor", "tech_breakthrough"));
        edges.add(edge("tech_breakthrough", "industry_news"));
        edges.add(edge("industry_news", "web_search"));
        edges.add(edge("web_search", "financial_interpretation"));
        edges.add(edge("financial_interpretation", "recommendation"));
        edges.add(edge("recommendation", "end"));
        return edges;
    }

    // Aligned with research_graph.build_single_name_research_layout (sequential multi-specialist).
    static List<Map<String, Object>> stockAnalysisNodes() {
        List<Map<String, Object>> nodes = new ArrayList<>();
        nodes.add(flowNode("start", "Start", "start", 80, 260, "scheduler.manual", null));
        nodes.add(flowNode("fundamentals", "Fundamentals", "agent", 340, 80, "finance.fundamental_analysis", null));
        nodes.add(flowNode("news", "Industry News", "agent", 340, 200, "finance.industry_news", null));
        nodes.add(flowNode("valuation", "Valuation", "agent", 340, 320, "finance.valuation_analysis", null));
        nodes.add(flowNode("risk", "Risk Assessment", "agent", 340, 440, "finance.risk_assessment", null));
        nodes.add(flowNode("aggregate", "Recommendation Aggregate", "agent", 620, 260, "finance.stock_recommendation_aggregate", null));
        nodes.add(flowNode("end", "End", "end", 880, 260, "workflow.end", null));
        return nodes;
    }

    static List<Map<String, Object>> stockAnalysisEdges() {
        List<Map<String, Object>> edges = new ArrayList<>();
        /* Parallel specialists fan in to the aggregate (orchestrator defers the join). */
        for (String specialist : Arrays.asList("fundamentals", "news", "valuation", "risk")) {
            edges.add(edge("start", specialist));
            edges.add(edge(specialist, "aggregate"));
        }
        edges.add(edge("aggregate", "end"));
        return edges;
    }

    // ===== daily =====
    static List<Map<String, Object>> dailyNodes() {
        List<Map<String, Object>> nodes = new ArrayList<>();
        nodes.add(flowNode("start", "Start", "start", 80, 260, "scheduler.manual", null));
        nodes.add(flowNode("market_overview", "Market Overview", "agent", 320, 120, "finance.market_analysis", null));
        nodes.add(flowNode("sector_rotation", "Sector Rotation", "agent", 320, 400, "finance.industry_share", null));
        nodes.add(flowNode("sentiment_pulse", "Sentiment Pulse", "agent", 600, 120, "finance.sentiment_monitor", null));
        nodes.add(flowNode("key_indicators", "Key Indicators", "agent", 600, 400, "finance.financial_interpretation", null));
        nodes.add(flowNode("daily_summary", "Daily Briefing", "agent", 880, 260, DIGEST_AGGREGATE_HANDLER, null));
        nodes.add(flowNode("end", "End", "end", 1140, 260, "workflow.end", null));
        return nodes;
    }
    static List<Map<String, Object>> dailyEdges() {
        List<Map<String, Object>> edges = new ArrayList<>();
        edges.add(edge("start", "market_overview"));
        edges.add(edge("start", "sector_rotation"));
        edges.add(edge("market_overview", "sentiment_pulse"));
        edges.add(edge("sector_rotation", "key_indicators"));
        edges.add(edge("sentiment_pulse", "daily_summary"));
        edges.add(edge("key_indicators", "daily_summary"));
        edges.add(edge("daily_summary", "end"));
        return edges;
    }

    // ===== deep_dive =====
    static List<Map<String, Object>> deepDiveNodes() {
        List<Map<String, Object>> nodes = new ArrayList<>();
        nodes.add(flowNode("start", "Start", "start", 80, 360, "scheduler.manual", null));
        nodes.add(flowNode("fundamental", "Fundamental", "agent", 260, 100, "finance.fundamental_analysis", "agent-preset-fundamental"));
        nodes.add(flowNode("technical", "Technical", "agent", 260, 280, "finance.technical_analysis", "agent-preset-technical"));
        nodes.add(flowNode("valuation", "Valuation", "agent", 260, 460, "finance.valuation_analysis", "agent-preset-value-investing"));
        nodes.add(flowNode("money_flow", "Money Flow", "agent", 480, 100, "finance.money_flow_analysis", null));
        nodes.add(flowNode("industry", "Industry", "agent", 480, 280, "finance.industry_share", null));
        nodes.add(flowNode("sentiment", "Sentiment", "agent", 480, 460, "finance.sentiment_monitor", null));
        nodes.add(flowNode("news", "News", "agent", 700, 100, "finance.industry_news", null));
        nodes.add(flowNode("tech_break", "Tech Breakthrough", "agent", 700, 280, "finance.tech_breakthrough", null));
        nodes.add(flowNode("risk", "Risk Assessment", "agent", 700, 460, "finance.risk_assessment", "agent-preset-risk-exit"));
        nodes.add(flowNode("peer_comp", "Peer Comparison", "agent", 920, 100, "finance.peer_comparison", null));
        nodes.add(flowNode("catalysts", "Catalyst Analysis", "agent", 920, 280, "finance.catalyst_analysis", null));
        nodes.add(flowNode("thesis", "Thesis Builder", "agent", 920, 460, "finance.thesis_builder", null));
        nodes.add(flowNode("risk_reward", "Risk-Reward", "agent", 1140, 180, "finance.risk_reward_analysis", null));
        nodes.add(flowNode("entry", "Entry Strategy", "agent", 1140, 380, "finance.entry_strategy", "agent-preset-price-action"));
        nodes.add(flowNode("recommendation", "Recommendation", "agent", 1360, 280, "finance.stock_recommendation_aggregate", null));
        nodes.add(flowNode("end", "End", "end", 1580, 280, "workflow.end", null));
        return nodes;
    }
    static List<Map<String, Object>> deepDiveEdges() {
        List<Map<String, Object>> edges = new ArrayList<>();
        edges.add(edge("start", "fundamental"));
        edges.add(edge("start", "technical"));
        edges.add(edge("start", "valuation"));
        edges.add(edge("fundamental", "money_flow"));
        edges.add(edge("technical", "industry"));
        edges.add(edge("valuation", "sentiment"));
        edges.add(edge("money_flow", "news"));
        edges.add(edge("industry", "tech_break"));
        edges.add(edge("sentiment", "risk"));
        edges.add(edge("news", "peer_comp"));
        edges.add(edge("tech_break", "catalysts"));
        edges.add(edge("risk", "thesis"));
        edges.add(edge("peer_comp", "risk_reward"));
        edges.add(edge("catalysts", "risk_reward"));
        edges.add(edge("thesis", "entry"));
        edges.add(edge("risk_reward", "recommendation"));
        edges.add(edge("entry", "recommendation"));
        edges.add(edge("recommendation", "end"));
        return edges;
    }

    // ===== exit_workflow =====
    static List<Map<String, Object>> exitWorkflowNodes() {
        List<Map<String, Object>> nodes = new ArrayList<>();
        nodes.add(flowNode("start", "Start", "start", 80, 260, "scheduler.manual", null));
        nodes.add(flowNode("position_check", "Position Check", "logic", 300, 140, "portfolio.get_positions", null));
        nodes.add(flowNode("pnl_analysis", "P&L Analysis", "agent", 300, 380, "finance.market_analysis", null));
        nodes.add(flowNode("stop_loss", "Stop Loss Review", "agent", 540, 140, "general.agent", null));
        nodes.add(flowNode("take_profit", "Take Profit Review", "agent", 540, 380, "general.agent", null));
        nodes.add(flowNode("signal_decay", "Signal Decay", "agent", 780, 140, "finance.risk_assessment", null));
        nodes.add(flowNode("news_risk", "News Risk Scan", "agent", 780, 380, "finance.industry_news", null));
        nodes.add(flowNode("exit_decision", "Exit Decision", "agent", 1020, 260, DIGEST_AGGREGATE_HANDLER, null));
        nodes.add(flowNode("end", "End", "end", 1260, 260, "workflow.end", null));
        return nodes;
    }
    static List<Map<String, Object>> exitWorkflowEdges() {
        List<Map<String, Object>> edges = new ArrayList<>();
        edges.add(edge("start", "position_check"));
        edges.add(edge("start", "pnl_analysis"));
        edges.add(edge("position_check", "stop_loss"));
        edges.add(edge("pnl_analysis", "take_profit"));
        edges.add(edge("stop_loss", "signal_decay"));
        edges.add(edge("take_profit", "news_risk"));
        edges.add(edge("signal_decay", "exit_decision"));
        edges.add(edge("news_risk", "exit_decision"));
        edges.add(edge("exit_decision", "end"));
        return edges;
    }

    // ===== portfolio_workflow =====
    static List<Map<String, Object>> portfolioWorkflowNodes() {
        List<Map<String, Object>> nodes = new ArrayList<>();
        nodes.add(flowNode("start", "Start", "start", 80, 260, "scheduler.manual", null));
        nodes.add(flowNode("holdings", "Holdings Overview", "logic", 320, 140, "portfolio.get_context", null));
        nodes.add(flowNode("market_scan", "Market Scan", "agent", 320, 380, "finance.market_analysis", null));
        nodes.add(flowNode("sector_exposure", "Sector Exposure", "agent", 580, 140, "finance.industry_share", null));
        nodes.add(flowNode("risk_metrics", "Risk Metrics", "agent", 580, 380, "finance.risk_assessment", null));
        nodes.add(flowNode("rebalance", "Rebalancing Plan", "agent", 840, 260, DIGEST_AGGREGATE_HANDLER, null));
        nodes.add(flowNode("end", "End", "end", 1100, 260, "workflow.end", null));
        return nodes;
    }
    static List<Map<String, Object>> portfolioWorkflowEdges() {
        List<Map<String, Object>> edges = new ArrayList<>();
        edges.add(edge("start", "holdings"));
        edges.add(edge("start", "market_scan"));
        edges.add(edge("holdings", "sector_exposure"));
        edges.add(edge("market_scan", "risk_metrics"));
        edges.add(edge("sector_exposure", "rebalance"));
        edges.add(edge("risk_metrics", "rebalance"));
        edges.add(edge("rebalance", "end"));
        return edges;
    }

    // ===== position_workflow =====
    static List<Map<String, Object>> positionWorkflowNodes() {
        List<Map<String, Object>> nodes = new ArrayList<>();
        nodes.add(flowNode("start", "Start", "start", 80, 340, "scheduler.manual", null));
        nodes.add(flowNode("holdings", "Load Holdings", "logic", 260, 340, "portfolio.get_positions", null));
        nodes.add(flowNode("pnl", "P&L Analysis", "agent", 440, 140, "finance.market_analysis", null));
        nodes.add(flowNode("cost_basis", "Cost Basis", "agent", 440, 340, "finance.financial_interpretation", null));
        nodes.add(flowNode("duration", "Duration Analysis", "agent", 440, 540, "finance.technical_analysis", null));
        nodes.add(flowNode("correlation", "Correlation", "agent", 640, 140, "finance.valuation_analysis", null));
        nodes.add(flowNode("concentration", "Concentration Risk", "agent", 640, 340, "finance.risk_assessment", null));
        nodes.add(flowNode("sector_break", "Sector Breakdown", "agent", 640, 540, "finance.industry_share", null));
        nodes.add(flowNode("money_flow", "Cash Flow", "agent", 840, 140, "finance.money_flow_analysis", null));
        nodes.add(flowNode("sentiment", "Position Sentiment", "agent", 840, 340, "finance.sentiment_monitor", null));
        nodes.add(flowNode("tax_impact", "Tax Impact", "agent", 840, 540, "general.agent", null));
        nodes.add(flowNode("hedge", "Hedge Ideas", "agent", 1040, 140, "general.agent", null));
        nodes.add(flowNode("action_items", "Action Items", "agent", 1040, 440, "general.agent", null));
        nodes.add(flowNode("aggregate", "Portfolio Summary", "agent", 1260, 340, DIGEST_AGGREGATE_HANDLER, null));
        nodes.add(flowNode("end", "End", "end", 1480, 340, "workflow.end", null));
        return nodes;
    }
    static List<Map<String, Object>> positionWorkflowEdges() {
        List<Map<String, Object>> edges = new ArrayList<>();
        edges.add(edge("start", "holdings"));
        edges.add(edge("holdings", "pnl"));
        edges.add(edge("holdings", "cost_basis"));
        edges.add(edge("holdings", "duration"));
        edges.add(edge("pnl", "correlation"));
        edges.add(edge("cost_basis", "concentration"));
        edges.add(edge("duration", "sector_break"));
        edges.add(edge("correlation", "money_flow"));
        edges.add(edge("concentration", "sentiment"));
        edges.add(edge("sector_break", "tax_impact"));
        edges.add(edge("money_flow", "hedge"));
        edges.add(edge("sentiment", "action_items"));
        edges.add(edge("tax_impact", "action_items"));
        edges.add(edge("hedge", "aggregate"));
        edges.add(edge("action_items", "aggregate"));
        edges.add(edge("aggregate", "end"));
        return edges;
    }

    // ===== sector-analyst-workflow =====
    static List<Map<String, Object>> sectorAnalystNodes() {
        List<Map<String, Object>> nodes = new ArrayList<>();
        nodes.add(flowNode("start", "Start", "start", 80, 340, "scheduler.manual", null));
        nodes.add(flowNode("macro", "Macro Environment", "agent", 280, 100, "general.agent", null));
        nodes.add(flowNode("industry_chain", "Industry Chain", "agent", 280, 340, "finance.industry_share", null));
        nodes.add(flowNode("policy", "Policy Impact", "agent", 280, 580, "general.agent", null));
        nodes.add(flowNode("competitive", "Competitive Map", "agent", 520, 100, "finance.industry_news", null));
        nodes.add(flowNode("top_players", "Top Players", "agent", 520, 340, "finance.fundamental_analysis", null));
        nodes.add(flowNode("tech_trends", "Tech Trends", "agent", 520, 580, "finance.tech_breakthrough", null));
        nodes.add(flowNode("valuation_band", "Valuation Band", "agent", 760, 100, "finance.valuation_analysis", null));
        nodes.add(flowNode("sentiment", "Sector Sentiment", "agent", 760, 340, "finance.sentiment_monitor", null));
        nodes.add(flowNode("money_flow", "Capital Flow", "agent", 760, 580, "finance.money_flow_analysis", null));
        nodes.add(flowNode("risk", "Sector Risk", "agent", 1000, 100, "finance.risk_assessment", null));
        nodes.add(flowNode("catalysts", "Catalyst Calendar", "agent", 1000, 340, "general.agent", null));
        nodes.add(flowNode("rotation", "Rotation Signal", "agent", 1000, 580, "finance.market_analysis", null));
        nodes.add(flowNode("recommendation", "Sector Call", "agent", 1240, 340, DIGEST_AGGREGATE_HANDLER, null));
        nodes.add(flowNode("end", "End", "end", 1480, 340, "workflow.end", null));
        return nodes;
    }
    static List<Map<String, Object>> sectorAnalystEdges() {
        List<Map<String, Object>> edges = new ArrayList<>();
        edges.add(edge("start", "macro"));
        edges.add(edge("start", "industry_chain"));
        edges.add(edge("start", "policy"));
        edges.add(edge("macro", "competitive"));
        edges.add(edge("industry_chain", "top_players"));
        edges.add(edge("policy", "tech_trends"));
        edges.add(edge("competitive", "valuation_band"));
        edges.add(edge("top_players", "sentiment"));
        edges.add(edge("tech_trends", "money_flow"));
        edges.add(edge("valuation_band", "risk"));
        edges.add(edge("sentiment", "catalysts"));
        edges.add(edge("money_flow", "rotation"));
        edges.add(edge("risk", "recommendation"));
        edges.add(edge("catalysts", "recommendation"));
        edges.add(edge("rotation", "recommendation"));
        edges.add(edge("recommendation", "end"));
        return edges;
    }

    // ===== telegram_hourly_news_digest =====
    static List<Map<String, Object>> telegramDigestNodes() {
        List<Map<String, Object>> nodes = new ArrayList<>();
        nodes.add(flowNode("start", "Start", "start", 80, 200, "scheduler.manual", null));
        nodes.add(flowNode("news_fetch", "Fetch News", "agent", 320, 200, "general.web_search", null));
        nodes.add(flowNode("sentiment_filter", "Sentiment Filter", "agent", 560, 200, "finance.sentiment_monitor", null));
        nodes.add(flowNode("end", "End", "end", 800, 200, "workflow.end", null));
        return nodes;
    }
    static List<Map<String, Object>> telegramDigestEdges() {
        List<Map<String, Object>> edges = new ArrayList<>();
        edges.add(edge("start", "news_fetch"));
        edges.add(edge("news_fetch", "sentiment_filter"));
        edges.add(edge("sentiment_filter", "end"));
        return edges;
    }

    // Aligned with research_graph.build_earnings_reaction_layout.
    static List<Map<String, Object>> earningsReactionNodes() {
        List<Map<String, Object>> nodes = new ArrayList<>();
        nodes.add(flowNode("start", "Start", "start", 80, 260, "scheduler.manual", null));
        nodes.add(flowNode("market_analysis", "Market Reaction", "agent", 340, 140, "finance.market_analysis", null));
        nodes.add(flowNode("financial_interpretation", "Earnings Interpretation", "agent", 340, 260, "finance.financial_interpretation", null));
        nodes.add(flowNode("industry_news", "Industry & Guidance News", "agent", 340, 380, "finance.industry_news", null));
        nodes.add(flowNode("aggregate", "Earnings Recommendation", "agent", 620, 260, "finance.stock_recommendation_aggregate", null));
        nodes.add(flowNode("end", "End", "end", 880, 260, "workflow.end", null));
        return nodes;
    }
    static List<Map<String, Object>> earningsReactionEdges() {
        List<Map<String, Object>> edges = new ArrayList<>();
        for (String specialist : Arrays.asList("market_analysis", "financial_interpretation", "industry_news")) {
            edges.add(edge("start", specialist));
            edges.add(edge(specialist, "aggregate"));
        }
        edges.add(edge("aggregate", "end"));
        return edges;
    }

    // Aligned with research_graph.build_watchlist_digest_layout (lightweight, no aggregate).
    static List<Map<String, Object>> watchlistDigestNodes() {
        List<Map<String, Object>> nodes = new ArrayList<>();
        nodes.add(flowNode("start", "Start", "start", 80, 260, "scheduler.manual", null));
        nodes.add(flowNode("market_analysis", "Watchlist Market Snapshot", "agent", 340, 180, "finance.market_analysis", null));
        nodes.add(flowNode("industry_news", "Watchlist News", "agent", 340, 340, "finance.industry_news", null));
        nodes.add(flowNode("risk_assessment", "Digest Risk Summary", "agent", 620, 260, "finance.risk_assessment", null));
        nodes.add(flowNode("end", "End", "end", 880, 260, "workflow.end", null));
        return nodes;
    }
    static List<Map<String, Object>> watchlistDigestEdges() {
        List<Map<String, Object>> edges = new ArrayList<>();
        for (String specialist : Arrays.asList("market_analysis", "industry_news")) {
            edges.add(edge("start", specialist));
            edges.add(edge(specialist, "risk_assessment"));
        }
        edges.add(edge("risk_assessment", "end"));
        return edges;
    }

    static Map<String, Object> emptyLayout(String workflowKey) {
        Map<String, Object> empty = new LinkedHashMap<>();
        empty.put("workflowKey", workflowKey);
        empty.put("engine", "langgraph");
        empty.put("nodes", Collections.emptyList());
        empty.put("edges", Collections.emptyList());
        return empty;
    }

    static Map<String, Object> flowNode(String id, String label, String nodeType, int x, int y, String handler, String agentId) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", id);
        node.put("type", "workflowNode");
        Map<String, Object> position = new LinkedHashMap<>();
        position.put("x", x);
        position.put("y", y);
        node.put("position", position);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("label", label);
        data.put("nodeType", nodeType);
        data.put("handler", handler);
        data.put("agentId", agentId);
        data.put("prompt", "");
        data.put("inputKeys", Collections.emptyList());
        data.put("outputKeys", Collections.emptyList());
        node.put("data", data);
        return node;
    }

    static Map<String, Object> edge(String source, String target) {
        Map<String, Object> edge = new LinkedHashMap<>();
        edge.put("id", source + "-" + target);
        edge.put("source", source);
        edge.put("target", target);
        edge.put("animated", true);
        return edge;
    }
}
