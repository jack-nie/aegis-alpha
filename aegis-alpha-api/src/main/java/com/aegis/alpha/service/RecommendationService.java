package com.aegis.alpha.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aegis.alpha.domain.BacktestRun;
import com.aegis.alpha.domain.EvidenceItem;
import com.aegis.alpha.domain.Recommendation;
import com.aegis.alpha.domain.WorkflowRun;
import com.aegis.alpha.mapper.GovernanceMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class RecommendationService {
    private final GovernanceMapper governanceMapper;
    private final EvidenceService evidenceService;
    private final ObjectMapper objectMapper;

    public RecommendationService(GovernanceMapper governanceMapper, EvidenceService evidenceService, ObjectMapper objectMapper) {
        this.governanceMapper = governanceMapper;
        this.evidenceService = evidenceService;
        this.objectMapper = objectMapper;
    }

    public List<Recommendation> recommendations() {
        return governanceMapper.findRecommendations();
    }

    public Map<String, Object> detail(String workflowRunId) {
        Recommendation recommendation = governanceMapper.findRecommendation(workflowRunId);
        if (recommendation == null) {
            return null;
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("recommendation", recommendation);
        detail.put("evidence", evidenceService.evidence(workflowRunId));
        detail.put("approvable", isApprovable(recommendation));
        return detail;
    }

    public Recommendation createFromWorkflowRun(WorkflowRun workflowRun, BacktestRun backtestRun, Map<String, Object> inputs) {
        if (workflowRun == null || workflowRun.getRunId() == null || backtestRun == null) {
            return null;
        }
        Recommendation existing = governanceMapper.findRecommendation(workflowRun.getRunId());
        if (existing != null) {
            return existing;
        }

        Map<String, Object> result = parse(workflowRun.getResultJson());
        Map<String, Object> output = outputMap(result);
        Map<String, Object> data = dataMap(output);
        List<EvidenceItem> evidence = evidenceService.evidence(workflowRun.getRunId());
        String summary = text(first(output.get("summary"), output.get("content")), backtestRun.getFinalRecommendation());

        String label = resolveLabel(workflowRun, summary, output, data, evidence);
        BigDecimal conf = confidence(output, data, backtestRun);
        boolean degraded = isDegraded(output, data, evidence, label);
        List<String> missing = missingData(output, data, evidence, degraded);

        if (isActionable(label) && (evidence.isEmpty() || degraded)) {
            label = "INSUFFICIENT_DATA";
            conf = conf.min(new BigDecimal("0.30"));
            degraded = true;
            if (!missing.contains("missing_evidence_or_degraded")) {
                missing.add("missing_evidence_or_degraded");
            }
        }
        if ("INSUFFICIENT_DATA".equals(label) || degraded) {
            conf = conf.min(new BigDecimal("0.50"));
        }

        Recommendation recommendation = new Recommendation();
        recommendation.setRecommendationId(UUID.randomUUID().toString());
        recommendation.setWorkflowRunId(workflowRun.getRunId());
        recommendation.setBacktestRunId(backtestRun.getId());
        recommendation.setTraceId(workflowRun.getTraceId());
        recommendation.setSymbol(symbol(inputs, backtestRun));
        recommendation.setRecommendation(label);
        recommendation.setConfidence(conf);
        Object horizonInput = inputs == null ? null : first(inputs.get("timeHorizon"), inputs.get("time_horizon"));
        recommendation.setTimeHorizon(text(first(data.get("timeHorizon"), horizonInput), "6M"));
        recommendation.setRationaleJson(toJson(rationale(summary, output, evidence, data, degraded)));
        recommendation.setRiskJson(toJson(Arrays.asList(
                "AI output requires human review before use.",
                "Market data, filings, and news can be stale or incomplete.",
                "Valuation, liquidity, macro, and suitability risks remain unresolved.")));
        recommendation.setMissingDataJson(toJson(missing));
        String disclaimer = "This AI-generated recommendation is not investment advice. Review evidence, suitability, risk, and conflicts before any action.";
        if (degraded) {
            disclaimer = "[DRAFT/DEGRADED — not approvable until re-run with complete evidence] " + disclaimer;
        }
        recommendation.setDisclaimer(disclaimer);
        recommendation.setApprovalStatus("PENDING_REVIEW");
        recommendation.setCreatedAt(now());
        governanceMapper.insertRecommendation(recommendation);
        return recommendation;
    }

    public Recommendation approve(String workflowRunId) {
        Recommendation existing = governanceMapper.findRecommendation(workflowRunId);
        if (existing == null) {
            return null;
        }
        if (!isApprovable(existing)) {
            throw new IllegalStateException("Recommendation is not approvable: degraded, insufficient data, or missing evidence.");
        }
        return updateApproval(workflowRunId, "APPROVED");
    }

    public Recommendation reject(String workflowRunId) {
        return updateApproval(workflowRunId, "REJECTED");
    }

    public boolean isApprovable(Recommendation recommendation) {
        if (recommendation == null) {
            return false;
        }
        String status = recommendation.getApprovalStatus();
        if (status != null && !"PENDING_REVIEW".equals(status)) {
            return false;
        }
        String label = recommendation.getRecommendation() == null ? "" : recommendation.getRecommendation().toUpperCase();
        if ("INSUFFICIENT_DATA".equals(label)) {
            return false;
        }
        String disclaimer = recommendation.getDisclaimer() == null ? "" : recommendation.getDisclaimer();
        if (disclaimer.contains("DEGRADED") || disclaimer.contains("DRAFT/DEGRADED")) {
            return false;
        }
        Map<String, Object> rationale = parse(recommendation.getRationaleJson());
        if (Boolean.TRUE.equals(rationale.get("degraded"))) {
            return false;
        }
        List<EvidenceItem> evidence = evidenceService.evidence(recommendation.getWorkflowRunId());
        if (isActionable(label) && (evidence == null || evidence.isEmpty())) {
            return false;
        }
        return true;
    }

    private Recommendation updateApproval(String workflowRunId, String approvalStatus) {
        if (governanceMapper.findRecommendation(workflowRunId) == null) {
            return null;
        }
        governanceMapper.updateRecommendationApproval(workflowRunId, approvalStatus);
        return governanceMapper.findRecommendation(workflowRunId);
    }

    private Map<String, Object> rationale(String summary, Map<String, Object> output, List<EvidenceItem> evidence,
                                          Map<String, Object> data, boolean degraded) {
        Map<String, Object> rationale = new LinkedHashMap<>();
        rationale.put("summary", summary);
        rationale.put("handler", text(output.get("handler"), "finance.stock_recommendation_aggregate"));
        rationale.put("evidenceCount", evidence.size());
        rationale.put("signals", output.get("signals") == null ? java.util.Collections.emptyList() : output.get("signals"));
        rationale.put("degraded", degraded);
        rationale.put("claims", data.get("claims") == null ? java.util.Collections.emptyList() : data.get("claims"));
        rationale.put("draft", true);
        return rationale;
    }

    private boolean isActionable(String label) {
        return "BUY".equals(label) || "SELL".equals(label);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> dataMap(Map<String, Object> output) {
        Object data = output.get("data");
        if (data instanceof Map) {
            return (Map<String, Object>) data;
        }
        return new LinkedHashMap<>();
    }

    private String resolveLabel(WorkflowRun workflowRun, String summary, Map<String, Object> output,
                                Map<String, Object> data, List<EvidenceItem> evidence) {
        Object structured = first(data.get("recommendation"), output.get("recommendation"));
        if (structured != null) {
            String s = String.valueOf(structured).trim().toUpperCase().replace(' ', '_');
            if ("STRONG_BUY".equals(s) || "STRONGBUY".equals(s)) {
                s = "BUY";
            }
            if (Arrays.asList("BUY", "HOLD", "SELL", "WATCH", "INSUFFICIENT_DATA").contains(s)) {
                return s;
            }
        }
        return label(workflowRun, summary);
    }

    private boolean isDegraded(Map<String, Object> output, Map<String, Object> data, List<EvidenceItem> evidence, String label) {
        if (Boolean.TRUE.equals(output.get("degraded")) || Boolean.TRUE.equals(data.get("degraded"))) {
            return true;
        }
        if ("INSUFFICIENT_DATA".equals(label)) {
            return true;
        }
        if (evidence == null || evidence.isEmpty()) {
            return true;
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private List<String> missingData(Map<String, Object> output, Map<String, Object> data, List<EvidenceItem> evidence, boolean degraded) {
        List<String> missing = new java.util.ArrayList<>();
        Object md = first(data.get("missingData"), data.get("missing_data"));
        if (md instanceof List) {
            for (Object item : (List<Object>) md) {
                if (item != null) {
                    missing.add(String.valueOf(item));
                }
            }
        }
        if (evidence == null || evidence.isEmpty()) {
            missing.add("No external evidence captured by workflow nodes.");
        }
        if (degraded && !missing.contains("degraded")) {
            missing.add("degraded");
        }
        return missing;
    }

    private String symbol(Map<String, Object> inputs, BacktestRun backtestRun) {
        Object value = inputs == null ? null : first(inputs.get("ticker"), inputs.get("symbol"));
        String symbol = text(value, backtestRun.getSymbol());
        return symbol == null ? "" : symbol.trim().toUpperCase();
    }

    private String label(WorkflowRun workflowRun, String summary) {
        if (!"COMPLETED".equals(workflowRun.getStatus())) {
            return "INSUFFICIENT_DATA";
        }
        String value = summary == null ? "" : summary.toUpperCase();
        if (value.contains("STRONG BUY") || value.contains("BUY")) {
            return "BUY";
        }
        if (value.contains("SELL")) {
            return "SELL";
        }
        if (value.contains("HOLD")) {
            return "HOLD";
        }
        if (value.contains("WATCH")) {
            return "WATCH";
        }
        return "INSUFFICIENT_DATA";
    }

    private BigDecimal confidence(Map<String, Object> output, Map<String, Object> data, BacktestRun backtestRun) {
        Object value = first(data.get("confidence"), output.get("confidence"));
        if (value instanceof Number) {
            return BigDecimal.valueOf(((Number) value).doubleValue());
        }
        if (value != null) {
            try {
                return new BigDecimal(String.valueOf(value));
            } catch (Exception ignored) {
            }
        }
        return backtestRun.getConfidence() == null ? BigDecimal.ZERO : backtestRun.getConfidence();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> outputMap(Map<String, Object> result) {
        Object recommendation = firstPresent(result, "stock_recommendation", "recommendation", "recommend", "finance.stock_recommendation_aggregate", "final_recommendation");
        if (recommendation instanceof Map) {
            return (Map<String, Object>) recommendation;
        }
        Map<String, Object> wrapped = new LinkedHashMap<>();
        if (recommendation != null) {
            wrapped.put("summary", recommendation);
        }
        return wrapped;
    }

    private Object firstPresent(Map<String, Object> result, String... keys) {
        for (String key : keys) {
            if (result.containsKey(key)) {
                return result.get(key);
            }
        }
        return null;
    }

    private Object first(Object value, Object fallback) {
        return value == null || String.valueOf(value).trim().isEmpty() ? fallback : value;
    }

    private String text(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? fallback : text;
    }

    private Map<String, Object> parse(String json) {
        if (json == null || json.trim().isEmpty()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception ignored) {
            return new LinkedHashMap<>();
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ignored) {
            return "{}";
        }
    }

    private String now() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}
