"use client";

import ReactMarkdown from "react-markdown";
import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  Background,
  Controls,
  Handle,
  MarkerType,
  MiniMap,
  Position,
  ReactFlow,
  ReactFlowProvider,
  addEdge,
  applyEdgeChanges,
  applyNodeChanges,
} from "@xyflow/react";

const API_BASE = "/_backend";

const KNOWN_APP_PATHS = new Set([
  "/",
  "/portfolio",
  "/portfolio/assets",
  "/portfolio/trades",
  "/portfolio/portfolios",
  "/agent",
  "/workflow",
  "/workflow/runs",
  "/data-center/dashboard",
  "/data-center/macro",
  "/data-center/stock",
  "/data-center/option",
  "/data-center/index",
  "/data-center/industry",
  "/backtest",
  "/backtest/history",
  "/governance/audit",
  "/governance/models",
  "/recommendations",
  "/watchlist",
  "/profile",
  "/profile/strategy",
  "/profile/audit",
  "/profile/setting",
  "/report",
]);

function normalizePathname(value) {
  const raw =
    String(value || "/")
      .split("?")[0]
      .replace(/\/+$/, "") || "/";
  return KNOWN_APP_PATHS.has(raw) ? raw : "/";
}

function readInitialPathname() {
  if (typeof window === "undefined") return "/";
  return normalizePathname(window.location.pathname);
}

function readSearchParam(name, fallback = "") {
  if (typeof window === "undefined") return fallback;
  return new URLSearchParams(window.location.search).get(name) || fallback;
}

const pageTitles = {
  "/": "Aegis Alpha",
  "/portfolio": "投资组合 / 组合概览",
  "/portfolio/assets": "投资组合 / 持仓",
  "/portfolio/trades": "投资组合 / 交易流水",
  "/portfolio/portfolios": "组合列表",
  "/agent": "AI+ / Agent",
  "/workflow": "AI+ / 工作流",
  "/workflow/runs": "AI+ / 运行中心",
  "/report": "Report",
  "/backtest": "回测管理",
  "/backtest/history": "回测历史",
  "/data-center/dashboard": "数据中心仪表盘",
  "/data-center/macro": "宏观数据",
  "/data-center/stock": "个股数据",
  "/data-center/option": "期权数据",
  "/data-center/index": "指数数据",
  "/data-center/industry": "行业数据",
  "/profile": "个人信息",
  "/profile/strategy": "我的策略",
  "/profile/audit": "审计",
  "/profile/setting": "设置",
  "/governance/audit": "治理 / 审计日志",
  "/governance/models": "治理 / 模型治理",
  "/recommendations": "研究与推荐 / 推荐历史",
};

const navItems = [
  { label: "主页", icon: "home", path: "/", exact: true },
  {
    label: "投资组合",
    icon: "portfolio",
    children: [
      { label: "组合概览", path: "/portfolio", exact: true },
      { label: "持仓", path: "/portfolio/assets" },
      { label: "交易流水", path: "/portfolio/trades" },
      { label: "组合列表", path: "/portfolio/portfolios" },
    ],
  },
  {
    label: "AI+",
    icon: "bolt",
    children: [
      { label: "Agent", path: "/agent" },
      { label: "工作流", path: "/workflow" },
      { label: "运行中心", path: "/workflow/runs" },
      { label: "报告", path: "/report" },
    ],
  },
  { label: "自选股", icon: "star", path: "/watchlist" },
  {
    label: "回测",
    icon: "backtest",
    children: [
      { label: "回测管理", path: "/backtest", exact: true },
      { label: "回测历史", path: "/backtest/history" },
    ],
  },
  {
    label: "研究与推荐",
    icon: "market",
    children: [{ label: "推荐历史", path: "/recommendations" }],
  },
  { label: "市场", icon: "market", path: "/data-center/dashboard" },
  {
    label: "治理",
    icon: "audit",
    children: [
      { label: "模型治理", path: "/governance/models" },
      { label: "审计日志", path: "/governance/audit" },
    ],
  },
];

const nodeTypeStyles = {
  start: { border: "border-blue-500", bg: "bg-blue-50", icon: "◎", label: "开始" },
  logic: { border: "border-blue-500", bg: "bg-blue-50", icon: "◇", label: "Logic" },
  agent: { border: "border-blue-500", bg: "bg-blue-50", icon: "◇", label: "Agent" },
  condition: { border: "border-amber-400", bg: "bg-amber-50", icon: "⌁", label: "条件" },
  end: { border: "border-red-400", bg: "bg-red-50", icon: "⚑", label: "结束" },
};

/* nodePalette removed - unused */

const workflowCatalogGroups = [
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

const workflowCatalogDetailMap = {
  "fdb.classification_data": {
    description: "FDB 分类读取聚合节点；动作仅限 get_classification_tree / get_classification_members。",
  },
};

const workflowPreviewDefinitions = [
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
];

const workflowPreviewByKey = Object.fromEntries(workflowPreviewDefinitions.map((item) => [item.workflowKey, item]));

const defaultWorkflowLayouts = {
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

const tradeFieldGroups = [
  { title: "成交识别", fields: "交易日、结算日、股票代码、交易所、市场、证券名称、买卖方向" },
  { title: "成交金额", fields: "数量、成交价、成交金额、佣金、印花税、平台费、其他费用、净金额、币种、汇率" },
  { title: "账户策略", fields: "组合、券商、账户、策略、交易类型、订单类型、备注" },
];

const emptyTradeForm = {
  portfolioId: "",
  tradeDate: new Date().toISOString().slice(0, 10),
  tradeDateTime: "",
  settlementDate: "",
  symbol: "",
  exchange: "",
  market: "US",
  securityName: "",
  side: "BUY",
  quantity: "",
  price: "",
  fee: "0",
  tax: "0",
  commission: "0",
  otherFee: "0",
  currency: "USD",
  fxRate: "1",
  broker: "",
  accountNo: "",
  strategy: "",
  tradeType: "STOCK",
  orderType: "MARKET",
  action: "",
  notes: "",
};

const tradeHeaderAliases = {
  portfolioId: ["portfolioId", "portfolio_id", "组合ID", "组合", "投资组合"],
  tradeDate: ["tradeDate", "trade_date", "交易日期", "交易日", "成交日期", "成交日"],
  settlementDate: ["settlementDate", "settlement_date", "结算日期", "结算日"],
  symbol: ["symbol", "ticker", "代码", "股票代码", "证券代码", "标的代码"],
  exchange: ["exchange", "交易所", "市场代码"],
  market: ["market", "市场", "地区"],
  securityName: ["securityName", "security_name", "证券名称", "股票名称", "名称"],
  side: ["side", "方向", "买卖", "交易方向", "买卖方向"],
  quantity: ["quantity", "qty", "shares", "数量", "股数", "成交数量"],
  price: ["price", "成交价", "价格", "成交价格"],
  fee: ["fee", "费用", "平台费", "交易费"],
  tax: ["tax", "税费", "印花税"],
  commission: ["commission", "佣金", "手续费"],
  otherFee: ["otherFee", "other_fee", "其他费用", "杂费"],
  currency: ["currency", "币种", "货币"],
  fxRate: ["fxRate", "fx_rate", "汇率"],
  broker: ["broker", "券商", "经纪商"],
  accountNo: ["accountNo", "account_no", "账户", "账号", "资金账户"],
  strategy: ["strategy", "策略", "交易策略"],
  tradeType: ["tradeType", "trade_type", "交易类型", "资产类型"],
  orderType: ["orderType", "order_type", "订单类型", "委托类型"],
  notes: ["notes", "备注", "说明"],
};

async function request(path, options = {}, token) {
  const headers = {
    "Content-Type": "application/json",
    ...(options.headers || {}),
    ...(token ? { Authorization: `Bearer ${token}` } : {}),
  };
  const response = await fetch(`${API_BASE}${path}`, { ...options, headers, cache: "no-store" });
  if (response.status === 204) return null;
  const text = await response.text();
  const payload = text ? JSON.parse(text) : null;
  if (!response.ok) throw new Error(payload?.message || payload?.detail || `Request failed: ${response.status}`);
  return payload;
}

function Icon({ name, className = "w-5 h-5" }) {
  const paths = {
    home: "M3 12l2-2m0 0l7-7 7 7M5 10v10a1 1 0 001 1h3m10-11l2 2m-2-2v10a1 1 0 01-1 1h-3m-6 0a1 1 0 001-1v-4a1 1 0 011-1h2a1 1 0 011 1v4a1 1 0 001 1m-6 0h6",
    portfolio:
      "M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z",
    bolt: "M13 10V3L4 14h7v7l9-11h-7z",
    backtest:
      "M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2m-3 7h3m-3 4h3m-6-4h.01M9 16h.01",
    database:
      "M4 7v10c0 2.21 3.582 4 8 4s8-1.79 8-4V7M4 7c0 2.21 3.582 4 8 4s8-1.79 8-4M4 7c0-2.21 3.582-4 8-4s8 1.79 8 4",
    star: "M11.049 2.927c.3-.921 1.603-.921 1.902 0l1.286 3.955a1 1 0 00.95.69h4.159c.969 0 1.371 1.24.588 1.81l-3.364 2.444a1 1 0 00-.364 1.118l1.285 3.955c.3.921-.755 1.688-1.539 1.118l-3.364-2.444a1 1 0 00-1.176 0l-3.364 2.444c-.784.57-1.838-.197-1.539-1.118l1.285-3.955a1 1 0 00-.364-1.118L4.066 9.382c-.783-.57-.38-1.81.588-1.81h4.159a1 1 0 00.95-.69l1.286-3.955z",
    market: "M4 19h16M6 17V9m4 8V5m4 12v-7m4 7V3",
    audit: "M12 3l7 4v5c0 4.4-2.9 7.4-7 9-4.1-1.6-7-4.6-7-9V7l7-4zm0 5v5m0 4h.01",
    user: "M16 7a4 4 0 11-8 0 4 4 0 018 0zM12 14a7 7 0 00-7 7h14a7 7 0 00-7-7z",
    send: "M12 19l9 2-9-18-9 18 9-2zm0 0v-8",
    close: "M6 18L18 6M6 6l12 12",
    arrowRight: "M13 7l5 5m0 0l-5 5m5-5H6",
  };
  return (
    <svg className={className} fill="none" stroke="currentColor" viewBox="0 0 24 24">
      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d={paths[name]} />
    </svg>
  );
}

function WorkflowPreviewGlyph({ className = "h-4 w-4" }) {
  return (
    <svg className={className} fill="none" stroke="currentColor" viewBox="0 0 18 18">
      <path
        d="M6.8 5.1 4.4 7.3m6.8-2.2 2.3 2.2M6.6 12.8 4.4 10.7m6.9 2.1 2.2-2.1M7.2 4.5h3.6M7.2 13.5h3.6"
        strokeLinecap="round"
        strokeLinejoin="round"
        strokeWidth="1.35"
      />
      <circle cx="4" cy="9" r="2" strokeWidth="1.35" />
      <circle cx="6" cy="3.8" r="1.8" strokeWidth="1.35" />
      <circle cx="12" cy="3.8" r="1.8" strokeWidth="1.35" />
      <circle cx="6" cy="14.2" r="1.8" strokeWidth="1.35" />
      <circle cx="12" cy="14.2" r="1.8" strokeWidth="1.35" />
    </svg>
  );
}

function ChatBubbleIcon({ className = "h-5 w-5" }) {
  return (
    <svg className={className} fill="none" stroke="currentColor" viewBox="0 0 24 24">
      <path
        d="M7.5 18.5 4 20l1-3.5A7.7 7.7 0 0 1 4 12.7C4 8.4 7.8 5 12.5 5S21 8.4 21 12.7s-3.8 7.7-8.5 7.7a9.1 9.1 0 0 1-5-1.9Z"
        strokeLinecap="round"
        strokeLinejoin="round"
        strokeWidth="1.8"
      />
    </svg>
  );
}

function useFocusTrap(open, onClose) {
  const containerRef = useRef(null);
  const returnFocusRef = useRef(null);
  const onCloseRef = useRef(onClose);

  useEffect(() => {
    onCloseRef.current = onClose;
  }, [onClose]);

  useEffect(() => {
    if (!open || typeof document === "undefined") return undefined;
    returnFocusRef.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const container = containerRef.current;
    const focusableSelector = [
      "a[href]",
      "button:not([disabled])",
      "textarea:not([disabled])",
      "input:not([disabled])",
      "select:not([disabled])",
      "[tabindex]:not([tabindex='-1'])",
    ].join(",");
    const focusFirst = () => {
      const target = container?.querySelector(focusableSelector) || container;
      target?.focus?.();
    };
    const frame = window.requestAnimationFrame(focusFirst);
    const onKeyDown = (event) => {
      if (event.key === "Escape") {
        event.preventDefault();
        onCloseRef.current?.();
        return;
      }
      if (event.key !== "Tab" || !container) return;
      const focusable = Array.from(container.querySelectorAll(focusableSelector)).filter(
        (item) => item.offsetParent !== null || item === document.activeElement,
      );
      if (!focusable.length) {
        event.preventDefault();
        container.focus();
        return;
      }
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    };
    document.addEventListener("keydown", onKeyDown);
    return () => {
      window.cancelAnimationFrame(frame);
      document.removeEventListener("keydown", onKeyDown);
      returnFocusRef.current?.focus?.();
    };
  }, [open]);

  return containerRef;
}

function ModalShell({ title, onClose, children, widthClass = "max-w-lg" }) {
  const titleId = useMemo(() => `modal-${Math.random().toString(36).slice(2)}`, []);
  const containerRef = useFocusTrap(true, onClose);
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center px-4 py-6">
      <div className="absolute inset-0 bg-gray-900/35" onClick={onClose} />
      <section
        ref={containerRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        tabIndex={-1}
        className={`relative max-h-[calc(100vh-48px)] w-full overflow-auto rounded-lg bg-white shadow-xl ${widthClass}`}
      >
        <div className="flex items-start justify-between gap-4 border-b border-gray-100 px-6 py-4">
          <h3 id={titleId} className="text-base font-semibold text-gray-900">
            {title}
          </h3>
          <button
            type="button"
            onClick={onClose}
            className="grid h-9 w-9 place-items-center rounded-lg text-xl leading-none text-gray-500 hover:bg-gray-100 hover:text-gray-900"
            aria-label={`关闭${title}`}
          >
            ×
          </button>
        </div>
        {children}
      </section>
    </div>
  );
}

function DrawerShell({ title, onClose, children, widthClass = "max-w-3xl", topClass = "inset-y-0" }) {
  const titleId = useMemo(() => `drawer-${Math.random().toString(36).slice(2)}`, []);
  const containerRef = useFocusTrap(true, onClose);
  return (
    <div className="fixed inset-0 z-50">
      <div className="absolute inset-0 bg-white/60 backdrop-blur-[3px]" onClick={onClose} />
      <aside
        ref={containerRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        tabIndex={-1}
        className={`absolute ${topClass} right-0 flex w-full ${widthClass} flex-col border-l border-gray-100 bg-white shadow-2xl`}
      >
        <div className="flex items-start justify-between gap-4 border-b border-gray-100 px-5 py-4">
          <h3 id={titleId} className="text-base font-semibold text-gray-900">
            {title}
          </h3>
          <button
            type="button"
            onClick={onClose}
            className="grid h-9 w-9 place-items-center rounded-lg text-2xl leading-none text-gray-700 hover:bg-gray-100"
            aria-label={`关闭${title}`}
          >
            ×
          </button>
        </div>
        {children}
      </aside>
    </div>
  );
}

function Sidebar({ path, navigate, _collapsed, _setCollapsed, mobile = false, onNavigate }) {
  const [expandedMenus, setExpandedMenus] = useState(["投资组合", "AI+", "回测"]);

  useEffect(() => {
    if (path.startsWith("/portfolio")) setExpandedMenus((items) => Array.from(new Set([...items, "投资组合"])));
    if (path.startsWith("/agent") || path.startsWith("/workflow") || path.startsWith("/report"))
      setExpandedMenus((items) => Array.from(new Set([...items, "AI+"])));
    if (path.startsWith("/backtest")) setExpandedMenus((items) => Array.from(new Set([...items, "回测"])));
    if (path.startsWith("/recommendations")) setExpandedMenus((items) => Array.from(new Set([...items, "研究与推荐"])));
    if (path.startsWith("/governance")) setExpandedMenus((items) => Array.from(new Set([...items, "治理"])));
  }, [path]);

  const toggleMenu = (label) =>
    setExpandedMenus((prev) => (prev.includes(label) ? prev.filter((item) => item !== label) : [...prev, label]));
  const isActive = (target, exact) => (exact ? path === target : path === target || path.startsWith(`${target}/`));
  const handleNavigate = (target) => {
    navigate(target);
    onNavigate?.();
  };

  return (
    <aside
      className={`${mobile ? "flex w-[280px]" : "hidden w-[255px] lg:flex"} flex-shrink-0 flex-col border-r border-gray-200 bg-white text-gray-700`}
    >
      <div className="flex h-16 items-center px-4">
        <span className="text-xl font-bold tracking-tight text-black">Aegis Alpha</span>
      </div>
      <nav className="flex-1 overflow-y-auto px-3 py-3">
        {navItems.map((item) => {
          const itemOpen = expandedMenus.includes(item.label);
          const itemActive = item.children
            ? item.children.some((child) => isActive(child.path, child.exact))
            : isActive(item.path, item.exact);
          return (
            <div key={item.label} className="mb-1">
              {item.children ? (
                <div>
                  <button
                    onClick={() => toggleMenu(item.label)}
                    style={itemActive ? { backgroundColor: "#171717", color: "#ffffff" } : undefined}
                    className={`flex h-9 w-full items-center rounded-lg px-3 text-sm transition-colors ${itemActive ? "" : "text-gray-700 hover:bg-gray-100"}`}
                  >
                    <span className="flex-shrink-0">
                      <Icon name={item.icon} className="h-4 w-4" />
                    </span>
                    <span className="ml-3 flex-1 text-left">{item.label}</span>
                    <span className={`text-base leading-none transition-transform ${itemOpen ? "rotate-90" : ""}`}>
                      ›
                    </span>
                  </button>
                  {itemOpen && (
                    <div className="mt-2 space-y-1 pl-6">
                      {item.children.map((child) => (
                        <button
                          key={child.path}
                          onClick={() => handleNavigate(child.path)}
                          style={
                            isActive(child.path, child.exact)
                              ? { backgroundColor: "#171717", color: "#ffffff" }
                              : undefined
                          }
                          className={`block h-8 w-full rounded-lg px-3 text-left text-sm transition-colors ${isActive(child.path, child.exact) ? "" : "text-gray-600 hover:bg-gray-100 hover:text-gray-900"}`}
                        >
                          {child.label}
                        </button>
                      ))}
                    </div>
                  )}
                </div>
              ) : (
                <button
                  onClick={() => handleNavigate(item.path)}
                  style={itemActive ? { backgroundColor: "#171717", color: "#ffffff" } : undefined}
                  className={`flex h-9 w-full items-center rounded-lg px-3 text-sm transition-colors ${itemActive ? "" : "text-gray-700 hover:bg-gray-100"}`}
                >
                  <span className="flex-shrink-0">
                    <Icon name={item.icon} className="h-4 w-4" />
                  </span>
                  <span className="ml-3">{item.label}</span>
                </button>
              )}
            </div>
          );
        })}
      </nav>
    </aside>
  );
}

function Header({ path, copilotOpen, setCopilotOpen, me, onLogout, onMenuOpen }) {
  const title = pageTitles[path] || "Aegis Alpha";
  return (
    <header className="min-h-16 flex-shrink-0 border-b border-gray-200 bg-white px-3 py-3 sm:px-4 lg:px-6">
      <div className="flex min-w-0 items-center justify-between gap-3">
        <div className="flex min-w-0 items-center gap-2">
          <button
            type="button"
            onClick={onMenuOpen}
            className="grid h-10 w-10 place-items-center rounded-lg border border-gray-200 bg-white text-gray-800 hover:bg-gray-50 lg:hidden"
            aria-label="打开导航"
          >
            <span className="text-xl leading-none">☰</span>
          </button>
          <h1 className="truncate text-lg font-semibold text-black sm:text-xl">{title}</h1>
        </div>
        <div className="flex shrink-0 items-center gap-2 sm:gap-3">
          <button
            type="button"
            onClick={() => setCopilotOpen(!copilotOpen)}
            className={`grid h-10 w-10 place-items-center rounded-lg border border-gray-200 transition-colors ${copilotOpen ? "bg-gray-100 text-black" : "bg-white text-gray-700 hover:bg-gray-50"}`}
            aria-label={copilotOpen ? "关闭 AI Copilot" : "打开 AI Copilot"}
          >
            <Icon name="send" className="h-4 w-4" />
          </button>
          <span className="hidden max-w-[180px] truncate text-sm text-gray-600 underline underline-offset-2 sm:inline">
            当前用户: {me?.username || "guanghui.nie"}
          </span>
          <button
            type="button"
            onClick={onLogout}
            className="h-10 rounded-lg px-2 text-sm font-semibold text-black hover:bg-gray-50 hover:text-gray-600"
          >
            退出
          </button>
        </div>
      </div>
    </header>
  );
}

function AICopilot({ setCopilotOpen, api, promptRequest, onPromptHandled }) {
  const initialMessages = [
    {
      role: "assistant",
      content: "你好！我是 Aegis Alpha Copilot，可以帮你分析市场、查询数据、生成报告。有什么可以帮你的？",
    },
  ];
  const suggestions = ["分析当前市场宏观环境", "查看我的投资组合概况", "生成一份行业研究报告", "回测某个策略的表现"];
  const [messages, setMessages] = useState(initialMessages);
  const [input, setInput] = useState("");
  const [loading, setLoading] = useState(false);
  const messagesEndRef = useRef(null);
  const handledPromptIdRef = useRef(null);
  useEffect(() => messagesEndRef.current?.scrollIntoView({ behavior: "smooth" }), [messages]);

  const sendMessage = useCallback(
    async (rawText) => {
      const text = (rawText || "").trim();
      if (!text) return;
      setMessages((prev) => [...prev, { role: "user", content: text }]);
      setLoading(true);
      try {
        const result = await api("/chat/messages", { method: "POST", body: JSON.stringify({ message: text }) });
        let content = result?.content || result?.message || "后端没有返回内容。";
        if (result?.routedToWorkflow && result?.runId) {
          try {
            // Poll until workflow completes (async dispatch may still be RUNNING)
            let run = await api(`/workflow/runs/${result.runId}`);
            const pollStart = Date.now();
            while (run?.status === "RUNNING" || run?.status === "QUEUED") {
              if (Date.now() - pollStart > 90000) break;
              await new Promise((r) => setTimeout(r, 2000));
              run = await api(`/workflow/runs/${result.runId}`);
            }
            const runResult = run?.resultJson ? JSON.parse(run.resultJson) : null;
            if (runResult) {
              // Try recommendation node first
              const rec = runResult.recommendation;
              if (rec) {
                content =
                  rec.summary ||
                  rec.content ||
                  (rec.data && typeof rec.data === "object" && (rec.data.summary || rec.data.content)) ||
                  "";
                if (rec.data && typeof rec.data === "object" && !content) {
                  const textParts = Object.values(rec.data)
                    .filter((v) => typeof v === "string" && v.trim())
                    .join(String.fromCharCode(10));
                  if (textParts) content = textParts;
                }
              }
              // Collect summaries from all executed nodes
              if (!content) {
                const nodeEntries = Object.entries(runResult)
                  .filter(([, v]) => v && typeof v === "object" && v.nodeId && v.status)
                  .map(([k, v]) => {
                    const label = v.nodeName || k;
                    const summary =
                      v.summary || v.content || (v.data && v.data.summary) || (v.data && v.data.content) || "";
                    return { label, summary, ok: v.ok, status: v.status };
                  })
                  .filter((n) => n.summary.trim());
                if (nodeEntries.length > 0) {
                  content = nodeEntries
                    .map((n) => "**" + n.label + "** (" + n.status + "): " + n.summary)
                    .join(String.fromCharCode(10) + String.fromCharCode(10));
                }
              }
              // Final fallback
              if (!content) {
                const sep = String.fromCharCode(10);
                const nodeStatuses = Object.entries(runResult)
                  .filter(([, v]) => v && typeof v === "object" && v.nodeId)
                  .map(([, v]) => "- " + (v.nodeName || v.nodeId) + ": " + v.status)
                  .join(sep);
                const nodeCount = run.nodeCount || 0;
                content =
                  "工作流 " +
                  (result.workflowKey || "") +
                  " 已完成，" +
                  nodeCount +
                  " 个节点已执行：" +
                  sep +
                  nodeStatuses;
              }
            }
          } catch (fetchErr) {
            // eslint-disable-next-line no-console
            console.warn("Failed to fetch workflow run result:", fetchErr);
          }
        }
        setMessages((prev) => [
          ...prev,
          { role: "assistant", content, ok: result?.ok, provider: result?.provider, reason: result?.reason },
        ]);
      } catch (error) {
        setMessages((prev) => [
          ...prev,
          {
            role: "assistant",
            content: error.message || "发送失败，请稍后重试。",
            ok: false,
            provider: "request-error",
          },
        ]);
      } finally {
        setLoading(false);
      }
    },
    [api],
  );

  useEffect(() => {
    if (!promptRequest?.id || handledPromptIdRef.current === promptRequest.id) return;
    handledPromptIdRef.current = promptRequest.id;
    sendMessage(promptRequest.text);
    onPromptHandled?.();
  }, [promptRequest, sendMessage, onPromptHandled]);

  const handleSend = () => {
    if (!input.trim() || loading) return;
    const text = input.trim();
    setInput("");
    sendMessage(text);
  };
  const handleSuggestion = (text) => {
    if (loading) return;
    sendMessage(text);
  };

  return (
    <div className="flex w-96 flex-shrink-0 flex-col border-l border-gray-200 bg-white shadow-lg">
      <div className="flex h-14 items-center justify-between border-b border-gray-200 px-4">
        <div className="flex items-center gap-2">
          <Icon name="bolt" className="h-5 w-5 text-blue-600" />
          <span className="text-sm font-semibold">AI Copilot</span>
        </div>
        <button
          type="button"
          onClick={() => setCopilotOpen(false)}
          className="rounded-lg p-1.5 text-gray-400 transition-colors hover:bg-gray-100 hover:text-gray-600"
          aria-label="关闭 AI Copilot"
        >
          <Icon name="close" />
        </button>
      </div>
      <div className="flex-1 space-y-4 overflow-y-auto bg-gray-50 p-4">
        {messages.map((msg, idx) => (
          <div key={idx} className={`flex ${msg.role === "user" ? "justify-end" : "justify-start"}`}>
            <div
              className={`max-w-[85%] whitespace-pre-wrap rounded-lg px-4 py-2.5 text-sm leading-relaxed ${msg.role === "user" ? "rounded-br-none border border-gray-200 bg-white text-gray-800 shadow-sm" : msg.ok === false ? "rounded-bl-none border border-red-200 bg-red-50 text-red-700 shadow-sm" : "rounded-bl-none border border-gray-200 bg-white text-gray-700 shadow-sm"}`}
            >
              <div className="chat-markdown text-sm leading-relaxed">
                <ReactMarkdown
                  components={{
                    h1: ({ children }) => (
                      <h1 className="mb-2 mt-4 border-b border-gray-200 pb-1 text-lg font-bold text-gray-900">
                        {children}
                      </h1>
                    ),
                    h2: ({ children }) => (
                      <h2 className="mb-1.5 mt-3 flex items-center gap-1 text-base font-bold text-gray-800">
                        {children}
                      </h2>
                    ),
                    h3: ({ children }) => <h3 className="mb-1 mt-2 text-sm font-semibold text-gray-700">{children}</h3>,
                    p: ({ children }) => <p className="my-1 text-gray-700">{children}</p>,
                    ul: ({ children }) => <ul className="my-1 ml-4 list-disc space-y-0.5">{children}</ul>,
                    ol: ({ children }) => <ol className="my-1 ml-4 list-decimal space-y-0.5">{children}</ol>,
                    li: ({ children }) => <li className="text-gray-700">{children}</li>,
                    strong: ({ children }) => <strong className="font-semibold text-gray-900">{children}</strong>,
                    blockquote: ({ children }) => (
                      <blockquote className="my-2 rounded-lg border-l-4 border-blue-400 bg-blue-50 px-3 py-2 text-xs text-blue-800">
                        {children}
                      </blockquote>
                    ),
                    table: ({ children }) => (
                      <div className="my-2 overflow-x-auto rounded-lg border border-gray-200">
                        <table className="w-full text-xs">{children}</table>
                      </div>
                    ),
                    thead: ({ children }) => <thead className="bg-gray-50">{children}</thead>,
                    th: ({ children }) => (
                      <th className="border-b border-gray-200 px-3 py-1.5 text-left font-semibold text-gray-700">
                        {children}
                      </th>
                    ),
                    td: ({ children }) => (
                      <td className="border-b border-gray-100 px-3 py-1.5 text-gray-600">{children}</td>
                    ),
                    tr: ({ children }) => {
                      return <tr className="even:bg-gray-50/50">{children}</tr>;
                    },
                    code: ({ inline, children }) => {
                      if (inline)
                        return (
                          <code className="rounded bg-slate-100 px-1 py-0.5 font-mono text-xs text-blue-700">
                            {children}
                          </code>
                        );
                      return (
                        <code className="block overflow-x-auto rounded-lg bg-slate-900 p-3 font-mono text-xs text-slate-100">
                          {children}
                        </code>
                      );
                    },
                    hr: () => <hr className="my-3 border-gray-200" />,
                    a: ({ href, children }) => (
                      <a
                        href={href}
                        target="_blank"
                        rel="noopener noreferrer"
                        className="text-blue-600 underline hover:text-blue-800"
                      >
                        {children}
                      </a>
                    ),
                  }}
                >
                  {String(msg.content)}
                </ReactMarkdown>
              </div>
              {msg.role === "assistant" && msg.provider && (
                <div className={`mt-2 text-[11px] ${msg.ok === false ? "text-red-500" : "text-gray-400"}`}>
                  {msg.ok === false ? "ERROR" : "INFO"} · {msg.provider}
                </div>
              )}
              {msg.reason && <div className="mt-1 text-[11px] text-red-500">{msg.reason}</div>}
            </div>
          </div>
        ))}
        {loading && (
          <div className="flex justify-start">
            <div className="rounded-xl rounded-bl-none border border-gray-200 bg-white px-5 py-3.5 shadow-sm">
              <div className="flex items-center gap-3">
                <div className="relative h-5 w-5">
                  <div className="absolute inset-0 rounded-full border-2 border-gray-200" />
                  <div className="absolute inset-0 animate-spin rounded-full border-2 border-transparent border-t-blue-500" />
                </div>
                <span className="text-sm text-gray-500">AI 正在分析中...</span>
              </div>
            </div>
          </div>
        )}
        <div ref={messagesEndRef} />
        {messages.length === 1 && (
          <div className="mt-6">
            <p className="mb-3 text-center text-xs text-gray-400">试试这些问题</p>
            <div className="space-y-2">
              {suggestions.map((s) => (
                <button
                  key={s}
                  onClick={() => handleSuggestion(s)}
                  className="w-full rounded-lg border border-gray-200 bg-white px-4 py-2.5 text-left text-sm text-gray-600 shadow-sm transition-colors hover:border-blue-300 hover:text-blue-600"
                >
                  {s}
                </button>
              ))}
            </div>
          </div>
        )}
      </div>
      <div className="border-t border-gray-200 bg-white p-4">
        <div className="flex gap-2">
          <input
            type="text"
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter" && !e.shiftKey) {
                e.preventDefault();
                handleSend();
              }
            }}
            placeholder="输入你的问题..."
            className="flex-1 rounded-lg border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none focus:ring-2 focus:ring-blue-500"
          />
          <button
            type="button"
            onClick={handleSend}
            disabled={!input.trim() || loading}
            className="rounded-lg bg-blue-600 px-3 py-2 text-white transition-colors hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-50"
            aria-label="发送 AI Copilot 消息"
          >
            <Icon name="send" className="h-4 w-4" />
          </button>
        </div>
      </div>
    </div>
  );
}

function Home({ navigate, openCopilotWithPrompt }) {
  const [query, setQuery] = useState("");
  const quickActions = [
    { label: "分析投资组合", icon: "📊", desc: "查看持仓和风险暴露", path: "/portfolio" },
    { label: "回测策略", icon: "📈", desc: "验证交易策略表现", path: "/backtest" },
    { label: "查看数据中心", icon: "🏛️", desc: "宏观、个股、行业数据", path: "/data-center/dashboard" },
    { label: "管理 Agent", icon: "🤖", desc: "配置 AI 自动任务", path: "/agent" },
  ];
  return (
    <div className="mx-auto max-w-4xl">
      <div className="mb-10 text-center">
        <h2 className="mb-2 text-2xl font-bold text-gray-800">Aegis Alpha</h2>
        <p className="text-sm text-gray-500">智能投资研究助手，帮你洞察市场、管理组合、优化策略</p>
      </div>
      <form
        onSubmit={(e) => {
          e.preventDefault();
          const text = query.trim();
          if (text) {
            openCopilotWithPrompt(text);
            setQuery("");
          }
        }}
        className="mb-8"
      >
        <div className="relative">
          <input
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="输入你的问题，例如：分析当前市场宏观环境..."
            className="w-full rounded-xl border border-gray-300 bg-white px-5 py-4 pr-12 text-base shadow-sm focus:border-blue-500 focus:outline-none focus:ring-2 focus:ring-blue-500"
          />
          <button
            type="submit"
            className="absolute right-2 top-1/2 -translate-y-1/2 rounded-lg bg-blue-600 p-2.5 text-white transition-colors hover:bg-blue-700"
            aria-label="发送研究问题"
          >
            <Icon name="send" />
          </button>
        </div>
      </form>
      <div className="mb-8 grid grid-cols-2 gap-4 md:grid-cols-4">
        {quickActions.map((action) => (
          <button
            key={action.label}
            onClick={() => navigate(action.path)}
            className="card group cursor-pointer text-left transition-all hover:border-blue-200 hover:shadow-md"
          >
            <div className="mb-2 text-2xl">{action.icon}</div>
            <h3 className="text-sm font-medium text-gray-800 transition-colors group-hover:text-blue-600">
              {action.label}
            </h3>
            <p className="mt-1 text-xs text-gray-400">{action.desc}</p>
          </button>
        ))}
      </div>
      <div className="card">
        <h3 className="mb-4 font-medium text-gray-800">快速开始</h3>
        <div className="space-y-3">
          {[
            "在搜索框中输入你的研究问题",
            "AI Copilot 将分析数据并给出回答",
            "使用左侧导航浏览数据中心、组合管理和回测功能",
          ].map((text, index) => (
            <div key={text} className="flex items-center gap-3 text-sm text-gray-600">
              <span className="flex h-6 w-6 items-center justify-center rounded-full bg-blue-100 text-xs font-medium text-blue-600">
                {index + 1}
              </span>
              <span>{text}</span>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}

function AgentPage({ api }) {
  const [agents, setAgents] = useState([]);
  const [modalMode, setModalMode] = useState(null);
  const [selectedAgent, setSelectedAgent] = useState(null);
  const [form, setForm] = useState({ name: "", desc: "", type: "用户", schedule: "手动触发", prompt: "" });
  const [busy, setBusy] = useState(false);
  const [logResult, setLogResult] = useState(null);
  const [configNotice, setConfigNotice] = useState("");
  const [runForm, setRunForm] = useState({ subject: "manual agent test", ticker: "AAPL", model: "", apiKey: "" });

  const load = useCallback(() => api("/agents").then(setAgents), [api]);
  useEffect(() => {
    load().catch(() => {});
  }, [load]);
  const runningCount = useMemo(() => agents.filter((agent) => (agent.status || "IDLE") === "RUNNING").length, [agents]);

  const toCard = (agent) => ({
    id: agent.agentId,
    name: agent.name,
    desc: agent.description || agent.category,
    status: (agent.status || "IDLE") === "RUNNING" ? "运行中" : "已停止",
    type: agent.systemPreset ? "系统" : "用户",
    schedule: agent.scheduleCron || "手动触发",
    lastRun: agent.lastRunAt || "尚未运行",
    raw: agent,
  });

  const openCreateModal = () => {
    setForm({
      name: "",
      desc: "",
      type: "用户",
      schedule: "手动触发",
      prompt: "你是 Aegis Alpha 投研 Agent，请基于流程 state 输出结构化、可执行结论。",
    });
    setSelectedAgent(null);
    setConfigNotice("");
    setModalMode("create");
  };
  const openConfigModal = async (agent) => {
    setLogResult(null);
    let editableAgent = agent;
    let notice = "";

    if (agent.readonlyFlag) {
      const existingCopy = agents.find(
        (item) => !item.readonlyFlag && item.name === agent.name && item.agentId !== agent.agentId,
      );
      if (existingCopy) {
        editableAgent = existingCopy;
        notice = "系统预设 Agent 是只读模板，已打开同名用户副本进行配置。";
      } else {
        setBusy(true);
        try {
          editableAgent = await api(`/agents/${agent.agentId}/copy`, { method: "POST" });
          notice = "系统预设 Agent 是只读模板，已自动复制为用户副本，下面保存的是副本配置。";
          await load();
        } catch (error) {
          setSelectedAgent(agent);
          setLogResult({
            ok: false,
            provider: "request-error",
            content: error.message || "复制 Agent 失败",
            reason: error.message || "Request failed",
          });
          setModalMode("log");
          return;
        } finally {
          setBusy(false);
        }
      }
    }

    const card = toCard(editableAgent);
    setSelectedAgent(editableAgent);
    setConfigNotice(notice);
    setForm({
      name: card.name,
      desc: card.desc,
      type: card.type,
      schedule: card.schedule,
      prompt: editableAgent.prompt || "",
    });
    setModalMode("config");
  };
  const closeModal = () => {
    setModalMode(null);
    setSelectedAgent(null);
    setLogResult(null);
    setConfigNotice("");
  };
  const saveAgent = async (event) => {
    event.preventDefault();
    if (!form.name.trim() || !form.desc.trim()) return;
    setBusy(true);
    const body = {
      name: form.name.trim(),
      description: form.desc.trim(),
      category: form.type === "系统" ? "system" : "analyst",
      tags: "analyst,custom",
      prompt: form.prompt,
      scheduleCron: form.schedule,
      modelName: selectedAgent?.modelName || "deepseek-v4-flash",
      toolsJson: selectedAgent?.toolsJson || '["market-data","portfolio","news"]',
    };
    try {
      if (modalMode === "config" && selectedAgent) {
        await api(`/agents/${selectedAgent.agentId}`, { method: "PUT", body: JSON.stringify(body) });
      } else {
        await api("/agents", { method: "POST", body: JSON.stringify(body) });
      }
      await load();
      closeModal();
    } finally {
      setBusy(false);
    }
  };
  const runAgent = async (agent) => {
    setSelectedAgent(agent);
    setModalMode("run");
  };
  const executeAgentRun = async () => {
    if (!selectedAgent) return;
    setLogResult({ loading: true });
    try {
      const body = {
        subject: runForm.subject,
        model: runForm.model || undefined,
        state: { ticker: runForm.ticker, subject: runForm.subject },
      };
      if (runForm.apiKey.trim()) body.apiKey = runForm.apiKey.trim();
      const result = await api(`/agents/${selectedAgent.agentId}/run`, {
        method: "POST",
        body: JSON.stringify(body),
      });
      setLogResult(result);
      await load();
    } catch (error) {
      setLogResult({
        ok: false,
        provider: "request-error",
        content: error.message || "Agent 运行失败",
        reason: error.message || "Request failed",
      });
    }
  };
  const copyAgent = async (agent) => {
    await api(`/agents/${agent.agentId}/copy`, { method: "POST" });
    await load();
  };
  const logIsError = logResult && logResult.ok === false;
  const logMeta = logResult?.loading
    ? "RUNNING · LangGraph"
    : `${logIsError ? "ERROR" : "INFO"} · ${logResult?.provider || "LangGraph"}`;

  return (
    <div className="space-y-6">
      <div className="flex flex-col gap-4 lg:flex-row lg:items-center lg:justify-between">
        <div>
          <h2 className="text-lg font-semibold text-gray-800">AI+ Agent</h2>
          <p className="mt-1 text-sm text-gray-500">
            共 {agents.length} 个 Agent，{runningCount} 个正在运行
          </p>
        </div>
        <button onClick={openCreateModal} className="btn-primary text-sm">
          新建 Agent
        </button>
      </div>
      <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
        {agents.map((agent) => {
          const card = toCard(agent);
          return (
            <div key={agent.agentId} className="card transition-shadow hover:shadow-md">
              <div className="flex items-start justify-between gap-4">
                <div>
                  <h3 className="font-medium text-gray-800">{card.name}</h3>
                  <p className="mt-1 text-sm text-gray-500">{card.desc}</p>
                </div>
                <span className={`badge ${card.status === "运行中" ? "badge-green" : "badge-gray"}`}>
                  {card.status}
                </span>
              </div>
              <div className="mt-4 grid grid-cols-2 gap-3 border-t border-gray-100 pt-3 text-xs text-gray-500">
                <span>类型：{card.type}</span>
                <span>频率：{card.schedule}</span>
                <span className="col-span-2">上次运行：{card.lastRun}</span>
              </div>
              <div className="mt-3 flex flex-wrap gap-2">
                <button
                  onClick={() => openConfigModal(agent)}
                  className="btn-secondary min-h-8 px-3 py-1.5 text-xs"
                  disabled={busy}
                >
                  {agent.readonlyFlag ? "配置副本" : "配置"}
                </button>
                <button onClick={() => runAgent(agent)} className="btn-secondary min-h-8 px-3 py-1.5 text-xs">
                  启动
                </button>
                <button onClick={() => copyAgent(agent)} className="btn-secondary min-h-8 px-3 py-1.5 text-xs">
                  复制
                </button>
                <button
                  onClick={() => {
                    setSelectedAgent(agent);
                    setModalMode("log");
                    setLogResult(null);
                  }}
                  className="btn-secondary min-h-8 px-3 py-1.5 text-xs"
                >
                  日志
                </button>
              </div>
            </div>
          );
        })}
      </div>
      {(modalMode === "create" || modalMode === "config") && (
        <ModalShell title={modalMode === "config" ? "配置 Agent" : "新建 Agent"} onClose={closeModal}>
          <form onSubmit={saveAgent} className="p-6">
            {configNotice && (
              <div className="mb-4 rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-xs text-amber-700">
                {configNotice}
              </div>
            )}
            <div className="space-y-4">
              <label className="block">
                <span className="mb-1 block text-sm font-medium text-gray-700">Agent 名称</span>
                <input
                  value={form.name}
                  onChange={(event) => setForm((current) => ({ ...current, name: event.target.value }))}
                  className="input-field"
                  placeholder="例如：港股公告监控 Agent"
                />
              </label>
              <label className="block">
                <span className="mb-1 block text-sm font-medium text-gray-700">任务描述</span>
                <textarea
                  value={form.desc}
                  onChange={(event) => setForm((current) => ({ ...current, desc: event.target.value }))}
                  className="input-field min-h-[96px] resize-none"
                  placeholder="说明这个 Agent 需要监控、分析或自动执行的任务"
                />
              </label>
              <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
                <label className="block">
                  <span className="mb-1 block text-sm font-medium text-gray-700">类型</span>
                  <select
                    value={form.type}
                    onChange={(event) => setForm((current) => ({ ...current, type: event.target.value }))}
                    className="input-field"
                  >
                    <option>用户</option>
                    <option>系统</option>
                  </select>
                </label>
                <label className="block">
                  <span className="mb-1 block text-sm font-medium text-gray-700">运行频率</span>
                  <select
                    value={form.schedule}
                    onChange={(event) => setForm((current) => ({ ...current, schedule: event.target.value }))}
                    className="input-field"
                  >
                    <option>手动触发</option>
                    <option>每 15 分钟</option>
                    <option>每小时</option>
                    <option>每日 09:00</option>
                    <option>每周一 08:30</option>
                  </select>
                </label>
              </div>
              <label className="block">
                <span className="mb-1 block text-sm font-medium text-gray-700">Prompt</span>
                <textarea
                  value={form.prompt}
                  onChange={(event) => setForm((current) => ({ ...current, prompt: event.target.value }))}
                  className="input-field min-h-[120px] resize-y"
                />
              </label>
            </div>
            <div className="mt-6 flex justify-end gap-3">
              <button type="button" onClick={closeModal} className="btn-secondary">
                取消
              </button>
              <button type="submit" className="btn-primary" disabled={busy}>
                {modalMode === "config" ? "保存配置" : "创建 Agent"}
              </button>
            </div>
          </form>
        </ModalShell>
      )}
      {modalMode === "log" && selectedAgent && (
        <ModalShell title={`${selectedAgent.name} 日志`} onClose={closeModal} widthClass="max-w-xl">
          <div className="p-6">
            <div className="space-y-3 text-sm">
              <div className="rounded-lg border border-gray-200 bg-gray-50 p-3">
                <p className="font-medium text-gray-700">最近运行</p>
                <p className="mt-1 text-gray-500">{selectedAgent.lastRunAt || "尚未运行"}</p>
              </div>
              <div className={`rounded-lg border p-3 ${logIsError ? "border-red-200 bg-red-50" : "border-gray-200"}`}>
                <p className={logIsError ? "text-red-700" : "text-gray-700"}>
                  {logResult?.loading
                    ? "Agent 正在运行..."
                    : logResult?.content || "任务初始化完成，已加载市场数据源和策略参数。"}
                </p>
                {logResult?.reason && <p className="mt-2 text-xs text-red-600">{logResult.reason}</p>}
                <p className={`mt-1 text-xs ${logIsError ? "text-red-500" : "text-gray-400"}`}>{logMeta}</p>
              </div>
            </div>
            <div className="mt-6 flex justify-end">
              <button onClick={closeModal} className="btn-primary">
                关闭
              </button>
            </div>
          </div>
        </ModalShell>
      )}
      {modalMode === "run" && selectedAgent && (
        <ModalShell title={`运行 ${selectedAgent.name}`} onClose={closeModal} widthClass="max-w-xl">
          <div className="p-6">
            <p className="mb-4 text-sm text-gray-500">填写运行参数，直接调用底层大模型验证连通性。</p>
            <div className="space-y-4">
              <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
                <label className="block">
                  <span className="mb-1 block text-sm font-medium text-gray-700">Subject</span>
                  <input
                    className="input-field"
                    value={runForm.subject}
                    onChange={(e) => setRunForm((c) => ({ ...c, subject: e.target.value }))}
                  />
                </label>
                <label className="block">
                  <span className="mb-1 block text-sm font-medium text-gray-700">Ticker</span>
                  <input
                    className="input-field"
                    value={runForm.ticker}
                    onChange={(e) => setRunForm((c) => ({ ...c, ticker: e.target.value }))}
                  />
                </label>
                <label className="block">
                  <span className="mb-1 block text-sm font-medium text-gray-700">模型</span>
                  <input
                    className="input-field"
                    value={runForm.model}
                    onChange={(e) => setRunForm((c) => ({ ...c, model: e.target.value }))}
                    placeholder="默认使用后端配置"
                  />
                </label>
                <label className="block">
                  <span className="mb-1 block text-sm font-medium text-gray-700">API Key（可选）</span>
                  <input
                    className="input-field"
                    value={runForm.apiKey}
                    onChange={(e) => setRunForm((c) => ({ ...c, apiKey: e.target.value }))}
                    placeholder="不填则使用后端/编排引擎配置"
                  />
                </label>
              </div>
              <div className="flex justify-end gap-3">
                <button type="button" onClick={closeModal} className="btn-secondary">
                  取消
                </button>
                <button type="button" onClick={executeAgentRun} disabled={busy} className="btn-primary">
                  {busy ? "调用中..." : "测试调用大模型"}
                </button>
              </div>
              {logResult && !logResult.loading && (
                <div className="rounded-xl border border-gray-200 bg-gray-50 p-4 text-sm">
                  <div className="mb-2 text-xs text-gray-500">provider: {logResult.provider || "langgraph"}</div>
                  <div className="whitespace-pre-wrap text-gray-800">
                    {logResult.content || logResult.message || logResult.summary || JSON.stringify(logResult, null, 2)}
                  </div>
                </div>
              )}
              {logResult?.loading && (
                <div className="flex items-center gap-3 rounded-lg border border-gray-200 bg-gray-50 px-4 py-3">
                  <div className="relative h-5 w-5">
                    <div className="absolute inset-0 rounded-full border-2 border-gray-200" />
                    <div className="absolute inset-0 animate-spin rounded-full border-2 border-transparent border-t-blue-500" />
                  </div>
                  <span className="text-sm text-gray-500">Agent 正在调用中...</span>
                </div>
              )}
            </div>
          </div>
        </ModalShell>
      )}
    </div>
  );
}

function WorkflowPage({ api, token }) {
  return (
    <ReactFlowProvider>
      <WorkflowComposer api={api} token={token} />
    </ReactFlowProvider>
  );
}

function WorkflowComposer({ api, token }) {
  const [workflows, setWorkflows] = useState([]);
  const [agents, setAgents] = useState([]);
  const [workflow, setWorkflow] = useState(null);
  const [, setShowWorkflowList] = useState(false);
  const [activeTab, setActiveTab] = useState("graph");
  const [jsonText, setJsonText] = useState("");
  const [search, setSearch] = useState("");
  const [selectedNodeId, setSelectedNodeId] = useState(null);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [catalogDetailNode, setCatalogDetailNode] = useState(null);
  const [toast, setToast] = useState("");
  const [runStatuses, setRunStatuses] = useState({});
  const [runForm, setRunForm] = useState({
    ticker: "AAPL",
    industry: "AI Infrastructure",
    subject: "stock recommendation research",
    model: "",
    apiKey: "",
  });
  const [nodes, setNodes] = useState([]);
  const [edges, setEdges] = useState([]);

  const loadLists = useCallback(
    () =>
      Promise.all([api("/workflows"), api("/agents")]).then(([w, a]) => {
        setWorkflows(w);
        setAgents(a);
      }),
    [api],
  );
  useEffect(() => {
    loadLists().catch(() => {});
  }, [loadLists]);

  const showToast = useCallback((message) => {
    setToast(message);
    window.setTimeout(() => setToast(""), 2400);
  }, []);
  const selectedWorkflowNode = useMemo(
    () => nodes.find((node) => node.id === selectedNodeId) || null,
    [nodes, selectedNodeId],
  );
  const catalogGroups = useMemo(() => {
    const keyword = search.trim().toLowerCase();
    return workflowCatalogGroups.map((group) => ({
      ...group,
      nodes: group.nodes.filter((node) => {
        if (!keyword) return true;
        return [node.label, node.id, node.handler].join(" ").toLowerCase().includes(keyword);
      }),
    }));
  }, [search]);

  const openWorkflow = useCallback(
    async (definition) => {
      let layout = null;
      try {
        layout = await api(`/workflows/${definition.workflowKey}/layout`);
      } catch (ex) {
        layout = defaultWorkflowLayouts[definition.workflowKey];
        if (!layout) throw ex;
        showToast("后端未返回布局，已加载本地默认编排");
      }
      setWorkflow({ ...definition, layout });
      const normalizedNodes = normalizeNodes(layout.nodes || []);
      setNodes(normalizedNodes);
      setEdges(normalizeEdges(layout.edges || []));
      setSelectedNodeId(normalizedNodes[0]?.id || null);
      setDrawerOpen(false);
      setCatalogDetailNode(null);
      setShowWorkflowList(false);
    },
    [api, showToast],
  );

  const previewWorkflows = useMemo(() => {
    const backendByKey = new Map(workflows.map((item) => [item.workflowKey, item]));
    return workflowPreviewDefinitions.map((item) => {
      const backendItem = backendByKey.get(item.workflowKey) || {};
      return { ...backendItem, ...item, description: backendItem.description || item.description || "" };
    });
  }, [workflows]);

  const _createWorkflow = async () => {
    const created = await api("/workflows", {
      method: "POST",
      body: JSON.stringify({
        name: "Custom Agent Workflow",
        description: "可自定义的 Agent 编排流程",
        engine: "langgraph",
      }),
    });
    await loadLists();
    await openWorkflow(created);
  };

  const denormalizeForSave = (nodes, edges) => ({
    nodes: nodes.map(({ id, type, position, data }) => ({
      id,
      type: type === "workflowNode" ? "workflowNode" : type,
      position,
      data: {
        label: data.label || id,
        nodeType: data.nodeType || "logic",
        handler: data.handler || "general.node",
        agentId: data.agentId || "",
        prompt: data.prompt || "",
        inputKeys: data.inputKeys || [],
        outputKeys: data.outputKeys || [],
      },
    })),
    edges: edges.map(({ source, target, animated }) => ({
      id: `${source}-${target}`,
      source,
      target,
      animated: animated !== false,
    })),
  });

  const saveLayout = async () => {
    if (!workflow) return;
    const clean = denormalizeForSave(nodes, edges);
    await api(`/workflows/${workflow.workflowKey}/layout`, {
      method: "PUT",
      body: JSON.stringify({ name: workflow.name, engine: workflow.engine || "langgraph", ...clean }),
    });
    showToast("布局已保存到后端");
  };

  const runWorkflowSync = async () => {
    if (!workflow) return;
    if (!workflow.readonlyFlag) await saveLayout();
    setRunStatuses({});
    const inputs = {
      ticker: runForm.ticker.trim() || "AAPL",
      industry: runForm.industry.trim() || "AI Infrastructure",
      subject: runForm.subject.trim() || "stock recommendation research",
      trade_date: new Date().toISOString().slice(0, 10),
      ...(runForm.model.trim() ? { model: runForm.model.trim() } : {}),
      ...(runForm.apiKey.trim() ? { apiKey: runForm.apiKey.trim() } : {}),
    };
    const run = await api(`/workflows/${workflow.workflowKey}/run`, {
      method: "POST",
      body: JSON.stringify({ subject: inputs.subject, inputs }),
    });
    const nodeRuns = await api(`/workflow/runs/${run.runId}/nodes`);
    const statuses = {};
    (Array.isArray(nodeRuns) ? nodeRuns : []).forEach((item) => {
      statuses[item.nodeId] =
        item.status === "COMPLETED"
          ? "success"
          : item.status === "RUNNING"
            ? "running"
            : item.status === "FAILED"
              ? "error"
              : "idle";
    });
    setRunStatuses(statuses);
    setNodes((current) =>
      current.map((node) => ({ ...node, data: { ...node.data, runStatus: statuses[node.id] || "idle" } })),
    );
    showToast(`运行完成：${run.status}`);
  };

  const runWorkflow = async () => {
    if (!workflow) return;
    if (!workflow.readonlyFlag) await saveLayout();
    setRunStatuses({});
    const inputs = {
      ticker: runForm.ticker.trim() || "AAPL",
      industry: runForm.industry.trim() || "AI Infrastructure",
      subject: runForm.subject.trim() || "stock recommendation research",
      trade_date: new Date().toISOString().slice(0, 10),
      ...(runForm.model.trim() ? { model: runForm.model.trim() } : {}),
      ...(runForm.apiKey.trim() ? { apiKey: runForm.apiKey.trim() } : {}),
    };
    try {
      const resp = await fetch(`${API_BASE}/workflows/${workflow.workflowKey}/run/stream`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          ...(token ? { Authorization: `Bearer ${token}` } : {}),
        },
        body: JSON.stringify({ subject: inputs.subject, inputs }),
      });
      if (!resp.ok) {
        return runWorkflowSync();
      }
      const reader = resp.body.getReader();
      const decoder = new TextDecoder();
      let buffer = "";
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        const parts = buffer.split(String.fromCharCode(10));
        buffer = parts.pop() || "";
        let currentEvent = "";
        for (const part of parts) {
          if (part.startsWith("event:")) {
            currentEvent = part.slice(6).trim();
          } else if (part.startsWith("data:")) {
            const raw = part.slice(5).trim();
            try {
              const data = JSON.parse(raw);
              if (currentEvent === "node_update") {
                const nid = data.nodeId;
                const status = data.event === "node_failed" || data.ok === false ? "error" : "success";
                setRunStatuses((prev) => ({ ...prev, [nid]: "running" }));
                setNodes((cur) =>
                  cur.map((n) => (n.id === nid ? { ...n, data: { ...n.data, runStatus: "running" } } : n)),
                );
                setTimeout(() => {
                  setRunStatuses((prev) => ({ ...prev, [nid]: status }));
                  setNodes((cur) =>
                    cur.map((n) => (n.id === nid ? { ...n, data: { ...n.data, runStatus: status } } : n)),
                  );
                }, 300);
              } else if (currentEvent === "workflow_complete") {
                showToast("运行完成");
              } else if (currentEvent === "error") {
                showToast(`运行错误：${data.error || "unknown"}`);
              }
            } catch {
              // skip unparseable data
            }
          }
        }
      }
    } catch {
      return runWorkflowSync();
    }
  };

  const onNodesChange = useCallback((changes) => setNodes((current) => applyNodeChanges(changes, current)), []);
  const onEdgesChange = useCallback((changes) => setEdges((current) => applyEdgeChanges(changes, current)), []);
  const onConnect = useCallback(
    (connection) =>
      setEdges((current) =>
        addEdge(
          { ...connection, type: "smoothstep", label: "normal", markerEnd: { type: MarkerType.ArrowClosed } },
          current,
        ),
      ),
    [],
  );
  const selectNode = (nodeId) => {
    setCatalogDetailNode(null);
    setSelectedNodeId(nodeId);
    setDrawerOpen(true);
    setNodes((current) =>
      current.map((node) => ({
        ...node,
        data: { ...node.data, selected: node.id === nodeId, runStatus: runStatuses[node.id] || "idle" },
      })),
    );
  };
  const openCatalogDetail = (node, groupTitle) => {
    setDrawerOpen(false);
    setCatalogDetailNode({ ...node, groupTitle });
  };

  const addNode = (item) => {
    const id = `${item.nodeType}-${Date.now().toString(36)}`;
    const node = {
      id,
      type: "workflowNode",
      position: { x: 160 + nodes.length * 28, y: 140 + nodes.length * 20 },
      data: {
        label: item.label,
        nodeType: item.nodeType,
        handler: item.handler,
        agentId: item.nodeType === "agent" ? agents[0]?.agentId || "" : "",
        prompt: "",
        inputKeys: [],
        outputKeys: [],
      },
    };
    setNodes((current) => [...current, node]);
    setSelectedNodeId(id);
    setCatalogDetailNode(null);
    setDrawerOpen(true);
  };

  const applyNodeUpdate = (updated) => {
    setNodes((current) => current.map((node) => (node.id === updated.id ? updated : node)));
    setDrawerOpen(false);
    showToast("节点配置已应用到前端状态");
  };
  const currentPreviewMeta = workflow ? workflowPreviewByKey[workflow.workflowKey] : null;
  const displayWorkflowName = currentPreviewMeta?.name || workflow?.name || "工作流";
  const displayVersion = currentPreviewMeta?.versionLabel?.toUpperCase() || `V${workflow?.version || 1}`;
  const readonlyText = workflow?.readonlyFlag ? "只读 · 不可删除" : "可编辑";
  const workflowDescription = workflow?.readonlyFlag
    ? "系统预设工作流由平台统一管理，不可修改布局或节点配置。"
    : workflow?.description || "自定义工作流支持节点编排、配置和运行。";

  if (!workflow) {
    return (
      <div className="bg-white">
        <div className="mb-[11px]">
          <h2 className="text-[26px] font-bold leading-8 text-black">工作流预览</h2>
          <p className="mt-1 text-sm leading-5 text-gray-600">系统预设工作流（只读、不可删除）。</p>
        </div>
        <div className="grid grid-cols-1 gap-3 md:grid-cols-2 xl:grid-cols-3">
          {previewWorkflows.map((item) => (
            <div
              key={item.workflowKey}
              className="relative flex min-h-[146px] flex-col rounded-[14px] border border-[#dedede] bg-white px-4 pb-[18px] pt-[21px]"
            >
              <WorkflowPreviewGlyph className="absolute right-4 top-[21px] h-4 w-4 text-neutral-700" />
              <h3 className="pr-8 text-[16px] font-semibold leading-5 text-black">{item.name}</h3>
              <p className="mt-1 text-[13px] leading-4 text-gray-600">
                {item.workflowKey} · {item.versionLabel}
              </p>
              <p className="mt-3 text-[12px] leading-4 text-gray-600">
                <span>节点 {item.nodes}</span>
                <span className="ml-3">连线 {item.edges}</span>
                <span className="ml-3">系统预设</span>
              </p>
              <button
                onClick={() => openWorkflow(item)}
                className="mt-auto flex h-8 w-full items-center justify-between rounded-lg border border-[#e5e5e5] bg-white px-3 text-[14px] font-semibold text-black transition-colors hover:bg-gray-50"
              >
                <span>打开流程图</span>
                <Icon name="arrowRight" className="h-4 w-4" />
              </button>
            </div>
          ))}
        </div>
      </div>
    );
  }

  return (
    <div className="-m-6 flex h-[calc(100vh-4rem)] min-h-[760px] flex-col bg-white px-6 py-6">
      <section className="bg-white">
        <div className="flex flex-col gap-4 xl:flex-row xl:items-start xl:justify-between">
          <div>
            <h2 className="text-3xl font-bold leading-tight text-black">{displayWorkflowName}</h2>
            <p className="mt-1 text-sm text-gray-700">
              {workflow.workflowKey} · {displayVersion} · {readonlyText}
            </p>
            <p className="mt-5 text-sm text-gray-600">{workflowDescription}</p>
          </div>
          <div className="flex flex-wrap items-end gap-3 pt-2">
            <label className="block">
              <span className="mb-1 block text-xs font-medium text-gray-600">Ticker</span>
              <input
                value={runForm.ticker}
                onChange={(event) => setRunForm((current) => ({ ...current, ticker: event.target.value }))}
                className="h-9 w-24 rounded-lg border border-gray-200 px-3 text-sm outline-none focus:border-gray-300 focus:ring-2 focus:ring-gray-100"
              />
            </label>
            <label className="block">
              <span className="mb-1 block text-xs font-medium text-gray-600">Industry</span>
              <input
                value={runForm.industry}
                onChange={(event) => setRunForm((current) => ({ ...current, industry: event.target.value }))}
                className="h-9 w-44 rounded-lg border border-gray-200 px-3 text-sm outline-none focus:border-gray-300 focus:ring-2 focus:ring-gray-100"
              />
            </label>
            <label className="block">
              <span className="mb-1 block text-xs font-medium text-gray-600">Subject</span>
              <input
                value={runForm.subject}
                onChange={(event) => setRunForm((current) => ({ ...current, subject: event.target.value }))}
                className="h-9 w-60 rounded-lg border border-gray-200 px-3 text-sm outline-none focus:border-gray-300 focus:ring-2 focus:ring-gray-100"
              />
            </label>
            <label className="block">
              <span className="mb-1 block text-xs font-medium text-gray-600">Model</span>
              <input
                value={runForm.model}
                onChange={(event) => setRunForm((current) => ({ ...current, model: event.target.value }))}
                placeholder="默认"
                className="h-9 w-32 rounded-lg border border-gray-200 px-3 text-sm outline-none focus:border-gray-300 focus:ring-2 focus:ring-gray-100"
              />
            </label>
            <label className="block">
              <span className="mb-1 block text-xs font-medium text-gray-600">API Key</span>
              <input
                value={runForm.apiKey}
                onChange={(event) => setRunForm((current) => ({ ...current, apiKey: event.target.value }))}
                placeholder="可选"
                className="h-9 w-32 rounded-lg border border-gray-200 px-3 text-sm outline-none focus:border-gray-300 focus:ring-2 focus:ring-gray-100"
              />
            </label>
            <button
              onClick={runWorkflow}
              className="rounded-lg border border-gray-200 bg-white px-4 py-2 text-sm font-semibold text-black shadow-sm hover:bg-gray-50"
            >
              手动运行
            </button>
            {!workflow.readonlyFlag && (
              <button
                onClick={saveLayout}
                className="rounded-lg border border-gray-200 bg-white px-4 py-2 text-sm font-semibold text-black shadow-sm hover:bg-gray-50"
              >
                保存布局
              </button>
            )}
            <button
              onClick={() => {
                setShowWorkflowList(true);
                setWorkflow(null);
              }}
              className="rounded-lg border border-gray-200 bg-white px-4 py-2 text-sm font-semibold text-black shadow-sm hover:bg-gray-50"
            >
              返回工作流列表
            </button>
          </div>
        </div>
        <div className="mt-5 flex gap-2">
          <button
            onClick={() => setActiveTab("graph")}
            className={`rounded-md px-3 py-2 text-sm font-semibold ${activeTab === "graph" ? "bg-black text-white" : "border border-gray-200 bg-white text-black hover:bg-gray-50"}`}
          >
            图形视图
          </button>
          <button
            onClick={() => setActiveTab("json")}
            className={`rounded-md px-3 py-2 text-sm font-semibold ${activeTab === "json" ? "bg-black text-white" : "border border-gray-200 bg-white text-black hover:bg-gray-50"}`}
          >
            JSON 视图
          </button>
        </div>
        {toast && (
          <div className="mt-3 inline-flex rounded-lg border border-emerald-200 bg-emerald-50 px-3 py-2 text-sm text-emerald-700">
            {toast}
          </div>
        )}
      </section>
      <section className="mt-5 flex min-h-0 flex-1 gap-3">
        <NodeCatalog
          groupedNodes={catalogGroups}
          search={search}
          setSearch={setSearch}
          selectedNodeId={selectedNodeId}
          onSelectNode={() => {}}
          onAddNode={addNode}
          onOpenDetail={openCatalogDetail}
          readonly={workflow.readonlyFlag}
        />
        <main className="flex min-w-0 flex-1 flex-col overflow-hidden bg-white">
          <div className="mb-3 flex h-11 items-center rounded-xl border border-gray-200 px-4 text-xs text-gray-600">
            <span className="mr-5">图例</span>
            <span className="mr-4">♙ Agent 节点</span>
            <span className="mr-4">◇ Logic 节点</span>
            <span className="mr-4">⌁ 条件节点</span>
            <span className="mr-4">◎ 开始</span>
            <span className="mr-4">⚑ 结束</span>
            <span className="mr-4 text-emerald-600">○ 成功</span>
            <span className="mr-4 text-red-600">△ 错误</span>
            <span className="text-blue-600">◔ 运行中</span>
          </div>
          {activeTab === "graph" ? (
            <div className="min-h-0 flex-1 overflow-hidden rounded-xl border border-gray-200 bg-white">
              <ReactFlow
                nodes={nodes}
                edges={edges}
                nodeTypes={{ workflowNode: WorkflowNodeCard }}
                onNodesChange={onNodesChange}
                onEdgesChange={onEdgesChange}
                onConnect={onConnect}
                onNodeClick={(_, node) => selectNode(node.id)}
                fitView
                fitViewOptions={{ padding: 0.18 }}
                minZoom={0.2}
                maxZoom={1.6}
                defaultEdgeOptions={{ type: "smoothstep" }}
                className="workflow-canvas"
              >
                <Background gap={16} size={1} color="#d6d6d6" />
                <Controls />
                <MiniMap nodeStrokeWidth={3} pannable zoomable />
              </ReactFlow>
            </div>
          ) : (
            <div className="flex min-h-0 flex-1 flex-col gap-2">
              <div className="flex items-center justify-between">
                <span className="text-xs text-gray-500">
                  编辑 JSON 后点击「同步到图形」，确认后点「保存布局」持久化
                </span>
                <button
                  onClick={() => {
                    try {
                      const parsed = JSON.parse(jsonText);
                      if (parsed.nodes) setNodes(normalizeNodes(parsed.nodes));
                      if (parsed.edges) setEdges(normalizeEdges(parsed.edges));
                      showToast("JSON 已应用到图形视图");
                    } catch (e) {
                      showToast("JSON 格式错误: " + e.message);
                    }
                  }}
                  className="rounded-md bg-black px-3 py-1 text-xs font-semibold text-white hover:bg-gray-800"
                >
                  同步到图形
                </button>
              </div>
              <textarea
                className="min-h-0 w-full flex-1 resize-none rounded-lg bg-slate-950 p-3 font-mono text-xs leading-5 text-slate-100 outline-none focus:ring-2 focus:ring-blue-500"
                value={jsonText}
                onChange={(e) => setJsonText(e.target.value)}
                onFocus={() => setJsonText(JSON.stringify(denormalizeForSave(nodes, edges), null, 2))}
                spellCheck={false}
              />
            </div>
          )}
        </main>
      </section>
      {drawerOpen && selectedWorkflowNode && (
        <NodeEditorDrawer
          node={selectedWorkflowNode}
          agents={agents}
          onCancel={() => setDrawerOpen(false)}
          onApply={applyNodeUpdate}
        />
      )}
      {catalogDetailNode && (
        <CatalogNodeDetailDrawer node={catalogDetailNode} api={api} onClose={() => setCatalogDetailNode(null)} />
      )}
    </div>
  );
}

function normalizeNodes(rawNodes) {
  return rawNodes.map((node) => {
    const data = node.data || {};
    return {
      ...node,
      type: "workflowNode",
      position: node.position || { x: 120, y: 120 },
      data: {
        label: data.label || data.title || node.id,
        desc: data.desc || data.description || "",
        nodeType: data.nodeType || (node.type === "workflowNode" ? "logic" : node.type) || "logic",
        handler: data.handler || data.functionName || "general.node",
        agentId: data.agentId || data.agent_id || "",
        prompt: data.prompt || "",
        inputKeys: data.inputKeys || data.input_keys || [],
        outputKeys: data.outputKeys || data.output_keys || [],
        selected: false,
        runStatus: "idle",
      },
    };
  });
}

function normalizeEdges(rawEdges) {
  return rawEdges.map((edge) => ({
    ...edge,
    label: edge.label || "normal",
    type: "smoothstep",
    style: { stroke: "#9ca3af", strokeWidth: 1 },
    labelStyle: { fill: "#4b5563", fontSize: 7, fontWeight: 500 },
    labelBgStyle: { fill: "#fff", fillOpacity: 0.95 },
    markerEnd: { type: MarkerType.ArrowClosed, width: 10, height: 10 },
  }));
}

function NodeCatalog({
  groupedNodes,
  search,
  setSearch,
  _selectedNodeId,
  _onSelectNode,
  _onAddNode,
  onOpenDetail,
  _readonly = false,
}) {
  return (
    <aside className="flex w-[288px] flex-shrink-0 flex-col rounded-xl border border-gray-200 bg-white p-3">
      <h3 className="mb-3 text-sm font-semibold text-black">节点目录</h3>
      <input
        value={search}
        onChange={(event) => setSearch(event.target.value)}
        className="mb-3 h-9 rounded-lg border border-gray-200 px-3 text-sm outline-none placeholder:text-gray-400 focus:border-gray-300 focus:ring-2 focus:ring-gray-100"
        placeholder="搜索节点名称或标识"
      />
      <div className="workflow-catalog-scroll min-h-0 flex-1 space-y-2 overflow-auto pr-1">
        {groupedNodes.map((group) => (
          <div key={group.title} className="rounded-xl border border-gray-200 bg-white p-2">
            <div className="mb-2 flex items-center text-sm font-bold text-black">
              <span className="mr-1 text-xs">▼</span>
              <span>
                {group.title} ({group.nodes.length})
              </span>
            </div>
            <div className="space-y-1.5">
              {group.nodes.map((node) => (
                <button
                  key={node.id}
                  onClick={() => onOpenDetail?.(node, group.title)}
                  className="w-full rounded-lg border border-gray-200 bg-white px-3 py-2.5 text-left transition hover:border-gray-300 hover:bg-gray-50"
                >
                  <span className="block truncate text-sm font-semibold text-black">{node.label}</span>
                  <span className="mt-1 block truncate font-mono text-xs text-gray-600">{node.id}</span>
                </button>
              ))}
            </div>
          </div>
        ))}
      </div>
    </aside>
  );
}

function catalogNodeTypeLabel(node) {
  if (node.id?.startsWith("fdb.")) return "数据";
  if (node.nodeType === "start") return "开始";
  if (node.nodeType === "end") return "结束";
  if (node.nodeType === "agent") return "Agent";
  return node.nodeType || "Logic";
}

function catalogNodeDescription(node) {
  return (
    workflowCatalogDetailMap[node.id]?.description || `${node.label} 节点，可通过 ${node.handler || node.id} 执行。`
  );
}

function CatalogNodeDetailDrawer({ node, api, onClose }) {
  const [action, setAction] = useState("");
  const [params, setParams] = useState("");
  const [extraJson, setExtraJson] = useState("{}");
  const [running, setRunning] = useState(false);
  const [result, setResult] = useState(null);
  const [error, setError] = useState("");

  useEffect(() => {
    setAction("");
    setParams("");
    setExtraJson("{}");
    setResult(null);
    setError("");
  }, [node.id]);

  const runCatalogNode = async () => {
    setRunning(true);
    setError("");
    setResult(null);
    try {
      const extra = extraJson.trim() ? JSON.parse(extraJson) : {};
      if (extra === null || Array.isArray(extra) || typeof extra !== "object") {
        throw new Error("附加 JSON 必须是对象");
      }
      const payload = {
        nodeId: node.id,
        functionName: node.handler || node.id,
        action,
        params,
        extra,
      };
      const response = await api("/workflow-nodes/execute", { method: "POST", body: JSON.stringify(payload) });
      setResult(response);
    } catch (ex) {
      setError(ex.message || "运行节点失败");
    } finally {
      setRunning(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50">
      <div className="absolute inset-0 bg-white/55 backdrop-blur-[5px]" onClick={onClose} />
      <aside className="absolute inset-y-0 right-0 flex w-full max-w-[384px] flex-col border-l border-gray-100 bg-white px-4 py-5 shadow-2xl">
        <div className="mb-7 flex items-start justify-between gap-4">
          <div>
            <h3 className="text-lg font-semibold leading-6 text-black">节点详情</h3>
            <p className="mt-1 text-sm text-gray-600">{node.label}</p>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="rounded-md px-2 py-1 text-2xl leading-none text-black hover:bg-gray-100"
            aria-label="关闭节点详情"
          >
            ×
          </button>
        </div>

        <div className="min-h-0 flex-1 overflow-auto">
          <div className="rounded-lg border border-gray-200 bg-white p-3 text-sm leading-6 text-black">
            <p className="font-semibold">{node.label}</p>
            <p className="font-mono text-xs text-gray-700">{node.id}</p>
            <p className="mt-2">节点类型: {catalogNodeTypeLabel(node)}</p>
            <p>节点目录: {node.groupTitle || "节点"}</p>
            <p>描述: {catalogNodeDescription(node)}</p>
          </div>

          <div className="mt-5 space-y-3">
            <p className="text-sm font-medium text-gray-600">Contract 自动字段</p>
            <label className="block">
              <span className="mb-1.5 block text-sm text-gray-700">action * (string)</span>
              <input
                value={action}
                onChange={(event) => setAction(event.target.value)}
                className="h-9 w-full rounded-lg border border-gray-200 px-3 text-sm outline-none placeholder:text-gray-400 focus:border-gray-300 focus:ring-2 focus:ring-gray-100"
                placeholder="输入参数值"
              />
            </label>
            <label className="block">
              <span className="mb-1.5 block text-sm text-gray-700">params * (string)</span>
              <input
                value={params}
                onChange={(event) => setParams(event.target.value)}
                className="h-9 w-full rounded-lg border border-gray-200 px-3 text-sm outline-none placeholder:text-gray-400 focus:border-gray-300 focus:ring-2 focus:ring-gray-100"
                placeholder="输入参数值"
              />
            </label>
            <label className="block">
              <span className="mb-1.5 block text-sm text-gray-700">附加 JSON（对象）</span>
              <textarea
                value={extraJson}
                onChange={(event) => setExtraJson(event.target.value)}
                className="h-24 w-full resize-none rounded-lg border border-gray-200 px-3 py-2 font-mono text-sm outline-none focus:border-gray-300 focus:ring-2 focus:ring-gray-100"
              />
            </label>
            {error && <p className="text-sm text-red-600">{error}</p>}
            <button
              type="button"
              onClick={runCatalogNode}
              disabled={running}
              className="h-10 w-full rounded-lg bg-[#171717] px-4 text-sm font-semibold text-white hover:bg-black disabled:cursor-wait disabled:opacity-70"
            >
              {running ? "运行中..." : "运行该节点"}
            </button>
            {result && (
              <pre className="max-h-48 overflow-auto rounded-lg border border-gray-200 bg-gray-50 p-3 text-xs leading-5 text-gray-700">
                {JSON.stringify(result, null, 2)}
              </pre>
            )}
          </div>
        </div>
      </aside>
    </div>
  );
}

function WorkflowNodeCard({ data }) {
  const type = data.nodeType || "logic";
  const specialNeutral = data.handler === "workflow.run_exit";
  const style = specialNeutral
    ? { border: "border-gray-600", bg: "bg-white", icon: "", label: "Logic" }
    : nodeTypeStyles[type] || nodeTypeStyles.logic;
  const statusRing =
    data.runStatus === "running"
      ? "ring-2 ring-blue-400"
      : data.runStatus === "success"
        ? "ring-2 ring-emerald-400"
        : data.selected
          ? "ring-2 ring-gray-900"
          : "";
  return (
    <div
      className={`workflow-node-card min-w-[102px] max-w-[170px] rounded-md border ${style.border} ${style.bg} ${statusRing} px-2.5 py-2 shadow-sm`}
    >
      <Handle type="target" position={Position.Left} />
      <div className="flex items-start justify-between gap-1.5">
        <div className="min-w-0">
          <p className="truncate text-[10px] font-bold leading-4 text-black">
            <span className="mr-1">{style.icon}</span>
            {data.label}
          </p>
          <p className="mt-0.5 line-clamp-1 text-[8px] leading-3 text-gray-600">
            {data.desc || data.handler || "no handler"}
          </p>
        </div>
        {data.runStatus === "running" && <span className="h-3 w-3 animate-pulse rounded-full bg-blue-500" />}
        {data.runStatus === "success" && <span className="h-3 w-3 rounded-full bg-emerald-500" />}
      </div>
      <Handle type="source" position={Position.Right} />
    </div>
  );
}

function NodeEditorDrawer({ node, agents, onCancel, onApply }) {
  const [draft, setDraft] = useState(node);
  useEffect(() => setDraft(node), [node]);
  const patchData = (patch) => setDraft((current) => ({ ...current, data: { ...current.data, ...patch } }));
  return (
    <aside className="fixed inset-y-0 right-0 z-50 flex w-[560px] flex-col border-l border-slate-200 bg-white shadow-2xl">
      <div className="border-b border-slate-200 px-5 py-4">
        <div className="flex items-start justify-between gap-3">
          <div>
            <h3 className="text-base font-semibold text-slate-950">节点编辑</h3>
            <p className="mt-1 font-mono text-xs text-slate-500">{draft.id}</p>
          </div>
          <button
            onClick={onCancel}
            className="rounded-lg px-2 py-1 text-xl leading-none text-slate-400 hover:bg-slate-100 hover:text-slate-700"
          >
            ×
          </button>
        </div>
      </div>
      <div className="min-h-0 flex-1 space-y-5 overflow-auto px-5 py-4">
        <Panel title="基本信息">
          <ReadOnlyField label="id" value={draft.id} />
          <Field label="label">
            <input
              className="input-field"
              value={draft.data.label || ""}
              onChange={(event) => patchData({ label: event.target.value })}
            />
          </Field>
          <div className="grid grid-cols-2 gap-3">
            <Field label="type">
              <select
                className="input-field"
                value={draft.data.nodeType || "logic"}
                onChange={(event) => patchData({ nodeType: event.target.value })}
              >
                {Object.keys(nodeTypeStyles).map((type) => (
                  <option key={type} value={type}>
                    {type}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="handler">
              <input
                className="input-field font-mono"
                value={draft.data.handler || ""}
                onChange={(event) => patchData({ handler: event.target.value })}
              />
            </Field>
          </div>
          {draft.data.nodeType === "agent" && (
            <>
              <Field label="绑定 Agent">
                <select
                  className="input-field"
                  value={draft.data.agentId || ""}
                  onChange={(event) => patchData({ agentId: event.target.value })}
                >
                  <option value="">内联 Agent</option>
                  {agents.map((agent) => (
                    <option key={agent.agentId} value={agent.agentId}>
                      {agent.name}
                    </option>
                  ))}
                </select>
              </Field>
              <Field label="prompt">
                <textarea
                  className="input-field min-h-[160px] resize-y whitespace-pre-wrap"
                  value={draft.data.prompt || ""}
                  onChange={(event) => patchData({ prompt: event.target.value })}
                />
              </Field>
            </>
          )}
          <div className="grid grid-cols-2 gap-3">
            <Field label="input_keys">
              <input
                className="input-field"
                value={(draft.data.inputKeys || []).join(",")}
                onChange={(event) => patchData({ inputKeys: splitCsv(event.target.value) })}
              />
            </Field>
            <Field label="output_keys">
              <input
                className="input-field"
                value={(draft.data.outputKeys || []).join(",")}
                onChange={(event) => patchData({ outputKeys: splitCsv(event.target.value) })}
              />
            </Field>
          </div>
        </Panel>
        <JsonViewer value={draft} compact />
      </div>
      <div className="flex flex-wrap items-center justify-between gap-2 border-t border-slate-200 px-5 py-4">
        <Button onClick={onCancel}>取消</Button>
        <Button variant="primary" onClick={() => onApply(draft)}>
          应用修改
        </Button>
      </div>
    </aside>
  );
}

function splitCsv(value) {
  return value
    .split(",")
    .map((item) => item.trim())
    .filter(Boolean);
}

function Portfolio({ api, navigate, path }) {
  const [portfolios, setPortfolios] = useState([]);
  const [trades, setTrades] = useState([]);
  const [portfolioContract, setPortfolioContract] = useState(null);
  const [form, setForm] = useState(emptyTradeForm);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");
  const [importPreview, setImportPreview] = useState([]);
  const [includeClosedPositions, setIncludeClosedPositions] = useState(false);
  const [holdingView, setHoldingView] = useState("list");
  const [tradeDrawerOpen, setTradeDrawerOpen] = useState(false);
  const [createModalOpen, setCreateModalOpen] = useState(false);
  const [manualTradeModalOpen, setManualTradeModalOpen] = useState(false);
  const [tradeSearch, setTradeSearch] = useState("");
  const [tradeFilterSide, setTradeFilterSide] = useState("ALL");
  const [tradeFilterMarket, setTradeFilterMarket] = useState("ALL");
  const [tradePage, setTradePage] = useState(1);
  const [expandedTradeIdx, setExpandedTradeIdx] = useState(-1);
  const TRADE_PAGE_SIZE = 20;
  const [newPortfolioName, setNewPortfolioName] = useState("");
  const fileInputRef = useRef(null);

  const selectedPortfolioId = form.portfolioId || portfolios[0]?.id || "";
  const selectedPortfolio = useMemo(
    () => portfolios.find((portfolio) => portfolio.id === selectedPortfolioId) || portfolios[0] || null,
    [portfolios, selectedPortfolioId],
  );
  const contractTrades = Array.isArray(portfolioContract?.trades) ? portfolioContract.trades : trades;
  const visibleTrades = useMemo(() => {
    if (!selectedPortfolioId) return contractTrades;
    return contractTrades.filter((trade) => !trade.portfolioId || trade.portfolioId === selectedPortfolioId);
  }, [contractTrades, selectedPortfolioId]);

  const summary = useMemo(() => {
    return visibleTrades.reduce(
      (acc, trade) => {
        const quantity = Number(trade.quantity || 0);
        const price = Number(trade.price || 0);
        const gross = Number(trade.grossAmount || quantity * price || 0);
        const net = Number(trade.netAmount || 0);
        acc.count += 1;
        acc.gross += gross;
        acc.net += net;
        if (trade.side === "BUY") acc.buys += 1;
        if (trade.side === "SELL") acc.sells += 1;
        return acc;
      },
      { count: 0, buys: 0, sells: 0, gross: 0, net: 0 },
    );
  }, [visibleTrades]);

  const holdingRows = useMemo(() => {
    if (Array.isArray(portfolioContract?.positions)) {
      return portfolioContract.positions
        .map((row) => ({
          symbol: row.symbol,
          name: row.securityName || row.name || "",
          market: row.market || "",
          currency: row.currency || "USD",
          quantity: Number(row.quantity || 0),
          cost: Number(row.costBasis || row.cost || 0),
          averageCost: Number(row.quantity || 0)
            ? Number(row.costBasis || row.cost || 0) / Number(row.quantity || 0)
            : 0,
        }))
        .filter((row) => includeClosedPositions || Math.abs(row.quantity) > 0.000001);
    }
    const positions = new Map();
    visibleTrades.forEach((trade) => {
      const symbol = String(trade.symbol || "")
        .trim()
        .toUpperCase();
      if (!symbol) return;
      const quantity = Number(trade.quantity || 0);
      const signedQuantity = trade.side === "SELL" ? -quantity : quantity;
      const gross = Number(trade.grossAmount || quantity * Number(trade.price || 0) || 0);
      const current = positions.get(symbol) || {
        symbol,
        name: trade.securityName || "",
        market: trade.market || "",
        currency: trade.currency || "USD",
        quantity: 0,
        cost: 0,
      };
      current.quantity += signedQuantity;
      current.cost += trade.side === "SELL" ? -gross : gross;
      if (!current.name && trade.securityName) current.name = trade.securityName;
      if (!current.market && trade.market) current.market = trade.market;
      positions.set(symbol, current);
    });
    return Array.from(positions.values())
      .map((row) => ({ ...row, averageCost: row.quantity ? row.cost / row.quantity : 0 }))
      .filter((row) => includeClosedPositions || Math.abs(row.quantity) > 0.000001);
  }, [visibleTrades, includeClosedPositions, portfolioContract]);
  const dataCompleteness = useMemo(() => {
    if (portfolioContract?.dataCompleteness) return portfolioContract.dataCompleteness;
    const expectedAssets = Number(selectedPortfolio?.assets || 0);
    const expectedTransactions = Number(selectedPortfolio?.transactions || 0);
    if (!selectedPortfolio) return "NO_PORTFOLIO";
    if (!visibleTrades.length && (expectedAssets > 0 || expectedTransactions > 0)) return "SEEDED_SUMMARY_ONLY";
    if (!holdingRows.length && visibleTrades.length) return "NO_OPEN_POSITIONS";
    return "DETAILS_SYNCED";
  }, [holdingRows.length, selectedPortfolio, visibleTrades.length, portfolioContract]);
  const sourceStatus = {
    NO_PORTFOLIO: "尚未创建组合",
    SEEDED_SUMMARY_ONLY: "摘要已导入，交易与持仓明细待同步",
    NO_OPEN_POSITIONS: "交易明细已同步，当前没有未平仓仓位",
    DETAILS_SYNCED: "明细已同步",
  }[dataCompleteness];
  const contractSourceStatus = portfolioContract?.sourceStatus || sourceStatus;

  const load = useCallback(async () => {
    const [portfolioList, tradeList] = await Promise.all([
      api("/portfolio/portfolios"),
      api("/portfolio/trades").catch(() => []),
    ]);
    setPortfolios(portfolioList || []);
    setTrades(tradeList || []);
    setForm((current) => ({ ...current, portfolioId: current.portfolioId || portfolioList?.[0]?.id || "" }));
  }, [api]);

  useEffect(() => {
    load().catch((ex) => setMessage(ex.message));
  }, [load]);

  useEffect(() => {
    if (!selectedPortfolioId) {
      setPortfolioContract(null);
      return;
    }
    let cancelled = false;
    Promise.all([
      api(`/portfolio/${selectedPortfolioId}/positions`).catch(() => null),
      api(`/portfolio/${selectedPortfolioId}/trades`).catch(() => null),
    ])
      .then(([positionsContract, tradesContract]) => {
        if (cancelled) return;
        setPortfolioContract({
          ...(positionsContract || {}),
          trades: Array.isArray(tradesContract?.trades) ? tradesContract.trades : undefined,
          tradesContract,
        });
      })
      .catch(() => {
        if (!cancelled) setPortfolioContract(null);
      });
    return () => {
      cancelled = true;
    };
  }, [api, selectedPortfolioId]);

  const patchForm = (patch) => setForm((current) => ({ ...current, ...patch }));

  async function createPortfolio(name) {
    const portfolioName = (name || "").trim() || `Trading Portfolio ${portfolios.length + 1}`;
    setBusy(true);
    setMessage("");
    try {
      const created = await api("/portfolio/portfolios", {
        method: "POST",
        body: JSON.stringify({ name: portfolioName }),
      });
      await load();
      patchForm({ portfolioId: created.id });
      setCreateModalOpen(false);
      setNewPortfolioName("");
      setMessage("已创建组合，可以开始录入交易。");
    } catch (ex) {
      setMessage(ex.message);
    } finally {
      setBusy(false);
    }
  }

  async function _submitManualTrade(event) {
    event.preventDefault();
    setBusy(true);
    setMessage("");
    try {
      const payload = normalizeTradePayload(form, selectedPortfolioId);
      const saved = await api("/portfolio/trades", { method: "POST", body: JSON.stringify(payload) });
      setTrades((current) => [saved, ...current]);
      setForm((current) => ({
        ...emptyTradeForm,
        portfolioId: current.portfolioId || selectedPortfolioId,
        tradeDate: new Date().toISOString().slice(0, 10),
      }));
      setMessage("交易已保存。");
    } catch (ex) {
      setMessage(ex.message);
    } finally {
      setBusy(false);
    }
  }

  async function handleFileImport(event) {
    const file = event.target.files?.[0];
    if (!file) return;
    setBusy(true);
    setMessage("");
    setImportPreview([]);
    try {
      const rows = await readTradeWorkbook(file, selectedPortfolioId);
      if (!rows.length) throw new Error("文件里没有可导入的交易行。");
      const result = await api("/portfolio/trades/import", { method: "POST", body: JSON.stringify({ rows }) });
      const savedRows = Array.isArray(result) ? result : result?.rows || result?.trades || [];
      setImportPreview(rows.slice(0, 5));
      setMessage(`已从 ${file.name} 导入 ${savedRows.length || rows.length} 条交易。`);
      await load();
    } catch (ex) {
      setMessage(ex.message);
    } finally {
      setBusy(false);
      event.target.value = "";
    }
  }

  async function submitCompactTrade(event) {
    event.preventDefault();
    setBusy(true);
    setMessage("");
    try {
      const tradeDate = form.tradeDateTime ? form.tradeDateTime.slice(0, 10) : new Date().toISOString().slice(0, 10);
      const payload = normalizeTradePayload(
        { ...form, tradeDate, commission: form.commission || form.fee || 0 },
        form.portfolioId,
      );
      const saved = await api("/portfolio/trades", { method: "POST", body: JSON.stringify(payload) });
      setTrades((current) => [saved, ...current]);
      setForm((current) => ({ ...emptyTradeForm, portfolioId: current.portfolioId || selectedPortfolioId }));
      setMessage("交易已创建。");
      setTradeDrawerOpen(false);
    } catch (ex) {
      setMessage(ex.message);
    } finally {
      setBusy(false);
    }
  }

  const clearCompactTrade = () =>
    setForm((current) => ({
      ...current,
      tradeDateTime: "",
      symbol: "",
      side: "BUY",
      action: "",
      quantity: "",
      price: "",
      fee: "",
      currency: "USD",
      notes: "",
      strategy: "",
    }));

  function exportAssets() {
    if (!holdingRows.length) {
      setMessage("暂无资产可导出。");
      return;
    }
    const headers = ["symbol", "name", "market", "quantity", "averageCost", "cost", "currency"];
    const lines = [headers.join(",")].concat(
      holdingRows.map((row) => headers.map((key) => csvCell(row[key])).join(",")),
    );
    const blob = new Blob([lines.join("\n")], { type: "text/csv;charset=utf-8" });
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = "portfolio-assets.csv";
    link.click();
    URL.revokeObjectURL(url);
  }

  function exportPortfolios() {
    if (!portfolios.length) {
      setMessage("暂无组合可导出。");
      return;
    }
    const headers = ["id", "name", "nav", "returnPct", "assets", "transactions", "optionCombos", "updatedAt"];
    const lines = [headers.join(",")].concat(
      portfolios.map((portfolio) => headers.map((key) => csvCell(portfolio[key])).join(",")),
    );
    const blob = new Blob([lines.join("\n")], { type: "text/csv;charset=utf-8" });
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = "portfolios.csv";
    link.click();
    URL.revokeObjectURL(url);
  }

  const renderTradeEditor = (onBack) => {
    const createDisabled = busy || !form.symbol.trim() || !form.quantity || !form.price;
    return (
      <div className="bg-white">
        <div className="mb-4 flex items-center justify-between">
          <h2 className="text-[26px] font-bold leading-8 text-black">交易</h2>
          <button
            type="button"
            onClick={onBack}
            className="h-8 rounded-lg border border-gray-200 bg-white px-3 text-sm font-semibold text-black hover:bg-gray-50"
          >
            返回历史
          </button>
        </div>

        <form onSubmit={submitCompactTrade} className="rounded-[14px] border border-gray-200 bg-white p-4">
          <div className="rounded-lg border border-gray-200 bg-white p-3">
            <div className="grid grid-cols-1 gap-2 lg:grid-cols-[1fr_1fr_1fr_1fr_1fr_1fr_1fr]">
              <input
                className="input-field h-10"
                value={form.symbol}
                onChange={(event) => patchForm({ symbol: event.target.value.toUpperCase() })}
                placeholder="代码"
              />
              <select
                className="input-field h-10"
                aria-label="买卖方向"
                value={form.side}
                onChange={(event) => patchForm({ side: event.target.value })}
              >
                <option value="BUY">BUY</option>
                <option value="SELL">SELL</option>
              </select>
              <input
                className="input-field h-10"
                value={form.action}
                onChange={(event) => patchForm({ action: event.target.value })}
                placeholder="动作（可选）"
              />
              <input
                className="input-field h-10"
                value={form.quantity}
                onChange={(event) => patchForm({ quantity: event.target.value })}
                placeholder="数量（如 1 / 100）"
              />
              <input
                className="input-field h-10"
                value={form.price}
                onChange={(event) => patchForm({ price: event.target.value })}
                placeholder="单价（如 12.5）"
              />
              <input
                className="input-field h-10"
                value={form.fee === "0" ? "" : form.fee}
                onChange={(event) => patchForm({ fee: event.target.value, commission: event.target.value })}
                placeholder="手续费（可选，默认 0）"
              />
              <select
                className="input-field h-10 font-semibold text-black"
                aria-label="交易币种"
                value={form.currency}
                onChange={(event) => patchForm({ currency: event.target.value })}
              >
                <option value="USD">USD</option>
                <option value="HKD">HKD</option>
                <option value="CNY">CNY</option>
              </select>
            </div>
            <p className="mt-2 text-xs text-gray-500">
              数量/价格/手续费/币种分别代表仓位规模、成交价、费用与计价币种。手续费可留空，默认 0。
            </p>
            <div className="mt-2 grid grid-cols-1 gap-2 lg:grid-cols-[minmax(0,1fr)_minmax(0,1fr)]">
              <input
                type="datetime-local"
                className="input-field h-10"
                aria-label="交易时间"
                value={form.tradeDateTime}
                onChange={(event) => patchForm({ tradeDateTime: event.target.value })}
              />
              <input
                className="input-field h-10"
                value={form.strategy}
                onChange={(event) => patchForm({ strategy: event.target.value })}
                placeholder="标签（可选）"
              />
            </div>
            <textarea
              className="input-field mt-2 min-h-16 resize-y"
              value={form.notes}
              onChange={(event) => patchForm({ notes: event.target.value })}
              placeholder="备注（可选）：执行备注 / 交易日志..."
            />
            <div className="mt-3 flex justify-end">
              <button
                type="button"
                onClick={clearCompactTrade}
                className="min-h-8 px-3 text-sm font-semibold text-black hover:text-gray-600"
              >
                移除
              </button>
            </div>
          </div>

          <div className="mt-4 max-w-xl">
            <label className="block text-sm text-gray-700">关联组合</label>
            <select
              className="input-field mt-1 h-10 font-semibold text-black"
              aria-label="关联组合"
              value={form.portfolioId}
              onChange={(event) => patchForm({ portfolioId: event.target.value })}
            >
              <option value="">无组合</option>
              {portfolios.map((portfolio) => (
                <option key={portfolio.id} value={portfolio.id}>
                  {portfolio.name}
                </option>
              ))}
            </select>
          </div>

          <div className="mt-4">
            <label className="block text-sm text-gray-700">策略（可选）</label>
            <select
              className="input-field mt-1 h-10 font-semibold text-black"
              aria-label="交易策略"
              value={form.tradeType}
              onChange={(event) => patchForm({ tradeType: event.target.value })}
            >
              <option value="STOCK">无策略</option>
              <option value="CORE">Core</option>
              <option value="SWING">Swing</option>
              <option value="HEDGE">Hedge</option>
            </select>
          </div>

          <h3 className="mt-5 text-base font-semibold text-black">关联投资组合</h3>
          <div className="mt-8 flex items-center justify-between">
            <button
              type="button"
              onClick={() => setMessage("当前页面支持单笔交易录入。")}
              className="h-8 rounded-lg border border-gray-200 bg-white px-3 text-sm font-semibold text-black hover:bg-gray-50"
            >
              新增一行
            </button>
            <button
              type="submit"
              disabled={createDisabled}
              className="h-8 rounded-lg bg-gray-500 px-4 text-sm font-semibold text-white disabled:cursor-not-allowed disabled:opacity-80"
            >
              创建交易
            </button>
          </div>
        </form>

        {message && (
          <div className="mt-4 rounded-lg border border-blue-200 bg-blue-50 px-4 py-3 text-sm text-blue-800">
            {message}
          </div>
        )}
      </div>
    );
  };

  if (path === "/portfolio/trades") {
    const filteredTrades = visibleTrades.filter((trade) => {
      if (tradeSearch) {
        const q = tradeSearch.toLowerCase();
        const matchesSymbol = (trade.symbol || "").toLowerCase().includes(q);
        const matchesName = (trade.securityName || "").toLowerCase().includes(q);
        const matchesStrategy = (trade.strategy || "").toLowerCase().includes(q);
        if (!matchesSymbol && !matchesName && !matchesStrategy) return false;
      }
      if (tradeFilterSide !== "ALL" && trade.side !== tradeFilterSide) return false;
      if (tradeFilterMarket !== "ALL" && (trade.market || "").toUpperCase() !== tradeFilterMarket) return false;
      return true;
    });
    const tradeTotalPages = Math.max(1, Math.ceil(filteredTrades.length / TRADE_PAGE_SIZE));
    const tradeCurrentPage = filteredTrades.slice((tradePage - 1) * TRADE_PAGE_SIZE, tradePage * TRADE_PAGE_SIZE);
    const tradePageBtns = [];
    for (let i = 1; i <= tradeTotalPages; i += 1) {
      if (tradeTotalPages <= 7 || i === 1 || i === tradeTotalPages || Math.abs(i - tradePage) <= 1) {
        tradePageBtns.push(i);
      } else if (tradePageBtns[tradePageBtns.length - 1] !== "...") {
        tradePageBtns.push("...");
      }
    }

    const tradeTotalFees = filteredTrades.reduce((s, t) => s + totalFees(t), 0);
    const tradeNetFlow = filteredTrades.reduce((s, t) => s + Number(t.netAmount || 0), 0);

    return (
      <>
        <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
          <h2 className="text-[26px] font-bold leading-8 text-black">交易流水</h2>
          <div className="flex flex-wrap items-center gap-2">
            <button
              type="button"
              onClick={() => setManualTradeModalOpen(true)}
              className="h-8 rounded-lg bg-blue-600 px-4 text-sm font-semibold text-white hover:bg-blue-700 disabled:opacity-50"
              disabled={busy}
            >
              录入交易
            </button>
            <button
              type="button"
              onClick={() => fileInputRef.current?.click()}
              className="h-8 rounded-lg border border-gray-200 bg-white px-3 text-sm font-semibold text-black hover:bg-gray-50"
            >
              导入 Excel
            </button>
            <button
              type="button"
              onClick={exportAssets}
              className="h-8 rounded-lg border border-gray-200 bg-white px-3 text-sm font-semibold text-black hover:bg-gray-50"
            >
              导出 CSV
            </button>
          </div>
        </div>

        <div className="mb-4 grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-5">
          {[
            { label: "总交易数", value: filteredTrades.length },
            {
              label: "买入/卖出",
              value: `${filteredTrades.filter((t) => t.side === "BUY").length}/${filteredTrades.filter((t) => t.side === "SELL").length}`,
            },
            {
              label: "总成交额",
              value: formatMoney(
                filteredTrades.reduce(
                  (s, t) => s + Number(t.grossAmount || Number(t.quantity || 0) * Number(t.price || 0)),
                  0,
                ),
              ),
            },
            { label: "总费用", value: formatNumber(tradeTotalFees) },
            { label: "净流入", value: formatMoney(tradeNetFlow) },
          ].map((card) => (
            <div key={card.label} className="rounded-lg border border-gray-200 bg-white p-4">
              <div className="text-xs text-gray-500">{card.label}</div>
              <div className="mt-1 text-lg font-bold text-gray-900">{card.value}</div>
            </div>
          ))}
        </div>

        <div className="mb-4 flex flex-wrap items-center gap-3">
          <input
            value={tradeSearch}
            onChange={(e) => {
              setTradeSearch(e.target.value);
              setTradePage(1);
            }}
            placeholder="搜索代码 / 名称 / 策略..."
            className="h-8 w-64 rounded-lg border border-gray-200 px-3 text-sm focus:border-blue-500 focus:outline-none"
          />
          <select
            value={tradeFilterSide}
            onChange={(e) => {
              setTradeFilterSide(e.target.value);
              setTradePage(1);
            }}
            className="h-8 rounded-lg border border-gray-200 px-2 text-sm"
          >
            <option value="ALL">全部方向</option>
            <option value="BUY">买入</option>
            <option value="SELL">卖出</option>
          </select>
          <select
            value={tradeFilterMarket}
            onChange={(e) => {
              setTradeFilterMarket(e.target.value);
              setTradePage(1);
            }}
            className="h-8 rounded-lg border border-gray-200 px-2 text-sm"
          >
            <option value="ALL">全部市场</option>
            <option value="US">美股</option>
            <option value="HK">港股</option>
            <option value="CN">A股</option>
          </select>
        </div>

        {tradeCurrentPage.length === 0 ? (
          <div className="rounded-lg border border-dashed border-gray-300 bg-gray-50 py-14 text-center text-sm text-gray-400">
            暂无交易记录，先导入 Excel 或手动录入一笔交易。
          </div>
        ) : (
          <div className="overflow-auto rounded-lg border border-gray-200">
            <table className="w-full min-w-[980px] divide-y divide-gray-200">
              <thead className="bg-gray-50">
                <tr>
                  {["交易日", "代码", "名称", "方向", "数量", "成交价", "成交额", "费用", "净金额", "策略"].map(
                    (head) => (
                      <th key={head} className="table-header">
                        {head}
                      </th>
                    ),
                  )}
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100 bg-white">
                {tradeCurrentPage.map((trade, idx) => {
                  const globalIdx = (tradePage - 1) * TRADE_PAGE_SIZE + idx;
                  return (
                    <React.Fragment key={trade.tradeId || `${trade.symbol}-${trade.tradeDate}-${idx}`}>
                      <tr
                        className="cursor-pointer hover:bg-blue-50/40"
                        onClick={() => setExpandedTradeIdx(expandedTradeIdx === globalIdx ? -1 : globalIdx)}
                      >
                        <td className="table-cell">{trade.tradeDate}</td>
                        <td className="table-cell font-semibold text-gray-900">{trade.symbol}</td>
                        <td className="table-cell">{trade.securityName || "-"}</td>
                        <td className="table-cell">
                          <span className={trade.side === "SELL" ? "badge-red" : "badge-green"}>
                            {trade.side === "SELL" ? "卖出" : "买入"}
                          </span>
                        </td>
                        <td className="table-cell">{formatNumber(trade.quantity)}</td>
                        <td className="table-cell">{formatNumber(trade.price)}</td>
                        <td className="table-cell">
                          {formatMoney(
                            Number(trade.grossAmount || 0) || Number(trade.quantity || 0) * Number(trade.price || 0),
                            trade.currency || "USD",
                          )}
                        </td>
                        <td className="table-cell">{formatNumber(totalFees(trade))}</td>
                        <td className="table-cell font-medium">
                          {formatMoney(trade.netAmount, trade.currency || "USD")}
                        </td>
                        <td className="table-cell">{trade.strategy || "-"}</td>
                      </tr>
                      {expandedTradeIdx === globalIdx && (
                        <tr>
                          <td colSpan={10} className="bg-gray-50 px-6 py-4">
                            <div className="grid grid-cols-2 gap-x-8 gap-y-2 text-sm sm:grid-cols-3 lg:grid-cols-5">
                              <div>
                                <span className="text-gray-500">结算日：</span>
                                {trade.settlementDate || "-"}
                              </div>
                              <div>
                                <span className="text-gray-500">市场：</span>
                                {trade.market || "-"}
                              </div>
                              <div>
                                <span className="text-gray-500">券商：</span>
                                {trade.broker || "-"}
                              </div>
                              <div>
                                <span className="text-gray-500">币种：</span>
                                {trade.currency || "USD"}
                              </div>
                              <div>
                                <span className="text-gray-500">佣金：</span>
                                {formatNumber(trade.commission)}
                              </div>
                              <div>
                                <span className="text-gray-500">税费：</span>
                                {formatNumber(trade.tax)}
                              </div>
                              <div>
                                <span className="text-gray-500">汇率：</span>
                                {trade.fxRate || "-"}
                              </div>
                              <div>
                                <span className="text-gray-500">来源：</span>
                                {trade.sourceType || "MANUAL"}
                              </div>
                              <div className="col-span-2 sm:col-span-3 lg:col-span-5">
                                <span className="text-gray-500">备注：</span>
                                {trade.notes || "-"}
                              </div>
                            </div>
                          </td>
                        </tr>
                      )}
                    </React.Fragment>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}

        {tradeTotalPages > 1 && (
          <div className="mt-4 flex items-center justify-center gap-1">
            <button
              type="button"
              disabled={tradePage <= 1}
              onClick={() => setTradePage(tradePage - 1)}
              className="h-8 rounded-lg border border-gray-200 bg-white px-3 text-sm text-gray-700 hover:bg-gray-50 disabled:opacity-50"
            >
              上一页
            </button>
            {tradePageBtns.map((p, i) =>
              p === "..." ? (
                <span key={`ellipsis-${i}`} className="px-2 text-sm text-gray-400">
                  ...
                </span>
              ) : (
                <button
                  type="button"
                  key={p}
                  onClick={() => setTradePage(p)}
                  className={`h-8 min-w-[32px] rounded-lg px-2 text-sm ${
                    p === tradePage
                      ? "bg-blue-600 font-semibold text-white"
                      : "border border-gray-200 bg-white text-gray-700 hover:bg-gray-50"
                  }`}
                >
                  {p}
                </button>
              ),
            )}
            <button
              type="button"
              disabled={tradePage >= tradeTotalPages}
              onClick={() => setTradePage(tradePage + 1)}
              className="h-8 rounded-lg border border-gray-200 bg-white px-3 text-sm text-gray-700 hover:bg-gray-50 disabled:opacity-50"
            >
              下一页
            </button>
          </div>
        )}
      </>
    );
  }

  if (path === "/portfolio/portfolios") {
    return (
      <div className="bg-white">
        <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
          <h2 className="text-[26px] font-bold leading-8 text-black">组合列表</h2>
          <div className="flex flex-wrap items-center gap-2">
            <button
              type="button"
              onClick={exportPortfolios}
              className="h-8 rounded-lg border border-gray-200 bg-white px-3 text-sm font-semibold text-black hover:bg-gray-50"
            >
              导出组合
            </button>
            <button
              type="button"
              onClick={() => {
                setNewPortfolioName("");
                setCreateModalOpen(true);
              }}
              className="h-8 rounded-lg bg-neutral-950 px-3 text-sm font-semibold text-white hover:bg-neutral-800"
            >
              新建组合
            </button>
          </div>
        </div>

        {message && (
          <div className="mb-4 rounded-lg border border-blue-200 bg-blue-50 px-4 py-3 text-sm text-blue-800">
            {message}
          </div>
        )}

        {portfolios.length ? (
          <div data-testid="portfolio-list-table" className="overflow-auto rounded-lg border border-gray-200 bg-white">
            <table className="w-full min-w-[980px] divide-y divide-gray-200 text-sm">
              <thead className="bg-gray-50 text-left text-xs font-semibold text-gray-500">
                <tr>
                  {["组合名称", "净资产", "收益率", "资产", "交易", "期权组合", "更新日期", "操作"].map((item) => (
                    <th key={item} className="px-4 py-3">
                      {item}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100 bg-white">
                {portfolios.map((portfolio, index) => {
                  const returnPct = Number(portfolio.returnPct);
                  const returnTone = Number.isFinite(returnPct) && returnPct < 0 ? "text-red-600" : "text-green-600";
                  return (
                    <tr key={portfolio.id || `${portfolio.name}-${index}`} className="hover:bg-gray-50">
                      <td className="px-4 py-3">
                        <p className="font-semibold text-gray-900">{portfolio.name || "未命名组合"}</p>
                        <p className="mt-1 max-w-[220px] truncate font-mono text-xs text-gray-400">
                          {portfolio.id || "-"}
                        </p>
                      </td>
                      <td className="px-4 py-3 font-medium text-gray-900">{formatMoney(portfolio.nav)}</td>
                      <td className={`px-4 py-3 font-semibold ${returnTone}`}>{formatPercent(portfolio.returnPct)}</td>
                      <td className="px-4 py-3 text-gray-700">{formatNumber(portfolio.assets)}</td>
                      <td className="px-4 py-3 text-gray-700">{formatNumber(portfolio.transactions)}</td>
                      <td className="px-4 py-3 text-gray-700">{formatNumber(portfolio.optionCombos)}</td>
                      <td className="px-4 py-3 text-gray-700">{portfolio.updatedAt || "-"}</td>
                      <td className="px-4 py-3">
                        <div className="flex flex-wrap items-center gap-2">
                          <button
                            type="button"
                            onClick={() => {
                              patchForm({ portfolioId: portfolio.id });
                              navigate("/portfolio/assets");
                            }}
                            className="h-8 rounded-lg border border-gray-200 bg-white px-3 text-xs font-semibold text-black hover:bg-gray-50"
                          >
                            查看持仓
                          </button>
                          <button
                            type="button"
                            onClick={() => {
                              patchForm({ portfolioId: portfolio.id });
                              navigate("/portfolio/trades");
                            }}
                            className="h-8 rounded-lg bg-neutral-950 px-3 text-xs font-semibold text-white hover:bg-neutral-800"
                          >
                            记录交易
                          </button>
                        </div>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        ) : (
          <div className="rounded-lg border border-gray-200 bg-white px-6 py-7 text-sm text-gray-600">
            还没有组合，请先创建并开始记录资产与交易。
          </div>
        )}
      </div>
    );
  }

  if (path === "/portfolio/assets") {
    return (
      <div className="bg-white">
        <div>
          <h2 className="text-[26px] font-bold leading-8 text-black">持仓</h2>
          <div className="mt-3 inline-flex h-10 items-center rounded-lg bg-gray-100 p-1">
            <button type="button" className="h-8 rounded-md bg-white px-5 text-sm font-semibold text-black shadow-sm">
              持仓
            </button>
            <button
              type="button"
              onClick={() => navigate("/portfolio/trades")}
              className="h-8 rounded-md px-5 text-sm text-gray-700"
            >
              交易
            </button>
          </div>
        </div>

        <div className="mt-4 flex flex-wrap items-center justify-end gap-2">
          <label className="flex h-10 overflow-hidden rounded-lg border border-gray-200 bg-white text-sm">
            <span className="flex items-center border-r border-gray-200 px-3 text-gray-500">视图</span>
            <select
              value={holdingView}
              aria-label="持仓视图"
              onChange={(event) => setHoldingView(event.target.value)}
              className="bg-white px-3 pr-8 font-semibold text-black outline-none"
            >
              <option value="list">仓位列表</option>
              <option value="group">分组视图</option>
            </select>
          </label>
          <label className="flex h-10 items-center gap-2 rounded-lg border border-gray-200 bg-white px-3 text-sm font-semibold text-black">
            <input
              type="checkbox"
              checked={includeClosedPositions}
              onChange={(event) => setIncludeClosedPositions(event.target.checked)}
              className="h-4 w-4 rounded border-gray-300"
            />
            <span>包含已平仓仓位</span>
          </label>
          <button
            type="button"
            onClick={() => {
              setMessage("");
              setTradeDrawerOpen(true);
            }}
            className="h-10 rounded-lg border border-gray-200 bg-white px-4 text-sm font-semibold text-black hover:bg-gray-50"
          >
            新建交易
          </button>
          <button
            type="button"
            onClick={() => fileInputRef.current?.click()}
            disabled={busy}
            className="h-10 rounded-lg border border-gray-200 bg-white px-4 text-sm font-semibold text-black hover:bg-gray-50 disabled:opacity-50"
          >
            CSV 批量导入
          </button>
          <button
            type="button"
            onClick={exportAssets}
            className="h-10 rounded-lg border border-gray-200 bg-white px-4 text-sm font-semibold text-black hover:bg-gray-50"
          >
            导出资产
          </button>
          <input
            ref={fileInputRef}
            type="file"
            accept=".csv,.xlsx"
            aria-label="导入交易文件"
            onChange={handleFileImport}
            className="sr-only"
          />
        </div>

        {message && (
          <div className="mt-4 rounded-lg border border-blue-200 bg-blue-50 px-4 py-3 text-sm text-blue-800">
            {message}
          </div>
        )}

        {holdingRows.length ? (
          <div className="mt-5 overflow-auto rounded-lg border border-gray-200">
            <table className="w-full min-w-[880px] divide-y divide-gray-200 text-sm">
              <thead className="bg-gray-50 text-left text-xs font-semibold text-gray-500">
                <tr>
                  {["代码", "名称", "市场", "数量", "平均成本", "成本", "币种"].map((item) => (
                    <th key={item} className="px-4 py-3">
                      {item}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100 bg-white">
                {holdingRows.map((row) => (
                  <tr key={row.symbol}>
                    <td className="px-4 py-3 font-semibold text-gray-900">{row.symbol}</td>
                    <td className="px-4 py-3 text-gray-700">{row.name || "-"}</td>
                    <td className="px-4 py-3 text-gray-700">{row.market || "-"}</td>
                    <td className="px-4 py-3 text-gray-700">{formatNumber(row.quantity)}</td>
                    <td className="px-4 py-3 text-gray-700">{formatNumber(row.averageCost)}</td>
                    <td className="px-4 py-3 text-gray-700">{formatMoney(row.cost, row.currency)}</td>
                    <td className="px-4 py-3 text-gray-700">{row.currency}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <div className="mt-5 rounded-lg border border-dashed border-gray-300 bg-gray-50 px-4 py-5 text-sm text-gray-600">
            <p className="font-medium text-gray-900">{contractSourceStatus}</p>
            <p className="mt-1 text-gray-500">
              当前组合：{selectedPortfolio?.name || "未选择组合"}。
              {dataCompleteness === "SEEDED_SUMMARY_ONLY"
                ? "组合列表已有摘要计数，但明细接口尚未同步，暂不展示为空持仓。"
                : "明细同步后会在这里展示持仓、成本和币种。"}
            </p>
          </div>
        )}

        {tradeDrawerOpen && (
          <DrawerShell
            title="新建交易"
            onClose={() => setTradeDrawerOpen(false)}
            widthClass="max-w-4xl"
            topClass="top-16 bottom-0"
          >
            <div className="min-h-0 flex-1 overflow-y-auto p-6">
              {renderTradeEditor(() => setTradeDrawerOpen(false))}
            </div>
          </DrawerShell>
        )}
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h2 className="text-lg font-semibold text-gray-800">投资组合总览</h2>
          <p className="mt-1 text-sm text-gray-500">记录股票成交流水，支持 Excel 批量导入和手动补录。</p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <button
            onClick={() => fileInputRef.current?.click()}
            disabled={busy || !selectedPortfolioId}
            className="btn-primary"
          >
            导入 Excel
          </button>
          <button
            onClick={() => {
              setNewPortfolioName("");
              setCreateModalOpen(true);
            }}
            className="btn-secondary"
          >
            新建组合
          </button>
          <button onClick={() => navigate("/portfolio")} className="btn-secondary">
            刷新总览
          </button>
        </div>
        <input
          ref={fileInputRef}
          type="file"
          accept=".xlsx,.csv"
          aria-label="导入交易文件"
          onChange={handleFileImport}
          className="sr-only"
        />
      </div>

      {message && (
        <div className="rounded-lg border border-blue-200 bg-blue-50 px-4 py-3 text-sm text-blue-800">{message}</div>
      )}

      <div className="grid grid-cols-1 gap-4 md:grid-cols-4">
        <SummaryCard label="交易笔数" value={summary.count} />
        <SummaryCard label="买入 / 卖出" value={`${summary.buys} / ${summary.sells}`} />
        <SummaryCard label="成交金额" value={formatMoney(summary.gross, form.currency || "USD")} />
        <SummaryCard label="净现金流" value={formatMoney(summary.net, form.currency || "USD")} />
      </div>

      <div className="grid grid-cols-1 gap-6 xl:grid-cols-[minmax(0,1fr)_420px]">
        <section className="card min-w-0">
          <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
            <div>
              <h3 className="text-sm font-semibold text-gray-800">交易流水</h3>
              <p className="mt-1 text-xs text-gray-500">后端会按数量、价格和费用重新计算成交金额与净金额。</p>
            </div>
            <select
              className="input-field w-64"
              aria-label="选择组合"
              value={selectedPortfolioId}
              onChange={(event) => patchForm({ portfolioId: event.target.value })}
            >
              <option value="">选择组合</option>
              {portfolios.map((portfolio) => (
                <option key={portfolio.id} value={portfolio.id}>
                  {portfolio.name}
                </option>
              ))}
            </select>
          </div>
          <TradeTable trades={visibleTrades} />
        </section>

        <section className="card">
          <div className="flex items-center justify-between">
            <h3 className="text-sm font-semibold text-gray-800">手动录入交易</h3>
            <button
              type="button"
              onClick={() => setManualTradeModalOpen(true)}
              disabled={!selectedPortfolioId}
              className="rounded-lg bg-neutral-950 px-4 py-2 text-sm font-semibold text-white hover:bg-neutral-800 disabled:cursor-not-allowed disabled:opacity-60"
            >
              录入交易
            </button>
          </div>
          <p className="mt-2 text-xs text-gray-400">手动录入单笔交易，支持美股、港股、A股市场。</p>
        </section>
      </div>

      <section className="card">
        <div className="mb-4 flex items-center justify-between gap-3">
          <h3 className="text-sm font-semibold text-gray-800">Excel 字段需求</h3>
          <span className="text-xs text-gray-400">必填：交易日、股票代码、买卖方向、数量、成交价</span>
        </div>
        <div className="grid grid-cols-1 gap-3 md:grid-cols-3">
          {tradeFieldGroups.map((group) => (
            <div key={group.title} className="rounded-lg border border-gray-200 bg-gray-50 p-4">
              <p className="text-sm font-semibold text-gray-800">{group.title}</p>
              <p className="mt-2 text-sm leading-6 text-gray-500">{group.fields}</p>
            </div>
          ))}
        </div>
        {importPreview.length > 0 && <JsonViewer className="mt-4" value={importPreview} compact />}
      </section>

      {createModalOpen && (
        <ModalShell title="新建组合" onClose={() => setCreateModalOpen(false)}>
          <form
            onSubmit={(e) => {
              e.preventDefault();
              createPortfolio(newPortfolioName);
            }}
            className="p-6"
          >
            <div className="space-y-4">
              <label className="block">
                <span className="mb-1 block text-sm font-medium text-gray-700">组合名称</span>
                <input
                  value={newPortfolioName}
                  onChange={(e) => setNewPortfolioName(e.target.value)}
                  className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none focus:ring-2 focus:ring-blue-500"
                  placeholder="例如：美股核心持仓 / A股量化组合"
                  autoFocus
                />
              </label>
              <p className="text-xs text-gray-400">留空将自动生成默认名称。</p>
            </div>
            <div className="mt-6 flex justify-end gap-3">
              <button
                type="button"
                onClick={() => setCreateModalOpen(false)}
                className="rounded-lg border border-gray-200 px-4 py-2 text-sm text-gray-600 hover:bg-gray-50"
              >
                取消
              </button>
              <button
                type="submit"
                disabled={busy}
                className="rounded-lg bg-neutral-950 px-4 py-2 text-sm font-semibold text-white hover:bg-neutral-800 disabled:cursor-not-allowed disabled:opacity-60"
              >
                {busy ? "创建中..." : "确认创建"}
              </button>
            </div>
          </form>
        </ModalShell>
      )}

      {manualTradeModalOpen && (
        <ModalShell title="录入交易" onClose={() => setManualTradeModalOpen(false)} widthClass="max-w-4xl">
          <div className="min-h-0 flex-1 overflow-y-auto p-6">
            {renderTradeEditor(() => setManualTradeModalOpen(false))}
          </div>
        </ModalShell>
      )}
    </div>
  );
}

function SummaryCard({ label, value }) {
  return (
    <div className="card">
      <p className="text-xs text-gray-500">{label}</p>
      <p className="mt-2 text-xl font-bold text-gray-900">{value}</p>
    </div>
  );
}

function MetricTile({ label, value }) {
  return (
    <div className="rounded-xl border border-gray-200 bg-white px-4 py-3">
      <p className="text-xs font-medium text-gray-500">{label}</p>
      <p className="mt-2 text-2xl font-semibold text-gray-950">{value}</p>
    </div>
  );
}

function _TradeInput({ label, required, children }) {
  return (
    <label className="block">
      <span className="mb-1.5 block text-xs font-medium text-gray-600">
        {label}
        {required && <span className="text-red-500"> *</span>}
      </span>
      {children}
    </label>
  );
}

function TradeTable({ trades }) {
  if (!trades.length)
    return (
      <div className="rounded-lg border border-dashed border-gray-300 bg-gray-50 py-14 text-center text-sm text-gray-400">
        暂无交易记录，先导入 Excel 或手动录入一笔交易。
      </div>
    );
  return (
    <div className="overflow-auto rounded-lg border border-gray-200">
      <table className="w-full min-w-[980px] divide-y divide-gray-200">
        <thead className="bg-gray-50">
          <tr>
            {["交易日", "代码", "名称", "方向", "数量", "成交价", "费用", "净金额", "策略", "来源"].map((head) => (
              <th key={head} className="table-header">
                {head}
              </th>
            ))}
          </tr>
        </thead>
        <tbody className="divide-y divide-gray-100 bg-white">
          {trades.slice(0, 80).map((trade) => (
            <tr key={trade.tradeId || `${trade.symbol}-${trade.tradeDate}-${trade.quantity}`}>
              <td className="table-cell">{trade.tradeDate}</td>
              <td className="table-cell font-semibold text-gray-900">{trade.symbol}</td>
              <td className="table-cell">{trade.securityName || "-"}</td>
              <td className="table-cell">
                <span className={trade.side === "SELL" ? "badge-red" : "badge-green"}>
                  {trade.side === "SELL" ? "卖出" : "买入"}
                </span>
              </td>
              <td className="table-cell">{formatNumber(trade.quantity)}</td>
              <td className="table-cell">{formatNumber(trade.price)}</td>
              <td className="table-cell">{formatNumber(totalFees(trade))}</td>
              <td className="table-cell font-medium">{formatMoney(trade.netAmount, trade.currency || "USD")}</td>
              <td className="table-cell">{trade.strategy || "-"}</td>
              <td className="table-cell">{trade.sourceType || "MANUAL"}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

async function readTradeWorkbook(file, defaultPortfolioId) {
  const fileName = file.name.toLowerCase();
  if (fileName.endsWith(".csv")) {
    const text = await file.text();
    return parseTradeCsv(text)
      .map((row) => normalizeTradePayload(row, defaultPortfolioId, "CSV"))
      .filter((row) => row.symbol && row.tradeDate);
  }
  if (!fileName.endsWith(".xlsx")) {
    throw new Error("当前支持 .xlsx 和 .csv 文件，请先另存为 xlsx 后导入。");
  }
  const { default: readXlsxFile } = await import("read-excel-file/browser");
  const rows = await readXlsxFile(file);
  if (!rows.length) return [];
  const headers = rows[0].map((cell) => String(cell || "").trim());
  const rawRows = rows.slice(1).map((row) =>
    headers.reduce((record, header, index) => {
      if (header) record[header] = row[index] ?? "";
      return record;
    }, {}),
  );
  return rawRows
    .map((row) => normalizeTradePayload(row, defaultPortfolioId, "EXCEL"))
    .filter((row) => row.symbol && row.tradeDate);
}

function parseTradeCsv(text) {
  const rows = parseCsvRows(text).filter((row) => row.some((cell) => String(cell).trim()));
  if (!rows.length) return [];
  const headers = rows[0].map((cell) => String(cell || "").trim());
  return rows.slice(1).map((row) =>
    headers.reduce((record, header, index) => {
      if (header) record[header] = row[index] ?? "";
      return record;
    }, {}),
  );
}

function parseCsvRows(text) {
  const rows = [];
  let row = [];
  let cell = "";
  let inQuotes = false;
  for (let index = 0; index < text.length; index += 1) {
    const char = text[index];
    const next = text[index + 1];
    if (char === '"' && next === '"') {
      cell += '"';
      index += 1;
    } else if (char === '"') {
      inQuotes = !inQuotes;
    } else if (char === "," && !inQuotes) {
      row.push(cell);
      cell = "";
    } else if ((char === "\n" || char === "\r") && !inQuotes) {
      if (char === "\r" && next === "\n") index += 1;
      row.push(cell);
      rows.push(row);
      row = [];
      cell = "";
    } else {
      cell += char;
    }
  }
  row.push(cell);
  rows.push(row);
  return rows;
}

function normalizeTradePayload(row, defaultPortfolioId, sourceType = "MANUAL") {
  const pick = (field) => pickTradeValue(row, field);
  const quantity = positiveNumber(pick("quantity") || row.quantity);
  const price = positiveNumber(pick("price") || row.price);
  const side = normalizeSide(pick("side") || row.side);
  return {
    portfolioId: pick("portfolioId") || row.portfolioId || defaultPortfolioId,
    tradeDate: normalizeTradeDate(pick("tradeDate") || row.tradeDate),
    settlementDate: normalizeTradeDate(pick("settlementDate") || row.settlementDate),
    symbol: String(pick("symbol") || row.symbol || "")
      .trim()
      .toUpperCase(),
    exchange: String(pick("exchange") || row.exchange || "").trim(),
    market: String(pick("market") || row.market || "US")
      .trim()
      .toUpperCase(),
    securityName: String(pick("securityName") || row.securityName || "").trim(),
    side,
    quantity,
    price,
    fee: positiveNumber(pick("fee") || row.fee),
    tax: positiveNumber(pick("tax") || row.tax),
    commission: positiveNumber(pick("commission") || row.commission),
    otherFee: positiveNumber(pick("otherFee") || row.otherFee),
    currency: String(pick("currency") || row.currency || "USD")
      .trim()
      .toUpperCase(),
    fxRate: positiveNumber(pick("fxRate") || row.fxRate || 1) || 1,
    broker: String(pick("broker") || row.broker || "").trim(),
    accountNo: String(pick("accountNo") || row.accountNo || "").trim(),
    strategy: String(pick("strategy") || row.strategy || "").trim(),
    tradeType: String(pick("tradeType") || row.tradeType || "STOCK")
      .trim()
      .toUpperCase(),
    orderType: String(pick("orderType") || row.orderType || "MARKET")
      .trim()
      .toUpperCase(),
    notes: String(pick("notes") || row.notes || "").trim(),
    sourceType,
  };
}

function pickTradeValue(row, field) {
  const aliases = tradeHeaderAliases[field] || [field];
  for (const alias of aliases) {
    if (row[alias] !== undefined && row[alias] !== null && String(row[alias]).trim() !== "") return row[alias];
  }
  const normalized = Object.entries(row).reduce((acc, [key, value]) => {
    acc[String(key).trim().toLowerCase()] = value;
    return acc;
  }, {});
  for (const alias of aliases) {
    const value = normalized[String(alias).trim().toLowerCase()];
    if (value !== undefined && value !== null && String(value).trim() !== "") return value;
  }
  return "";
}

function normalizeSide(value) {
  const text = String(value || "BUY")
    .trim()
    .toUpperCase();
  if (["SELL", "S", "卖", "卖出", "出售"].includes(text)) return "SELL";
  return "BUY";
}

function normalizeTradeDate(value) {
  if (!value) return "";
  if (value instanceof Date && !Number.isNaN(value.getTime())) return value.toISOString().slice(0, 10);
  const text = String(value).trim();
  if (!text) return "";
  const normalized = text.replace(/[./]/g, "-");
  const date = new Date(normalized);
  if (!Number.isNaN(date.getTime())) return date.toISOString().slice(0, 10);
  return normalized.slice(0, 10);
}

function positiveNumber(value) {
  if (value === undefined || value === null || value === "") return 0;
  const parsed = Number(
    String(value)
      .replace(/,/g, "")
      .replace(/[^\d.-]/g, ""),
  );
  return Number.isFinite(parsed) ? Math.abs(parsed) : 0;
}

function totalFees(trade) {
  return Number(trade.fee || 0) + Number(trade.tax || 0) + Number(trade.commission || 0) + Number(trade.otherFee || 0);
}

function formatNumber(value) {
  const number = Number(value || 0);
  return Number.isFinite(number) ? number.toLocaleString("zh-CN", { maximumFractionDigits: 4 }) : "-";
}

function formatMoney(value, currency = "USD") {
  const number = Number(value || 0);
  return `${currency} ${Number.isFinite(number) ? number.toLocaleString("zh-CN", { maximumFractionDigits: 2 }) : "0"}`;
}

function formatPercent(value) {
  if (value === undefined || value === null || value === "") return "-";
  const number = Number(value);
  if (!Number.isFinite(number)) return "-";
  return `${number >= 0 ? "+" : ""}${number.toFixed(2)}%`;
}

function csvCell(value) {
  const text = String(value ?? "");
  return /[",\n\r]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text;
}

function Dashboard({ api }) {
  const [dashboard, setDashboard] = useState(null);
  const [marketSymbol, setMarketSymbol] = useState("AAPL");
  const [marketDataResult, setMarketDataResult] = useState(null);
  const [marketDataLoading, setMarketDataLoading] = useState(false);
  const [marketDataError, setMarketDataError] = useState("");
  useEffect(() => {
    api("/dashboard")
      .then(setDashboard)
      .catch(() => {});
  }, [api]);
  const loadMarketData = useCallback(
    async (symbol = marketSymbol) => {
      const normalized = String(symbol || "")
        .trim()
        .toUpperCase();
      if (!normalized) {
        setMarketDataError("请输入股票代码");
        return;
      }
      setMarketDataLoading(true);
      setMarketDataError("");
      try {
        const response = await api(`/market-data/overview?symbol=${encodeURIComponent(normalized)}`);
        setMarketDataResult(response);
        setMarketSymbol(normalized);
      } catch (ex) {
        setMarketDataError(ex.message || "实时数据查询失败");
      } finally {
        setMarketDataLoading(false);
      }
    },
    [api, marketSymbol],
  );
  const markets = dashboard?.markets?.length
    ? dashboard.markets
    : [
        { symbol: "SPY", name: "S&P 500", changePct: 0.77 },
        { symbol: "QQQ", name: "Nasdaq 100", changePct: 1.91 },
        { symbol: "FXI", name: "China Large Cap", changePct: 1.01 },
      ];
  const macroCards = [
    { label: "GDP 增速", value: "5.2%", change: "+0.3%", status: "up" },
    { label: "CPI 同比", value: "2.1%", change: "-0.1%", status: "down" },
    { label: "PMI", value: "50.8", change: "+0.4", status: "up" },
    { label: "社融规模", value: "3.45万亿", change: "+0.52万亿", status: "up" },
  ];
  const quote = marketDataResult?.quote;
  const financials = marketDataResult?.financials;
  const news = marketDataResult?.news;
  const metrics = Array.isArray(financials?.metrics) ? financials.metrics.slice(0, 5) : [];
  const articles = Array.isArray(news?.articles) ? news.articles.slice(0, 5) : [];
  const [, setAnalysisResult] = useState(null);
  const [, setAnalysisLoading] = useState(false);
  const [, setAnalysisError] = useState("");
  const [, setShowDataRequest] = useState(false);
  const [, setMissingFields] = useState([]);
  const [extraInfo] = useState({ sector: "", industry: "", timeframe: "", notes: "" });

  const REQUIRED_ANALYSIS_FIELDS = [
    { key: "price", label: "\u6700\u65b0\u4ef7\u683c" },
    { key: "changePct", label: "\u6da8\u8dcc\u5e45" },
    { key: "marketCap", label: "\u603b\u5e02\u503c" },
    { key: "pe", label: "\u5e02\u76c8\u7387 PE" },
    { key: "pb", label: "\u5e02\u51c0\u7387 PB" },
    { key: "dividendYield", label: "\u80a1\u606f\u7387" },
    { key: "week52High", label: "52\u5468\u6700\u9ad8" },
    { key: "week52Low", label: "52\u5468\u6700\u4f4e" },
  ];

  function resolveAnalysisFieldValue(key) {
    const q = quote || {};
    const f = financials || {};
    const pick = (v) => (v === undefined || v === null || v === "" ? "" : String(v));
    switch (key) {
      case "price":
        return pick(q.price);
      case "changePct":
        return pick(q.changePct);
      case "marketCap":
        return pick(q.marketCap ?? f.marketCap);
      case "pe":
        return pick(q.pe ?? f.pe);
      case "pb":
        return pick(q.pb ?? f.pb);
      case "dividendYield":
        return pick(q.dividendYield ?? f.dividendYield);
      case "week52High":
        return pick(q.week52High ?? f.week52High);
      case "week52Low":
        return pick(q.week52Low ?? f.week52Low);
      default:
        return "";
    }
  }

  const _openDataRequest = useCallback(() => {
    const missing = REQUIRED_ANALYSIS_FIELDS.filter((field) => !resolveAnalysisFieldValue(field.key));
    setMissingFields(missing);
    setShowDataRequest(true);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [quote, financials]);

  const _generateDeepAnalysis = useCallback(async () => {
    const missing = REQUIRED_ANALYSIS_FIELDS.filter((field) => !resolveAnalysisFieldValue(field.key));
    if (missing.length) {
      setMissingFields(missing);
      setShowDataRequest(true);
      return;
    }
    const payload = [
      `\u80a1\u7968\u4ee3\u7801: ${marketSymbol}`,
      quote?.name ? `\u540d\u79f0: ${quote.name}` : "",
      resolveAnalysisFieldValue("price") ? `\u6700\u65b0\u4ef7\u683c: ${resolveAnalysisFieldValue("price")}` : "",
      resolveAnalysisFieldValue("changePct") ? `\u6da8\u8dcc\u5e45: ${resolveAnalysisFieldValue("changePct")}%` : "",
      resolveAnalysisFieldValue("marketCap") ? `\u603b\u5e02\u503c: ${resolveAnalysisFieldValue("marketCap")}` : "",
      resolveAnalysisFieldValue("pe") ? `PE(TTM): ${resolveAnalysisFieldValue("pe")}` : "",
      resolveAnalysisFieldValue("pb") ? `PB: ${resolveAnalysisFieldValue("pb")}` : "",
      resolveAnalysisFieldValue("dividendYield")
        ? `\u80a1\u606f\u7387: ${resolveAnalysisFieldValue("dividendYield")}`
        : "",
      resolveAnalysisFieldValue("week52High") ? `52\u5468\u6700\u9ad8: ${resolveAnalysisFieldValue("week52High")}` : "",
      resolveAnalysisFieldValue("week52Low") ? `52\u5468\u6700\u4f4e: ${resolveAnalysisFieldValue("week52Low")}` : "",
      extraInfo.sector ? `\u884c\u4e1a: ${extraInfo.sector}` : "",
      extraInfo.industry ? `\u7ec6\u5206\u884c\u4e1a: ${extraInfo.industry}` : "",
      extraInfo.timeframe ? `\u5173\u6ce8\u5468\u671f: ${extraInfo.timeframe}` : "",
      extraInfo.notes ? `\u8865\u5145\u8bf4\u660e: ${extraInfo.notes}` : "",
    ]
      .filter(Boolean)
      .join("\n");

    setAnalysisLoading(true);
    setAnalysisError("");
    try {
      const result = await api("/chat/messages", {
        method: "POST",
        body: JSON.stringify({
          message: payload,
          workflowKey: "deep_dive",
          state: { ticker: marketSymbol, symbol: marketSymbol, sector: extraInfo.sector, industry: extraInfo.industry },
        }),
      });
      let content = result?.content || result?.message || "\u672a\u8fd4\u56de\u5206\u6790\u5185\u5bb9";
      if (result?.routedToWorkflow && result?.runId) {
        try {
          const run = await api(`/workflow/runs/${result.runId}`);
          const runResult = run?.resultJson ? JSON.parse(run.resultJson) : null;
          if (runResult?.recommendation?.summary) {
            content = runResult.recommendation.summary;
          } else if (runResult?.recommendation?.content) {
            content = runResult.recommendation.content;
          }
        } catch (fetchErr) {
          // eslint-disable-next-line no-console
          console.warn("Failed to fetch workflow run result:", fetchErr);
        }
      }
      setAnalysisResult(content);
    } catch (ex) {
      setAnalysisError(ex.message || "\u751f\u6210\u6df1\u5ea6\u5206\u6790\u5931\u8d25");
    } finally {
      setAnalysisLoading(false);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [api, marketSymbol, quote, financials, extraInfo]);

  return (
    <div>
      <h2 className="mb-6 text-lg font-semibold text-gray-800">数据中心仪表盘</h2>
      <div className="mb-6 grid grid-cols-1 gap-4 lg:grid-cols-2">
        <div className="card">
          <h3 className="mb-3 text-sm font-medium text-gray-700">宏观象限</h3>
          <div className="rounded-lg border border-gray-200 bg-gray-50 p-4">
            <p className="text-sm font-semibold text-gray-900">数据接入中</p>
            <p className="mt-2 text-sm leading-6 text-gray-500">
              将展示增长、通胀、流动性和风险偏好的最新分区。接口上线前不展示模拟图形。
            </p>
          </div>
        </div>
        <div className="card">
          <h3 className="mb-3 text-sm font-medium text-gray-700">信用脉冲指数</h3>
          <div className="rounded-lg border border-gray-200 bg-gray-50 p-4">
            <p className="text-sm font-semibold text-gray-900">数据接入中</p>
            <p className="mt-2 text-sm leading-6 text-gray-500">等待后端返回信用扩张、社融和期限利差序列后展示趋势。</p>
          </div>
        </div>
      </div>
      <div className="mb-6 grid grid-cols-2 gap-4 md:grid-cols-4">
        {macroCards.map((card) => (
          <div key={card.label} className="card">
            <p className="mb-1 text-xs text-gray-500">{card.label}</p>
            <p className="text-xl font-bold text-gray-800">{card.value}</p>
            <p className={`mt-1 text-xs ${card.status === "up" ? "text-green-600" : "text-red-600"}`}>{card.change}</p>
          </div>
        ))}
      </div>
      <div className="card mb-6">
        <h3 className="mb-3 text-sm font-medium text-gray-700">全球市场快照</h3>
        <div className="grid grid-cols-2 gap-4 md:grid-cols-3 lg:grid-cols-6">
          {markets.slice(0, 6).map((m) => (
            <div key={m.symbol} className="rounded-lg bg-gray-50 p-3 text-center">
              <p className="mb-1 text-xs text-gray-500">{m.symbol}</p>
              <p className="text-sm font-semibold text-gray-800">{m.name}</p>
              <p className={`text-xs ${Number(m.changePct) >= 0 ? "text-green-600" : "text-red-600"}`}>
                {Number(m.changePct).toFixed(2)}%
              </p>
            </div>
          ))}
        </div>
      </div>
      <div className="card" data-testid="market-data-query-panel">
        <div className="flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
          <div>
            <h3 className="text-sm font-medium text-gray-700">个股研究</h3>
            <p className="mt-1 text-xs text-gray-500">行情、SEC 财报和近期新闻实时查询</p>
          </div>
          <form
            className="flex w-full gap-2 lg:w-[360px]"
            onSubmit={(event) => {
              event.preventDefault();
              loadMarketData();
            }}
          >
            <input
              type="text"
              className="input-field flex-1"
              value={marketSymbol}
              onChange={(event) => setMarketSymbol(event.target.value)}
              placeholder="AAPL / MSFT / NVDA"
            />
            <button className="btn-primary text-sm" type="submit" disabled={marketDataLoading}>
              {marketDataLoading ? "查询中" : "查询"}
            </button>
          </form>
        </div>
        {marketDataError && (
          <div className="mt-3 rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
            {marketDataError}
          </div>
        )}
        <div className="mt-4 grid grid-cols-1 gap-4 xl:grid-cols-3">
          <div className="rounded-lg border border-gray-200 bg-white p-4" data-testid="market-data-quote-card">
            <div className="flex items-start justify-between gap-3">
              <div>
                <p className="text-xs text-gray-500">实时行情</p>
                <p className="mt-1 text-lg font-semibold text-gray-900">{quote?.symbol || marketSymbol || "-"}</p>
              </div>
              <span className="rounded-full bg-gray-100 px-2 py-1 text-xs text-gray-600">
                {quote?.provider || "未查询"}
              </span>
            </div>
            <p className="mt-4 text-2xl font-semibold text-gray-950">
              {quote?.price !== undefined ? formatNumber(quote.price) : "-"}
            </p>
            <p className={`mt-1 text-sm ${Number(quote?.changePct || 0) >= 0 ? "text-green-600" : "text-red-600"}`}>
              {quote?.changePct !== undefined ? formatPercent(quote.changePct) : "-"}
            </p>
            <p className="mt-3 text-xs text-gray-500">
              更新时间：{formatMarketTimestamp(quote?.asOf || quote?.retrievedAt)}
            </p>
            <p className="mt-1 text-xs text-gray-400">
              {quote?.delayHint || (quote?.provider ? "延迟由数据源决定" : "等待查询")}
            </p>
          </div>
          <div className="rounded-lg border border-gray-200 bg-white p-4" data-testid="market-data-financials-list">
            <div className="flex items-start justify-between gap-3">
              <div>
                <p className="text-xs text-gray-500">财报数据</p>
                <p className="mt-1 text-sm font-semibold text-gray-900">
                  {financials?.companyName || financials?.symbol || "-"}
                </p>
              </div>
              <span className="rounded-full bg-gray-100 px-2 py-1 text-xs text-gray-600">
                {financials?.provider || "未查询"}
              </span>
            </div>
            <div className="mt-3 space-y-2">
              {metrics.length ? (
                metrics.map((metric) => (
                  <div
                    key={`${metric.metric}-${metric.filed || metric.end || metric.unit}`}
                    className="flex items-center justify-between gap-3 text-sm"
                  >
                    <span className="truncate text-gray-600">{sanitizeText(metric.label || metric.metric)}</span>
                    <span className="shrink-0 font-medium text-gray-900">
                      {formatNumber(metric.value)} {metric.unit || ""}
                    </span>
                  </div>
                ))
              ) : (
                <p className="text-sm text-gray-400">等待查询</p>
              )}
            </div>
          </div>
          <div className="rounded-lg border border-gray-200 bg-white p-4" data-testid="market-data-news-list">
            <div className="flex items-start justify-between gap-3">
              <div>
                <p className="text-xs text-gray-500">近期新闻</p>
                <p className="mt-1 text-sm font-semibold text-gray-900">
                  {articles.length ? `${articles.length} 条` : "-"}
                </p>
              </div>
              <span className="rounded-full bg-gray-100 px-2 py-1 text-xs text-gray-600">
                {news?.provider || "未查询"}
              </span>
            </div>
            <div className="mt-3 space-y-3">
              {articles.length ? (
                articles.map((article, index) => (
                  <a
                    key={`${article.url || article.title}-${index}`}
                    className="block rounded-lg border border-gray-100 px-3 py-2 hover:bg-gray-50"
                    href={article.url || "#"}
                    target="_blank"
                    rel="noreferrer"
                  >
                    <p className="line-clamp-2 text-sm font-medium text-gray-800">
                      {sanitizeText(article.title) || "-"}
                    </p>
                    <p className="mt-1 text-xs text-gray-500">
                      {sanitizeText(article.source || article.provider || "news")} ·{" "}
                      {formatMarketTimestamp(article.publishedAt || news?.asOf || news?.retrievedAt)}
                    </p>
                  </a>
                ))
              ) : (
                <p className="text-sm text-gray-400">等待查询</p>
              )}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}

function WorkflowRunCenterPage({ api }) {
  const [runs, setRuns] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [actionBusy, setActionBusy] = useState("");
  const [detail, setDetail] = useState({ open: false, row: null, nodes: [], loading: false, error: "" });
  const [statusFilter, setStatusFilter] = useState(() => readSearchParam("status", "ALL").toUpperCase());
  const [traceSearch, setTraceSearch] = useState(() => readSearchParam("traceId", ""));

  const loadRuns = useCallback(async () => {
    setLoading(true);
    setError("");
    try {
      const params = new URLSearchParams();
      if (statusFilter && statusFilter !== "ALL") params.set("status", statusFilter);
      if (traceSearch.trim()) params.set("traceId", traceSearch.trim());
      const response = await api(`/workflow/runs${params.toString() ? `?${params.toString()}` : ""}`);
      setRuns(normalizeListResponse(response));
    } catch (ex) {
      setError(ex.message || "加载运行中心失败");
    } finally {
      setLoading(false);
    }
  }, [api, statusFilter, traceSearch]);

  useEffect(() => {
    loadRuns();
  }, [loadRuns]);

  const openTrace = async (run) => {
    const runId = pickFirst(run, ["runId", "run_id", "id"]);
    setDetail({
      open: true,
      row: run,
      nodes: [],
      loading: Boolean(runId),
      error: runId ? "" : "该运行记录缺少 runId，无法查询节点详情。",
    });
    if (!runId) return;
    try {
      const response = await api(`/workflow/runs/${runId}/nodes`);
      setDetail({ open: true, row: run, nodes: normalizeListResponse(response), loading: false, error: "" });
    } catch (ex) {
      setDetail({ open: true, row: run, nodes: [], loading: false, error: ex.message || "加载节点详情失败" });
    }
  };

  const runAction = async (run, action) => {
    const runId = pickFirst(run, ["runId", "run_id", "id"]);
    if (!runId) return;
    setActionBusy(`${action}:${runId}`);
    setError("");
    try {
      await api(`/workflow/runs/${runId}/${action}`, { method: "POST" });
      await loadRuns();
    } catch (ex) {
      setError(ex.message || `Workflow run ${action} failed`);
    } finally {
      setActionBusy("");
    }
  };

  const statusCounts = useMemo(
    () =>
      runs.reduce((acc, run) => {
        const status = String(
          pickFirst(run, ["status", "state", "runStatus", "run_status"]) || "UNKNOWN",
        ).toUpperCase();
        acc[status] = (acc[status] || 0) + 1;
        return acc;
      }, {}),
    [runs],
  );
  const filteredRuns = useMemo(
    () =>
      runs.filter((run) => {
        const status = String(
          pickFirst(run, ["status", "state", "runStatus", "run_status"]) || "UNKNOWN",
        ).toUpperCase();
        const traceId = displayValue(pickFirst(run, ["traceId", "trace_id", "runId", "run_id", "id"])).toLowerCase();
        const matchesStatus = statusFilter === "ALL" || status === statusFilter;
        const matchesTrace = !traceSearch.trim() || traceId.includes(traceSearch.trim().toLowerCase());
        return matchesStatus && matchesTrace;
      }),
    [runs, statusFilter, traceSearch],
  );
  const updateStatusFilter = (nextStatus) => {
    setStatusFilter(nextStatus);
    if (typeof window === "undefined") return;
    const params = new URLSearchParams(window.location.search);
    if (nextStatus === "ALL") params.delete("status");
    else params.set("status", nextStatus);
    const nextUrl = `${window.location.pathname}${params.toString() ? `?${params.toString()}` : ""}`;
    window.history.replaceState({}, "", nextUrl);
  };
  const renderRunActions = (run) => {
    const runId = displayValue(pickFirst(run, ["runId", "run_id", "id"]));
    const status = displayValue(pickFirst(run, ["status", "state", "runStatus", "run_status"])).toUpperCase();
    const availableActions =
      Array.isArray(run.availableActions) && run.availableActions.length
        ? run.availableActions
        : status === "QUEUED"
          ? ["dispatch", "cancel"]
          : status === "RUNNING"
            ? ["pause", "cancel"]
            : status === "PAUSED"
              ? ["resume", "cancel"]
              : [];
    const actionReasons = run.actionReasons || {
      dispatch: status === "QUEUED" ? "" : "仅队列中的运行可以派发",
      pause: ["QUEUED", "RUNNING"].includes(status) ? "" : "仅队列中或运行中的任务可以暂停",
      resume: status === "PAUSED" ? "" : "仅暂停中的任务可以恢复",
      cancel: ["QUEUED", "PAUSED", "RUNNING"].includes(status) ? "" : "已完成或已失败的任务不能取消",
    };
    const labels = { dispatch: "派发", pause: "暂停", resume: "恢复", cancel: "取消" };
    const busyPrefix = actionBusy.endsWith(`:${runId}`) ? actionBusy.split(":")[0] : "";
    if (!availableActions.length) {
      return (
        <span className="text-xs text-gray-500" title={actionReasons.cancel || "当前状态没有可执行操作"}>
          无可执行操作
        </span>
      );
    }
    return (
      <div className="flex flex-wrap items-center gap-1.5">
        {availableActions.slice(0, 2).map((action) => (
          <button
            key={action}
            type="button"
            data-testid={`workflow-run-${action}`}
            onClick={() => runAction(run, action)}
            disabled={Boolean(busyPrefix)}
            title={actionReasons[action] || labels[action] || action}
            className={`min-h-8 rounded-lg border px-2.5 py-1.5 text-xs font-semibold hover:bg-gray-50 disabled:cursor-not-allowed disabled:opacity-40 ${action === "cancel" ? "border-red-200 text-red-700 hover:bg-red-50" : "border-gray-200 text-gray-800"}`}
          >
            {busyPrefix === action ? "..." : labels[action] || action}
          </button>
        ))}
      </div>
    );
  };

  return (
    <div className="space-y-4">
      <div className="flex flex-col gap-3 lg:flex-row lg:items-start lg:justify-between">
        <div>
          <h2 className="text-xl font-semibold text-gray-900">运行中心</h2>
          <p className="mt-1 text-sm text-gray-500">集中查看工作流执行状态、traceId、节点数量与运行详情。</p>
        </div>
        <button
          type="button"
          onClick={loadRuns}
          className="h-9 rounded-lg border border-gray-200 bg-white px-3 text-sm font-semibold text-gray-800 hover:bg-gray-50"
        >
          刷新
        </button>
      </div>

      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        <MetricTile label="总运行数" value={runs.length} />
        <MetricTile label="队列中" value={statusCounts.QUEUED || 0} />
        <MetricTile label="运行中" value={statusCounts.RUNNING || 0} />
        <MetricTile
          label="需处理"
          value={(statusCounts.PAUSED || 0) + (statusCounts.ERROR || 0) + (statusCounts.FAILED || 0)}
        />
      </div>

      <div className="flex flex-col gap-3 rounded-xl border border-gray-200 bg-white p-3 sm:flex-row sm:items-center">
        <label className="flex flex-1 items-center gap-2 text-sm text-gray-600">
          <span className="shrink-0 font-medium">状态</span>
          <select
            className="input-field h-10"
            value={statusFilter}
            onChange={(event) => updateStatusFilter(event.target.value)}
          >
            {["ALL", "QUEUED", "RUNNING", "PAUSED", "COMPLETED", "FAILED", "CANCELLED"].map((status) => (
              <option key={status} value={status}>
                {status === "ALL" ? "全部" : status}
              </option>
            ))}
          </select>
        </label>
        <label className="flex flex-[2] items-center gap-2 text-sm text-gray-600">
          <span className="shrink-0 font-medium">traceId</span>
          <input
            className="input-field h-10"
            value={traceSearch}
            onChange={(event) => setTraceSearch(event.target.value)}
            placeholder="搜索 traceId / runId"
          />
        </label>
      </div>

      <div data-testid="workflow-run-card-list" className="space-y-3 lg:hidden">
        {loading && (
          <div className="rounded-xl border border-gray-200 bg-white p-5 text-center text-sm text-gray-400">
            正在加载运行记录...
          </div>
        )}
        {!loading && error && (
          <div className="rounded-xl border border-red-200 bg-red-50 p-5 text-center text-sm text-red-600">{error}</div>
        )}
        {!loading && !error && !filteredRuns.length && (
          <div className="rounded-xl border border-gray-200 bg-white p-5 text-center text-sm text-gray-400">
            暂无匹配运行记录
          </div>
        )}
        {!loading &&
          !error &&
          filteredRuns.map((run, index) => {
            const runId = displayValue(pickFirst(run, ["runId", "run_id", "id"]));
            const traceId = displayValue(pickFirst(run, ["traceId", "trace_id"]));
            const subject = displayValue(pickFirst(run, ["subject", "topic", "name"]));
            return (
              <div key={runId || index} className="rounded-xl border border-gray-200 bg-white p-4">
                <div className="flex items-start justify-between gap-3">
                  <div className="min-w-0">
                    <p className="truncate font-semibold text-gray-900">
                      {displayValue(pickFirst(run, ["workflowName", "workflow_name", "workflowKey", "workflow_key"])) ||
                        "-"}
                    </p>
                    <p className="mt-1 truncate text-sm text-gray-500">{subject || "-"}</p>
                  </div>
                  <StatusPill status={pickFirst(run, ["status", "state", "runStatus", "run_status"])} />
                </div>
                <div className="mt-3 grid grid-cols-2 gap-2 text-xs text-gray-500">
                  <RunMeta
                    label="开始"
                    value={formatDateTime(pickFirst(run, ["startedAt", "started_at", "createdAt", "created_at"]))}
                  />
                  <RunMeta
                    label="节点"
                    value={
                      displayValue(pickFirst(run, ["nodeCount", "node_count", "nodes", "totalNodes", "total_nodes"])) ||
                      "-"
                    }
                  />
                  <RunMeta label="traceId" value={traceId || "-"} mono />
                  <RunMeta label="runId" value={runId || "-"} mono />
                </div>
                <div className="mt-4 flex flex-wrap items-center justify-between gap-2">
                  <button
                    type="button"
                    onClick={() => openTrace(run)}
                    className="min-h-8 rounded-lg border border-gray-200 px-3 py-1.5 text-xs font-semibold text-gray-800 hover:bg-gray-50"
                  >
                    详情
                  </button>
                  {renderRunActions(run)}
                </div>
              </div>
            );
          })}
      </div>

      <div className="overflow-hidden rounded-xl border border-gray-200 bg-white">
        <div className="hidden lg:block">
          <table data-testid="workflow-run-center-table" className="w-full min-w-[920px] divide-y divide-gray-200">
            <thead className="bg-gray-50">
              <tr>
                {["开始时间", "工作流", "主题", "状态", "节点数", "最近事件", "操作"].map((head) => (
                  <th key={head} className="table-header">
                    {head}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100 bg-white">
              {loading && (
                <tr>
                  <td colSpan={7} className="px-4 py-10 text-center text-sm text-gray-400">
                    正在加载运行记录...
                  </td>
                </tr>
              )}
              {!loading && error && (
                <tr>
                  <td colSpan={7} className="px-4 py-10 text-center text-sm text-red-600">
                    {error}
                  </td>
                </tr>
              )}
              {!loading && !error && !filteredRuns.length && (
                <tr>
                  <td colSpan={7} className="px-4 py-10 text-center text-sm text-gray-400">
                    暂无匹配运行记录
                  </td>
                </tr>
              )}
              {!loading &&
                !error &&
                filteredRuns.map((run, index) => {
                  const runId = displayValue(pickFirst(run, ["runId", "run_id", "id"]));
                  const traceId = displayValue(pickFirst(run, ["traceId", "trace_id"]));
                  const status = displayValue(pickFirst(run, ["status", "state", "runStatus", "run_status"]));
                  const lastEvent =
                    displayValue(
                      pickFirst(run, [
                        "lastEvent",
                        "last_event",
                        "errorMessage",
                        "error_message",
                        "controlStatus",
                        "control_status",
                      ]),
                    ) ||
                    traceId ||
                    runId ||
                    "-";
                  return (
                    <tr key={runId || index} className="hover:bg-gray-50">
                      <td className="table-cell whitespace-nowrap">
                        {formatDateTime(pickFirst(run, ["startedAt", "started_at", "createdAt", "created_at"]))}
                      </td>
                      <td className="table-cell font-medium text-gray-900">
                        {displayValue(
                          pickFirst(run, ["workflowName", "workflow_name", "workflowKey", "workflow_key"]),
                        ) || "-"}
                      </td>
                      <td
                        className="table-cell max-w-[220px] truncate"
                        title={displayValue(pickFirst(run, ["subject", "topic", "name"]))}
                      >
                        {displayValue(pickFirst(run, ["subject", "topic", "name"])) || "-"}
                      </td>
                      <td className="table-cell">
                        <StatusPill status={status} />
                      </td>
                      <td className="table-cell">
                        {displayValue(
                          pickFirst(run, ["nodeCount", "node_count", "nodes", "totalNodes", "total_nodes"]),
                        ) || "-"}
                      </td>
                      <td className="table-cell max-w-[260px] truncate text-xs text-gray-500" title={lastEvent}>
                        {lastEvent}
                      </td>
                      <td className="table-cell">
                        <div className="flex items-center gap-2">
                          <button
                            type="button"
                            onClick={() => openTrace(run)}
                            className="min-h-8 rounded-lg border border-gray-200 px-2.5 py-1.5 text-xs font-semibold text-gray-800 hover:bg-gray-50"
                          >
                            详情
                          </button>
                          {renderRunActions(run)}
                        </div>
                      </td>
                    </tr>
                  );
                })}
            </tbody>
          </table>
        </div>
      </div>

      {detail.open && (
        <BacktestTracePanel
          detail={detail}
          onClose={() => setDetail({ open: false, row: null, nodes: [], loading: false, error: "" })}
        />
      )}
    </div>
  );
}

function AuditLogPage({ api }) {
  const [events, setEvents] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [pendingBackend, setPendingBackend] = useState(false);

  const loadEvents = useCallback(async () => {
    setLoading(true);
    setError("");
    setPendingBackend(false);
    try {
      const response = await api("/admin/audit-events");
      setEvents(normalizeListResponse(response));
    } catch (ex) {
      const message = ex.message || "加载审计日志失败";
      if (message.includes("404")) {
        setPendingBackend(true);
        setEvents([]);
      } else {
        setError(message);
      }
    } finally {
      setLoading(false);
    }
  }, [api]);

  useEffect(() => {
    loadEvents();
  }, [loadEvents]);

  return (
    <div className="space-y-4">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <h2 className="text-xl font-semibold text-gray-900">审计日志</h2>
          <p className="mt-1 text-sm text-gray-500">
            查看用户、工作流、Agent 与系统治理事件，支持后端审计接口灰度接入。
          </p>
        </div>
        <button
          type="button"
          onClick={loadEvents}
          className="h-9 rounded-lg border border-gray-200 bg-white px-3 text-sm font-semibold text-gray-800 hover:bg-gray-50"
        >
          刷新
        </button>
      </div>

      {pendingBackend && (
        <div className="rounded-xl border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-800">
          后端审计接口暂未启用（404）。页面已就绪，接口上线后会自动展示最新事件。
        </div>
      )}

      <div className="overflow-hidden rounded-xl border border-gray-200 bg-white">
        <div className="overflow-auto">
          <table data-testid="audit-event-table" className="w-full min-w-[1120px] divide-y divide-gray-200">
            <thead className="bg-gray-50">
              <tr>
                {["时间", "动作", "操作者", "资源类型", "资源 ID", "traceId", "requestId", "结果"].map((head) => (
                  <th key={head} className="table-header">
                    {head}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100 bg-white">
              {loading && (
                <tr>
                  <td colSpan={8} className="px-4 py-10 text-center text-sm text-gray-400">
                    正在加载审计日志...
                  </td>
                </tr>
              )}
              {!loading && error && (
                <tr>
                  <td colSpan={8} className="px-4 py-10 text-center text-sm text-red-600">
                    {error}
                  </td>
                </tr>
              )}
              {!loading && !error && !events.length && (
                <tr>
                  <td colSpan={8} className="px-4 py-10 text-center text-sm text-gray-400">
                    {pendingBackend ? "等待后端审计事件接口上线" : "暂无审计事件"}
                  </td>
                </tr>
              )}
              {!loading &&
                !error &&
                events.map((event, index) => {
                  const action = displayValue(pickFirst(event, ["action", "eventAction", "event_action", "type"]));
                  const actor = displayValue(
                    pickFirst(event, ["username", "actor", "actorUsername", "actor_username", "userId", "user_id"]),
                  );
                  const traceId = displayValue(pickFirst(event, ["traceId", "trace_id"]));
                  return (
                    <tr key={pickFirst(event, ["eventId", "event_id", "id"]) || index} className="hover:bg-gray-50">
                      <td className="table-cell whitespace-nowrap">
                        {formatDateTime(pickFirst(event, ["createdAt", "created_at", "timestamp"]))}
                      </td>
                      <td className="table-cell font-medium text-gray-900">{action || "-"}</td>
                      <td className="table-cell">{actor || "-"}</td>
                      <td className="table-cell">
                        {displayValue(pickFirst(event, ["resourceType", "resource_type"])) || "-"}
                      </td>
                      <td className="table-cell max-w-[220px] truncate font-mono text-xs">
                        {displayValue(pickFirst(event, ["resourceId", "resource_id"])) || "-"}
                      </td>
                      <td className="table-cell max-w-[180px] truncate font-mono text-xs" title={traceId}>
                        {traceId || "-"}
                      </td>
                      <td className="table-cell max-w-[180px] truncate font-mono text-xs">
                        {displayValue(pickFirst(event, ["requestId", "request_id"])) || "-"}
                      </td>
                      <td className="table-cell">
                        <StatusPill status={pickFirst(event, ["status", "result", "outcome"]) || "RECORDED"} />
                      </td>
                    </tr>
                  );
                })}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
}

function ModelGovernancePage({ api }) {
  const [models, setModels] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  const loadModels = useCallback(async () => {
    setLoading(true);
    setError("");
    try {
      const response = await api("/governance/models");
      setModels(normalizeListResponse(response));
    } catch (ex) {
      setError(ex.message || "加载模型治理配置失败");
    } finally {
      setLoading(false);
    }
  }, [api]);

  useEffect(() => {
    loadModels();
  }, [loadModels]);

  const activeModels = models.filter((model) => String(pickFirst(model, ["status"])).toUpperCase() === "ACTIVE").length;
  const totalContext = models.reduce(
    (sum, model) => sum + Number(pickFirst(model, ["contextWindow", "context_window"]) || 0),
    0,
  );

  return (
    <div className="space-y-4">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
        <div>
          <h2 className="text-xl font-semibold text-gray-900">模型治理</h2>
          <p className="mt-1 text-sm text-gray-500">集中管理可用模型、上下文窗口、成本参数和降级模型。</p>
        </div>
        <button
          type="button"
          onClick={loadModels}
          className="h-9 rounded-lg border border-gray-200 bg-white px-3 text-sm font-semibold text-gray-800 hover:bg-gray-50"
        >
          刷新
        </button>
      </div>

      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        <MetricTile label="模型总数" value={models.length} />
        <MetricTile label="启用模型" value={activeModels} />
        <MetricTile label="上下文合计" value={formatNumber(totalContext)} />
        <MetricTile label="治理状态" value={error ? "ERROR" : "READY"} />
      </div>

      <div className="overflow-hidden rounded-xl border border-gray-200 bg-white">
        <div className="overflow-auto">
          <table data-testid="model-governance-table" className="w-full min-w-[1040px] divide-y divide-gray-200">
            <thead className="bg-gray-50">
              <tr>
                {["Provider", "Model", "状态", "上下文", "Prompt 成本", "Completion 成本", "降级模型", "创建时间"].map(
                  (head) => (
                    <th key={head} className="table-header">
                      {head}
                    </th>
                  ),
                )}
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100 bg-white">
              {loading && (
                <tr>
                  <td colSpan={8} className="px-4 py-10 text-center text-sm text-gray-400">
                    正在加载模型配置...
                  </td>
                </tr>
              )}
              {!loading && error && (
                <tr>
                  <td colSpan={8} className="px-4 py-10 text-center text-sm text-red-600">
                    {error}
                  </td>
                </tr>
              )}
              {!loading && !error && !models.length && (
                <tr>
                  <td colSpan={8} className="px-4 py-10 text-center text-sm text-gray-400">
                    暂无模型配置
                  </td>
                </tr>
              )}
              {!loading &&
                !error &&
                models.map((model, index) => {
                  const modelName = displayValue(pickFirst(model, ["modelName", "model_name"]));
                  return (
                    <tr
                      key={pickFirst(model, ["modelConfigId", "model_config_id"]) || `${modelName}-${index}`}
                      className="hover:bg-gray-50"
                    >
                      <td className="table-cell font-medium text-gray-900">
                        {displayValue(pickFirst(model, ["provider"])) || "-"}
                      </td>
                      <td className="table-cell font-mono text-xs text-gray-800">{modelName || "-"}</td>
                      <td className="table-cell">
                        <StatusPill status={pickFirst(model, ["status"])} />
                      </td>
                      <td className="table-cell">
                        {formatNumber(pickFirst(model, ["contextWindow", "context_window"]))}
                      </td>
                      <td className="table-cell">
                        {formatMoney(pickFirst(model, ["promptTokenCostUsd", "prompt_token_cost_usd"]), "USD")}
                      </td>
                      <td className="table-cell">
                        {formatMoney(pickFirst(model, ["completionTokenCostUsd", "completion_token_cost_usd"]), "USD")}
                      </td>
                      <td className="table-cell font-mono text-xs">
                        {displayValue(pickFirst(model, ["fallbackModel", "fallback_model"])) || "-"}
                      </td>
                      <td className="table-cell whitespace-nowrap">
                        {formatDateTime(pickFirst(model, ["createdAt", "created_at"]))}
                      </td>
                    </tr>
                  );
                })}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
}

function RecommendationHistoryPage({ api }) {
  const [items, setItems] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [actionBusy, setActionBusy] = useState("");
  const [detail, setDetail] = useState({
    open: false,
    recommendation: null,
    evidence: [],
    llmCalls: [],
    loading: false,
    error: "",
  });

  const loadRecommendations = useCallback(async () => {
    setLoading(true);
    setError("");
    try {
      const response = await api("/recommendations");
      setItems(normalizeListResponse(response));
    } catch (ex) {
      setError(ex.message || "加载推荐历史失败");
    } finally {
      setLoading(false);
    }
  }, [api]);

  useEffect(() => {
    loadRecommendations();
  }, [loadRecommendations]);

  const openDetail = async (row) => {
    const workflowRunId = pickFirst(row, ["workflowRunId", "workflow_run_id"]);
    setDetail({
      open: true,
      recommendation: row,
      evidence: [],
      llmCalls: [],
      loading: Boolean(workflowRunId),
      error: workflowRunId ? "" : "缺少 workflowRunId，无法加载推荐证据。",
    });
    if (!workflowRunId) return;
    try {
      const response = await api(`/recommendations/${workflowRunId}`);
      let llmCalls = [];
      try {
        llmCalls = normalizeListResponse(
          await api(`/governance/llm-calls?workflowRunId=${encodeURIComponent(workflowRunId)}`),
        );
      } catch {
        llmCalls = [];
      }
      setDetail({
        open: true,
        recommendation: response?.recommendation || row,
        evidence: normalizeListResponse(response?.evidence || []),
        llmCalls,
        loading: false,
        error: "",
      });
    } catch (ex) {
      setDetail({
        open: true,
        recommendation: row,
        evidence: [],
        llmCalls: [],
        loading: false,
        error: ex.message || "加载推荐详情失败",
      });
    }
  };

  const decide = async (row, action) => {
    const workflowRunId = pickFirst(row, ["workflowRunId", "workflow_run_id"]);
    if (!workflowRunId) return;
    setActionBusy(`${action}:${workflowRunId}`);
    setError("");
    try {
      const updated = await api(recommendationDecisionPath(workflowRunId, action), { method: "POST" });
      setDetail((prev) =>
        prev.open && pickFirst(prev.recommendation, ["workflowRunId", "workflow_run_id"]) === workflowRunId
          ? { ...prev, recommendation: updated }
          : prev,
      );
      await loadRecommendations();
    } catch (ex) {
      setError(ex.message || "更新推荐审批状态失败");
    } finally {
      setActionBusy("");
    }
  };

  const pendingCount = items.filter(
    (item) => String(pickFirst(item, ["approvalStatus", "approval_status"])).toUpperCase() === "PENDING_REVIEW",
  ).length;
  const approvedCount = items.filter(
    (item) => String(pickFirst(item, ["approvalStatus", "approval_status"])).toUpperCase() === "APPROVED",
  ).length;

  return (
    <div className="space-y-4">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
        <div>
          <h2 className="text-xl font-semibold text-gray-900">推荐历史</h2>
          <p className="mt-1 text-sm text-gray-500">查看工作流生成的股票推荐、证据链、模型调用和审批状态。</p>
        </div>
        <button
          type="button"
          onClick={loadRecommendations}
          className="h-9 rounded-lg border border-gray-200 bg-white px-3 text-sm font-semibold text-gray-800 hover:bg-gray-50"
        >
          刷新
        </button>
      </div>

      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        <MetricTile label="推荐总数" value={items.length} />
        <MetricTile label="待复核" value={pendingCount} />
        <MetricTile label="已批准" value={approvedCount} />
        <MetricTile
          label="覆盖标的"
          value={new Set(items.map((item) => pickFirst(item, ["symbol"])).filter(Boolean)).size}
        />
      </div>

      <div className="overflow-hidden rounded-xl border border-gray-200 bg-white">
        <div className="overflow-auto">
          <table data-testid="recommendation-history-table" className="w-full min-w-[1320px] divide-y divide-gray-200">
            <thead className="bg-gray-50">
              <tr>
                {["创建时间", "标的", "建议", "置信度", "周期", "审批状态", "traceId", "workflowRunId", "操作"].map(
                  (head) => (
                    <th key={head} className="table-header">
                      {head}
                    </th>
                  ),
                )}
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100 bg-white">
              {loading && (
                <tr>
                  <td colSpan={9} className="px-4 py-10 text-center text-sm text-gray-400">
                    正在加载推荐历史...
                  </td>
                </tr>
              )}
              {!loading && error && (
                <tr>
                  <td colSpan={9} className="px-4 py-10 text-center text-sm text-red-600">
                    {error}
                  </td>
                </tr>
              )}
              {!loading && !error && !items.length && (
                <tr>
                  <td colSpan={9} className="px-4 py-10 text-center text-sm text-gray-400">
                    暂无推荐历史
                  </td>
                </tr>
              )}
              {!loading &&
                !error &&
                items.map((item, index) => {
                  const workflowRunId = displayValue(pickFirst(item, ["workflowRunId", "workflow_run_id"]));
                  const busyPrefix = actionBusy.endsWith(`:${workflowRunId}`) ? actionBusy.split(":")[0] : "";
                  return (
                    <tr
                      key={pickFirst(item, ["recommendationId", "recommendation_id"]) || workflowRunId || index}
                      className="hover:bg-gray-50"
                    >
                      <td className="table-cell whitespace-nowrap">
                        {formatDateTime(pickFirst(item, ["createdAt", "created_at"]))}
                      </td>
                      <td className="table-cell font-semibold text-gray-900">
                        {displayValue(pickFirst(item, ["symbol"])) || "-"}
                      </td>
                      <td className="table-cell">
                        <StatusPill status={pickFirst(item, ["recommendation"])} />
                      </td>
                      <td className="table-cell">{formatConfidence(pickFirst(item, ["confidence"]))}</td>
                      <td className="table-cell">
                        {displayValue(pickFirst(item, ["timeHorizon", "time_horizon"])) || "-"}
                      </td>
                      <td className="table-cell">
                        <StatusPill status={pickFirst(item, ["approvalStatus", "approval_status"])} />
                      </td>
                      <td className="table-cell max-w-[180px] truncate font-mono text-xs">
                        {displayValue(pickFirst(item, ["traceId", "trace_id"])) || "-"}
                      </td>
                      <td className="table-cell max-w-[220px] truncate font-mono text-xs" title={workflowRunId}>
                        {workflowRunId || "-"}
                      </td>
                      <td className="table-cell">
                        <div className="flex flex-wrap items-center gap-1.5">
                          <button
                            type="button"
                            onClick={() => openDetail(item)}
                            className="rounded-lg border border-gray-200 px-2.5 py-1.5 text-xs font-semibold text-gray-800 hover:bg-gray-50"
                          >
                            详情
                          </button>
                          <button
                            type="button"
                            data-testid="recommendation-approve"
                            onClick={() => decide(item, "approve")}
                            disabled={Boolean(busyPrefix)}
                            className="rounded-lg border border-emerald-200 px-2.5 py-1.5 text-xs font-semibold text-emerald-700 hover:bg-emerald-50 disabled:cursor-not-allowed disabled:opacity-40"
                          >
                            {busyPrefix === "approve" ? "..." : "批准"}
                          </button>
                          <button
                            type="button"
                            data-testid="recommendation-reject"
                            onClick={() => decide(item, "reject")}
                            disabled={Boolean(busyPrefix)}
                            className="rounded-lg border border-red-200 px-2.5 py-1.5 text-xs font-semibold text-red-700 hover:bg-red-50 disabled:cursor-not-allowed disabled:opacity-40"
                          >
                            {busyPrefix === "reject" ? "..." : "驳回"}
                          </button>
                        </div>
                      </td>
                    </tr>
                  );
                })}
            </tbody>
          </table>
        </div>
      </div>

      {detail.open && (
        <RecommendationDetailPanel
          detail={detail}
          actionBusy={actionBusy}
          onClose={() =>
            setDetail({ open: false, recommendation: null, evidence: [], llmCalls: [], loading: false, error: "" })
          }
          onDecision={decide}
        />
      )}
    </div>
  );
}

function recommendationDecisionPath(workflowRunId, action) {
  if (action === "approve") return `/recommendations/${workflowRunId}/approve`;
  if (action === "reject") return `/recommendations/${workflowRunId}/reject`;
  return `/recommendations/${workflowRunId}/${action}`;
}

function RecommendationDetailPanel({ detail, actionBusy, onClose, onDecision }) {
  const recommendation = detail.recommendation || {};
  const workflowRunId = displayValue(pickFirst(recommendation, ["workflowRunId", "workflow_run_id"]));
  const evidence = detail.evidence || [];
  const llmCalls = detail.llmCalls || [];
  const totalTokens = llmCalls.reduce(
    (sum, call) => sum + Number(pickFirst(call, ["totalTokens", "total_tokens"]) || 0),
    0,
  );
  const busyPrefix = actionBusy.endsWith(`:${workflowRunId}`) ? actionBusy.split(":")[0] : "";

  return (
    <div className="fixed inset-0 z-50">
      <div className="absolute inset-0 bg-white/60 backdrop-blur-[4px]" onClick={onClose} />
      <aside className="absolute inset-y-0 right-0 flex w-full max-w-4xl flex-col border-l border-gray-100 bg-white shadow-2xl">
        <div className="flex items-start justify-between border-b border-gray-100 px-5 py-4">
          <div>
            <h3 className="text-lg font-semibold text-gray-900">
              {displayValue(pickFirst(recommendation, ["symbol"])) || "-"} 推荐详情
            </h3>
            <p className="mt-1 font-mono text-xs text-gray-500">{workflowRunId || "no-workflow-run"}</p>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="rounded-md px-2 py-1 text-2xl leading-none text-gray-800 hover:bg-gray-100"
            aria-label="关闭推荐详情"
          >
            ×
          </button>
        </div>

        <div className="min-h-0 flex-1 overflow-auto p-5">
          {detail.loading && (
            <div className="rounded-lg border border-dashed border-gray-200 py-12 text-center text-sm text-gray-400">
              正在加载推荐详情...
            </div>
          )}
          {!detail.loading && detail.error && (
            <div className="rounded-lg border border-red-200 bg-red-50 p-4 text-sm text-red-700">{detail.error}</div>
          )}
          {!detail.loading && !detail.error && (
            <div className="space-y-5">
              <section className="rounded-xl border border-gray-200 bg-white p-4">
                <div className="flex flex-wrap items-center justify-between gap-3">
                  <div className="flex flex-wrap items-center gap-2">
                    <StatusPill status={pickFirst(recommendation, ["recommendation"])} />
                    <StatusPill status={pickFirst(recommendation, ["approvalStatus", "approval_status"])} />
                    <span className="text-sm text-gray-500">
                      置信度 {formatConfidence(pickFirst(recommendation, ["confidence"]))}
                    </span>
                    <span className="text-sm text-gray-500">Token {formatNumber(totalTokens)}</span>
                  </div>
                  <div className="flex items-center gap-2">
                    <button
                      type="button"
                      data-testid="recommendation-approve"
                      onClick={() => onDecision(recommendation, "approve")}
                      disabled={Boolean(busyPrefix)}
                      className="rounded-lg border border-emerald-200 px-3 py-1.5 text-xs font-semibold text-emerald-700 hover:bg-emerald-50 disabled:cursor-not-allowed disabled:opacity-40"
                    >
                      批准
                    </button>
                    <button
                      type="button"
                      data-testid="recommendation-reject"
                      onClick={() => onDecision(recommendation, "reject")}
                      disabled={Boolean(busyPrefix)}
                      className="rounded-lg border border-red-200 px-3 py-1.5 text-xs font-semibold text-red-700 hover:bg-red-50 disabled:cursor-not-allowed disabled:opacity-40"
                    >
                      驳回
                    </button>
                  </div>
                </div>
                <p className="mt-3 text-sm leading-6 text-gray-600">
                  {displayValue(pickFirst(recommendation, ["disclaimer"])) || "-"}
                </p>
                <JsonViewer
                  compact
                  value={summarizeJson(pickFirst(recommendation, ["rationaleJson", "rationale_json"]))}
                />
              </section>

              <section className="rounded-xl border border-gray-200 bg-white p-4">
                <h4 className="text-sm font-semibold text-gray-900">证据链</h4>
                <div className="mt-3 space-y-3">
                  {!evidence.length && (
                    <div className="rounded-lg border border-dashed border-gray-200 py-8 text-center text-sm text-gray-400">
                      暂无证据记录
                    </div>
                  )}
                  {evidence.map((item, index) => (
                    <div
                      key={pickFirst(item, ["evidenceId", "evidence_id"]) || index}
                      className="rounded-lg border border-gray-100 bg-gray-50 px-3 py-2"
                    >
                      <div className="flex flex-wrap items-center justify-between gap-2">
                        <p className="font-medium text-gray-900">{displayValue(pickFirst(item, ["title"])) || "-"}</p>
                        <div className="flex items-center gap-2">
                          <Badge tone="blue">
                            {displayValue(pickFirst(item, ["sourceType", "source_type"])) || "source"}
                          </Badge>
                          <Badge>{displayValue(pickFirst(item, ["trustTier", "trust_tier"])) || "TIER"}</Badge>
                        </div>
                      </div>
                      <p className="mt-1 text-sm text-gray-600">{displayValue(pickFirst(item, ["summary"])) || "-"}</p>
                      {pickFirst(item, ["url"]) && (
                        <a
                          className="mt-1 block truncate text-xs text-blue-700 hover:underline"
                          href={pickFirst(item, ["url"])}
                          target="_blank"
                          rel="noreferrer"
                        >
                          {pickFirst(item, ["url"])}
                        </a>
                      )}
                    </div>
                  ))}
                </div>
              </section>

              <section className="rounded-xl border border-gray-200 bg-white p-4">
                <h4 className="text-sm font-semibold text-gray-900">模型调用</h4>
                <div className="mt-3 overflow-auto">
                  <table className="w-full min-w-[760px] divide-y divide-gray-200">
                    <thead className="bg-gray-50">
                      <tr>
                        {["Provider", "Model", "状态", "Prompt", "Completion", "Total"].map((head) => (
                          <th key={head} className="table-header">
                            {head}
                          </th>
                        ))}
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-gray-100 bg-white">
                      {!llmCalls.length && (
                        <tr>
                          <td colSpan={6} className="px-4 py-8 text-center text-sm text-gray-400">
                            暂无模型调用记录
                          </td>
                        </tr>
                      )}
                      {llmCalls.map((call, index) => (
                        <tr key={pickFirst(call, ["llmCallId", "llm_call_id"]) || index}>
                          <td className="table-cell">{displayValue(pickFirst(call, ["provider"])) || "-"}</td>
                          <td className="table-cell font-mono text-xs">
                            {displayValue(pickFirst(call, ["modelName", "model_name"])) || "-"}
                          </td>
                          <td className="table-cell">
                            <StatusPill status={pickFirst(call, ["status"])} />
                          </td>
                          <td className="table-cell">
                            {formatNumber(pickFirst(call, ["promptTokens", "prompt_tokens"]))}
                          </td>
                          <td className="table-cell">
                            {formatNumber(pickFirst(call, ["completionTokens", "completion_tokens"]))}
                          </td>
                          <td className="table-cell">
                            {formatNumber(pickFirst(call, ["totalTokens", "total_tokens"]))}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </section>
            </div>
          )}
        </div>
      </aside>
    </div>
  );
}

function BacktestHistoryPage({ api }) {
  const [items, setItems] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [detail, setDetail] = useState({ open: false, row: null, nodes: [], loading: false, error: "" });

  const loadHistory = useCallback(async () => {
    setLoading(true);
    setError("");
    try {
      const response = await api("/backtest/history");
      setItems(normalizeListResponse(response));
    } catch (ex) {
      setError(ex.message || "加载回测历史失败");
    } finally {
      setLoading(false);
    }
  }, [api]);

  useEffect(() => {
    loadHistory();
  }, [loadHistory]);

  const openTrace = async (row) => {
    const workflowRunId = pickFirst(row, ["workflowRunId", "workflow_run_id", "runId", "run_id"]);
    setDetail({
      open: true,
      row,
      nodes: [],
      loading: Boolean(workflowRunId),
      error: workflowRunId ? "" : "该历史记录没有 workflowRunId，无法查询调用栈。",
    });
    if (!workflowRunId) return;
    try {
      const response = await api(`/workflow/runs/${workflowRunId}/nodes`);
      setDetail({ open: true, row, nodes: normalizeListResponse(response), loading: false, error: "" });
    } catch (ex) {
      setDetail({ open: true, row, nodes: [], loading: false, error: ex.message || "加载调用栈失败" });
    }
  };

  return (
    <div className="space-y-4">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <h2 className="text-xl font-semibold text-gray-900">回测历史</h2>
          <p className="mt-1 text-sm text-gray-500">展示历史任务、推荐结果和关联工作流调用栈。</p>
        </div>
        <button
          type="button"
          onClick={loadHistory}
          className="h-9 rounded-lg border border-gray-200 bg-white px-3 text-sm font-semibold text-gray-800 hover:bg-gray-50"
        >
          刷新
        </button>
      </div>

      <div className="overflow-hidden rounded-xl border border-gray-200 bg-white">
        <div className="overflow-auto">
          <table className="w-full min-w-[1280px] divide-y divide-gray-200">
            <thead className="bg-gray-50">
              <tr>
                {[
                  "运行时间",
                  "任务名称",
                  "工作流/策略",
                  "股票/主题",
                  "状态",
                  "节点数",
                  "最终建议",
                  "置信度",
                  "traceId",
                  "操作",
                ].map((head) => (
                  <th key={head} className="table-header">
                    {head}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100 bg-white">
              {loading && (
                <tr>
                  <td colSpan={10} className="px-4 py-10 text-center text-sm text-gray-400">
                    正在加载回测历史...
                  </td>
                </tr>
              )}
              {!loading && error && (
                <tr>
                  <td colSpan={10} className="px-4 py-10 text-center text-sm text-red-600">
                    {error}
                  </td>
                </tr>
              )}
              {!loading && !error && !items.length && (
                <tr>
                  <td colSpan={10} className="px-4 py-10 text-center text-sm text-gray-400">
                    暂无回测历史
                  </td>
                </tr>
              )}
              {!loading &&
                !error &&
                items.map((row, index) => {
                  const workflowName = displayValue(
                    pickFirst(row, [
                      "workflowName",
                      "workflow_name",
                      "workflowKey",
                      "workflow_key",
                      "strategyName",
                      "strategy_name",
                      "strategy",
                    ]),
                  );
                  const ticker = displayValue(pickFirst(row, ["ticker", "symbol", "stock", "stockCode", "stock_code"]));
                  const subject = displayValue(pickFirst(row, ["subject", "topic", "theme", "industry"]));
                  const status = displayValue(pickFirst(row, ["status", "state", "runStatus", "run_status"]));
                  const confidence = pickFirst(row, [
                    "confidence",
                    "score",
                    "recommendationConfidence",
                    "recommendation_confidence",
                  ]);
                  const traceId = displayValue(
                    pickFirst(row, ["traceId", "trace_id", "workflowRunId", "workflow_run_id", "runId", "run_id"]),
                  );
                  const nodeCount = Array.isArray(row.nodes)
                    ? row.nodes.length
                    : displayValue(pickFirst(row, ["nodeCount", "node_count", "nodes", "totalNodes", "total_nodes"]));
                  const recommendation = displayValue(
                    pickFirst(row, [
                      "finalRecommendation",
                      "final_recommendation",
                      "recommendation",
                      "advice",
                      "finalAdvice",
                      "final_advice",
                    ]),
                  );
                  return (
                    <tr
                      key={
                        pickFirst(row, ["id", "historyId", "history_id", "workflowRunId", "workflow_run_id"]) || index
                      }
                    >
                      <td className="table-cell whitespace-nowrap">
                        {formatDateTime(
                          pickFirst(row, [
                            "runTime",
                            "run_time",
                            "createdAt",
                            "created_at",
                            "startedAt",
                            "started_at",
                            "completedAt",
                            "completed_at",
                          ]),
                        )}
                      </td>
                      <td className="table-cell font-medium text-gray-900">
                        {displayValue(pickFirst(row, ["taskName", "task_name", "name", "title"])) || "-"}
                      </td>
                      <td className="table-cell">{workflowName || "-"}</td>
                      <td className="table-cell">{[ticker, subject].filter(Boolean).join(" / ") || "-"}</td>
                      <td className="table-cell">
                        <StatusPill status={status} />
                      </td>
                      <td className="table-cell">{nodeCount || "-"}</td>
                      <td className="table-cell max-w-[260px] truncate" title={recommendation}>
                        {recommendation || "-"}
                      </td>
                      <td className="table-cell">{formatConfidence(confidence)}</td>
                      <td className="table-cell font-mono text-xs">{traceId || "-"}</td>
                      <td className="table-cell">
                        <button
                          type="button"
                          onClick={() => openTrace(row)}
                          className="rounded-lg border border-gray-200 px-3 py-1.5 text-xs font-semibold text-gray-800 hover:bg-gray-50"
                        >
                          查看调用栈
                        </button>
                      </td>
                    </tr>
                  );
                })}
            </tbody>
          </table>
        </div>
      </div>

      {detail.open && (
        <BacktestTracePanel
          detail={detail}
          onClose={() => setDetail({ open: false, row: null, nodes: [], loading: false, error: "" })}
        />
      )}
    </div>
  );
}

function BacktestTracePanel({ detail, onClose }) {
  const row = detail.row || {};
  const runId = displayValue(pickFirst(row, ["workflowRunId", "workflow_run_id", "runId", "run_id", "id"]));
  const traceId = displayValue(pickFirst(row, ["traceId", "trace_id"]));
  const status = displayValue(pickFirst(row, ["status", "state", "runStatus", "run_status"]));
  const workflowName = displayValue(
    pickFirst(row, [
      "workflowName",
      "workflow_name",
      "workflowKey",
      "workflow_key",
      "strategyName",
      "strategy_name",
      "strategy",
    ]),
  );
  const startedAt = pickFirst(row, ["startedAt", "started_at", "runTime", "run_time", "createdAt", "created_at"]);
  const completedAt = pickFirst(row, ["completedAt", "completed_at", "endedAt", "ended_at"]);
  const workflowVersionId = displayValue(pickFirst(row, ["workflowVersionId", "workflow_version_id"]));
  const idempotencyKey = displayValue(pickFirst(row, ["idempotencyKey", "idempotency_key"]));
  const controlStatus = displayValue(pickFirst(row, ["controlStatus", "control_status"]));
  const nodeCount =
    detail.nodes.length ||
    displayValue(pickFirst(row, ["nodeCount", "node_count", "nodes", "totalNodes", "total_nodes"])) ||
    "-";
  return (
    <DrawerShell title="工作流运行详情" onClose={onClose}>
      <div className="min-h-0 flex-1 overflow-auto p-5">
        <p className="mb-4 text-sm text-gray-500">
          {pickFirst(row, ["taskName", "task_name", "name", "title", "subject"]) || runId || "-"}
        </p>
        <section className="mb-5 rounded-xl border border-gray-200 bg-white p-4">
          <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
            <div>
              <p className="text-sm font-semibold text-gray-900">{workflowName || "未命名工作流"}</p>
              <p className="mt-1 font-mono text-xs text-gray-500">{runId || "no-run-id"}</p>
            </div>
            <StatusPill status={status} />
          </div>
          <div className="grid grid-cols-1 gap-3 text-sm sm:grid-cols-2">
            <RunMeta label="traceId" value={traceId || "-"} mono />
            <RunMeta label="节点数" value={nodeCount} />
            <RunMeta label="开始时间" value={formatDateTime(startedAt)} />
            <RunMeta label="完成时间" value={formatDateTime(completedAt)} />
            <RunMeta label="workflowVersionId" value={workflowVersionId || "-"} mono />
            <RunMeta label="idempotencyKey" value={idempotencyKey || "-"} mono />
            <RunMeta label="controlStatus" value={controlStatus || status || "-"} />
          </div>
        </section>
        {detail.loading && (
          <div className="rounded-lg border border-dashed border-gray-200 py-12 text-center text-sm text-gray-400">
            正在加载节点调用栈...
          </div>
        )}
        {!detail.loading && detail.error && (
          <div className="rounded-lg border border-red-200 bg-red-50 p-4 text-sm text-red-700">{detail.error}</div>
        )}
        {!detail.loading && !detail.error && !detail.nodes.length && (
          <div className="rounded-lg border border-dashed border-gray-200 py-12 text-center text-sm text-gray-400">
            暂无节点调用记录
          </div>
        )}
        {!detail.loading && !detail.error && detail.nodes.length > 0 && (
          <div>
            <h4 className="mb-3 text-sm font-semibold text-gray-900">节点时间线</h4>
            <div className="space-y-3">
              {detail.nodes.map((node, index) => (
                <TraceNodeCard
                  key={pickFirst(node, ["id", "nodeRunId", "node_run_id", "nodeId", "node_id"]) || index}
                  node={node}
                  index={index}
                />
              ))}
            </div>
          </div>
        )}
      </div>
    </DrawerShell>
  );
}

function TraceNodeCard({ node, index }) {
  const output = pickFirst(node, ["outputJson", "output_json", "output", "outputs", "result", "response", "data"]);
  const input = pickFirst(node, ["inputJson", "input_json", "input", "inputs"]);
  const handler = pickFirst(node, [
    "handler",
    "functionName",
    "function_name",
    "agent",
    "agentName",
    "agent_name",
    "agentId",
    "agent_id",
  ]);
  const nodeType = pickFirst(node, ["nodeType", "node_type", "type"]);
  return (
    <div className="rounded-xl border border-gray-200 bg-white p-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <div className="flex items-center gap-2">
            <span className="grid h-6 w-6 place-items-center rounded-full bg-gray-900 text-xs font-semibold text-white">
              {index + 1}
            </span>
            <h4 className="font-semibold text-gray-900">
              {pickFirst(node, ["nodeName", "node_name", "label", "name", "nodeId", "node_id"]) || "节点"}
            </h4>
            <StatusPill status={pickFirst(node, ["status", "state", "runStatus", "run_status"])} />
          </div>
          <p className="mt-2 text-xs text-gray-500">
            handler/agent: <span className="font-mono text-gray-700">{handler || "-"}</span>
            <span className="ml-3">
              type: <span className="font-mono text-gray-700">{nodeType || "-"}</span>
            </span>
          </p>
        </div>
        <div className="text-right text-xs text-gray-500">
          <p>
            耗时:{" "}
            {formatDuration(
              pickFirst(node, ["durationMs", "duration_ms", "elapsedMs", "elapsed_ms", "latencyMs", "latency_ms"]),
            )}
          </p>
          <p className="mt-1">
            {formatDateTime(pickFirst(node, ["startedAt", "started_at", "createdAt", "created_at"]))}
          </p>
        </div>
      </div>
      {pickFirst(node, ["error", "errorMessage", "error_message", "exception"]) && (
        <div className="mt-3 rounded-lg border border-red-200 bg-red-50 p-3 text-sm text-red-700">
          {String(pickFirst(node, ["error", "errorMessage", "error_message", "exception"]))}
        </div>
      )}
      <div className="mt-3 grid grid-cols-1 gap-3 lg:grid-cols-2">
        {input !== undefined && input !== null && (
          <div>
            <p className="text-xs font-semibold text-gray-500">输入摘要</p>
            <JsonViewer className="max-h-52" value={summarizeJson(input)} compact />
          </div>
        )}
        {output !== undefined && output !== null && (
          <div>
            <p className="text-xs font-semibold text-gray-500">输出摘要</p>
            <JsonViewer className="max-h-52" value={summarizeJson(output)} compact />
          </div>
        )}
      </div>
    </div>
  );
}

function RunMeta({ label, value, mono = false }) {
  return (
    <div>
      <p className="text-xs font-medium text-gray-500">{label}</p>
      <p className={`mt-1 truncate text-gray-900 ${mono ? "font-mono text-xs" : "text-sm"}`}>{value || "-"}</p>
    </div>
  );
}

function normalizeListResponse(response) {
  if (Array.isArray(response)) return response;
  if (Array.isArray(response?.items)) return response.items;
  if (Array.isArray(response?.records)) return response.records;
  if (Array.isArray(response?.data)) return response.data;
  if (Array.isArray(response?.results)) return response.results;
  return [];
}

function pickFirst(source, keys) {
  for (const key of keys) {
    const value = source?.[key];
    if (value !== undefined && value !== null && value !== "") return value;
  }
  return "";
}

function displayValue(value) {
  if (value === undefined || value === null || value === "") return "";
  if (typeof value === "string" || typeof value === "number" || typeof value === "boolean") return String(value);
  if (Array.isArray(value)) return `${value.length} 条`;
  return JSON.stringify(value);
}

function formatDateTime(value) {
  if (!value) return "-";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return String(value);
  return date.toLocaleString("zh-CN", { hour12: false });
}

function formatMarketTimestamp(value) {
  if (!value) return "等待查询";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return sanitizeText(value);
  return date.toLocaleString("zh-CN", { hour12: false, timeZoneName: "short" });
}

function sanitizeText(value) {
  if (value === undefined || value === null) return "";
  return String(value)
    .replace(/â€™|â|&#39;|&apos;/g, "'")
    .replace(/â€œ|â|&ldquo;/g, '"')
    .replace(/â€|â|&rdquo;/g, '"')
    .replace(/â€“|â/g, "-")
    .replace(/â€”|â/g, "-")
    .replace(/&amp;/g, "&")
    .replace(/&quot;/g, '"')
    .trim();
}

function formatConfidence(value) {
  if (value === undefined || value === null || value === "") return "-";
  const number = Number(value);
  if (!Number.isFinite(number)) return String(value);
  return number <= 1 ? `${Math.round(number * 100)}%` : `${number.toFixed(1)}%`;
}

function formatDuration(value) {
  const number = Number(value);
  if (!Number.isFinite(number)) return "-";
  return number >= 1000 ? `${(number / 1000).toFixed(2)}s` : `${Math.round(number)}ms`;
}

function summarizeJson(value) {
  if (typeof value === "string") {
    try {
      return JSON.parse(value);
    } catch {
      return value.length > 1200 ? `${value.slice(0, 1200)}...` : value;
    }
  }
  if (Array.isArray(value)) return value.slice(0, 6);
  if (!value || typeof value !== "object") return value;
  return Object.fromEntries(Object.entries(value).slice(0, 20));
}

function StatusPill({ status }) {
  const text = String(status || "UNKNOWN").toUpperCase();
  const tone =
    text.includes("FAIL") || text.includes("ERROR")
      ? "border-red-200 bg-red-50 text-red-700"
      : text.includes("RUN") || text.includes("PENDING")
        ? "border-blue-200 bg-blue-50 text-blue-700"
        : text.includes("COMPLETE") || text.includes("SUCCESS")
          ? "border-emerald-200 bg-emerald-50 text-emerald-700"
          : "border-gray-200 bg-gray-50 text-gray-600";
  return (
    <span className={`inline-flex rounded-full border px-2 py-0.5 text-[11px] font-semibold ${tone}`}>{text}</span>
  );
}

function Placeholder({ title, desc = "该能力正在接入真实业务数据，当前入口仅展示上线状态。" }) {
  return (
    <div className="card">
      <h2 className="mb-2 text-lg font-semibold text-gray-800">{title}</h2>
      <p className="text-sm text-gray-500">{desc}</p>
      <div className="mt-6 rounded-lg border border-dashed border-gray-300 bg-gray-50 py-10 text-center text-sm text-gray-500">
        功能接入中，后端契约稳定后开放操作。
      </div>
    </div>
  );
}

function LoginPage({ onLogin }) {
  const [username, setUsername] = useState("guanghui.nie");
  const [password, setPassword] = useState("guanghui.nie");
  const [error, setError] = useState("");
  const submit = async (event) => {
    event.preventDefault();
    setError("");
    try {
      const result = await request("/auth/login", { method: "POST", body: JSON.stringify({ username, password }) });
      onLogin(result.access_token, { username });
    } catch (ex) {
      setError(ex.message);
    }
  };
  return (
    <div className="flex min-h-screen items-center justify-center bg-gray-50 px-4">
      <form onSubmit={submit} className="w-full max-w-md rounded-xl border border-gray-200 bg-white p-6 shadow-sm">
        <h1 className="text-xl font-bold text-gray-900">Aegis Alpha</h1>
        <p className="mt-1 text-sm text-gray-500">使用 Aegis Alpha 账号进入平台。</p>
        <div className="mt-6 space-y-4">
          <label className="block">
            <span className="mb-1 block text-sm font-medium text-gray-700">用户名</span>
            <input
              className="input-field"
              name="username"
              autoComplete="username"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
            />
          </label>
          <label className="block">
            <span className="mb-1 block text-sm font-medium text-gray-700">密码</span>
            <input
              className="input-field"
              name="password"
              autoComplete="current-password"
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
            />
          </label>
        </div>
        {error && <p className="mt-3 text-sm text-red-600">{error}</p>}
        <button className="btn-primary mt-6 w-full justify-center" type="submit">
          登录
        </button>
      </form>
    </div>
  );
}

function JsonViewer({ value, compact = false, className = "" }) {
  return (
    <pre
      className={`${className} overflow-auto rounded-lg bg-slate-950 p-3 text-xs leading-5 text-slate-100 ${compact ? "mt-3 max-h-56" : ""}`}
    >
      {JSON.stringify(value, null, 2)}
    </pre>
  );
}
function Panel({ title, children }) {
  return (
    <section className="space-y-4 rounded-xl border border-slate-200 bg-white p-4">
      <h4 className="text-sm font-semibold text-slate-900">{title}</h4>
      {children}
    </section>
  );
}
function Field({ label, children }) {
  return (
    <label className="block">
      <span className="mb-1.5 block text-sm font-medium text-slate-700">{label}</span>
      {children}
    </label>
  );
}
function ReadOnlyField({ label, value }) {
  return (
    <div>
      <span className="mb-1.5 block text-sm font-medium text-slate-700">{label}</span>
      <div className="truncate rounded-lg border border-slate-200 bg-slate-50 px-3 py-2 font-mono text-sm text-slate-700">
        {value}
      </div>
    </div>
  );
}
function Button({ children, onClick, variant = "default" }) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={
        variant === "primary"
          ? "rounded-lg bg-slate-950 px-4 py-2 text-sm font-semibold text-white hover:bg-slate-800"
          : "rounded-lg border border-slate-200 bg-white px-4 py-2 text-sm font-semibold text-slate-700 hover:bg-slate-50"
      }
    >
      {children}
    </button>
  );
}
function Badge({ children, tone = "default" }) {
  const styles = {
    default: "bg-slate-100 text-slate-600",
    warning: "border border-amber-200 bg-amber-50 text-amber-700",
    dark: "bg-slate-900 text-white",
    blue: "bg-blue-100 text-blue-700",
  };
  return (
    <span
      className={`inline-flex rounded-full px-2 py-0.5 text-[11px] font-semibold ${styles[tone] || styles.default}`}
    >
      {children}
    </span>
  );
}
/* Tabs / tabClass removed - unused */

export default function App() {
  const [path, setPath] = useState(readInitialPathname);
  const [copilotOpen, setCopilotOpen] = useState(false);
  const [copilotPrompt, setCopilotPrompt] = useState(null);
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false);
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false);
  const [token, setToken] = useState("");
  const [me, setMe] = useState(null);
  const [booting, setBooting] = useState(true);
  const mobileMenuRef = useFocusTrap(mobileMenuOpen, () => setMobileMenuOpen(false));

  useEffect(() => {
    const saved = window.localStorage.getItem("marketmind_access_token");
    if (!saved) {
      setBooting(false);
      return;
    }
    setToken(saved);
    request("/auth/me", {}, saved)
      .then(setMe)
      .catch(() => {
        window.localStorage.removeItem("marketmind_access_token");
        setToken("");
      })
      .finally(() => setBooting(false));
  }, []);
  const api = useCallback((target, options = {}) => request(target, options, token), [token]);
  useEffect(() => {
    const syncFromHistory = () => setPath(readInitialPathname());
    window.addEventListener("popstate", syncFromHistory);
    return () => window.removeEventListener("popstate", syncFromHistory);
  }, []);
  const navigate = useCallback((target) => {
    const nextUrl = String(target || "/");
    const nextPath = normalizePathname(nextUrl);
    if (typeof window !== "undefined") {
      const currentUrl = `${window.location.pathname}${window.location.search}`;
      if (currentUrl !== nextUrl) window.history.pushState({}, "", nextUrl);
    }
    setPath(nextPath);
    setMobileMenuOpen(false);
  }, []);
  const openCopilotWithPrompt = useCallback((text) => {
    setCopilotPrompt({ id: `${Date.now()}-${Math.random().toString(36).slice(2)}`, text });
    setCopilotOpen(true);
  }, []);
  const clearCopilotPrompt = useCallback(() => setCopilotPrompt(null), []);
  const logout = () => {
    window.localStorage.removeItem("marketmind_access_token");
    setToken("");
    setMe(null);
  };
  const login = (accessToken, user) => {
    window.localStorage.setItem("marketmind_access_token", accessToken);
    setToken(accessToken);
    setMe(user);
  };

  if (booting)
    return <div className="grid min-h-screen place-items-center text-sm text-gray-500">加载 Aegis Alpha...</div>;
  if (!token) return <LoginPage onLogin={login} />;

  let page = <Home navigate={navigate} openCopilotWithPrompt={openCopilotWithPrompt} />;
  if (path === "/agent") page = <AgentPage api={api} />;
  else if (path === "/workflow/runs") page = <WorkflowRunCenterPage api={api} />;
  else if (path === "/workflow") page = <WorkflowPage api={api} token={token} />;
  else if (path.startsWith("/portfolio")) page = <Portfolio api={api} navigate={navigate} path={path} />;
  else if (path === "/data-center/dashboard") page = <Dashboard api={api} />;
  else if (path.startsWith("/data-center")) page = <Dashboard api={api} />;
  else if (path === "/backtest/history") page = <BacktestHistoryPage api={api} />;
  else if (path.startsWith("/backtest")) page = <Placeholder title={pageTitles[path] || "回测"} />;
  else if (path === "/governance/models") page = <ModelGovernancePage api={api} />;
  else if (path === "/governance/audit") page = <AuditLogPage api={api} />;
  else if (path === "/recommendations") page = <RecommendationHistoryPage api={api} />;
  else if (path === "/watchlist")
    page = <Placeholder title="自选股" desc="自选股页面保持当前导航样式，后续可接入关注列表与价格提醒。" />;
  else if (path.startsWith("/profile")) page = <Placeholder title={pageTitles[path] || "个人中心"} />;
  else if (path === "/report") page = <Placeholder title="Report" desc="报告生成入口，沿用 Aegis Alpha 平台风格。" />;

  return (
    <div className="flex h-screen overflow-hidden bg-white">
      <Sidebar path={path} navigate={navigate} collapsed={sidebarCollapsed} setCollapsed={setSidebarCollapsed} />
      <div className="flex min-w-0 flex-1 flex-col">
        <Header
          path={path}
          copilotOpen={copilotOpen}
          setCopilotOpen={setCopilotOpen}
          me={me}
          onLogout={logout}
          onMenuOpen={() => setMobileMenuOpen(true)}
        />
        <main className="flex-1 overflow-auto bg-white">
          <div className="p-4 sm:p-5 lg:p-6">{page}</div>
        </main>
      </div>
      {mobileMenuOpen && (
        <div
          ref={mobileMenuRef}
          role="dialog"
          aria-modal="true"
          aria-label="导航菜单"
          tabIndex={-1}
          className="fixed inset-0 z-50 lg:hidden"
        >
          <div className="absolute inset-0 bg-gray-900/35" onClick={() => setMobileMenuOpen(false)} />
          <div className="absolute inset-y-0 left-0 bg-white shadow-2xl">
            <Sidebar
              path={path}
              navigate={navigate}
              collapsed={false}
              setCollapsed={setSidebarCollapsed}
              mobile
              onNavigate={() => setMobileMenuOpen(false)}
            />
          </div>
        </div>
      )}
      {!copilotOpen && (
        <button
          type="button"
          onClick={() => setCopilotOpen(true)}
          className="fixed bottom-6 right-6 z-40 grid h-10 w-10 place-items-center rounded-full bg-neutral-950 text-white shadow-lg transition-colors hover:bg-neutral-800"
          aria-label="打开 AI Copilot"
        >
          <ChatBubbleIcon className="h-5 w-5" />
        </button>
      )}
      {copilotOpen && (
        <AICopilot
          setCopilotOpen={setCopilotOpen}
          api={api}
          promptRequest={copilotPrompt}
          onPromptHandled={clearCopilotPrompt}
        />
      )}
    </div>
  );
}
