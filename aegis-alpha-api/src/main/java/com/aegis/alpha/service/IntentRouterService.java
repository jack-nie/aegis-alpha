package com.aegis.alpha.service;

import com.aegis.alpha.domain.WorkflowDefinition;
import com.aegis.alpha.mapper.WorkflowMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class IntentRouterService {
    private static final long CACHE_TTL_MS = 5 * 60 * 1000;
    private static final Pattern STOCK_ANALYSIS_PATTERN =
            Pattern.compile("分析.{0,20}(?:股票|个股)|(?:股票|个股).{0,10}分析|分析一下.{0,20}");
    private static final Pattern SECTOR_ANALYSIS_PATTERN =
            Pattern.compile("分析.{0,20}(?:板块|行业)|(?:板块|行业).{0,10}分析");
    private static final Pattern TICKER_PATTERN = Pattern.compile("\\b[A-Z]{1,5}(?:[._-][A-Z])?\\b");

    private final WorkflowMapper workflowMapper;
    private final LangChainGateway langChainGateway;
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
        // Step 1: try LLM function calling classification
        IntentResult llmResult = classifyByLlm(message, definitions);
        if (llmResult != null && llmResult.getWorkflowKey() != null) {
            return llmResult;
        }
        // Step 2: fallback to DB-driven keyword matching
        return classifyByKeywords(message, definitions);
    }

    @SuppressWarnings("unchecked")
    private IntentResult classifyByLlm(String message, List<WorkflowDefinition> definitions) {
        List<Map<String, String>> workflows = new ArrayList<>();
        for (WorkflowDefinition def : definitions) {
            Map<String, String> wf = new LinkedHashMap<>();
            wf.put("workflowKey", def.getWorkflowKey());
            wf.put("name", def.getName());
            wf.put("triggerKeywords", def.getTriggerKeywords() != null ? def.getTriggerKeywords() : "");
            wf.put("routingDescription", def.getRoutingDescription() != null ? def.getRoutingDescription() : "");
            workflows.add(wf);
        }
        try {
            Map<String, Object> result = langChainGateway.classifyIntent(message, workflows);
            if (result == null) {
                return null;
            }
            String workflowKey = string(result.get("workflowKey"));
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
        } catch (Exception ex) {
            return null;
        }
    }

    private IntentResult classifyByKeywords(String message, List<WorkflowDefinition> definitions) {
        String lower = message.toLowerCase();
        // regex patterns first (higher precision)
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
            return cachedDefinitions;
        } catch (Exception ex) {
            return cachedDefinitions != null ? cachedDefinitions : Collections.<WorkflowDefinition>emptyList();
        }
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