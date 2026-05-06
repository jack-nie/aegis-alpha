package com.marketmind.alpha.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketmind.alpha.domain.BacktestRun;
import com.marketmind.alpha.domain.WorkflowRun;
import com.marketmind.alpha.mapper.BacktestMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class BacktestService {
    private final BacktestMapper mapper;
    private final ObjectMapper objectMapper;
    private final ModelGovernanceService modelGovernanceService;
    private final EvidenceService evidenceService;
    private final RecommendationService recommendationService;

    public BacktestService(BacktestMapper mapper,
                           ObjectMapper objectMapper,
                           ModelGovernanceService modelGovernanceService,
                           EvidenceService evidenceService,
                           RecommendationService recommendationService) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.modelGovernanceService = modelGovernanceService;
        this.evidenceService = evidenceService;
        this.recommendationService = recommendationService;
    }

    public List<BacktestRun> findAll() {
        return mapper.findAll();
    }

    public BacktestRun create(String runName, String strategy) {
        BacktestRun run = new BacktestRun();
        run.setId(UUID.randomUUID().toString());
        run.setRunName(runName == null ? "New Backtest" : runName);
        run.setStrategy(strategy == null ? "Quality Value" : strategy);
        run.setStatus("SUCCESS");
        run.setTotalReturnPct(new BigDecimal("8.40"));
        run.setSharpe(new BigDecimal("1.24"));
        run.setStartedAt(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
        run.setNodeCount(0);
        run.setConfidence(BigDecimal.ZERO);
        mapper.insert(run);
        return run;
    }

    public BacktestRun createFromWorkflowRun(WorkflowRun workflowRun, Map<String, Object> inputs) {
        if (workflowRun == null) {
            throw new IllegalArgumentException("workflowRun is required");
        }
        BacktestRun existing = mapper.findByWorkflowRunId(workflowRun.getRunId());
        if (existing != null) {
            materializeGovernance(workflowRun, existing, inputs);
            return existing;
        }
        BacktestRun run = new BacktestRun();
        run.setId(UUID.randomUUID().toString());
        run.setRunName(displayName(workflowRun));
        run.setStrategy(workflowRun.getWorkflowKey());
        run.setStatus(workflowRun.getStatus());
        run.setTotalReturnPct(BigDecimal.ZERO);
        run.setSharpe(BigDecimal.ZERO);
        run.setStartedAt(workflowRun.getStartedAt());
        run.setCompletedAt(workflowRun.getCompletedAt());
        run.setWorkflowRunId(workflowRun.getRunId());
        run.setTraceId(workflowRun.getTraceId());
        run.setSubject(workflowRun.getSubject());
        run.setSymbol(symbol(inputs));
        run.setInputsJson(toJson(inputs == null ? new LinkedHashMap<String, Object>() : inputs));
        run.setResultJson(workflowRun.getResultJson());
        run.setErrorMessage(workflowRun.getErrorMessage());
        run.setNodeCount(workflowRun.getNodeCount());
        run.setFinalRecommendation(finalRecommendation(workflowRun.getResultJson()));
        run.setConfidence(confidence(workflowRun.getResultJson()));
        mapper.insert(run);
        materializeGovernance(workflowRun, run, inputs);
        return run;
    }

    private void materializeGovernance(WorkflowRun workflowRun, BacktestRun backtestRun, Map<String, Object> inputs) {
        modelGovernanceService.materializeCalls(workflowRun);
        evidenceService.materializeEvidence(workflowRun);
        recommendationService.createFromWorkflowRun(workflowRun, backtestRun, inputs);
    }

    private String displayName(WorkflowRun workflowRun) {
        String subject = workflowRun.getSubject();
        if (subject != null && !subject.trim().isEmpty()) {
            return subject.trim();
        }
        return "Workflow " + workflowRun.getWorkflowKey();
    }

    private String symbol(Map<String, Object> inputs) {
        if (inputs == null) {
            return "";
        }
        Object ticker = inputs.get("ticker");
        if (ticker == null) {
            ticker = inputs.get("symbol");
        }
        return ticker == null ? "" : String.valueOf(ticker).trim().toUpperCase();
    }

    @SuppressWarnings("unchecked")
    private String finalRecommendation(String resultJson) {
        Map<String, Object> result = parse(resultJson);
        Object recommendation = firstPresent(result, "stock_recommendation", "recommendation", "recommend", "finance.stock_recommendation_aggregate");
        if (recommendation instanceof Map) {
            Object summary = ((Map<String, Object>) recommendation).get("summary");
            if (summary == null) {
                summary = ((Map<String, Object>) recommendation).get("content");
            }
            return summary == null ? "" : String.valueOf(summary);
        }
        return recommendation == null ? "" : String.valueOf(recommendation);
    }

    @SuppressWarnings("unchecked")
    private BigDecimal confidence(String resultJson) {
        Map<String, Object> result = parse(resultJson);
        Object recommendation = firstPresent(result, "stock_recommendation", "recommendation", "recommend", "finance.stock_recommendation_aggregate");
        if (recommendation instanceof Map) {
            Object value = ((Map<String, Object>) recommendation).get("confidence");
            if (value instanceof Number) {
                return BigDecimal.valueOf(((Number) value).doubleValue());
            }
            if (value != null) {
                try {
                    return new BigDecimal(String.valueOf(value));
                } catch (Exception ignored) {
                }
            }
        }
        return BigDecimal.ZERO;
    }

    private Object firstPresent(Map<String, Object> result, String... keys) {
        for (String key : keys) {
            if (result.containsKey(key)) {
                return result.get(key);
            }
        }
        return null;
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
}
