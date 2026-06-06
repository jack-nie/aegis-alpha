package com.marketmind.alpha.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketmind.alpha.domain.AgentCallSpan;
import com.marketmind.alpha.domain.WorkflowNodeRun;
import com.marketmind.alpha.domain.WorkflowRun;
import com.marketmind.alpha.mapper.AgentCallSpanMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AgentTraceServiceTest {
    private AgentCallSpanMapper mapper;
    private ObjectMapper objectMapper;
    private AgentTraceService service;

    @BeforeEach
    void setUp() {
        mapper = mock(AgentCallSpanMapper.class);
        objectMapper = new ObjectMapper();
        service = new AgentTraceService(mapper, objectMapper);
    }

    @Test
    void recordNodeSpanSetsAgentType() {
        WorkflowRun run = new WorkflowRun();
        run.setRunId("run-1");
        run.setTraceId("trace-1");

        WorkflowNodeRun nodeRun = new WorkflowNodeRun();
        nodeRun.setNodeRunId("nr-1");
        nodeRun.setNodeType("agent");
        nodeRun.setAgentId("agent-1");
        nodeRun.setStartedAt("2026-01-01 10:00:00");
        nodeRun.setCompletedAt("2026-01-01 10:00:05");
        nodeRun.setSortOrder(1);

        Map<String, Object> input = Map.of("query", "analyze AAPL");
        Map<String, Object> output = Map.of("ok", true, "model", "deepseek-v4-flash");

        service.recordNodeSpan(run, nodeRun, "finance.stock_recommendation_aggregate", input, output);

        verify(mapper).insert(argThat(span -> {
            assertThat(span.getSpanType()).isEqualTo("agent");
            assertThat(span.getAgentId()).isEqualTo("agent-1");
            assertThat(span.getToolName()).isEqualTo("finance.stock_recommendation_aggregate");
            assertThat(span.getModelName()).isEqualTo("deepseek-v4-flash");
            assertThat(span.getStatus()).isEqualTo("COMPLETED");
            assertThat(span.getLatencyMs()).isEqualTo(5000L);
            return true;
        }));
    }

    @Test
    void recordNodeSpanSetsNodeTypeForNonAgent() {
        WorkflowRun run = new WorkflowRun();
        run.setRunId("run-2");
        run.setTraceId("trace-2");

        WorkflowNodeRun nodeRun = new WorkflowNodeRun();
        nodeRun.setNodeRunId("nr-2");
        nodeRun.setNodeType("function");
        nodeRun.setStartedAt("2026-01-01 10:00:00");
        nodeRun.setCompletedAt("2026-01-01 10:00:03");
        nodeRun.setSortOrder(2);

        service.recordNodeSpan(run, nodeRun, "hydrate_market_data", Map.of(), Map.of("ok", true));

        verify(mapper).insert(argThat(span -> {
            assertThat(span.getSpanType()).isEqualTo("node");
            return true;
        }));
    }

    @Test
    void recordNodeSpanSetsErrorStatusOnFailure() {
        WorkflowRun run = new WorkflowRun();
        run.setRunId("run-3");
        run.setTraceId("trace-3");

        WorkflowNodeRun nodeRun = new WorkflowNodeRun();
        nodeRun.setNodeRunId("nr-3");
        nodeRun.setNodeType("agent");
        nodeRun.setStartedAt("2026-01-01 10:00:00");
        nodeRun.setCompletedAt("2026-01-01 10:00:01");
        nodeRun.setSortOrder(3);

        Map<String, Object> output = Map.of("ok", false, "reason", "API error");

        service.recordNodeSpan(run, nodeRun, "handler", Map.of(), output);

        verify(mapper).insert(argThat(span -> {
            assertThat(span.getStatus()).isEqualTo("ERROR");
            assertThat(span.getErrorMessage()).isEqualTo("API error");
            return true;
        }));
    }

    @Test
    void recordNodeSpanExtractsUsageFromData() {
        WorkflowRun run = new WorkflowRun();
        run.setRunId("run-4");
        run.setTraceId("trace-4");

        WorkflowNodeRun nodeRun = new WorkflowNodeRun();
        nodeRun.setNodeRunId("nr-4");
        nodeRun.setNodeType("agent");
        nodeRun.setStartedAt("2026-01-01 10:00:00");
        nodeRun.setCompletedAt("2026-01-01 10:00:05");
        nodeRun.setSortOrder(4);

        Map<String, Object> output = Map.of(
                "ok", true,
                "data", Map.of(
                        "usage", Map.of(
                                "prompt_tokens", 200,
                                "completion_tokens", 100,
                                "total_tokens", 300
                        )
                )
        );

        service.recordNodeSpan(run, nodeRun, "handler", Map.of(), output);

        verify(mapper).insert(argThat(span -> {
            assertThat(span.getPromptTokens()).isEqualTo(200);
            assertThat(span.getCompletionTokens()).isEqualTo(100);
            assertThat(span.getTotalTokens()).isEqualTo(300);
            return true;
        }));
    }

    @Test
    void recordNodeSpanUsesCurrentTimeWhenCompletedAtIsNull() {
        WorkflowRun run = new WorkflowRun();
        run.setRunId("run-5");
        run.setTraceId("trace-5");

        WorkflowNodeRun nodeRun = new WorkflowNodeRun();
        nodeRun.setNodeRunId("nr-5");
        nodeRun.setNodeType("agent");
        nodeRun.setStartedAt("2026-01-01 10:00:00");
        nodeRun.setCompletedAt(null);
        nodeRun.setSortOrder(5);

        service.recordNodeSpan(run, nodeRun, "handler", Map.of(), Map.of("ok", true));

        verify(mapper).insert(argThat(span -> {
            assertThat(span.getCompletedAt()).isNotNull();
            return true;
        }));
    }
}