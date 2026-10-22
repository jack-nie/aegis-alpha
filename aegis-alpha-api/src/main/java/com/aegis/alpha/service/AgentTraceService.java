package com.aegis.alpha.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.aegis.alpha.domain.AgentCallSpan;
import com.aegis.alpha.domain.WorkflowNodeRun;
import com.aegis.alpha.domain.WorkflowRun;
import com.aegis.alpha.mapper.AgentCallSpanMapper;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class AgentTraceService {
    private final AgentCallSpanMapper mapper;
    private final ObjectMapper objectMapper;

    public AgentTraceService(AgentCallSpanMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    public void recordNodeSpan(WorkflowRun run, WorkflowNodeRun nodeRun, String handler, Map<String, Object> input, Map<String, Object> output) {
        AgentCallSpan span = new AgentCallSpan();
        span.setSpanId(UUID.randomUUID().toString());
        span.setTraceId(run.getTraceId());
        span.setWorkflowRunId(run.getRunId());
        span.setNodeRunId(nodeRun.getNodeRunId());
        span.setSpanType("agent".equals(nodeRun.getNodeType()) ? "agent" : "node");
        span.setAgentId(nodeRun.getAgentId());
        span.setToolName(handler);
        span.setModelName(text(output, "model", ""));
        span.setStatus(Boolean.FALSE.equals(output == null ? null : output.get("ok")) ? "ERROR" : "COMPLETED");
        span.setInputJson(toJson(input));
        span.setOutputJson(toJson(output));
        span.setErrorMessage(text(output, "reason", text(output, "error", "")));
        span.setStartedAt(nodeRun.getStartedAt());
        span.setCompletedAt(nodeRun.getCompletedAt() == null ? now() : nodeRun.getCompletedAt());
        span.setLatencyMs(latencyMillis(nodeRun.getStartedAt(), span.getCompletedAt()));
        Map<String, Object> usage = usageFrom(output);
        span.setPromptTokens(firstNumber(usage, "prompt_tokens", "inputTokens", "input_tokens", "promptTokens"));
        span.setCompletionTokens(firstNumber(usage, "completion_tokens", "outputTokens", "output_tokens", "completionTokens"));
        Integer total = firstNumber(usage, "total_tokens", "totalTokens");
        if (total == null && span.getPromptTokens() != null && span.getCompletionTokens() != null) {
            total = span.getPromptTokens() + span.getCompletionTokens();
        }
        span.setTotalTokens(total);
        span.setSortOrder(nodeRun.getSortOrder());
        mapper.insert(span);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> usageFrom(Map<String, Object> output) {
        if (output == null) {
            return new LinkedHashMap<>();
        }
        // Orchestrator contract (P2-5): data.tokenUsage{inputTokens,outputTokens,totalTokens};
        // legacy shapes data.usage / usage / top-level OpenAI keys still accepted.
        Object data = output.get("data");
        if (data instanceof Map) {
            for (String key : new String[] {"tokenUsage", "usage"}) {
                Object usage = ((Map<String, Object>) data).get(key);
                if (usage instanceof Map) {
                    return (Map<String, Object>) usage;
                }
            }
        }
        for (String key : new String[] {"tokenUsage", "usage"}) {
            Object usage = output.get(key);
            if (usage instanceof Map) {
                return (Map<String, Object>) usage;
            }
        }
        return output;
    }

    private Long latencyMillis(String startedAt, String completedAt) {
        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            return Duration.between(LocalDateTime.parse(startedAt, formatter), LocalDateTime.parse(completedAt, formatter)).toMillis();
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private Integer firstNumber(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Integer value = number(map, key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private Integer number(Map<String, Object> map, String key) {
        if (map == null || map.get(key) == null) {
            return null;
        }
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ignored) {
            return null;
        }
    }

    private String text(Map<String, Object> map, String key, String fallback) {
        if (map == null || map.get(key) == null) {
            return fallback;
        }
        String value = String.valueOf(map.get(key)).trim();
        return value.isEmpty() ? fallback : value;
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
