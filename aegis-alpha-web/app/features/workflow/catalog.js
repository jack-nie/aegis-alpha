export const nodeTypeStyles = {
  start: { border: "border-blue-500", bg: "bg-blue-50", icon: "◎", label: "开始" },
  logic: { border: "border-blue-500", bg: "bg-blue-50", icon: "◇", label: "Logic" },
  agent: { border: "border-blue-500", bg: "bg-blue-50", icon: "◇", label: "Agent" },
  condition: { border: "border-amber-400", bg: "bg-amber-50", icon: "⌁", label: "条件" },
  end: { border: "border-red-400", bg: "bg-red-50", icon: "⚑", label: "结束" },
};

/* nodePalette removed - unused */

export const workflowCatalogGroups = [
  {
    title: "起止节点",
    nodes: [
      { label: "结束", id: "end", nodeType: "end", handler: "workflow.end" },
      { label: "开始", id: "start", nodeType: "start", handler: "scheduler.daily" },
    ],
  },
  {
    title: "数据",
    nodes: [
      { label: "分类数据", id: "fdb.classification_data", nodeType: "logic", handler: "fdb.classification_data" },
      { label: "日频行情", id: "fdb.daily_ohlc", nodeType: "logic", handler: "fdb.daily_ohlc" },
      { label: "财务比率", id: "fdb.financial_ratios", nodeType: "logic", handler: "fdb.financial_ratios" },
      { label: "基本面数据", id: "fdb.fundamental_data", nodeType: "logic", handler: "fdb.fundamental_data" },
      { label: "全局新闻", id: "fdb.global_news", nodeType: "logic", handler: "fdb.global_news" },
      { label: "历史估值", id: "fdb.historical_valuation", nodeType: "logic", handler: "fdb.historical_valuation" },
      { label: "指标数据", id: "fdb.indicators", nodeType: "logic", handler: "fdb.indicators" },
      { label: "元数据", id: "fdb.metadata_data", nodeType: "logic", handler: "fdb.metadata_data" },
      { label: "宏观数据", id: "fdb.macro_data", nodeType: "logic", handler: "fdb.macro_data" },
      { label: "资金流向", id: "fdb.money_flow", nodeType: "logic", handler: "fdb.money_flow" },
      { label: "期权链", id: "fdb.option_chain", nodeType: "logic", handler: "fdb.option_chain" },
      { label: "研报摘要", id: "fdb.research_reports", nodeType: "logic", handler: "fdb.research_reports" },
      { label: "行业数据", id: "fdb.industry_data", nodeType: "logic", handler: "fdb.industry_data" },
    ],
  },
  {
    title: "通用能力",
    nodes: [
      { label: "获取新闻", id: "general.fetch_news", nodeType: "logic", handler: "general.fetch_news" },
      { label: "获取市场份额", id: "general.get_market_share", nodeType: "logic", handler: "general.get_market_share" },
      { label: "获取行业新闻", id: "general.get_sector_news", nodeType: "logic", handler: "general.get_sector_news" },
      {
        label: "获取技术突破",
        id: "general.get_tech_breakthroughs",
        nodeType: "logic",
        handler: "general.get_tech_breakthroughs",
      },
      { label: "网页搜索", id: "general.web_search", nodeType: "logic", handler: "general.web_search" },
      {
        label: "股票筛选智能体",
        id: "general.stock_screener_agent",
        nodeType: "agent",
        handler: "general.stock_screener_agent",
      },
    ],
  },
  {
    title: "金融研究/股票推荐",
    nodes: [
      { label: "市场分析", id: "finance.market_analysis", nodeType: "agent", handler: "finance.market_analysis" },
      { label: "行业份额", id: "finance.industry_share", nodeType: "logic", handler: "finance.industry_share" },
      { label: "舆情监测", id: "finance.sentiment_monitor", nodeType: "agent", handler: "finance.sentiment_monitor" },
      { label: "技术突破", id: "finance.tech_breakthrough", nodeType: "logic", handler: "finance.tech_breakthrough" },
      { label: "行业新闻", id: "finance.industry_news", nodeType: "logic", handler: "finance.industry_news" },
      {
        label: "财务解读",
        id: "finance.financial_interpretation",
        nodeType: "agent",
        handler: "finance.financial_interpretation",
      },
      {
        label: "股票推荐聚合",
        id: "finance.stock_recommendation_aggregate",
        nodeType: "agent",
        handler: "finance.stock_recommendation_aggregate",
      },
    ],
  },
  {
    title: "持仓数据",
    nodes: [
      { label: "持仓上下文", id: "portfolio.get_context", nodeType: "logic", handler: "portfolio.get_context" },
      { label: "持仓明细", id: "portfolio.positions", nodeType: "logic", handler: "portfolio.positions" },
      { label: "组合摘要", id: "portfolio.summary", nodeType: "logic", handler: "portfolio.summary" },
      { label: "交易记录", id: "portfolio.trades", nodeType: "logic", handler: "portfolio.trades" },
    ],
  },
  {
    title: "量化分析",
    nodes: [
      { label: "技术指标", id: "quant.technical_indicators", nodeType: "logic", handler: "quant.technical_indicators" },
      { label: "因子打分", id: "quant.factor_scoring", nodeType: "logic", handler: "quant.factor_scoring" },
      { label: "风险分析", id: "quant.risk_analysis", nodeType: "agent", handler: "quant.risk_analysis" },
      { label: "回测评估", id: "quant.backtest_evaluation", nodeType: "logic", handler: "quant.backtest_evaluation" },
    ],
  },
];

export const workflowCatalogDetailMap = {
  "fdb.classification_data": {
    description: "FDB 分类读取聚合节点；动作仅限 get_classification_tree / get_classification_members。",
  },
};

export const workflowPreviewDefinitions = [
  {
    workflowKey: "daily",
    name: "日报工作流",
    version: 2,
    versionLabel: "v2",
    nodes: 7,
    edges: 6,
    readonlyFlag: false,
    engine: "langgraph",
  },
  {
    workflowKey: "deep_dive",
    name: "个股分析工作流",
    version: 2,
    versionLabel: "v2",
    nodes: 17,
    edges: 16,
    readonlyFlag: false,
    engine: "langgraph",
  },
  {
    workflowKey: "exit_workflow",
    name: "退出策略工作流",
    version: 2,
    versionLabel: "v2",
    nodes: 8,
    edges: 8,
    readonlyFlag: false,
    engine: "langgraph",
  },
  {
    workflowKey: "portfolio_workflow",
    name: "投资组合分析工作流",
    version: 2,
    versionLabel: "v2",
    nodes: 6,
    edges: 5,
    readonlyFlag: false,
    engine: "langgraph",
  },
  {
    workflowKey: "position_workflow",
    name: "持仓分析工作流",
    version: 1,
    versionLabel: "v1",
    nodes: 23,
    edges: 23,
    readonlyFlag: false,
    engine: "langgraph",
  },
  {
    workflowKey: "sector-analyst-workflow",
    name: "行业分析工作流",
    version: 2,
    versionLabel: "v2",
    nodes: 16,
    edges: 15,
    readonlyFlag: false,
    engine: "langgraph",
  },
  {
    workflowKey: "telegram_hourly_news_digest",
    name: "Telegram 每小时新闻摘要",
    version: 1,
    versionLabel: "v1",
    nodes: 4,
    edges: 3,
    readonlyFlag: false,
    engine: "langgraph",
  },
  {
    workflowKey: "stock_analysis",
    name: "个股综合分析",
    version: 1,
    versionLabel: "v1",
    nodes: 7,
    edges: 6,
    readonlyFlag: false,
    engine: "langgraph",
  },
  {
    workflowKey: "earnings_reaction",
    name: "财报反应",
    version: 1,
    versionLabel: "v1",
    nodes: 6,
    edges: 5,
    readonlyFlag: false,
    engine: "langgraph",
  },
  {
    workflowKey: "watchlist_digest",
    name: "自选早报",
    version: 1,
    versionLabel: "v1",
    nodes: 5,
    edges: 4,
    readonlyFlag: false,
    engine: "langgraph",
  },
];

export const workflowPreviewByKey = Object.fromEntries(workflowPreviewDefinitions.map((item) => [item.workflowKey, item]));

export const defaultWorkflowLayouts = {
  stock_recommendation_research: {
    nodes: [
      {
        id: "start",
        type: "workflowNode",
        position: { x: 40, y: 250 },
        data: { label: "开始", nodeType: "start", handler: "scheduler.manual" },
      },
      {
        id: "market_analysis",
        type: "workflowNode",
        position: { x: 250, y: 80 },
        data: {
          label: "市场分析",
          nodeType: "agent",
          handler: "finance.market_analysis",
          inputKeys: ["ticker", "industry", "subject"],
          outputKeys: ["market_view"],
        },
      },
      {
        id: "industry_share",
        type: "workflowNode",
        position: { x: 250, y: 220 },
        data: {
          label: "行业份额",
          nodeType: "logic",
          handler: "finance.industry_share",
          inputKeys: ["industry"],
          outputKeys: ["industry_share"],
        },
      },
      {
        id: "industry_news",
        type: "workflowNode",
        position: { x: 250, y: 360 },
        data: {
          label: "行业新闻",
          nodeType: "logic",
          handler: "finance.industry_news",
          inputKeys: ["industry", "subject"],
          outputKeys: ["industry_news"],
        },
      },
      {
        id: "web_search",
        type: "workflowNode",
        position: { x: 250, y: 500 },
        data: {
          label: "网页搜索",
          nodeType: "logic",
          handler: "general.web_search",
          inputKeys: ["ticker", "subject"],
          outputKeys: ["web_results"],
        },
      },
      {
        id: "sentiment_monitor",
        type: "workflowNode",
        position: { x: 520, y: 80 },
        data: {
          label: "舆情监测",
          nodeType: "agent",
          handler: "finance.sentiment_monitor",
          inputKeys: ["industry_news", "web_results"],
          outputKeys: ["sentiment"],
        },
      },
      {
        id: "tech_breakthrough",
        type: "workflowNode",
        position: { x: 520, y: 240 },
        data: {
          label: "技术突破",
          nodeType: "logic",
          handler: "finance.tech_breakthrough",
          inputKeys: ["industry", "subject"],
          outputKeys: ["tech_breakthroughs"],
        },
      },
      {
        id: "financial_interpretation",
        type: "workflowNode",
        position: { x: 520, y: 400 },
        data: {
          label: "财务解读",
          nodeType: "agent",
          handler: "finance.financial_interpretation",
          inputKeys: ["ticker"],
          outputKeys: ["financial_view"],
        },
      },
      {
        id: "stock_screener",
        type: "workflowNode",
        position: { x: 780, y: 250 },
        data: {
          label: "股票筛选智能体",
          nodeType: "agent",
          handler: "general.stock_screener_agent",
          inputKeys: ["market_view", "industry_share", "sentiment", "tech_breakthroughs", "financial_view"],
          outputKeys: ["candidates"],
        },
      },
      {
        id: "aggregate",
        type: "workflowNode",
        position: { x: 1040, y: 250 },
        data: {
          label: "股票推荐聚合",
          nodeType: "agent",
          handler: "finance.stock_recommendation_aggregate",
          inputKeys: ["candidates"],
          outputKeys: ["final_recommendation", "confidence"],
        },
      },
      {
        id: "end",
        type: "workflowNode",
        position: { x: 1300, y: 250 },
        data: { label: "结束", nodeType: "end", handler: "workflow.end" },
      },
    ],
    edges: [
      { id: "e-start-market", source: "start", target: "market_analysis", label: "ticker" },
      { id: "e-start-share", source: "start", target: "industry_share", label: "industry" },
      { id: "e-start-news", source: "start", target: "industry_news", label: "industry" },
      { id: "e-start-search", source: "start", target: "web_search", label: "subject" },
      { id: "e-news-sentiment", source: "industry_news", target: "sentiment_monitor", label: "news" },
      { id: "e-search-sentiment", source: "web_search", target: "sentiment_monitor", label: "web" },
      { id: "e-start-tech", source: "start", target: "tech_breakthrough", label: "industry" },
      { id: "e-start-financial", source: "start", target: "financial_interpretation", label: "ticker" },
      { id: "e-market-screen", source: "market_analysis", target: "stock_screener", label: "market" },
      { id: "e-share-screen", source: "industry_share", target: "stock_screener", label: "share" },
      { id: "e-sentiment-screen", source: "sentiment_monitor", target: "stock_screener", label: "sentiment" },
      { id: "e-tech-screen", source: "tech_breakthrough", target: "stock_screener", label: "tech" },
      { id: "e-financial-screen", source: "financial_interpretation", target: "stock_screener", label: "financial" },
      { id: "e-screen-aggregate", source: "stock_screener", target: "aggregate", label: "candidates" },
      { id: "e-aggregate-end", source: "aggregate", target: "end", label: "recommendation" },
    ],
  },
  portfolio_workflow: {
    nodes: [
      {
        id: "start",
        type: "workflowNode",
        position: { x: 80, y: 260 },
        data: { label: "Start", nodeType: "start", handler: "scheduler.manual" },
      },
      {
        id: "holdings",
        type: "workflowNode",
        position: { x: 320, y: 140 },
        data: {
          label: "Holdings Overview",
          nodeType: "logic",
          handler: "portfolio.get_context",
          inputKeys: ["portfolioId"],
          outputKeys: ["holdings"],
        },
      },
      {
        id: "market_scan",
        type: "workflowNode",
        position: { x: 320, y: 380 },
        data: {
          label: "Market Scan",
          nodeType: "agent",
          handler: "finance.market_analysis",
          inputKeys: ["ticker", "subject"],
          outputKeys: ["market_view"],
        },
      },
      {
        id: "sector_exposure",
        type: "workflowNode",
        position: { x: 580, y: 140 },
        data: {
          label: "Sector Exposure",
          nodeType: "agent",
          handler: "finance.industry_share",
          inputKeys: ["industry"],
          outputKeys: ["sector_exposure"],
        },
      },
      {
        id: "risk_metrics",
        type: "workflowNode",
        position: { x: 580, y: 380 },
        data: {
          label: "Risk Metrics",
          nodeType: "agent",
          handler: "finance.risk_assessment",
          inputKeys: ["ticker", "market_view"],
          outputKeys: ["risk_assessment"],
        },
      },
      {
        id: "rebalance",
        type: "workflowNode",
        position: { x: 840, y: 260 },
        data: {
          label: "Rebalancing Plan",
          nodeType: "agent",
          handler: "finance.stock_recommendation_aggregate",
          inputKeys: ["sector_exposure", "risk_assessment"],
          outputKeys: ["rebalancing_plan"],
        },
      },
      {
        id: "end",
        type: "workflowNode",
        position: { x: 1100, y: 260 },
        data: { label: "End", nodeType: "end", handler: "workflow.end" },
      },
    ],
    edges: [
      { id: "e-start-holdings", source: "start", target: "holdings", label: "portfolio" },
      { id: "e-start-market", source: "start", target: "market_scan", label: "ticker" },
      { id: "e-holdings-sector", source: "holdings", target: "sector_exposure", label: "holdings" },
      { id: "e-market-risk", source: "market_scan", target: "risk_metrics", label: "market" },
      { id: "e-sector-rebalance", source: "sector_exposure", target: "rebalance", label: "sector" },
      { id: "e-risk-rebalance", source: "risk_metrics", target: "rebalance", label: "risk" },
      { id: "e-rebalance-end", source: "rebalance", target: "end", label: "plan" },
    ],
  },
};

export function splitCsv(value) {
  return value
    .split(",")
    .map((item) => item.trim())
    .filter(Boolean);
}
