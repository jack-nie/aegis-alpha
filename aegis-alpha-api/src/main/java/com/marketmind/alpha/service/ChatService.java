package com.marketmind.alpha.service;

import com.marketmind.alpha.domain.AgentTemplate;
import com.marketmind.alpha.service.IntentRouterService.IntentResult;
import com.marketmind.alpha.domain.WorkflowRun;
import com.marketmind.alpha.mapper.ChatMapper;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ChatService {
    private static final Pattern TICKER_PATTERN = Pattern.compile("\\b[A-Z]{1,5}(?:[._-][A-Z])?\\b");
    private static final Set<String> SYMBOL_STOPWORDS = new HashSet<String>(Arrays.asList(
            "AI", "API", "CPI", "ETF", "GDP", "IPO", "LLM", "SEC", "USA", "USD", "CEO", "CFO", "ESG"
    ));

    /* Flexible patterns: analysis + stock name + stock/sector keywords */
    private static final Pattern STOCK_ANALYSIS_PATTERN =
            Pattern.compile("分析.{0,20}(?:股票|个股)|(?:股票|个股).{0,10}分析|分析一下.{0,20}");
    private static final Pattern SECTOR_ANALYSIS_PATTERN =
            Pattern.compile("分析.{0,20}(?:板块|行业)|(?:板块|行业).{0,10}分析");

    /* ---- the 6 valid workflow keys seeded in ExistingDataSeeder ---- */
    private static final Set<String> VALID_WORKFLOW_KEYS = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
            "daily", "deep_dive", "stock_recommendation_research", "exit_workflow",
            "portfolio_workflow", "position_workflow", "sector-analyst-workflow"
    )));

    private final ChatMapper mapper;
    private final LangChainGateway langChainGateway;
    private final MarketDataService marketDataService;
    private final WorkflowService workflowService;
    private final IntentRouterService intentRouterService;

    public ChatService(ChatMapper mapper,
                       LangChainGateway langChainGateway,
                       MarketDataService marketDataService,
                       WorkflowService workflowService,
                       IntentRouterService intentRouterService) {
        this.mapper = mapper;
        this.langChainGateway = langChainGateway;
        this.marketDataService = marketDataService;
        this.workflowService = workflowService;
        this.intentRouterService = intentRouterService;
    }

    public Object threads() {
        return mapper.threads();
    }

    public Map<String, Object> reply(Map<String, Object> body) {
        String message = text(body, "message", "");
        String threadId = UUID.randomUUID().toString();
        mapper.insertThread(threadId, title(message));
        mapper.insertMessage(UUID.randomUUID().toString(), threadId, "user", message);

        /* ---- intent-based workflow routing via LLM + DB keywords ---- */
        IntentResult intent = intentRouterService.classify(message);
        String workflowKey = intent != null ? intent.getWorkflowKey() : null;
        // explicit override from caller takes priority
        String explicit = text(body, "workflowKey", "");
        if (!explicit.isEmpty()) {
            workflowKey = explicit;
        }
        if (workflowKey != null) {
            return dispatchWorkflow(workflowKey, message, threadId, body);
        }

        /* ---- default: copilot chat ---- */
        return copilotReply(message, threadId, body);
    }

    /* ================================================================ *
     * Intent routing  –  only the 6 seeded keys are valid targets.
     * ================================================================ */

    String resolveWorkflowKey(Map<String, Object> body, String message) {
        /* explicit override from caller takes priority */
        String explicit = text(body, "workflowKey", "");
        if (!explicit.isEmpty() && VALID_WORKFLOW_KEYS.contains(explicit)) {
            return explicit;
        }

        String msg = message == null ? "" : message.toLowerCase();

        /* daily briefing */
        if (matchesAny(msg,
                "日报", "晨报", "每日", "盘前", "daily", "morning briefing", "daily graph")) {
            return "daily";
        }
        /* deep dive */
        if (matchesAny(msg,
                "深度分析", "深度研究", "深入研究", "个股分析", "股票分析", "分析个股", "分析股票", "deep dive", "deep analysis", "stock analysis", "analyze stock",
                "帮我分析", "分析一下")) {
            return "deep_dive";
        }
        /* deep dive - flexible regex: analysis + stock name + stock keywords */
        if (STOCK_ANALYSIS_PATTERN.matcher(msg).find()) {
            return "deep_dive";
        }
        /* exit workflow */
        if (matchesAny(msg,
                "止损", "止盈", "卖出", "平仓", "退出", "exit", "stop loss", "take profit", "close position")) {
            return "exit_workflow";
        }
        /* portfolio workflow */
        if (matchesAny(msg,
                "投资组合", "资产配置", "组合分析", "portfolio", "asset allocation", "portfolio workflow")) {
            return "portfolio_workflow";
        }
        /* position workflow */
        if (matchesAny(msg,
                "仓位", "持仓", "建仓", "加仓", "减仓", "头寸",
                "position sizing", "position management", "open position", "add position")) {
            return "position_workflow";
        }
        /* sector analyst */
        if (matchesAny(msg,
                "板块", "行业", "行业分析", "sector", "industry analysis", "sector analyst")) {
            return "sector-analyst-workflow";
        }
        /* sector analyst - flexible regex: analysis + sector/industry name */
        if (SECTOR_ANALYSIS_PATTERN.matcher(msg).find()) {
            return "sector-analyst-workflow";
        }

        return null;
    }

    private static boolean matchesAny(String lowerMsg, String... keywords) {
        for (String kw : keywords) {
            if (lowerMsg.contains(kw.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    /* ================================================================ *
     * Workflow dispatch
     * ================================================================ */

    private Map<String, Object> dispatchWorkflow(String workflowKey,
                                                  String message,
                                                  String threadId,
                                                  Map<String, Object> body) {
        String ticker = tickerFrom(body, objectMap(body == null ? null : body.get("state")), message);
        String subject = ticker.isEmpty() ? message : ticker;

        Map<String, Object> inputs = new LinkedHashMap<String, Object>();
        inputs.put("message", message);
        if (!ticker.isEmpty()) {
            inputs.put("ticker", ticker);
            inputs.put("symbol", ticker);
        }
        Map<String, Object> extraState = objectMap(body == null ? null : body.get("state"));
        inputs.putAll(extraState);

        WorkflowRun run;
        try {
            run = workflowService.start(workflowKey, subject, inputs);
        } catch (Exception ex) {
            /* If workflow layout is not published or invalid, fall back to copilot */
            return copilotReply(message, threadId, body);
        }

        String reply = String.format("\u5df2\u81ea\u52a8\u8def\u7531\u5230\u5de5\u4f5c\u6d41 [%s]\uff0c\u8fd0\u884c ID: %s\uff0c\u72b6\u6001: %s",
                workflowKey, run.getRunId(), run.getStatus());
        mapper.insertMessage(UUID.randomUUID().toString(), threadId, "assistant", reply);

        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("threadId", threadId);
        response.put("message", reply);
        response.put("content", reply);
        response.put("workflowKey", workflowKey);
        response.put("runId", run.getRunId());
        response.put("runStatus", run.getStatus());
        response.put("routedToWorkflow", true);
        return response;
    }

    /* ================================================================ *
     * Default copilot chat (unchanged logic)
     * ================================================================ */

    private Map<String, Object> copilotReply(String message, String threadId, Map<String, Object> body) {
        AgentTemplate copilot = new AgentTemplate();
        copilot.setAgentId("aegis-alpha-copilot");
        copilot.setName("Aegis Alpha Copilot");
        copilot.setDescription("Conversational investment research assistant.");
        copilot.setCategory("copilot");
        copilot.setTags("chat,copilot,research");
        copilot.setPrompt("\u4f60\u662f Aegis Alpha Copilot\u3002\u8bf7\u7528\u4e2d\u6587\u56de\u7b54\u7528\u6237\u7684\u6295\u8d44\u7814\u7a76\u95ee\u9898\u3002\u5fc5\u987b\u5f15\u7528\u5f53\u524d\u4e0a\u4e0b\u6587\u4e2d\u7684\u5b9e\u65f6\u884c\u60c5\u6570\u636e\uff08\u4ef7\u683c\u3001\u6da8\u8dcc\u5e45\u3001PE\u3001PB\u3001\u5e02\u503c\u7b49\uff09\u548c\u65b0\u95fb\u6807\u9898\u6765\u5206\u6790\u3002\u5982\u679c\u5e02\u573a\u6570\u636e\u4e0a\u4e0b\u6587\u5df2\u7ecf\u63d0\u4f9b\uff0c\u5fc5\u987b\u57fa\u4e8e\u8fd9\u4e9b\u6570\u636e\u7ed9\u51fa\u5177\u4f53\u5206\u6790\uff0c\u4e0d\u8981\u8bf4\u6570\u636e\u4e0d\u8db3\u3002");
        copilot.setModelName(text(body, "modelName", ""));
        copilot.setToolsJson("[\"market-data\",\"portfolio\",\"news\",\"workflow\"]");

        Map<String, Object> state = objectMap(body == null ? null : body.get("state"));
        state.put("message", message);
        String ticker = tickerFrom(body, state, message);
        if (!ticker.isEmpty()) {
            state.put("ticker", ticker);
            state.put("symbol", ticker);
            state.put("subject", ticker);
            if (!state.containsKey("marketDataOverview")) {
                state.put("marketDataOverview", marketOverview(ticker));
            }
        }
        Map<String, Object> node = copilotNode(body);
        Map<String, Object> result = langChainGateway.runAgent(copilot, state, node, ticker.isEmpty() ? "copilot chat" : ticker);
        String reply = text(result, "content", text(result, "message", ""));
        if (reply.trim().isEmpty()) {
            reply = "\u771f\u5b9e LLM \u672a\u8fd4\u56de\u5185\u5bb9\u3002";
            result.put("content", reply);
        }
        mapper.insertMessage(UUID.randomUUID().toString(), threadId, "assistant", reply);

        Map<String, Object> response = new LinkedHashMap<String, Object>(result);
        response.put("threadId", threadId);
        response.put("message", reply);
        response.put("content", reply);
        response.put("routedToWorkflow", false);
        return response;
    }

    /* ================================================================ *
     * Helpers (mostly unchanged from original)
     * ================================================================ */

    private Map<String, Object> marketOverview(String ticker) {
        try {
            return marketDataService.overview(ticker);
        } catch (Exception ex) {
            Map<String, Object> unavailable = new LinkedHashMap<String, Object>();
            unavailable.put("ok", false);
            unavailable.put("symbol", ticker);
            unavailable.put("status", "unavailable");
            unavailable.put("error", ex.getMessage() == null ? String.valueOf(ex) : ex.getMessage());
            return unavailable;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> objectMap(Object value) {
        if (value instanceof Map) {
            return new LinkedHashMap<String, Object>((Map<String, Object>) value);
        }
        return new LinkedHashMap<String, Object>();
    }

    private Map<String, Object> copilotNode(Map<String, Object> body) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("label", "Aegis Alpha Copilot");
        data.put("nodeType", "agent");
        data.put("handler", text(body, "handler", "general.agent"));
        data.put("agentId", "aegis-alpha-copilot");
        Map<String, Object> node = new LinkedHashMap<String, Object>();
        node.put("id", "aegis-alpha-copilot");
        node.put("type", "workflowNode");
        node.put("data", data);
        return node;
    }

    private static final Pattern A_SHARE_CODE_PATTERN = Pattern.compile("(\\d{6})(?:\\.(SZ|SH))?");

    private String tickerFrom(Map<String, Object> body, Map<String, Object> state, String message) {
        String direct = firstText(body, "ticker", "symbol", "code");
        if (!direct.isEmpty()) {
            return cleanTicker(direct);
        }
        String existing = firstText(state, "ticker", "symbol", "code");
        if (!existing.isEmpty()) {
            return cleanTicker(existing);
        }
        String msg = message == null ? "" : message;

        String chineseResolved = marketDataService.resolveAShareSymbolPublic(msg);
        if (chineseResolved != null && !chineseResolved.equals(msg) && !chineseResolved.isEmpty()) {
            return cleanTicker(chineseResolved);
        }

        String normalized = marketDataService.normalizeSymbolPublic(msg);
        if (normalized != null && !normalized.isEmpty()) {
            return normalized;
        }

        Matcher aShareMatcher = A_SHARE_CODE_PATTERN.matcher(msg);
        if (aShareMatcher.find()) {
            String code = aShareMatcher.group(1);
            String suffix = aShareMatcher.group(2);
            if (suffix != null) {
                return code + "." + suffix;
            }
            if (code.startsWith("6")) return code + ".SH";
            return code + ".SZ";
        }

        Matcher matcher = TICKER_PATTERN.matcher(msg);
        while (matcher.find()) {
            String candidate = cleanTicker(matcher.group());
            if (!candidate.isEmpty() && !SYMBOL_STOPWORDS.contains(candidate)) {
                return candidate;
            }
        }
        return "";
    }

    private String firstText(Map<String, Object> body, String... keys) {
        if (body == null) {
            return "";
        }
        for (String key : keys) {
            if (body.get(key) != null) {
                String value = String.valueOf(body.get(key)).trim();
                if (!value.isEmpty()) {
                    return value;
                }
            }
        }
        return "";
    }

    private String cleanTicker(String value) {
        return value == null ? "" : value.trim().toUpperCase().replaceAll("[^A-Z0-9._-]", "");
    }

    private String text(Map<String, Object> body, String key, String fallback) {
        if (body == null || body.get(key) == null) {
            return fallback;
        }
        String value = String.valueOf(body.get(key)).trim();
        return value.isEmpty() ? fallback : value;
    }

    private String title(String message) {
        if (message == null || message.trim().isEmpty()) {
            return "New Chat";
        }
        String clean = message.trim();
        return clean.length() > 24 ? clean.substring(0, 24) : clean;
    }
}
