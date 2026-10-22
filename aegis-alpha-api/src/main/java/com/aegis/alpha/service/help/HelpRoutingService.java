package com.aegis.alpha.service.help;

import com.aegis.alpha.service.IntentRouterService;
import com.aegis.alpha.service.IntentRouterService.IntentResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adaptive router. High-precision rules run first and never pay for an LLM call:
 * diagnosis (runId in text / diagnosis words) → product help (help words / playbook) →
 * research keyword rules. Only messages no rule claims are sent to the LLM classifier.
 */
@Service
public class HelpRoutingService {

    /** Minimum research confidence to dispatch a workflow (also decides help+research mixes). */
    @Value("${aegis.router.research-priority-confidence:0.6}")
    private double researchPriorityConfidence = 0.6;

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

    /** Pure greetings / thanks: never worth an LLM routing call or a workflow. */
    private static final String[] CHITCHAT_PHRASES = {
            "你好", "您好", "嗨", "哈喽", "在吗", "谢谢", "多谢", "早上好", "下午好", "晚上好", "再见",
            "hello", "hi", "hey", "thanks", "thank you", "bye"
    };
    private static final int CHITCHAT_MAX_LENGTH = 12;

    private static final Pattern RUN_ID_PATTERN = Pattern.compile(
            "\\b([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern TICKER_PATTERN = Pattern.compile("\\b([A-Z]{1,5}(?:\\.[A-Z]{2})?)\\b");

    private final IntentRouterService intentRouterService;
    private final PlaybookService playbookService;

    public HelpRoutingService(IntentRouterService intentRouterService, PlaybookService playbookService) {
        this.intentRouterService = intentRouterService;
        this.playbookService = playbookService;
    }

    public HelpIntent.Result classifyExtended(String message, HelpIntent.PageContext pageContext) {
        return classifyExtended(message, pageContext, null);
    }

    /** Routes and emits one structured {@code aegis.routing} log line (no message text). */
    public HelpIntent.Result classifyExtended(String message, HelpIntent.PageContext pageContext, String traceId) {
        long started = System.nanoTime();
        RoutingTelemetry.Call call = RoutingTelemetry.begin();
        HelpIntent.Result result = null;
        try {
            result = classifyInternal(message, pageContext);
            return result;
        } finally {
            RoutingTelemetry.end();
            long latencyMs = (System.nanoTime() - started) / 1_000_000L;
            RoutingTelemetry.logRoute(traceId,
                    result == null || result.getDomain() == null ? "error" : result.getDomain().name(),
                    result == null || result.getRouteAction() == null ? "error" : result.getRouteAction().name(),
                    result == null ? "error" : result.getSource(),
                    result == null ? 0d : result.getConfidence(),
                    latencyMs, call.isLlmUsed(), call.isCacheHit(), message);
        }
    }

    private HelpIntent.Result classifyInternal(String message, HelpIntent.PageContext pageContext) {
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

        // 0) Safety boundary (P2-6): approve-on-behalf / trade / cross-tenant requests never reach
        //    the LLM classifier and never dispatch a workflow, whatever the model would say.
        HelpSafetyRules.Category safety = HelpSafetyRules.classify(text);
        if (safety != HelpSafetyRules.Category.NONE) {
            HelpIntent.Result guarded = HelpIntent.Result.empty();
            guarded.setTicker(entities.getTicker());
            guarded.setConfidence(1.0);
            guarded.setSource("safety_rule");
            guarded.setDomain(HelpIntent.Domain.PRODUCT_HELP);
            guarded.setComplexity(HelpIntent.Complexity.L0);
            guarded.setRouteAction(HelpIntent.RouteAction.PLAYBOOK);
            guarded.setPlaybookId(safety == HelpSafetyRules.Category.APPROVE_ON_BEHALF ? "approval-inbox" : null);
            guarded.setEntities(entities);
            guarded.setCacheable(false);
            guarded.setReason("Safety rule: " + safety.name());
            return guarded;
        }

        boolean runIdInText = RUN_ID_PATTERN.matcher(text).find();
        boolean diagnosisWords = containsAny(text, DIAGNOSIS_HINTS);
        boolean helpWords = containsAny(text, HELP_HINTS);

        // 1) Run diagnosis: explicit runId in the message or diagnosis vocabulary
        if (runIdInText || diagnosisWords) {
            return diagnosis(entities);
        }

        // 2) Product help. A help+research mix (golden C02) is RESEARCH only when the
        //    deterministic research rule is confident; otherwise PRODUCT_HELP.
        if (helpWords) {
            if (containsAny(text, RESEARCH_HINTS)) {
                IntentResult mixed = intentRouterService.classifyByRules(text);
                if (isConfidentResearch(mixed)) {
                    return research(mixed, entities, "Help+research mix: research rule above threshold");
                }
            }
            return productHelp(text, entities, cacheable);
        }

        // 2b) Clearly unrelated requests (creative writing, jokes, weather ...) → polite decline (P2-7)
        if (isOutOfScope(text)) {
            return outOfScope(entities, "Out-of-scope rule");
        }

        // 3) Greetings / thanks → copilot chit-chat without consulting the LLM router
        if (isChitchat(text)) {
            return chitchat(entities, "Greeting rule");
        }

        // 4) Research keyword rules (no remote call)
        IntentResult rule = intentRouterService.classifyByRules(text);
        if (isConfidentResearch(rule)) {
            return research(rule, entities, "Research keyword rule");
        }

        // 5) Ambiguous: only now consult the LLM classifier (keyword fallback inside)
        IntentResult llm = intentRouterService.classify(text);
        if (isConfidentResearch(llm)) {
            return research(llm, entities, "Published workflow match (classifier)");
        }
        if (llm != null && IntentRouterService.SOURCE_LLM_OUT_OF_SCOPE.equals(llm.getSource())
                && !containsAny(text, RESEARCH_HINTS)) {
            return outOfScope(entities, "Classifier: out of scope");
        }

        // Soft research hints without workflow → copilot research path
        if (containsAny(text, RESEARCH_HINTS)) {
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

        // 6) Page-context runId (e.g. asking from /runs/{id}) still means diagnosis
        if (notEmpty(entities.getRunId())) {
            return diagnosis(entities);
        }

        // 7) Default chitchat → research copilot
        return chitchat(entities, "Fallback copilot");
    }

    static boolean isChitchat(String text) {
        if (text == null) {
            return false;
        }
        String normalized = text.toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{Punct}\\s，。！？、～~]+", "");
        if (normalized.isEmpty() || normalized.length() > CHITCHAT_MAX_LENGTH) {
            return false;
        }
        for (String phrase : CHITCHAT_PHRASES) {
            String compact = phrase.replace(" ", "");
            if (normalized.equals(compact)) {
                return true;
            }
            boolean cjk = Character.UnicodeScript.of(compact.codePointAt(0)) == Character.UnicodeScript.HAN;
            if (cjk && normalized.startsWith(compact) && normalized.length() <= compact.length() + 3) {
                return true;
            }
        }
        return false;
    }

    /** Creative-writing / lifestyle requests unrelated to research or the product. */
    static final String[] OUT_OF_SCOPE_HINTS = {
            "写首诗", "写一首诗", "写诗", "作诗", "写首歌", "写歌词", "讲个笑话", "说个笑话", "讲笑话",
            "天气怎么样", "天气预报", "今天天气", "菜谱", "怎么做菜", "写小说", "写作文", "星座", "算命",
            "write a poem", "write me a poem", "tell me a joke", "tell a joke", "weather today",
            "weather forecast", "recipe for", "write a story", "write a song", "horoscope"
    };

    static boolean isOutOfScope(String text) {
        if (text == null) {
            return false;
        }
        return containsAny(text, OUT_OF_SCOPE_HINTS) && !containsAny(text, RESEARCH_HINTS);
    }

    private HelpIntent.Result outOfScope(HelpIntent.Entities entities, String reason) {
        HelpIntent.Result result = HelpIntent.Result.empty();
        result.setConfidence(0.9);
        result.setSource("out_of_scope");
        result.setDomain(HelpIntent.Domain.OUT_OF_SCOPE);
        result.setComplexity(HelpIntent.Complexity.L0);
        result.setRouteAction(HelpIntent.RouteAction.DECLINE);
        result.setEntities(entities);
        result.setCacheable(false);
        result.setReason(reason);
        return result;
    }

    private HelpIntent.Result chitchat(HelpIntent.Entities entities, String reason) {
        HelpIntent.Result result = HelpIntent.Result.empty();
        result.setTicker(entities.getTicker());
        result.setConfidence(0.2);
        result.setSource("default_copilot");
        result.setDomain(HelpIntent.Domain.CHITCHAT);
        result.setComplexity(HelpIntent.Complexity.L0);
        result.setRouteAction(HelpIntent.RouteAction.COPILOT);
        result.setEntities(entities);
        result.setCacheable(false);
        result.setReason(reason);
        return result;
    }

    private boolean isConfidentResearch(IntentResult research) {
        return research != null
                && research.getWorkflowKey() != null
                && !research.getWorkflowKey().isEmpty()
                && research.getConfidence() >= researchPriorityConfidence;
    }

    private HelpIntent.Result research(IntentResult research, HelpIntent.Entities entities, String reason) {
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
        result.setReason(reason);
        return result;
    }

    private HelpIntent.Result diagnosis(HelpIntent.Entities entities) {
        HelpIntent.Result result = HelpIntent.Result.empty();
        result.setDomain(HelpIntent.Domain.RUN_DIAGNOSIS);
        result.setComplexity(HelpIntent.Complexity.L2);
        result.setEntities(entities);
        result.setCacheable(false);
        if (!notEmpty(entities.getRunId())) {
            result.setConfidence(0.8);
            result.setSource("diagnosis_clarify");
            result.setRouteAction(HelpIntent.RouteAction.CLARIFY);
            result.setNeedsClarification(true);
            result.setClarificationPrompt("请提供运行 ID（runId），或从「运行中心」打开对应 run 后再问我。");
            result.setReason("Diagnosis without runId");
            return result;
        }
        result.setConfidence(0.85);
        result.setSource("diagnosis");
        result.setRouteAction(HelpIntent.RouteAction.HELP_GRAPH);
        result.setReason("Run diagnosis with runId");
        return result;
    }

    private HelpIntent.Result productHelp(String text, HelpIntent.Entities entities, boolean cacheable) {
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

    void setResearchPriorityConfidence(double researchPriorityConfidence) {
        this.researchPriorityConfidence = researchPriorityConfidence;
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
        return com.aegis.alpha.service.RoutingRules.isTickerStopword(symbol);
    }
}
