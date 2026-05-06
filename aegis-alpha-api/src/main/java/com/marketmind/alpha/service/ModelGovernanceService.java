package com.marketmind.alpha.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketmind.alpha.domain.AgentCallSpan;
import com.marketmind.alpha.domain.LlmCall;
import com.marketmind.alpha.domain.ModelConfig;
import com.marketmind.alpha.domain.WorkflowRun;
import com.marketmind.alpha.mapper.AgentCallSpanMapper;
import com.marketmind.alpha.mapper.GovernanceMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ModelGovernanceService {
    private final GovernanceMapper governanceMapper;
    private final AgentCallSpanMapper agentCallSpanMapper;
    private final ObjectMapper objectMapper;

    public ModelGovernanceService(GovernanceMapper governanceMapper, AgentCallSpanMapper agentCallSpanMapper, ObjectMapper objectMapper) {
        this.governanceMapper = governanceMapper;
        this.agentCallSpanMapper = agentCallSpanMapper;
        this.objectMapper = objectMapper;
    }

    public List<ModelConfig> models() {
        ensureDefaultModel();
        return governanceMapper.findModelConfigs();
    }

    public List<LlmCall> llmCalls(String workflowRunId) {
        return governanceMapper.findLlmCalls(workflowRunId);
    }

    public void materializeCalls(WorkflowRun run) {
        if (run == null || run.getRunId() == null || governanceMapper.countLlmCalls(run.getRunId()) > 0) {
            return;
        }
        ensureDefaultModel();
        List<AgentCallSpan> spans = agentCallSpanMapper.findByWorkflowRunId(run.getRunId());
        for (AgentCallSpan span : spans) {
            if (!"agent".equals(span.getSpanType())) {
                continue;
            }
            Map<String, Object> output = parse(span.getOutputJson());
            Map<String, Object> usage = usage(output);
            String provider = text(output.get("provider"), "openai");
            String modelName = text(first(output.get("model"), span.getModelName()), "deepseek-v4-flash");

            LlmCall call = new LlmCall();
            call.setLlmCallId(UUID.randomUUID().toString());
            call.setWorkflowRunId(run.getRunId());
            call.setNodeRunId(span.getNodeRunId());
            call.setTraceId(span.getTraceId());
            call.setProvider(provider);
            call.setModelName(modelName);
            call.setStatus(span.getStatus());
            call.setPromptTokens(firstInt(span.getPromptTokens(), usage.get("prompt_tokens"), usage.get("promptTokens")));
            call.setCompletionTokens(firstInt(span.getCompletionTokens(), usage.get("completion_tokens"), usage.get("completionTokens")));
            call.setTotalTokens(firstInt(span.getTotalTokens(), usage.get("total_tokens"), usage.get("totalTokens")));
            call.setEstimatedCostUsd(estimatedCost(provider, modelName, call.getPromptTokens(), call.getCompletionTokens()));
            call.setStartedAt(span.getStartedAt());
            call.setCompletedAt(span.getCompletedAt());
            governanceMapper.insertLlmCall(call);
        }
    }

    private void ensureDefaultModel() {
        if (governanceMapper.countModelConfigs() > 0) {
            return;
        }
        ModelConfig model = new ModelConfig();
        model.setModelConfigId("model-default-deepseek-v4-flash");
        model.setProvider("openai");
        model.setModelName("deepseek-v4-flash");
        model.setStatus("ACTIVE");
        model.setContextWindow(128000);
        model.setPromptTokenCostUsd(BigDecimal.ZERO);
        model.setCompletionTokenCostUsd(BigDecimal.ZERO);
        model.setFallbackModel("deepseek-v4-flash");
        model.setCreatedAt(now());
        governanceMapper.insertModelConfig(model);
    }

    private BigDecimal estimatedCost(String provider, String modelName, Integer promptTokens, Integer completionTokens) {
        ModelConfig config = governanceMapper.findModelConfig(normalizedProvider(provider), modelName);
        if (config == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal promptCost = config.getPromptTokenCostUsd() == null ? BigDecimal.ZERO : config.getPromptTokenCostUsd();
        BigDecimal completionCost = config.getCompletionTokenCostUsd() == null ? BigDecimal.ZERO : config.getCompletionTokenCostUsd();
        return promptCost.multiply(BigDecimal.valueOf(promptTokens == null ? 0 : promptTokens))
                .add(completionCost.multiply(BigDecimal.valueOf(completionTokens == null ? 0 : completionTokens)));
    }

    private String normalizedProvider(String provider) {
        String value = text(provider, "openai");
        return value.startsWith("langchain-") ? value.substring("langchain-".length()) : value;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> usage(Map<String, Object> output) {
        Object data = output.get("data");
        if (data instanceof Map && ((Map<String, Object>) data).get("usage") instanceof Map) {
            return (Map<String, Object>) ((Map<String, Object>) data).get("usage");
        }
        Object usage = output.get("usage");
        if (usage instanceof Map) {
            return (Map<String, Object>) usage;
        }
        return new LinkedHashMap<>();
    }

    private Integer firstInt(Object... values) {
        for (Object value : values) {
            Integer parsed = integer(value);
            if (parsed != null) {
                return parsed;
            }
        }
        return null;
    }

    private Integer integer(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (Exception ignored) {
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

    private String now() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}
