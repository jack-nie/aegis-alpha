package com.aegis.alpha.service;

import com.aegis.alpha.domain.WorkflowDefinition;
import com.aegis.alpha.mapper.WorkflowMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class IntentRouterService {
    private static final Logger log = LoggerFactory.getLogger(IntentRouterService.class);
    private static final long CACHE_TTL_MS = 5 * 60 * 1000;
    /** High-precision: must run before stock_analysis so 财报/业绩 phrases don't fall through. */
    private static final Pattern EARNINGS_REACTION_PATTERN =
            Pattern.compile("财报|业绩|季报|earnings reaction|earnings report");
    private static final Pattern WATCHLIST_DIGEST_PATTERN =
            Pattern.compile("早报|自选|watchlist|morning digest|digest");
    private static final Pattern STOCK_ANALYSIS_PATTERN =
            Pattern.compile("分析.{0,20}(?:股票|个股)|(?:股票|个股).{0,10}分析|分析一下.{0,20}");
    private static final Pattern SECTOR_ANALYSIS_PATTERN =
            Pattern.compile("分析.{0,20}(?:板块|行业)|(?:板块|行业).{0,10}分析");
    private static final Pattern TICKER_PATTERN = Pattern.compile("\\b[A-Z]{1,5}(?:[._-][A-Z])?\\b");

    private final WorkflowMapper workflowMapper;
    private final LangChainGateway langChainGateway;
    /** LLM results below this confidence never short-circuit the keyword rules. */
    @Value("${aegis.router.llm-min-confidence:0.6}")
    private double llmMinConfidence = 0.6;
    /** LLM classification cache: normalize(message) + definitions fingerprint → result. */
    @Value("${aegis.router.cache-ttl-ms:600000}")
    private long classificationCacheTtlMs = 10 * 60 * 1000L;
    @Value("${aegis.router.cache-max-entries:500}")
    private int classificationCacheMaxEntries = 500;
    /** Minimal circuit breaker around the remote classifier. */
    @Value("${aegis.router.breaker-failure-threshold:3}")
    private int breakerFailureThreshold = 3;
    @Value("${aegis.router.breaker-open-ms:30000}")
    private long breakerOpenMs = 30000L;
    private LongSupplier clock = System::currentTimeMillis;
    private final Map<String, CachedClassification> classificationCache =
            new LinkedHashMap<String, CachedClassification>(16, 0.75f, true);
    private int consecutiveLlmFailures;
    private long breakerOpenUntil;
    private volatile String definitionsFingerprint = "";
    private volatile List<WorkflowDefinition> cachedDefinitions;
    private volatile long cacheTimestamp;

    public IntentRouterService(WorkflowMapper workflowMapper, LangChainGateway langChainGateway) {
        this.workflowMapper = workflowMapper;
        this.langChainGateway = langChainGateway;
    }

    public IntentResult classify(String message) {
        if (message == null || message.trim().isEmpty()) {
            return IntentResult.empty();
        }
        List<WorkflowDefinition> definitions = getDefinitions();
        if (definitions.isEmpty()) {
            return IntentResult.empty();
        }
        // Step 1: LLM function calling classification
        IntentResult llmResult = classifyByLlm(message, definitions);
        if (llmResult != null && llmResult.getWorkflowKey() != null
                && llmResult.getConfidence() >= llmMinConfidence) {
            return llmResult;
        }
        // Step 2: low-confidence / missing LLM result never hides a keyword match
        IntentResult keywordResult = classifyByKeywords(message, definitions);
        return pickHigher(llmResult, keywordResult);
    }

    /**
     * Deterministic keyword/regex classification only (no remote call). Used by the
     * help router for high-precision rules before any LLM is consulted.
     */
    public IntentResult classifyByRules(String message) {
        if (message == null || message.trim().isEmpty()) {
            return IntentResult.empty();
        }
        List<WorkflowDefinition> definitions = getDefinitions();
        if (definitions.isEmpty()) {
            return IntentResult.empty();
        }
        return classifyByKeywords(message, definitions);
    }

    double getLlmMinConfidence() {
        return llmMinConfidence;
    }

    void setLlmMinConfidence(double llmMinConfidence) {
        this.llmMinConfidence = llmMinConfidence;
    }

    private static IntentResult pickHigher(IntentResult llm, IntentResult keyword) {
        boolean llmUsable = llm != null && llm.getWorkflowKey() != null;
        boolean keywordUsable = keyword != null && keyword.getWorkflowKey() != null;
        if (llmUsable && (!keywordUsable || llm.getConfidence() > keyword.getConfidence())) {
            return llm;
        }
        return keyword == null ? IntentResult.empty() : keyword;
    }

    private IntentResult classifyByLlm(String message, List<WorkflowDefinition> definitions) {
        String cacheKey = normalizeForCache(message) + "|" + definitionsFingerprint;
        CachedClassification cached = cachedClassification(cacheKey);
        if (cached != null) {
            return cached.result;
        }
        if (breakerOpen()) {
            return null;
        }
        Map<String, Object> response;
        try {
            response = langChainGateway.classifyIntent(message, workflowsPayload(definitions));
        } catch (Exception ex) {
            response = null;
            log.warn("[IntentRouter] LLM classification threw {} (messageLength={})",
                    ex.getClass().getSimpleName(), message.length());
        }
        if (response == null) {
            recordLlmFailure();
            return null;
        }
        recordLlmSuccess();
        IntentResult result = parseLlmResult(response, definitions);
        storeClassification(cacheKey, result);
        return result;
    }

    private List<Map<String, String>> workflowsPayload(List<WorkflowDefinition> definitions) {
        List<Map<String, String>> workflows = new ArrayList<>();
        for (WorkflowDefinition def : definitions) {
            Map<String, String> wf = new LinkedHashMap<>();
            wf.put("workflowKey", def.getWorkflowKey());
            wf.put("name", def.getName());
            wf.put("triggerKeywords", def.getTriggerKeywords() != null ? def.getTriggerKeywords() : "");
            wf.put("routingDescription", def.getRoutingDescription() != null ? def.getRoutingDescription() : "");
            workflows.add(wf);
        }
        return workflows;
    }

    private IntentResult parseLlmResult(Map<String, Object> result, List<WorkflowDefinition> definitions) {
        // Prefer camelCase (orchestrator alias); also accept snake_case
        String workflowKey = string(result.get("workflowKey"));
        if (workflowKey.isEmpty()) {
            workflowKey = string(result.get("workflow_key"));
        }
        String ticker = string(result.get("ticker"));
        Object confidenceObj = result.get("confidence");
        double confidence = 0;
        if (confidenceObj instanceof Number) {
            confidence = ((Number) confidenceObj).doubleValue();
        }
        if (workflowKey.isEmpty()) {
            return null;
        }
        for (WorkflowDefinition def : definitions) {
            if (def.getWorkflowKey().equals(workflowKey)) {
                return new IntentResult(workflowKey, ticker.isEmpty() ? null : ticker, confidence, "llm");
            }
        }
        return null;
    }

    /* ---------------- classification cache + circuit breaker ---------------- */

    private static String normalizeForCache(String message) {
        return message.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private synchronized CachedClassification cachedClassification(String key) {
        CachedClassification cached = classificationCache.get(key);
        if (cached == null) {
            return null;
        }
        if (clock.getAsLong() - cached.storedAt >= classificationCacheTtlMs) {
            classificationCache.remove(key);
            return null;
        }
        return cached;
    }

    private synchronized void storeClassification(String key, IntentResult result) {
        if (classificationCacheTtlMs <= 0 || classificationCacheMaxEntries <= 0) {
            return;
        }
        classificationCache.put(key, new CachedClassification(result, clock.getAsLong()));
        while (classificationCache.size() > classificationCacheMaxEntries) {
            String eldest = classificationCache.keySet().iterator().next();
            classificationCache.remove(eldest);
        }
    }

    private synchronized boolean breakerOpen() {
        return breakerOpenUntil > 0 && clock.getAsLong() < breakerOpenUntil;
    }

    private synchronized void recordLlmFailure() {
        consecutiveLlmFailures++;
        if (consecutiveLlmFailures >= breakerFailureThreshold) {
            breakerOpenUntil = clock.getAsLong() + breakerOpenMs;
            consecutiveLlmFailures = 0;
            log.warn("[IntentRouter] LLM classifier circuit opened for {} ms", breakerOpenMs);
        }
    }

    private synchronized void recordLlmSuccess() {
        consecutiveLlmFailures = 0;
        breakerOpenUntil = 0;
    }

    void setClock(LongSupplier clock) {
        this.clock = clock;
    }

    private static final class CachedClassification {
        private final IntentResult result;
        private final long storedAt;

        private CachedClassification(IntentResult result, long storedAt) {
            this.result = result;
            this.storedAt = storedAt;
        }
    }

    private IntentResult classifyByKeywords(String message, List<WorkflowDefinition> definitions) {
        String lower = message.toLowerCase();
        // regex patterns first (higher precision); order matters — research keys before stock_analysis
        if (EARNINGS_REACTION_PATTERN.matcher(lower).find()) {
            String key = findKey(definitions, "earnings_reaction");
            if (key != null) {
                return new IntentResult(key, extractTicker(message), 0.7, "keyword_regex");
            }
        }
        if (WATCHLIST_DIGEST_PATTERN.matcher(lower).find()) {
            String key = findKey(definitions, "watchlist_digest");
            if (key != null) {
                return new IntentResult(key, extractTicker(message), 0.7, "keyword_regex");
            }
        }
        if (STOCK_ANALYSIS_PATTERN.matcher(lower).find()) {
            String key = findKey(definitions, "stock_analysis");
            if (key != null) {
                return new IntentResult(key, extractTicker(message), 0.7, "keyword_regex");
            }
        }
        if (SECTOR_ANALYSIS_PATTERN.matcher(lower).find()) {
            String key = findKey(definitions, "sector-analyst-workflow");
            if (key != null) {
                return new IntentResult(key, null, 0.7, "keyword_regex");
            }
        }
        // keyword matching from DB trigger_keywords
        String bestKey = null;
        int bestLength = 0;
        for (WorkflowDefinition def : definitions) {
            String keywords = def.getTriggerKeywords();
            if (keywords == null || keywords.isEmpty()) {
                continue;
            }
            String[] parts = keywords.split(",");
            for (String kw : parts) {
                String trimmed = kw.trim().toLowerCase();
                if (!trimmed.isEmpty() && lower.contains(trimmed) && trimmed.length() > bestLength) {
                    bestLength = trimmed.length();
                    bestKey = def.getWorkflowKey();
                }
            }
        }
        if (bestKey != null) {
            return new IntentResult(bestKey, extractTicker(message), 0.6, "keyword_db");
        }
        return IntentResult.empty();
    }

    private String findKey(List<WorkflowDefinition> definitions, String key) {
        for (WorkflowDefinition def : definitions) {
            if (key.equals(def.getWorkflowKey())) {
                return def.getWorkflowKey();
            }
        }
        return null;
    }

    private String extractTicker(String message) {
        if (message == null) {
            return null;
        }
        Matcher matcher = TICKER_PATTERN.matcher(message);
        while (matcher.find()) {
            String candidate = matcher.group();
            if (!isStopword(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private boolean isStopword(String symbol) {
        String upper = symbol.toUpperCase();
        String[] stopwords = {"AI", "API", "CPI", "ETF", "GDP", "IPO", "LLM", "SEC", "USA", "USD", "CEO", "CFO", "ESG"};
        for (String sw : stopwords) {
            if (upper.equals(sw)) {
                return true;
            }
        }
        return false;
    }

    private List<WorkflowDefinition> getDefinitions() {
        long now = System.currentTimeMillis();
        if (cachedDefinitions != null && (now - cacheTimestamp) < CACHE_TTL_MS) {
            return cachedDefinitions;
        }
        try {
            cachedDefinitions = workflowMapper.findDefinitions();
            cacheTimestamp = now;
            definitionsFingerprint = fingerprint(cachedDefinitions);
            return cachedDefinitions;
        } catch (Exception ex) {
            return cachedDefinitions != null ? cachedDefinitions : Collections.<WorkflowDefinition>emptyList();
        }
    }

    private static String fingerprint(List<WorkflowDefinition> definitions) {
        StringBuilder builder = new StringBuilder();
        if (definitions != null) {
            for (WorkflowDefinition def : definitions) {
                builder.append(def.getWorkflowKey()).append('\u0001')
                        .append(def.getTriggerKeywords()).append('\u0001')
                        .append(def.getRoutingDescription()).append('\u0002');
            }
        }
        return Integer.toHexString(builder.toString().hashCode());
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    public static class IntentResult {
        private final String workflowKey;
        private final String ticker;
        private final double confidence;
        private final String source;

        public IntentResult(String workflowKey, String ticker, double confidence, String source) {
            this.workflowKey = workflowKey;
            this.ticker = ticker;
            this.confidence = confidence;
            this.source = source;
        }

        public static IntentResult empty() {
            return new IntentResult(null, null, 0, "none");
        }

        public String getWorkflowKey() { return workflowKey; }
        public String getTicker() { return ticker; }
        public double getConfidence() { return confidence; }
        public String getSource() { return source; }
    }
}