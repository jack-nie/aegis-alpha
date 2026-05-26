import express from "express";
import { ChatOpenAI } from "@langchain/openai";
import { HumanMessage, SystemMessage } from "@langchain/core/messages";

import crypto from "crypto";

const app = express();
app.use(express.json({ limit: "2mb" }));

// --- request context middleware: propagate request-id, trace-id, client-ip ---
app.use((req, _res, next) => {
  const requestId = req.headers["x-request-id"] || crypto.randomUUID();
  const traceId = req.headers["x-trace-id"] || req.headers["traceparent"] || null;
  const xff = req.headers["x-forwarded-for"];
  const clientIp = xff ? xff.split(",")[0].trim() : req.headers["x-real-ip"] || req.ip;
  const userAgent = req.headers["user-agent"] || null;
  req.context = { requestId, traceId, clientIp, userAgent };
  next();
});

const port = Number(process.env.MARKETMIND_LANGGRAPH_PORT || 8787);
const DEFAULT_DEEPSEEK_MODEL = "deepseek-v4-flash";
const SUPPORTED_DEEPSEEK_MODELS = new Set(["deepseek-v4-pro", "deepseek-v4-flash"]);
const DEFAULT_BACKEND_URL = "http://127.0.0.1:5178";
const HANDLERS = new Set([
  "general.agent",
  "finance.market_analysis",
  "finance.industry_share",
  "finance.sentiment_monitor",
  "finance.tech_breakthrough",
  "finance.industry_news",
  "general.web_search",
  "finance.financial_interpretation",
  "finance.stock_recommendation_aggregate",
  "finance.fundamental_analysis",
  "finance.technical_analysis",
  "finance.valuation_analysis",
  "finance.money_flow_analysis",
  "finance.risk_assessment",
]);
const FALLBACK_HANDLERS = new Set(["start", "end", "condition", "logic"]);

app.get("/health", (_, res) => {
  res.json({
    ok: true,
    engine: "langgraph",
    port,
    provider: process.env.MARKETMIND_LANGCHAIN_PROVIDER || "openai",
    model: resolveModel(
      process.env.MARKETMIND_LANGCHAIN_MODEL,
      process.env.MARKETMIND_LANGCHAIN_BASE_URL || process.env.OPENAI_BASE_URL
    ),
    hasApiKey: Boolean(process.env.OPENAI_API_KEY || process.env.MARKETMIND_LANGCHAIN_API_KEY),
    baseUrlConfigured: Boolean(process.env.MARKETMIND_LANGCHAIN_BASE_URL || process.env.OPENAI_BASE_URL),
    mock: isMockMode(),
  });
});

app.post("/execute-agent", async (req, res) => {
  try {
    const result = await executeAgent(req.body || {});
    res.json(result);
  } catch (error) {
    res.json(errorNodeResult(req.body || {}, error));
  }
});

app.post("/execute-node", async (req, res) => {
  try {
    const result = await executeNode(req.body || {});
    res.json(result);
  } catch (error) {
    res.json(errorNodeResult(req.body || {}, error));
  }
});

app.post("/execute-workflow", async (req, res) => {
  try {
    const result = await executeWorkflow(req.body || {});
    res.json(result);
  } catch (error) {
    res.status(500).json({
      ok: false,
      requestId: req.context?.requestId,
      traceId: req.context?.traceId,
      error: error?.message || String(error),
      finalState: req.body?.state || {},
      trace: [],
      nodeOutputs: {},
    });
  }
});

async function executeWorkflow(payload) {
  const nodes = Array.isArray(payload.nodes) ? payload.nodes : [];
  const edges = Array.isArray(payload.edges) ? payload.edges : [];
  const subject = payload.subject || payload.state?.subject || "Aegis Alpha workflow";
  const finalState = { ...(payload.state || {}), subject };
  const trace = [];
  const nodeOutputs = {};

  for (const node of orderNodes(nodes, edges)) {
    const startedAt = new Date().toISOString();
    const result = await executeNode({ ...payload, state: finalState, node, subject });
    nodeOutputs[node.id] = result;
    finalState[node.id] = result;
    if (result.handler) {
      finalState[result.handler] = result;
    }
    trace.push({
      nodeId: result.nodeId,
      nodeName: result.nodeName,
      handler: result.handler,
      status: result.status,
      ok: result.ok,
      degraded: result.degraded,
      startedAt,
      completedAt: result.completedAt,
      durationMs: result.durationMs,
    });
  }

  return {
    ok: trace.every((entry) => entry.ok !== false),
    finalState,
    trace,
    nodeOutputs,
    state: finalState,
  };
}

async function executeAgent(payload) {
  const result = await executeNode(payload);
  return {
    ...result,
    agentId: result.data.agentId,
    agentName: result.data.agentName,
    content: result.summary,
    stateSize: Object.keys(payload.state || {}).length,
  };
}

async function executeNode(payload) {
  const startedAt = new Date();
  const agent = payload.agent || {};
  const node = payload.node || {};
  const data = node.data || {};
  const state = payload.state || {};
  const subject = payload.subject || state.subject || "Aegis Alpha workflow";
  const handler = resolveHandler(node);
  const apiKey = resolveApiKey(payload);
  const baseUrl = payload.baseUrl || process.env.MARKETMIND_LANGCHAIN_BASE_URL || process.env.OPENAI_BASE_URL;
  const provider = payload.provider || process.env.MARKETMIND_LANGCHAIN_PROVIDER || "openai";
  const model = resolveModel(payload.model || agent.modelName || process.env.MARKETMIND_LANGCHAIN_MODEL, baseUrl);
  let marketDataContext = null;
  let hydratedState = state;

  try {
    marketDataContext = await hydrateMarketDataContext({ handler, state, subject, node });
    hydratedState = marketDataContext ? { ...state, marketDataContext } : state;
    if (isDeterministicToolNode(handler, data)) {
      return completeNodeResult(payload, startedAt, {
        ok: true,
        status: "tool-mock",
        degraded: false,
        provider: "mock",
        model,
        handler,
        summary: mockSummary(handler, subject, state),
        signals: mockSignals(handler, subject),
        sources: mockSources(handler, subject),
        confidence: 0.66,
        data: {
          mode: "deterministic_tool",
          agentId: agent.agentId || data.agentId || "inline",
          agentName: agent.name || data.label || "Inline Node",
          handlerType: handlerType(handler),
          marketDataContext,
        },
      });
    }

    if (provider !== "openai") {
      return completeNodeResult(payload, startedAt, {
        ok: false,
        status: "unsupported_provider",
        degraded: true,
        provider,
        model,
        handler,
        summary: `Unsupported provider: ${provider}`,
        signals: [],
        sources: [],
        confidence: 0,
        data: { reason: "unsupported_provider" },
      });
    }

    if (!apiKey || isMockMode()) {
      return completeNodeResult(payload, startedAt, {
        ok: true,
        status: "mock",
        degraded: true,
        provider: "mock",
        model,
        handler,
        summary: mockSummary(handler, subject, state),
        signals: mockSignals(handler, subject),
        sources: mockSources(handler, subject),
        confidence: 0.62,
        data: {
          mode: "mock",
          reason: !apiKey ? "missing_api_key" : "MARKETMIND_LANGCHAIN_MOCK=true",
          agentId: agent.agentId || data.agentId || "inline",
          agentName: agent.name || data.label || "Inline Agent",
          handlerType: handlerType(handler),
          marketDataContext,
        },
      });
    }

    const llm = await invokeModel({ payload: { ...payload, state: hydratedState }, state: hydratedState, subject, handler, apiKey, baseUrl, model });
    return completeNodeResult(payload, startedAt, {
      ok: true,
      status: "completed",
      degraded: false,
      provider: "langchain-openai",
      model,
      handler,
      summary: llm.summary,
      signals: llm.signals,
      sources: llm.sources,
      confidence: llm.confidence,
      data: {
        ...llm.data,
        agentId: agent.agentId || data.agentId || "inline",
        agentName: agent.name || data.label || "Inline Agent",
        marketDataContext,
      },
    });
  } catch (error) {
    return fallbackNodeResult(payload, error, startedAt, model, handler, hydratedState, subject, marketDataContext);
  }
}

async function invokeModel({ payload, state, subject, handler, apiKey, baseUrl, model }) {
  const agent = payload.agent || {};
  const node = payload.node || {};
  const data = node.data || {};
  const timeoutMs = Number(process.env.MARKETMIND_LANGCHAIN_TIMEOUT_MS || 25000);
  const chat = new ChatOpenAI({
    apiKey,
    model,
    temperature: 0.2,
    timeout: timeoutMs,
    maxRetries: 0,
    configuration: baseUrl ? { baseURL: baseUrl } : undefined,
  });
  const system = [
    `You are ${agent.name || data.label || "Aegis Alpha Agent"}.`,
    "Return only JSON. Do not use markdown.",
    "The JSON shape is {\"summary\":\"string\",\"signals\":[{\"name\":\"string\",\"value\":\"string\",\"weight\":0.5}],\"sources\":[{\"title\":\"string\",\"url\":\"string\",\"type\":\"string\"}],\"confidence\":0.5,\"data\":{}}.",
  ].join("\n");
  const context = JSON.stringify({ subject, state, node: data, handler }).slice(0, 12000);
  const prompt = data.prompt || agent.prompt || promptForHandler(handler);
  const response = await withTimeout(
    chat.invoke([
      new SystemMessage(system),
      new HumanMessage(`${prompt}\n\nWorkflow context:\n${context}`),
    ]),
    timeoutMs,
    `OpenAI request timed out after ${timeoutMs}ms`
  );
  return normalizeLlmContent(response.content, extractUsage(response));
}

function normalizeLlmContent(content, usage = emptyUsage()) {
  const rawContent = typeof content === "string" ? content : JSON.stringify(content);
  const cleaned = rawContent.trim().replace(/^```(?:json)?/i, "").replace(/```$/i, "").trim();
  try {
    const parsed = JSON.parse(cleaned);
    return {
      summary: String(parsed.summary || parsed.content || "Model returned structured output."),
      signals: asArray(parsed.signals),
      sources: asArray(parsed.sources),
      confidence: clampConfidence(parsed.confidence),
      data: {
        ...(typeof parsed.data === "object" && parsed.data !== null ? parsed.data : { parsed }),
        usage,
      },
    };
  } catch {
    return {
      summary: rawContent.slice(0, 500) || "Model returned empty content.",
      signals: [],
      sources: [],
      confidence: 0.5,
      data: { rawContent, usage },
    };
  }
}

function completeNodeResult(payload, startedAt, partial) {
  const completedAt = new Date();
  const node = payload.node || {};
  const data = node.data || {};
  const state = payload.state || {};
  const subject = payload.subject || state.subject || "Aegis Alpha workflow";
  return {
    ok: Boolean(partial.ok),
    status: partial.status || (partial.ok ? "completed" : "error"),
    provider: partial.provider || "mock",
    model: partial.model || resolveModel(payload.model, payload.baseUrl),
    handler: partial.handler || resolveHandler(node),
    nodeId: String(node.id || data.id || partial.handler || "inline"),
    nodeName: String(data.label || data.name || partial.handler || "Inline Node"),
    subject,
    summary: String(partial.summary || ""),
    signals: asArray(partial.signals),
    sources: asArray(partial.sources),
    confidence: clampConfidence(partial.confidence),
    data: partial.data && typeof partial.data === "object" ? partial.data : {},
    degraded: Boolean(partial.degraded),
    startedAt: startedAt.toISOString(),
    completedAt: completedAt.toISOString(),
    durationMs: completedAt.getTime() - startedAt.getTime(),
  };
}

function errorNodeResult(payload, error, startedAt = new Date()) {
  const message = error?.message || String(error);
  const agent = payload.agent || {};
  const nodeData = payload.node?.data || {};
  return completeNodeResult(payload, startedAt, {
    ok: false,
    status: "error",
    degraded: true,
    provider: payload.provider || process.env.MARKETMIND_LANGCHAIN_PROVIDER || "openai",
    model: resolveModel(payload.model || agent.modelName || process.env.MARKETMIND_LANGCHAIN_MODEL, payload.baseUrl),
    handler: resolveHandler(payload.node || {}),
    summary: message,
    signals: [],
    sources: [],
    confidence: 0,
    data: {
      error: message,
      agentId: agent.agentId || nodeData.agentId || "inline",
      agentName: agent.name || nodeData.label || "Inline Agent",
    },
  });
}

function fallbackNodeResult(payload, error, startedAt, model, handler, state, subject, marketDataContext = null) {
  const message = error?.message || String(error);
  const agent = payload.agent || {};
  const data = payload.node?.data || {};
  const failClosed = handler === "finance.stock_recommendation_aggregate";
  return completeNodeResult(payload, startedAt, {
    ok: !failClosed,
    status: failClosed ? "model_failed" : "degraded",
    degraded: true,
    provider: "fallback",
    model,
    handler,
    summary: fallbackSummary(handler, subject, state, message),
    signals: mockSignals(handler, subject),
    sources: mockSources(handler, subject),
    confidence: 0.42,
    data: {
      fallback: true,
      fallbackPolicy: failClosed ? "fail_closed" : "deterministic_degraded",
      error: message,
      agentId: agent.agentId || data.agentId || "inline",
      agentName: agent.name || data.label || "Inline Agent",
      stateSize: Object.keys(state || {}).length,
      marketDataContext,
    },
  });
}

function isDeterministicToolNode(handler, data) {
  const nodeType = String(data.nodeType || data.type || "").trim().toLowerCase();
  return HANDLERS.has(handler) && nodeType && nodeType !== "agent" && handler !== "finance.stock_recommendation_aggregate";
}

async function hydrateMarketDataContext({ handler, state, subject, node }) {
  if (!shouldHydrateMarketData(handler)) {
    return null;
  }
  if (state?.marketDataOverview && typeof state.marketDataOverview === "object") {
    return state.marketDataOverview;
  }
  if (state?.marketDataContext && typeof state.marketDataContext === "object") {
    return state.marketDataContext;
  }
  const backendUrl = String(process.env.MARKETMIND_BACKEND_URL || process.env.MARKETMIND_NODE_CALLBACK_BASE_URL || DEFAULT_BACKEND_URL).replace(/\/+$/, "");
  if (!backendUrl) {
    return null;
  }
  const timeoutMs = Number(process.env.MARKETMIND_MARKET_DATA_TIMEOUT_MS || 8000);
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetch(`${backendUrl}/_backend/internal/workflow-nodes/execute`, {
      method: "POST",
      signal: controller.signal,
      headers: {
        "content-type": "application/json",
        "x-marketmind-workflow-token": process.env.MARKETMIND_NODE_EXECUTION_TOKEN || "local-workflow-node-token",
      },
      body: JSON.stringify({
        functionName: handler,
        action: "hydrate_market_data",
        params: {
          symbol: state.symbol || state.ticker || state.code || subject,
          ticker: state.ticker || state.symbol || state.code || subject,
          industry: state.industry || state.sector || "",
        },
        extra: {
          subject,
          nodeId: node?.id || "",
        },
      }),
    });
    if (!response.ok) {
      return {
        ok: false,
        status: "unavailable",
        provider: "backend",
        error: `market data hydration returned ${response.status}`,
      };
    }
    const data = await response.json();
    return data && typeof data === "object" ? data : null;
  } catch (error) {
    return {
      ok: false,
      status: "unavailable",
      provider: "backend",
      error: error?.message || String(error),
    };
  } finally {
    clearTimeout(timer);
  }
}

function shouldHydrateMarketData(handler) {
  return HANDLERS.has(handler) && handler !== "finance.stock_recommendation_aggregate";
}

function fallbackSummary(handler, subject, state, errorMessage) {
  if (handler === "general.agent") {
    const message = typeof state?.message === "string" && state.message.trim() ? state.message.trim() : "当前问题";
    return `暂时无法连接真实模型，已收到你的问题：“${message}”。请检查模型 API Key、Base URL 或网络连接后重试。`;
  }
  if (handler === "finance.stock_recommendation_aggregate") {
    return `Fallback recommendation for ${subject}: upstream workflow evidence was collected, but the LLM aggregation step degraded due to ${errorMessage}. Treat confidence as limited and review node outputs before acting.`;
  }
  return `Fallback ${handler} result for ${subject}: ${errorMessage}.`;
}

function resolveHandler(node = {}) {
  const data = node.data || {};
  const candidate = data.handler || data.functionName || data.type || node.type || "logic";
  const handler = String(candidate).trim();
  if (HANDLERS.has(handler) || FALLBACK_HANDLERS.has(handler)) {
    return handler;
  }
  const lower = handler.toLowerCase();
  if (FALLBACK_HANDLERS.has(lower)) {
    return lower;
  }
  return HANDLERS.has(lower) ? lower : "logic";
}

function handlerType(handler) {
  if (HANDLERS.has(handler)) return "finance";
  if (FALLBACK_HANDLERS.has(handler)) return "control";
  return "fallback";
}

function promptForHandler(handler) {
  const prompts = {
    "finance.market_analysis": "Analyze market conditions, valuation direction, catalysts, and risk factors for the subject.",
    "finance.industry_share": "Estimate industry position and share dynamics for the subject.",
    "finance.sentiment_monitor": "Assess investor, media, and analyst sentiment for the subject.",
    "finance.tech_breakthrough": "Identify technical or product breakthroughs relevant to the subject.",
    "finance.industry_news": "Summarize important recent industry news relevant to the subject.",
    "general.agent": "You are an investment research assistant. Use the marketDataContext (quote, financials, news) in the workflow state to provide a concrete analysis. Always reference actual data values (price, PE, PB, news titles) in your response. Do NOT say data is insufficient if marketDataContext is present. Answer in Chinese.",
    "general.web_search": "Find concise web-search style evidence relevant to the subject.",
    "finance.financial_interpretation": "Interpret financial performance, margins, growth, cash flow, and balance-sheet signals.",
    "finance.stock_recommendation_aggregate": "Aggregate prior workflow findings into a stock recommendation with confidence and caveats.",
    "finance.fundamental_analysis": "Perform deep fundamental analysis: review income statement, balance sheet, and cash flow. Evaluate profitability (gross/net margins, ROE, ROA), solvency (debt-to-equity, current ratio), growth (revenue/EPS CAGR), and earnings quality. Reference actual financial data from marketDataContext.",
    "finance.technical_analysis": "Perform technical analysis: analyze price action, moving averages (MA5/10/20/60), MACD, RSI, KDJ, Bollinger Bands, volume trends, and support/resistance levels. Identify trend direction, momentum, and key technical signals.",
    "finance.valuation_analysis": "Perform valuation analysis: assess PE/PB/PS ratios vs historical averages and sector peers. Evaluate dividend yield, PEG ratio, EV/EBITDA. Determine whether the stock is overvalued, fairly valued, or undervalued relative to intrinsic value.",
    "finance.money_flow_analysis": "Analyze money flow patterns: institutional vs retail flow, net inflow/outflow trends, block trade activity, margin trading data, and foreign capital (northbound flow for A-shares) positioning changes.",
    "finance.risk_assessment": "Perform comprehensive risk assessment: identify systemic risks (macro, policy, rate), sector-specific risks, company-specific risks (governance, leverage, litigation), and black swan probability. Assign risk level and key risk factors.",
  };
  return prompts[handler] || "Pass through workflow state and produce concise structured output.";
}

function mockSummary(handler, subject, state) {
  if (handler === "start") return `Started workflow for ${subject}.`;
  if (handler === "end") return `Completed workflow for ${subject}.`;
  if (handler === "condition") return `Condition evaluated for ${subject}.`;
  if (handler === "logic") return `Logic node processed ${subject}.`;
  if (handler === "general.agent") {
    const message = typeof state?.message === "string" && state.message.trim() ? state.message.trim() : "当前问题";
    return `当前对话引擎处于模拟模式，已收到你的问题：“${message}”。请配置真实模型 API Key 后获取完整 AI 回复。`;
  }
  if (handler === "finance.stock_recommendation_aggregate") {
    const keys = Object.keys(state).filter((key) => key !== "subject");
    return `Mock aggregate recommendation for ${subject} based on ${keys.length} upstream outputs.`;
  }
  return `Mock ${handler} result for ${subject}.`;
  if (handler === "finance.fundamental_analysis") {
    return `Mock fundamental analysis for ${subject}: Revenue growth stable, margins healthy, balance sheet strong.`;
  }
  if (handler === "finance.technical_analysis") {
    return `Mock technical analysis for ${subject}: Trend neutral-to-bullish, MACD positive crossover, RSI in mid-range.`;
  }
  if (handler === "finance.valuation_analysis") {
    return `Mock valuation analysis for ${subject}: PE at sector median, PB below historical average, fair value estimate in range.`;
  }
  if (handler === "finance.money_flow_analysis") {
    return `Mock money flow analysis for ${subject}: Net institutional inflow positive, northbound capital adding positions.`;
  }
  if (handler === "finance.risk_assessment") {
    return `Mock risk assessment for ${subject}: Overall risk level moderate, key risks include market volatility and sector rotation.`;
  }
}

function mockSignals(handler, subject) {
  if (FALLBACK_HANDLERS.has(handler)) {
    return [{ name: "workflow_control", value: "pass", weight: 0.4 }];
  }
  return [
    { name: "subject", value: subject, weight: 0.3 },
    { name: handler.split(".").pop(), value: "neutral_positive", weight: 0.6 },
  ];
}

function mockSources(handler, subject) {
  return [
    {
      title: `Mock source for ${subject}`,
      url: "",
      type: HANDLERS.has(handler) ? "mock_research" : "workflow",
    },
  ];
}

function orderNodes(nodes, edges) {
  const byId = new Map(nodes.map((node) => [node.id, node]));
  const indegree = new Map(nodes.map((node) => [node.id, 0]));
  const outgoing = new Map(nodes.map((node) => [node.id, []]));
  for (const edge of edges) {
    if (!byId.has(edge.source) || !byId.has(edge.target)) continue;
    indegree.set(edge.target, (indegree.get(edge.target) || 0) + 1);
    outgoing.get(edge.source).push(edge.target);
  }
  const queue = nodes.filter((node) => (indegree.get(node.id) || 0) === 0);
  const ordered = [];
  const seen = new Set();
  while (queue.length) {
    const node = queue.shift();
    if (!node || seen.has(node.id)) continue;
    seen.add(node.id);
    ordered.push(node);
    for (const target of outgoing.get(node.id) || []) {
      indegree.set(target, (indegree.get(target) || 0) - 1);
      if ((indegree.get(target) || 0) === 0) {
        queue.push(byId.get(target));
      }
    }
  }
  for (const node of nodes) {
    if (!seen.has(node.id)) ordered.push(node);
  }
  return ordered;
}

function asArray(value) {
  return Array.isArray(value) ? value : [];
}

function extractUsage(response) {
  const usage = response?.usage_metadata || response?.response_metadata?.tokenUsage || response?.response_metadata?.usage;
  return normalizeUsage(usage);
}

function normalizeUsage(usage) {
  const prompt = numberOrNull(usage?.prompt_tokens ?? usage?.promptTokens ?? usage?.input_tokens ?? usage?.inputTokens);
  const completion = numberOrNull(usage?.completion_tokens ?? usage?.completionTokens ?? usage?.output_tokens ?? usage?.outputTokens);
  const total = numberOrNull(usage?.total_tokens ?? usage?.totalTokens);
  return {
    prompt_tokens: prompt,
    completion_tokens: completion,
    total_tokens: total ?? (prompt == null || completion == null ? null : prompt + completion),
  };
}

function emptyUsage() {
  return {
    prompt_tokens: null,
    completion_tokens: null,
    total_tokens: null,
  };
}

function numberOrNull(value) {
  const number = Number(value);
  return Number.isFinite(number) ? number : null;
}

function clampConfidence(value) {
  const number = Number(value);
  if (!Number.isFinite(number)) return 0;
  return Math.max(0, Math.min(1, number));
}

function isMockMode() {
  return String(process.env.MARKETMIND_LANGCHAIN_MOCK || "").toLowerCase() === "true";
}

function resolveApiKey(payload) {
  if (Object.prototype.hasOwnProperty.call(payload, "apiKey") && payload.apiKey) {
    return payload.apiKey;
  }
  return process.env.OPENAI_API_KEY || process.env.MARKETMIND_LANGCHAIN_API_KEY;
}

function resolveModel(requested, baseUrl) {
  const model = String(requested || process.env.MARKETMIND_LANGCHAIN_MODEL || DEFAULT_DEEPSEEK_MODEL).trim();
  if (usesDeepSeekCompatibleEndpoint(baseUrl) && !SUPPORTED_DEEPSEEK_MODELS.has(model)) {
    return SUPPORTED_DEEPSEEK_MODELS.has(process.env.MARKETMIND_LANGCHAIN_MODEL)
      ? process.env.MARKETMIND_LANGCHAIN_MODEL
      : DEFAULT_DEEPSEEK_MODEL;
  }
  return model || DEFAULT_DEEPSEEK_MODEL;
}

function usesDeepSeekCompatibleEndpoint(baseUrl) {
  return Boolean(baseUrl || process.env.MARKETMIND_LANGCHAIN_BASE_URL || process.env.OPENAI_BASE_URL)
    || SUPPORTED_DEEPSEEK_MODELS.has(process.env.MARKETMIND_LANGCHAIN_MODEL);
}

function withTimeout(promise, timeoutMs, message) {
  let timer;
  const timeout = new Promise((_, reject) => {
    timer = setTimeout(() => reject(new Error(message)), timeoutMs);
  });
  return Promise.race([promise, timeout]).finally(() => clearTimeout(timer));
}

app.listen(port, "127.0.0.1", () => {
  console.log(`Aegis Alpha orchestrator listening on http://127.0.0.1:${port}`);
});
