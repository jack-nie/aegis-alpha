package com.aegis.alpha.service.help;

import com.aegis.alpha.service.IntentRouterService;
import com.aegis.alpha.service.IntentRouterService.IntentResult;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adaptive router: RESEARCH workflow first, then diagnosis / product help / copilot.
 */
@Service
public class HelpRoutingService {

    private static final double RESEARCH_PRIORITY_CONFIDENCE = 0.6;

    private static final String[] RESEARCH_HINTS = {
            "日报", "晨报", "深度分析", "深度研究", "个股分析", "分析一下", "分析股票",
            "止损", "止盈", "投资组合", "持仓", "板块", "行业分析", "财报", "业绩", "自选股", "早报", "回测",
            "daily", "deep dive", "stock analysis", "portfolio", "earnings", "watchlist"
    };

    private static final String[] HELP_HINTS = {
            "怎么", "如何", "怎样", "在哪", "是什么", "什么是", "如何打开", "如何使用",
            "帮助", "教程", "操作步骤", "如何审批", "如何登录", "copilot",
            "工作流怎么", "如何运行", "degraded 是什么", "什么是 degraded"
    };

    /** Avoid bare "degraded" — FAQ like「什么是 DEGRADED」is PRODUCT_HELP. */
    private static final String[] DIAGNOSIS_HINTS = {
            "失败", "报错", "为什么失败", "为什么 degraded", "为何 degraded", "为何失败",
            "超时", "卡住", "节点失败", "run 失败", "执行失败", "failed", "error", "diagnose",
            "这个 run", "这次 run", "运行失败", "执行报错"
    };

    private static final Pattern RUN_ID_PATTERN = Pattern.compile(
            "\\b([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern TICKER_PATTERN = Pattern.compile("\\b([A-Z]{1,5}(?:\\.[A-Z]{2})?)\\b");
    private static final String[] TICKER_STOPWORDS = {
            "AI", "API", "CPI", "ETF", "GDP", "IPO", "LLM", "SEC", "USA", "USD", "CEO", "CFO", "ESG"
    };

    private final IntentRouterService intentRouterService;
    private final PlaybookService playbookService;

    public HelpRoutingService(IntentRouterService intentRouterService, PlaybookService playbookService) {
        this.intentRouterService = intentRouterService;
        this.playbookService = playbookService;
    }

    public HelpIntent.Result classifyExtended(String message, HelpIntent.PageContext pageContext) {
        HelpIntent.PageContext page = pageContext == null ? new HelpIntent.PageContext() : pageContext;
        String text = message == null ? "" : message.trim();
        HelpIntent.Entities entities = extractEntities(text, page);
        boolean cacheable = isCacheable(text, entities);

        if (text.isEmpty()) {
            HelpIntent.Result empty = HelpIntent.Result.empty();
            empty.setEntities(entities);
            empty.setReason("Empty message");
            return empty;
        }

        // 1) Research / published workflow (LLM + keyword via existing router)
        IntentResult research = intentRouterService.classify(text);
        if (research != null
                && research.getWorkflowKey() != null
                && !research.getWorkflowKey().isEmpty()
                && research.getConfidence() >= RESEARCH_PRIORITY_CONFIDENCE) {
            HelpIntent.Result result = HelpIntent.Result.empty();
            result.setWorkflowKey(research.getWorkflowKey());
            result.setTicker(firstNonEmpty(research.getTicker(), entities.getTicker()));
            result.setConfidence(research.getConfidence());
            result.setSource(research.getSource() == null ? "research" : research.getSource());
            result.setDomain(HelpIntent.Domain.RESEARCH);
            result.setComplexity(HelpIntent.Complexity.L2);
            result.setRouteAction(HelpIntent.RouteAction.DISPATCH_WORKFLOW);
            result.setEntities(entities);
            result.setCacheable(false);
            result.setReason("Published workflow match takes priority");
            return result;
        }

        // Soft research hints without workflow → copilot research path
        if (containsAny(text, RESEARCH_HINTS) && !containsAny(text, HELP_HINTS) && !containsAny(text, DIAGNOSIS_HINTS)) {
            HelpIntent.Result result = HelpIntent.Result.empty();
            result.setTicker(entities.getTicker());
            result.setConfidence(0.4);
            result.setSource("research_hint");
            result.setDomain(HelpIntent.Domain.RESEARCH);
            result.setComplexity(HelpIntent.Complexity.L1);
            result.setRouteAction(HelpIntent.RouteAction.COPILOT);
            result.setEntities(entities);
            result.setCacheable(false);
            result.setReason("Research-flavored message without workflow match");
            return result;
        }

        // 2) Run diagnosis
        if (notEmpty(entities.getRunId()) || containsAny(text, DIAGNOSIS_HINTS)) {
            if (!notEmpty(entities.getRunId())) {
                HelpIntent.Result result = HelpIntent.Result.empty();
                result.setConfidence(0.8);
                result.setSource("diagnosis_clarify");
                result.setDomain(HelpIntent.Domain.RUN_DIAGNOSIS);
                result.setComplexity(HelpIntent.Complexity.L2);
                result.setRouteAction(HelpIntent.RouteAction.CLARIFY);
                result.setNeedsClarification(true);
                result.setClarificationPrompt("请提供运行 ID（runId），或从「运行中心」打开对应 run 后再问我。");
                result.setEntities(entities);
                result.setCacheable(false);
                result.setReason("Diagnosis without runId");
                return result;
            }
            HelpIntent.Result result = HelpIntent.Result.empty();
            result.setConfidence(0.85);
            result.setSource("diagnosis");
            result.setDomain(HelpIntent.Domain.RUN_DIAGNOSIS);
            result.setComplexity(HelpIntent.Complexity.L2);
            result.setRouteAction(HelpIntent.RouteAction.HELP_GRAPH);
            result.setEntities(entities);
            result.setCacheable(false);
            result.setReason("Run diagnosis with runId");
            return result;
        }

        // 3) Product help
        if (containsAny(text, HELP_HINTS)) {
            PlaybookService.Playbook playbook = playbookService.findBestMatch(text);
            HelpIntent.Complexity complexity = estimateComplexity(text);
            HelpIntent.Result result = HelpIntent.Result.empty();
            result.setConfidence(playbook != null ? 0.9 : 0.7);
            result.setSource(playbook != null ? "playbook" : "help_l0");
            result.setDomain(HelpIntent.Domain.PRODUCT_HELP);
            result.setComplexity(complexity);
            result.setEntities(entities);
            result.setCacheable(cacheable);
            if (playbook != null) {
                result.setPlaybookId(playbook.getId());
                result.setRouteAction(HelpIntent.RouteAction.PLAYBOOK);
                result.setReason("Playbook hit: " + playbook.getId());
                return result;
            }
            // Playbook miss → hybrid lexical retrieval (L0/L1) or help-loop (L2)
            if (complexity == HelpIntent.Complexity.L2) {
                result.setRouteAction(HelpIntent.RouteAction.HELP_GRAPH);
                result.setCacheable(false);
                result.setReason("Product help L2 help-loop");
                return result;
            }
            result.setRouteAction(HelpIntent.RouteAction.KNOWLEDGE_RAG);
            result.setComplexity(complexity == HelpIntent.Complexity.L0 ? HelpIntent.Complexity.L1 : complexity);
            result.setReason("Product help playbook miss → hybrid lexical retrieval");
            return result;
        }

        // 4) Default chitchat → research copilot
        HelpIntent.Result result = HelpIntent.Result.empty();
        result.setTicker(entities.getTicker());
        result.setConfidence(0.2);
        result.setSource("default_copilot");
        result.setDomain(HelpIntent.Domain.CHITCHAT);
        result.setComplexity(HelpIntent.Complexity.L0);
        result.setRouteAction(HelpIntent.RouteAction.COPILOT);
        result.setEntities(entities);
        result.setCacheable(false);
        result.setReason("Fallback copilot");
        return result;
    }

    HelpIntent.Entities extractEntities(String message, HelpIntent.PageContext page) {
        HelpIntent.Entities entities = new HelpIntent.Entities();
        if (page != null) {
            entities.setRunId(emptyToNull(page.getRunId()));
            entities.setTicker(emptyToNull(page.getTicker()));
            entities.setPath(emptyToNull(page.getPath()));
            entities.setPortfolioId(emptyToNull(page.getPortfolioId()));
            entities.setRecommendationId(emptyToNull(page.getRecommendationId()));
        }
        if (message != null) {
            Matcher runMatcher = RUN_ID_PATTERN.matcher(message);
            if (runMatcher.find()) {
                entities.setRunId(runMatcher.group(1));
            }
            Matcher tickerMatcher = TICKER_PATTERN.matcher(message);
            while (tickerMatcher.find()) {
                String candidate = tickerMatcher.group(1);
                if (!isTickerStopword(candidate)) {
                    entities.setTicker(candidate);
                    break;
                }
            }
        }
        return entities;
    }

    boolean isCacheable(String message, HelpIntent.Entities entities) {
        if (entities != null) {
            if (notEmpty(entities.getRunId())
                    || notEmpty(entities.getPortfolioId())
                    || notEmpty(entities.getRecommendationId())) {
                return false;
            }
        }
        String text = message == null ? "" : message.toLowerCase(Locale.ROOT);
        if (text.contains("portfolio") || text.contains("持仓") || text.contains("组合明细") || text.contains("我的仓位")) {
            return false;
        }
        if (text.contains("run_id") || text.contains("run-id") || text.contains("run id")) {
            return false;
        }
        return true;
    }

    HelpIntent.Complexity estimateComplexity(String message) {
        String text = message == null ? "" : message;
        if ((text.contains("并且") || text.contains("同时") || text.contains("如果"))
                && (text.contains("还") || text.contains("是否") || text.contains("会不会"))) {
            return HelpIntent.Complexity.L2;
        }
        if (text.length() > 80 && containsAny(text, HELP_HINTS)) {
            return HelpIntent.Complexity.L1;
        }
        if (containsAny(text, "是什么", "在哪", "如何打开", "什么是")) {
            return HelpIntent.Complexity.L0;
        }
        return HelpIntent.Complexity.L0;
    }

    private static boolean containsAny(String text, String... hints) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String hint : hints) {
            if (hint != null && !hint.isEmpty() && lower.contains(hint.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static boolean notEmpty(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static String emptyToNull(String value) {
        return notEmpty(value) ? value.trim() : null;
    }

    private static String firstNonEmpty(String a, String b) {
        if (notEmpty(a)) {
            return a.trim();
        }
        if (notEmpty(b)) {
            return b.trim();
        }
        return null;
    }

    private static boolean isTickerStopword(String symbol) {
        if (symbol == null) {
            return true;
        }
        String upper = symbol.toUpperCase(Locale.ROOT);
        for (String sw : TICKER_STOPWORDS) {
            if (upper.equals(sw)) {
                return true;
            }
        }
        return false;
    }
}
