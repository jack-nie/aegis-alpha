package com.aegis.alpha.service;

import com.aegis.alpha.domain.AgentTemplate;
import com.aegis.alpha.domain.WorkflowRun;
import com.aegis.alpha.mapper.ChatMapper;
import com.aegis.alpha.service.help.HelpAnswerCacheService;
import com.aegis.alpha.service.help.HelpIntent;
import com.aegis.alpha.service.help.HelpRoutingService;
import com.aegis.alpha.service.help.HelpService;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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

    /* Seeded keys from ExistingDataSeeder (+ research Phase 1 add-ons) */
    private static final Set<String> VALID_WORKFLOW_KEYS = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
            "daily", "deep_dive", "stock_analysis", "stock_recommendation_research", "exit_workflow",
            "portfolio_workflow", "position_workflow", "sector-analyst-workflow",
            "earnings_reaction", "watchlist_digest"
    )));

    private final ChatMapper mapper;
    private final LangChainGateway langChainGateway;
    private final MarketDataService marketDataService;
    private final WorkflowService workflowService;
    private final HelpRoutingService helpRoutingService;
    private final HelpService helpService;
    private final HelpAnswerCacheService helpAnswerCacheService;

    public ChatService(ChatMapper mapper,
                       LangChainGateway langChainGateway,
                       MarketDataService marketDataService,
                       WorkflowService workflowService,
                       HelpRoutingService helpRoutingService,
                       HelpService helpService,
                       HelpAnswerCacheService helpAnswerCacheService) {
        this.mapper = mapper;
        this.langChainGateway = langChainGateway;
        this.marketDataService = marketDataService;
        this.workflowService = workflowService;
        this.helpRoutingService = helpRoutingService;
        this.helpService = helpService;
        this.helpAnswerCacheService = helpAnswerCacheService;
    }

    /** Backward-compatible constructor for older unit tests. */
    public ChatService(ChatMapper mapper,
                       LangChainGateway langChainGateway,
                       MarketDataService marketDataService,
                       WorkflowService workflowService,
                       HelpRoutingService helpRoutingService,
                       HelpService helpService) {
        this(mapper, langChainGateway, marketDataService, workflowService, helpRoutingService, helpService, null);
    }

    public Object threads() {
        return mapper.threads();
    }

    public Map<String, Object> reply(Map<String, Object> body) {
        String message = text(body, "message", "");
        String threadId = resolveThreadId(body);
        mapper.insertThread(threadId, title(message));
        mapper.insertMessage(UUID.randomUUID().toString(), threadId, "user", message);

        HelpIntent.PageContext pageContext = HelpIntent.PageContext.fromMap(objectMap(body == null ? null : body.get("pageContext")));

        /* Safety refusals first (no side effects) */
        Map<String, Object> safety = helpService.safetyRefuse(threadId, message, null);
        if (safety != null) {
            mergeDomainIfMissing(safety, HelpIntent.Domain.PRODUCT_HELP, HelpIntent.RouteAction.PLAYBOOK);
            persistAssistant(threadId, text(safety, "content", ""));
            return safety;
        }

        /* Explicit workflowKey override takes priority */
        String explicit = text(body, "workflowKey", "");
        if (!explicit.isEmpty()) {
            Map<String, Object> response = dispatchWorkflow(explicit, message, threadId, body);
            decorateResearch(response, explicit);
            return response;
        }

        HelpIntent.Result intent = helpRoutingService.classifyExtended(message, pageContext);
        applyPreferredDomain(body, intent);

        /* Public help answer cache (entity-gated; never private run/portfolio) */
        if (helpAnswerCacheService != null) {
            Map<String, Object> cached = helpAnswerCacheService.getIfAllowed(message, intent);
            if (cached != null) {
                cached.put("threadId", threadId);
                cached.put("cacheHit", true);
                persistAssistant(threadId, text(cached, "content", ""));
                return cached;
            }
        }

        List<String> roles = principalRoles(body);
        Map<String, Object> helpResponse = null;

        switch (intent.getRouteAction()) {
            case DISPATCH_WORKFLOW:
                if (intent.getWorkflowKey() != null && !intent.getWorkflowKey().isEmpty()) {
                    Map<String, Object> response = dispatchWorkflow(intent.getWorkflowKey(), message, threadId, body);
                    decorateFromIntent(response, intent);
                    return response;
                }
                break;
            case CLARIFY:
                Map<String, Object> clarify = helpService.clarify(threadId, intent);
                persistAssistant(threadId, text(clarify, "content", ""));
                return clarify;
            case PLAYBOOK: {
                helpResponse = helpService.answerFromPlaybook(threadId, message, intent);
                if (helpResponse == null) {
                    helpResponse = helpService.answerFromKnowledge(threadId, message, intent, roles);
                }
                if (helpResponse == null) {
                    helpResponse = helpService.answerFromHelpLoop(threadId, message, intent, roles);
                }
                break;
            }
            case HELP_GRAPH:
                if (intent.getDomain() == HelpIntent.Domain.RUN_DIAGNOSIS) {
                    Map<String, Object> diagnosis = helpService.diagnoseRun(threadId, message, intent);
                    persistAssistant(threadId, text(diagnosis, "content", ""));
                    return diagnosis;
                }
                helpResponse = helpService.answerFromPlaybook(threadId, message, intent);
                if (helpResponse == null) {
                    helpResponse = helpService.answerFromHelpLoop(threadId, message, intent, roles);
                }
                break;
            case KNOWLEDGE_RAG:
                helpResponse = helpService.answerFromKnowledge(threadId, message, intent, roles);
                if (helpResponse == null) {
                    helpResponse = helpService.answerFromHelpLoop(threadId, message, intent, roles);
                }
                break;
            case COPILOT:
            default:
                break;
        }

        if (helpResponse != null) {
            if (helpAnswerCacheService != null) {
                helpAnswerCacheService.putIfAllowed(message, intent, helpResponse);
            }
            persistAssistant(threadId, text(helpResponse, "content", ""));
            return helpResponse;
        }

        Map<String, Object> copilot = copilotReply(message, threadId, body);
        decorateFromIntent(copilot, intent);
        return copilot;
    }

    @SuppressWarnings("unchecked")
    private List<String> principalRoles(Map<String, Object> body) {
        if (body == null) {
            return Collections.emptyList();
        }
        Object roles = body.get("roles");
        if (roles instanceof List) {
            List<String> out = new java.util.ArrayList<String>();
            for (Object r : (List<?>) roles) {
                if (r != null) {
                    out.add(String.valueOf(r));
                }
            }
            return out;
        }
        String single = text(body, "role", "");
        if (!single.isEmpty()) {
            return Collections.singletonList(single);
        }
        return Collections.emptyList();
    }

    private void applyPreferredDomain(Map<String, Object> body, HelpIntent.Result intent) {
        String preferred = text(body, "preferredDomain", "");
        if (preferred.isEmpty() || intent == null) {
            return;
        }
        try {
            HelpIntent.Domain domain = HelpIntent.Domain.valueOf(preferred.trim().toUpperCase(Locale.ROOT));
            if (domain == HelpIntent.Domain.PRODUCT_HELP
                    && intent.getRouteAction() == HelpIntent.RouteAction.COPILOT) {
                intent.setDomain(domain);
                intent.setRouteAction(HelpIntent.RouteAction.PLAYBOOK);
            } else if (domain == HelpIntent.Domain.RUN_DIAGNOSIS
                    && intent.getRouteAction() == HelpIntent.RouteAction.COPILOT) {
                intent.setDomain(domain);
                if (intent.getEntities() == null || intent.getEntities().getRunId() == null) {
                    intent.setRouteAction(HelpIntent.RouteAction.CLARIFY);
                    intent.setNeedsClarification(true);
                    intent.setClarificationPrompt("请提供运行 ID（runId），或从「运行中心」打开对应 run 后再问我。");
                } else {
                    intent.setRouteAction(HelpIntent.RouteAction.HELP_GRAPH);
                }
            }
        } catch (IllegalArgumentException ignored) {
            // ignore invalid preferredDomain
        }
    }

    private String resolveThreadId(Map<String, Object> body) {
        String existing = text(body, "threadId", "");
        if (!existing.isEmpty()) {
            return existing;
        }
        return UUID.randomUUID().toString();
    }

    private void persistAssistant(String threadId, String content) {
        mapper.insertMessage(UUID.randomUUID().toString(), threadId, "assistant",
                content == null ? "" : content);
    }

    private void decorateFromIntent(Map<String, Object> response, HelpIntent.Result intent) {
        if (response == null || intent == null) {
            return;
        }
        Map<String, Object> envelope = intent.toEnvelopeFields();
        for (Map.Entry<String, Object> entry : envelope.entrySet()) {
            if (!response.containsKey(entry.getKey()) || response.get(entry.getKey()) == null) {
                response.put(entry.getKey(), entry.getValue());
            }
        }
        if (!response.containsKey("provider") || response.get("provider") == null) {
            if (Boolean.TRUE.equals(response.get("routedToWorkflow"))) {
                response.put("provider", "workflow-router");
            } else {
                response.put("provider", "copilot");
            }
        }
    }

    private void decorateResearch(Map<String, Object> response, String workflowKey) {
        if (response == null) {
            return;
        }
        response.put("domain", HelpIntent.Domain.RESEARCH.name());
        response.put("complexity", HelpIntent.Complexity.L2.name());
        response.put("routeAction", HelpIntent.RouteAction.DISPATCH_WORKFLOW.name());
        response.put("provider", "workflow-router");
        if (workflowKey != null) {
            response.put("workflowKey", workflowKey);
        }
    }

    private void mergeDomainIfMissing(Map<String, Object> response,
                                      HelpIntent.Domain domain,
                                      HelpIntent.RouteAction action) {
        if (!response.containsKey("domain")) {
            response.put("domain", domain.name());
        }
        if (!response.containsKey("routeAction")) {
            response.put("routeAction", action.name());
        }
        if (!response.containsKey("complexity")) {
            response.put("complexity", HelpIntent.Complexity.L0.name());
        }
    }

    /* ================================================================ *
     * Intent routing  –  only the seeded keys are valid targets.
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
        /* earnings reaction */
        if (matchesAny(msg,
                "earnings", "财报", "业绩", "季报", "earnings reaction", "earnings call", "业绩会", "财报解读")) {
            return "earnings_reaction";
        }
        /* watchlist morning digest */
        if (matchesAny(msg,
                "digest", "早报", "自选", "watchlist", "morning digest", "自选股", "晨间摘要", "watchlist digest")) {
            return "watchlist_digest";
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
        Map<String, Object> pageContext = objectMap(body == null ? null : body.get("pageContext"));
        if (!pageContext.isEmpty()) {
            inputs.put("pageContext", pageContext);
        }

        WorkflowRun run;
        try {
            run = workflowService.createRun(workflowKey, subject, inputs);
        } catch (Exception ex) {
            /* If workflow layout is not published or invalid, fall back to copilot */
            Map<String, Object> fallback = copilotReply(message, threadId, body);
            fallback.put("domain", HelpIntent.Domain.RESEARCH.name());
            fallback.put("routeAction", HelpIntent.RouteAction.COPILOT.name());
            return fallback;
        }

        final String runId = run.getRunId();
        final Map<String, Object> safeInputs = new LinkedHashMap<>(inputs);
        CompletableFuture.runAsync(() -> workflowService.executeAsync(run, safeInputs, workflowKey));

        String reply = String.format("\u5df2\u81ea\u52a8\u8def\u7531\u5230\u5de5\u4f5c\u6d41 [%s]\uff0c\u8fd0\u884c ID: %s\uff0c\u72b6\u6001: %s",
                workflowKey, runId, "RUNNING");
        mapper.insertMessage(UUID.randomUUID().toString(), threadId, "assistant", reply);

        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("threadId", threadId);
        response.put("message", reply);
        response.put("content", reply);
        response.put("workflowKey", workflowKey);
        response.put("runId", runId);
        response.put("runStatus", "RUNNING");
        response.put("routedToWorkflow", true);
        response.put("ok", true);
        response.put("domain", HelpIntent.Domain.RESEARCH.name());
        response.put("complexity", HelpIntent.Complexity.L2.name());
        response.put("routeAction", HelpIntent.RouteAction.DISPATCH_WORKFLOW.name());
        response.put("provider", "workflow-router");
        response.put("needsClarification", false);
        response.put("escalateHuman", false);
        response.put("cacheHit", false);
        return response;
    }

    /* ================================================================ *
     * Default copilot chat
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
        response.put("ok", true);
        response.put("provider", "copilot");
        response.put("needsClarification", false);
        response.put("escalateHuman", false);
        response.put("cacheHit", false);
        return response;
    }

    /* ================================================================ *
     * Helpers
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

        // Resolve Chinese names inside free-text (e.g. "分析一下生益科技" -> 600183.SH)
        String chineseResolved = marketDataService.resolveAShareSymbolPublic(msg);
        if (chineseResolved != null && !chineseResolved.trim().isEmpty()
                && !chineseResolved.trim().equals(msg.trim())) {
            String cleaned = cleanTicker(chineseResolved);
            if (!cleaned.isEmpty()) {
                return cleaned;
            }
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
