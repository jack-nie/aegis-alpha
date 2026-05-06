package com.marketmind.alpha.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketmind.alpha.domain.BacktestRun;
import com.marketmind.alpha.domain.EvidenceItem;
import com.marketmind.alpha.domain.Recommendation;
import com.marketmind.alpha.domain.WorkflowRun;
import com.marketmind.alpha.mapper.GovernanceMapper;
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
        List<EvidenceItem> evidence = evidenceService.evidence(workflowRun.getRunId());
        String summary = text(first(output.get("summary"), output.get("content")), backtestRun.getFinalRecommendation());

        Recommendation recommendation = new Recommendation();
        recommendation.setRecommendationId(UUID.randomUUID().toString());
        recommendation.setWorkflowRunId(workflowRun.getRunId());
        recommendation.setBacktestRunId(backtestRun.getId());
        recommendation.setTraceId(workflowRun.getTraceId());
        recommendation.setSymbol(symbol(inputs, backtestRun));
        recommendation.setRecommendation(label(workflowRun, summary));
        recommendation.setConfidence(confidence(output, backtestRun));
        recommendation.setTimeHorizon(text(first(inputs == null ? null : inputs.get("timeHorizon"), inputs == null ? null : inputs.get("time_horizon")), "6-12 months"));
        recommendation.setRationaleJson(toJson(rationale(summary, output, evidence)));
        recommendation.setRiskJson(toJson(Arrays.asList(
                "AI output requires human review before use.",
                "Market data, filings, and news can be stale or incomplete.",
                "Valuation, liquidity, macro, and suitability risks remain unresolved.")));
        recommendation.setMissingDataJson(toJson(evidence.isEmpty()
                ? Arrays.asList("No external evidence captured by workflow nodes.")
                : java.util.Collections.emptyList()));
        recommendation.setDisclaimer("This AI-generated recommendation is not investment advice. Review evidence, suitability, risk, and conflicts before any action.");
        recommendation.setApprovalStatus("PENDING_REVIEW");
        recommendation.setCreatedAt(now());
        governanceMapper.insertRecommendation(recommendation);
        return recommendation;
    }

    public Recommendation approve(String workflowRunId) {
        return updateApproval(workflowRunId, "APPROVED");
    }

    public Recommendation reject(String workflowRunId) {
        return updateApproval(workflowRunId, "REJECTED");
    }

    private Recommendation updateApproval(String workflowRunId, String approvalStatus) {
        if (governanceMapper.findRecommendation(workflowRunId) == null) {
            return null;
        }
        governanceMapper.updateRecommendationApproval(workflowRunId, approvalStatus);
        return governanceMapper.findRecommendation(workflowRunId);
    }

    private Map<String, Object> rationale(String summary, Map<String, Object> output, List<EvidenceItem> evidence) {
        Map<String, Object> rationale = new LinkedHashMap<>();
        rationale.put("summary", summary);
        rationale.put("handler", text(output.get("handler"), "finance.stock_recommendation_aggregate"));
        rationale.put("evidenceCount", evidence.size());
        rationale.put("signals", output.get("signals") == null ? java.util.Collections.emptyList() : output.get("signals"));
        return rationale;
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

    private BigDecimal confidence(Map<String, Object> output, BacktestRun backtestRun) {
        Object value = output.get("confidence");
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
