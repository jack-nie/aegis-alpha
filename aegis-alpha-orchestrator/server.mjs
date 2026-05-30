import path from "path";
import { fileURLToPath } from "url";
import { config as dotenvConfig } from "dotenv";
dotenvConfig({ path: path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..", ".env"), override: true });


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
  "finance.peer_comparison",
  "finance.catalyst_analysis",
  "finance.thesis_builder",
  "finance.risk_reward_analysis",
  "finance.entry_strategy",
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
      process.env.MARKETMIND_LANGCHAIN_BASE_URL || process.env.OPENAI_BASE_URL,
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

app.post("/classify-intent", async (req, res) => {
  try {
    const result = await classifyIntent(req.body || {});
    res.json(result);
  } catch (error) {
    res.json({ workflowKey: null, ticker: null, confidence: 0, error: error?.message || String(error) });
  }
});

async function classifyIntent(payload) {
  const { message, workflows } = payload;
  if (!message || !Array.isArray(workflows) || workflows.length === 0) {
    return { workflowKey: null, ticker: null, confidence: 0, reason: "Missing message or workflows" };
  }

  const apiKey = payload.apiKey || process.env.OPENAI_API_KEY || process.env.MARKETMIND_LANGCHAIN_API_KEY;
  const baseUrl = payload.baseUrl || process.env.MARKETMIND_LANGCHAIN_BASE_URL || process.env.OPENAI_BASE_URL;
  const model = resolveModel(payload.model || process.env.MARKETMIND_LANGCHAIN_MODEL, baseUrl);

  if (!apiKey || isMockMode()) {
    return keywordFallback(message, workflows);
  }

  const tools = workflows.map((wf) => ({
    type: "function",
    function: {
      name: "run_" + (wf.workflowKey || "unknown").replace(/-/g, "_"),
      description: wf.routingDescription || wf.name || "Execute workflow",
      parameters: {
        type: "object",
        properties: {
          ticker: {
            type: "string",
            description: "Stock ticker or symbol mentioned by the user (e.g. AAPL, 600519.SH). Empty string if not applicable.",
          },
        },
        required: ["ticker"],
      },
    },
  }));

  try {
    const chat = new ChatOpenAI({
      apiKey,
      model,
      temperature: 0,
      timeout: 5000,
      maxRetries: 0,
      configuration: baseUrl ? { baseURL: baseUrl } : undefined,
    });

    const response = await chat.invoke(
      [
        new SystemMessage(
          "You are an intent classifier. Based on the user message, call exactly ONE function that best matches the user's intent. " +
          "If no function matches, do not call any function. Always respond with a function call or empty response."
        ),
        new HumanMessage(message),
      ],
      { tools, tool_choice: "auto" }
    );

    const toolCalls = response?.additional_kwargs?.tool_calls;
    if (Array.isArray(toolCalls) && toolCalls.length > 0) {
      const call = toolCalls[0];
      const funcName = call?.function?.name || "";
      const workflowKey = funcName.replace(/^run_/, "").replace(/_/g, "-");
      let ticker = "";
      try {
        const args = JSON.parse(call.function.arguments || "{}");
        ticker = args.ticker || "";
      } catch (_) {}
      const matched = workflows.find((wf) => (wf.workflowKey || "").replace(/-/g, "_") === funcName.replace(/^run_/, ""));
      return {
        workflowKey: matched ? matched.workflowKey : workflowKey,
        ticker,
        confidence: 0.9,
        source: "llm_function_calling",
      };
    }

    return { workflowKey: null, ticker: null, confidence: 0, source: "llm_no_match" };
  } catch (error) {
    return keywordFallback(message, workflows);
  }
}

function keywordFallback(message, workflows) {
  if (!message) return { workflowKey: null, ticker: null, confidence: 0, source: "keyword_no_message" };
  const lower = message.toLowerCase();
  let bestMatch = null;
  let bestScore = 0;
  for (const wf of workflows) {
    const keywords = (wf.triggerKeywords || "").split(",").map((k) => k.trim().toLowerCase()).filter(Boolean);
    for (const kw of keywords) {
      if (lower.includes(kw) && kw.length > bestScore) {
        bestScore = kw.length;
        bestMatch = wf.workflowKey;
      }
    }
  }
  return {
    workflowKey: bestMatch,
    ticker: null,
    confidence: bestMatch ? 0.6 : 0,
    source: "keyword_fallback",
  };
}

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

    const llm = await invokeModel({
      payload: { ...payload, state: hydratedState },
      state: hydratedState,
      subject,
      handler,
      apiKey,
      baseUrl,
      model,
    });
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
  const baseTimeout = Number(process.env.MARKETMIND_LANGCHAIN_TIMEOUT_MS || 25000);
  const timeoutMs = handler === "finance.stock_recommendation_aggregate" ? Math.max(baseTimeout, 60000) : baseTimeout;
  const chat = new ChatOpenAI({
    apiKey,
    model,
    temperature: 0.2,
    timeout: timeoutMs,
    maxRetries: 0,
    configuration: baseUrl ? { baseURL: baseUrl } : undefined,
  });
  const isAggregateNode = handler === "finance.stock_recommendation_aggregate";
  const system = isAggregateNode
    ? [
        `You are ${agent.name || data.label || "Aegis Alpha Agent"}.`,
        "You are a senior investment research analyst producing a comprehensive stock deep-dive report.",
        "Return ONLY a JSON object. The summary field MUST contain a complete Markdown-formatted deep analysis report.",
        'The JSON shape is {"summary":"Markdown report here","signals":[{"name":"string","value":"string","weight":0.5}],"sources":[{"title":"string","url":"string","type":"string"}],"confidence":0.5,"data":{}}.',
        "The summary must follow the exact template structure provided in the user prompt. Include ALL sections: 公司概览, 行情与估值, 技术面分析, 基本面分析, 主要风险, 总结判断.",
        "Use actual data values from the workflow state (price, PE, PB, market cap, news, financials). Never say data is insufficient.",
      ].join("\n")
    : [
        `You are ${agent.name || data.label || "Aegis Alpha Agent"}.`,
        "Return only JSON. Do not use markdown.",
        'The JSON shape is {"summary":"string","signals":[{"name":"string","value":"string","weight":0.5}],"sources":[{"title":"string","url":"string","type":"string"}],"confidence":0.5,"data":{}}.',
      ].join("\n");
  const contextLimit = isAggregateNode ? 30000 : 12000;
  const context = JSON.stringify({ subject, state, node: data, handler }).slice(0, contextLimit);
  const prompt = data.prompt || agent.prompt || promptForHandler(handler);
  const response = await withTimeout(
    chat.invoke([new SystemMessage(system), new HumanMessage(`${prompt}\n\nWorkflow context:\n${context}`)]),
    timeoutMs,
    `OpenAI request timed out after ${timeoutMs}ms`,
  );
  return normalizeLlmContent(response.content, extractUsage(response));
}

function normalizeLlmContent(content, usage = emptyUsage()) {
  const rawContent = typeof content === "string" ? content : JSON.stringify(content);
  const cleaned = rawContent
    .trim()
    .replace(/^```(?:json)?/i, "")
    .replace(/```$/i, "")
    .trim();
  try {
    const parsed = JSON.parse(cleaned);
    const summary = String(parsed.summary || parsed.content || "Model returned structured output.");
    return {
      summary,
      signals: asArray(parsed.signals),
      sources: asArray(parsed.sources),
      confidence: clampConfidence(parsed.confidence),
      data: {
        ...(typeof parsed.data === "object" && parsed.data !== null ? parsed.data : { parsed }),
        usage,
      },
    };
  } catch {
    // If JSON parsing fails, check if the content itself is a markdown report
    // (common for aggregate nodes that produce rich markdown output)
    const looksLikeMarkdown =
      cleaned.includes("# ") || cleaned.includes("## ") || cleaned.includes("**") || cleaned.includes("| ");
    if (looksLikeMarkdown && cleaned.length > 200) {
      return {
        summary: cleaned,
        signals: [],
        sources: [],
        confidence: 0.7,
        data: { format: "markdown", usage },
      };
    }
    // Try to extract JSON from within the content (LLM may wrap JSON in explanatory text)
    const jsonMatch = cleaned.match(/\{[\s\S]*"summary"[\s\S]*\}/);
    if (jsonMatch) {
      try {
        const parsed = JSON.parse(jsonMatch[0]);
        return {
          summary: String(parsed.summary || parsed.content || cleaned.slice(0, 500)),
          signals: asArray(parsed.signals),
          sources: asArray(parsed.sources),
          confidence: clampConfidence(parsed.confidence),
          data: {
            ...(typeof parsed.data === "object" && parsed.data !== null ? parsed.data : { parsed }),
            usage,
          },
        };
      } catch {
        /* fall through */
      }
    }
    return {
      summary: cleaned.slice(0, 2000) || "Model returned empty content.",
      signals: [],
      sources: [],
      confidence: 0.5,
      data: { rawContent: cleaned.slice(0, 500), usage },
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
  const nodeType = String(data.nodeType || data.type || "")
    .trim()
    .toLowerCase();
  return (
    HANDLERS.has(handler) && nodeType && nodeType !== "agent" && handler !== "finance.stock_recommendation_aggregate"
  );
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
  const backendUrl = String(
    process.env.MARKETMIND_BACKEND_URL || process.env.MARKETMIND_NODE_CALLBACK_BASE_URL || DEFAULT_BACKEND_URL,
  ).replace(/\/+$/, "");
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
    "finance.market_analysis": `Analyze market conditions for the subject stock. Return JSON with these fields in data:
- "market_cap": total market capitalization with currency
- "pe_ratio": TTM P/E ratio
- "pb_ratio": price-to-book ratio
- "dividend_yield": dividend yield percentage
- "eps": earnings per share
- "book_value_per_share": book value per share
- "week52_high": 52-week high price
- "week52_low": 52-week low price
- "current_price": latest closing price
- "price_change_pct": recent price change percentage
- "sector": industry sector
- "exchange": stock exchange
Use actual data from marketDataContext if available. Answer in Chinese.`,

    "finance.industry_share": `Analyze the industry position and competitive landscape for the subject stock. Return JSON with these fields in data:
- "industry": specific industry name (e.g. "覆铜板/PCB材料")
- "company_description": 2-3 sentence company overview including what it does, headquarters, and key products
- "market_position": company's position in the industry (leader/strong/challenger/niche)
- "main_competitors": list of top 3-5 competitors
- "competitive_advantages": key moats and advantages
- "industry_growth_rate": industry growth rate or trend
- "chairman": chairman or CEO name if known
Answer in Chinese.`,

    "finance.sentiment_monitor": `Assess market sentiment for the subject stock. Return JSON with these fields in data:
- "sentiment_score": overall sentiment score 1-10 (10=extremely bullish)
- "analyst_consensus": analyst consensus (买入/增持/中性/减持/卖出)
- "institutional_sentiment": institutional investor sentiment description
- "retail_sentiment": retail investor sentiment
- "media_tone": media coverage tone (positive/neutral/negative)
- "recent_events": list of 2-3 recent events affecting sentiment
Answer in Chinese.`,

    "finance.tech_breakthrough": `Identify recent technology or product breakthroughs for the subject stock. Return JSON with these fields in data:
- "breakthroughs": list of objects with "date", "title", "description" for each breakthrough
- "r_and_d_spending": R&D spending trend if available
- "patent_count": recent patent filings or count
- "product_pipeline": key upcoming products or services
- "technology_moat": description of technology competitive advantage
Answer in Chinese.`,

    "finance.industry_news": `Summarize the most important recent industry news for the subject stock. Return JSON with these fields in data:
- "news_items": list of objects with "date", "title", "summary" (3-5 most important news)
- "policy_impact": any recent policy or regulatory changes affecting the industry
- "global_trends": relevant global industry trends
Answer in Chinese.`,

    "general.agent": `You are a senior investment research analyst. Use the marketDataContext (quote, financials, news) in the workflow state to provide a concrete, data-rich analysis. Always reference actual data values (price, PE, PB, market cap, news titles) in your response. Do NOT say data is insufficient if marketDataContext is present. Provide specific numbers and percentages. Answer in Chinese.`,

    "general.web_search":
      "Find concise web-search style evidence relevant to the subject. Return key facts, data points, and sources.",

    "finance.financial_interpretation": `Interpret the financial performance for the subject stock. Return JSON with these fields in data:
- "revenue": latest revenue with currency and period
- "revenue_growth_yoy": revenue year-over-year growth percentage
- "net_profit": latest net profit
- "net_profit_growth_yoy": net profit year-over-year growth percentage
- "gross_margin": gross margin percentage
- "net_margin": net margin percentage
- "roe": return on equity percentage
- "debt_to_equity": debt-to-equity ratio
- "operating_cash_flow": operating cash flow
- "earnings_quality": assessment of earnings quality (high/medium/low)
Answer in Chinese.`,

    "finance.stock_recommendation_aggregate": `你是资深投资研究分析师。请从上游工作流节点的输出中提取所有数据，生成一份完整的股票深度分析报告。

**必须按照以下模板格式输出 summary（使用 Markdown 格式）：**

# {股票名称}（{代码}）深度分析

## 公司概览
{公司简介：主营业务、总部、产品应用领域}
行业：{行业分类}
代码：{交易所代码}
董事长/CEO：{姓名}

## 行情与估值（截至 {日期}）
| 指标 | 数据 |
|------|------|
| 最新股价 | {价格}（{涨跌幅}） |
| 总市值 | {市值} |
| TTM市盈率 | {PE} |
| 市净率（PB） | {PB} |
| 股息率 | {股息率} |
| 每股净资产 | {数值} |
| 52周区间 | {低} – {高} |

## 技术面分析
| 指标 | 数值 | 信号 |
|------|------|------|
| 布林带中轨 | {数值} | {信号} |
| RSI | {数值} | {信号} |
| MACD | {数值} | {信号} |
趋势判断：{多头/空头/震荡}，{具体描述}

## 基本面分析
### 最新业绩亮点
| 指标 | 数值 | 同比变化 |
|------|------|----------|
| 营业收入 | {数值} | {变化} |
| 归母净利润 | {数值} | {变化} |
| 扣非净利润 | {数值} | {变化} |

### 核心催化剂
{列出2-3个核心增长驱动力}

## 主要风险
| 风险因素 | 说明 |
|----------|------|
| {风险1} | {说明} |
| {风险2} | {说明} |
| {风险3} | {说明} |

## 总结判断
{综合基本面、技术面、估值面的判断，2-3段}
建议：{具体投资建议}

注：本分析基于公开数据，不构成投资建议。

**重要：如果上游节点提供了具体数据（价格、PE、PB、新闻标题等），必须在报告中引用。不要说"数据不足"。**`,

    "finance.fundamental_analysis": `Perform deep fundamental analysis for the subject stock. Return JSON with these fields in data:
- "revenue": latest quarterly/annual revenue with currency
- "revenue_growth_yoy": revenue YoY growth percentage
- "net_profit": latest net profit
- "net_profit_growth_yoy": net profit YoY growth percentage
- "deducted_net_profit": deducted (扣非) net profit if available
- "gross_margin": gross margin percentage
- "net_margin": net margin percentage
- "roe": return on equity percentage
- "roa": return on assets percentage
- "debt_to_equity": debt-to-equity ratio
- "current_ratio": current ratio
- "operating_cash_flow": operating cash flow
- "eps": earnings per share
- "book_value_per_share": book value per share
- "business_segments": key business segment performance
Reference actual financial data from marketDataContext. Answer in Chinese.`,

    "finance.technical_analysis": `Perform technical analysis for the subject stock. Return JSON with these fields in data:
- "current_price": latest closing price
- "ma5": 5-day moving average
- "ma10": 10-day moving average
- "ma20": 20-day moving average
- "ma60": 60-day moving average
- "rsi": RSI value (0-100)
- "macd_signal": MACD signal (bullish/bearish/divergence)
- "kdj_signal": KDJ signal
- "bollinger_upper": Bollinger upper band
- "bollinger_middle": Bollinger middle band
- "bollinger_lower": Bollinger lower band
- "volume_trend": volume trend (increasing/decreasing/stable)
- "support_level": key support price
- "resistance_level": key resistance price
- "trend": overall trend (strong_upward/upward/neutral/downward/strong_downward)
- "overbought_oversold": overbought/oversold/neutral assessment
Answer in Chinese.`,

    "finance.valuation_analysis": `Perform valuation analysis for the subject stock. Return JSON with these fields in data:
- "pe_ttm": TTM P/E ratio
- "pb": price-to-book ratio
- "ps": price-to-sales ratio if available
- "peg": PEG ratio if available
- "ev_ebitda": EV/EBITDA if available
- "dividend_yield": dividend yield percentage
- "pe_sector_median": sector median PE for comparison
- "pb_sector_median": sector median PB for comparison
- "pe_historical_avg": historical average PE
- "valuation_assessment": overvalued/fairly_valued/undervalued
- "week52_high": 52-week high
- "week52_low": 52-week low
- "price_to_52w_high_pct": current price vs 52w high percentage
Answer in Chinese.`,

    "finance.money_flow_analysis": `Analyze money flow patterns for the subject stock. Return JSON with these fields in data:
- "net_inflow": net capital inflow/outflow amount
- "main_force_flow": main force (主力) net flow direction and amount
- "retail_flow": retail investor flow direction
- "northbound_flow": northbound (北向) capital flow if A-share
- "margin_trading": margin trading balance and change
- "block_trade": recent block trade activity
- "flow_trend": 5-day flow trend (inflow/outflow/mixed)
- "institutional_activity": institutional buying/selling description
Answer in Chinese.`,

    "finance.risk_assessment": `Perform comprehensive risk assessment for the subject stock. Return JSON with these fields in data:
- "overall_risk_level": risk level (低/中低/中/中高/高)
- "valuation_risk": valuation risk description
- "cycle_risk": industry cycle risk description
- "competition_risk": competition intensification risk
- "macro_risk": macroeconomic risk factors
- "policy_risk": policy/regulatory risk
- "governance_risk": corporate governance risk
- "risk_factors": list of 3-5 key risk factors with descriptions
- "risk_level_label": concise risk label for display
Answer in Chinese.`,

    "finance.peer_comparison": `You are an industry peer comparison analyst. Analyze the subject stock against its top competitors using data from the workflow state and marketDataContext.

Return JSON with these fields in data:
- "peers": list of 3-5 competitor objects, each with "name", "code", "market_cap", "pe", "revenue_growth_pct", "gross_margin_pct"
- "subject_position": company position in industry ("龙头"/"第二梯队"/"跟随者")
- "relative_valuation": relative to peers ("高估"/"合理"/"低估")
- "competitive_advantages": list of 2-3 key competitive advantages
- "competitive_weaknesses": list of 1-2 competitive weaknesses
- "peer_avg_pe": peer group average PE ratio (number)
- "peer_avg_pb": peer group average PB ratio (number)
Use actual data from marketDataContext and upstream industry_news. Answer in Chinese.`,

    "finance.catalyst_analysis": `You are a catalyst and event-driven analyst. Identify upcoming catalysts and recent events that could move the subject stock.

Consume upstream data: tech_breakthrough (technology breakthroughs), industry_news (industry news), and marketDataContext.

Return JSON with these fields in data:
- "upcoming_catalysts": list of objects with "event", "expected_date", "impact" ("利好"/"利空"), "impact_score" (1-10)
- "recent_positive": list of recent positive events (strings)
- "recent_negative": list of recent negative events (strings)
- "earnings_date": next earnings report date if known
- "catalyst_score": overall catalyst score 1-10
- "event_risk": upcoming risk events description
Use actual data from upstream nodes. Answer in Chinese.`,

    "finance.thesis_builder": `You are an investment thesis builder. Construct bull and bear cases with evidence from the workflow state.

Consume upstream data: risk_assessment output, and state entries for fundamental_analysis, technical_analysis, valuation_analysis, marketDataContext.

Return JSON with these fields in data:
- "bull_case": list of 3-4 bull arguments, each must reference specific data (price, PE, growth %, etc.)
- "bear_case": list of 3-4 bear arguments, each must reference specific data
- "base_case": base case scenario description (2-3 sentences)
- "base_case_probability": base case probability as percentage (number)
- "key_metrics_to_watch": list of 3-5 key metrics to monitor
- "investment_horizon": "短线" / "中线" / "长线"
- "conviction_level": conviction level 1-10 (number)
Answer in Chinese.`,

    "finance.risk_reward_analysis": `You are a risk-reward analyst. Calculate upside/downside targets and position sizing.

Consume upstream data: peer_comp (peer comparison with valuation data), catalysts (catalyst analysis with event scores).

Return JSON with these fields in data:
- "bullish_target": bullish price target (number)
- "bearish_target": bearish price target (number)
- "current_price": current stock price (number)
- "upside_pct": upside percentage (number)
- "downside_pct": downside percentage (number)
- "risk_reward_ratio": risk-reward ratio (number)
- "expected_value": expected return percentage (number)
- "suggested_position_pct": suggested portfolio position percentage 1-100 (number)
- "confidence": confidence level 1-10 (number)
Use actual price data from marketDataContext. Answer in Chinese.`,

    "finance.entry_strategy": `You are an entry strategy analyst. Define precise entry zones, stop-loss, and take-profit levels.

Consume upstream data: thesis (investment thesis with bull/bear cases and horizon), marketDataContext (current price, 52-week range).

Return JSON with these fields in data:
- "entry_zone_low": entry zone lower bound price (number)
- "entry_zone_high": entry zone upper bound price (number)
- "entry_signal": entry signal description ("突破"/"回踩"/"放量" etc.)
- "stop_loss": stop-loss price level (number)
- "stop_loss_pct": stop-loss percentage (number)
- "take_profit_1": first take-profit target (number)
- "take_profit_2": second take-profit target (number)
- "position_sizing": "轻仓" / "标准" / "重仓"
- "holding_period": suggested holding period
- "entry_timing": "立即" / "等待回调" / "观望"
Use actual price data from marketDataContext. Answer in Chinese.`,
  };
  return prompts[handler] || "Pass through workflow state and produce concise structured output. Answer in Chinese.";
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
  if (handler === "finance.peer_comparison") {
    return "Peer comparison for " + subject + ": Subject trades at premium to sector median, competitive position strong with 2 key moats identified. Peer avg PE 25x vs subject 30x.";
  }
  if (handler === "finance.catalyst_analysis") {
    return "Catalyst analysis for " + subject + ": 3 upcoming catalysts identified including earnings report and product launch. Overall catalyst score 7/10. Next earnings in 45 days.";
  }
  if (handler === "finance.thesis_builder") {
    return "Investment thesis for " + subject + ": Bull case - revenue growth 20%+ and margin expansion. Bear case - valuation stretched at 30x PE. Base case probability 60%. Conviction 7/10.";
  }
  if (handler === "finance.risk_reward_analysis") {
    return "Risk-reward for " + subject + ": Bullish target +25%, bearish target -10%, risk-reward ratio 2.5:1. Suggested position 5% of portfolio. Confidence 7/10.";
  }
  if (handler === "finance.entry_strategy") {
    return "Entry strategy for " + subject + ": Enter on pullback to support zone. Stop loss below key level at -8%. First target at resistance (+15%). Standard position sizing. Hold 3-6 months.";
  }
  return "Mock " + handler + " result for " + subject + ".";
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
  const usage =
    response?.usage_metadata || response?.response_metadata?.tokenUsage || response?.response_metadata?.usage;
  return normalizeUsage(usage);
}

function normalizeUsage(usage) {
  const prompt = numberOrNull(usage?.prompt_tokens ?? usage?.promptTokens ?? usage?.input_tokens ?? usage?.inputTokens);
  const completion = numberOrNull(
    usage?.completion_tokens ?? usage?.completionTokens ?? usage?.output_tokens ?? usage?.outputTokens,
  );
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
  return (
    Boolean(baseUrl || process.env.MARKETMIND_LANGCHAIN_BASE_URL || process.env.OPENAI_BASE_URL) ||
    SUPPORTED_DEEPSEEK_MODELS.has(process.env.MARKETMIND_LANGCHAIN_MODEL)
  );
}

function withTimeout(promise, timeoutMs, message) {
  let timer;
  const timeout = new Promise((_, reject) => {
    timer = setTimeout(() => reject(new Error(message)), timeoutMs);
  });
  return Promise.race([promise, timeout]).finally(() => clearTimeout(timer));
}

// Start server only when run directly
const _isDirectRun = fileURLToPath(import.meta.url) === process.argv[1];
if (_isDirectRun) {
  app.listen(port, '127.0.0.1', () => {
    console.log('Aegis Alpha orchestrator listening on http://127.0.0.1:' + port);
  });
}


// --- Test exports ---
export { HANDLERS, promptForHandler, mockSummary };
