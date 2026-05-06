package com.marketmind.alpha.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketmind.alpha.domain.AgentCallSpan;
import com.marketmind.alpha.domain.WorkflowNodeRun;
import com.marketmind.alpha.domain.WorkflowRun;
import com.marketmind.alpha.mapper.AgentCallSpanMapper;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
        span.setPromptTokens(number(output, "promptTokens"));
        span.setCompletionTokens(number(output, "completionTokens"));
        span.setTotalTokens(number(output, "totalTokens"));
        span.setSortOrder(nodeRun.getSortOrder());
        mapper.insert(span);
    }

    private Long latencyMillis(String startedAt, String completedAt) {
        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            return Duration.between(LocalDateTime.parse(startedAt, formatter), LocalDateTime.parse(completedAt, formatter)).toMillis();
        } catch (Exception ignored) {
            return 0L;
        }
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
