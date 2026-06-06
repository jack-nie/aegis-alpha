package com.marketmind.alpha.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketmind.alpha.domain.*;
import com.marketmind.alpha.mapper.AgentCallSpanMapper;
import com.marketmind.alpha.mapper.GovernanceMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ModelGovernanceServiceTest {
    private GovernanceMapper governanceMapper;
    private AgentCallSpanMapper agentCallSpanMapper;
    private ObjectMapper objectMapper;
    private ModelGovernanceService service;

    @BeforeEach
    void setUp() {
        governanceMapper = mock(GovernanceMapper.class);
        agentCallSpanMapper = mock(AgentCallSpanMapper.class);
        objectMapper = new ObjectMapper();
        service = new ModelGovernanceService(governanceMapper, agentCallSpanMapper, objectMapper);
    }

    @Test
    void modelsSeedsDefaultWhenEmpty() {
        when(governanceMapper.countModelConfigs()).thenReturn(0);
        when(governanceMapper.findModelConfigs()).thenReturn(Collections.emptyList());

        service.models();

        verify(governanceMapper).insertModelConfig(any(ModelConfig.class));
    }

    @Test
    void modelsDoesNotSeedWhenModelsExist() {
        when(governanceMapper.countModelConfigs()).thenReturn(1);
        when(governanceMapper.findModelConfigs()).thenReturn(List.of(new ModelConfig()));

        service.models();

        verify(governanceMapper, never()).insertModelConfig(any());
    }

    @Test
    void llmCallsDelegatesToMapper() {
        when(governanceMapper.findLlmCalls("run-1")).thenReturn(Collections.emptyList());

        assertThat(service.llmCalls("run-1")).isEmpty();
        verify(governanceMapper).findLlmCalls("run-1");
    }

    @Test
    void materializeCallsSkipsIfAlreadyMaterialized() {
        when(governanceMapper.countLlmCalls("run-1")).thenReturn(5);
        WorkflowRun run = new WorkflowRun();
        run.setRunId("run-1");

        service.materializeCalls(run);

        verify(agentCallSpanMapper, never()).findByWorkflowRunId(any());
    }

    @Test
    void materializeCallsSkipsIfRunIsNull() {
        service.materializeCalls(null);
        verifyNoInteractions(governanceMapper, agentCallSpanMapper);
    }

    @Test
    void materializeCallsCreatesLlmCallFromAgentSpan() throws Exception {
        when(governanceMapper.countLlmCalls("run-1")).thenReturn(0);
        when(governanceMapper.countModelConfigs()).thenReturn(1);

        AgentCallSpan span = new AgentCallSpan();
        span.setSpanType("agent");
        span.setNodeRunId("nr-1");
        span.setTraceId("trace-1");
        span.setModelName("deepseek-v4-flash");
        span.setStatus("COMPLETED");
        span.setStartedAt("2026-01-01 10:00:00");
        span.setCompletedAt("2026-01-01 10:00:05");
        span.setSortOrder(1);

        String outputJson = objectMapper.writeValueAsString(Map.of(
                "provider", "openai",
                "model", "deepseek-v4-flash",
                "data", Map.of("usage", Map.of(
                        "prompt_tokens", 100,
                        "completion_tokens", 50,
                        "total_tokens", 150
                ))
        ));
        span.setOutputJson(outputJson);

        when(agentCallSpanMapper.findByWorkflowRunId("run-1")).thenReturn(List.of(span));
        when(governanceMapper.findModelConfig("openai", "deepseek-v4-flash")).thenReturn(null);

        WorkflowRun run = new WorkflowRun();
        run.setRunId("run-1");

        service.materializeCalls(run);

        verify(governanceMapper).insertLlmCall(argThat(call ->
                "openai".equals(call.getProvider()) &&
                        "deepseek-v4-flash".equals(call.getModelName()) &&
                        call.getPromptTokens() == 100 &&
                        call.getCompletionTokens() == 50
        ));
    }

    @Test
    void materializeCallsSkipsNonAgentSpans() throws Exception {
        when(governanceMapper.countLlmCalls("run-1")).thenReturn(0);
        when(governanceMapper.countModelConfigs()).thenReturn(1);

        AgentCallSpan span = new AgentCallSpan();
        span.setSpanType("node");
        span.setNodeRunId("nr-1");
        span.setStatus("COMPLETED");
        span.setOutputJson("{}");

        when(agentCallSpanMapper.findByWorkflowRunId("run-1")).thenReturn(List.of(span));

        WorkflowRun run = new WorkflowRun();
        run.setRunId("run-1");

        service.materializeCalls(run);

        verify(governanceMapper, never()).insertLlmCall(any());
    }

    @Test
    void materializeCallsNormalizesLangchainProvider() throws Exception {
        when(governanceMapper.countLlmCalls("run-1")).thenReturn(0);
        when(governanceMapper.countModelConfigs()).thenReturn(1);

        AgentCallSpan span = new AgentCallSpan();
        span.setSpanType("agent");
        span.setNodeRunId("nr-1");
        span.setTraceId("trace-1");
        span.setModelName("gpt-4");
        span.setStatus("COMPLETED");
        span.setStartedAt("2026-01-01 10:00:00");
        span.setCompletedAt("2026-01-01 10:00:05");
        span.setOutputJson(objectMapper.writeValueAsString(Map.of("provider", "langchain-openai")));

        when(agentCallSpanMapper.findByWorkflowRunId("run-1")).thenReturn(List.of(span));
        when(governanceMapper.findModelConfig("openai", "gpt-4")).thenReturn(null);

        WorkflowRun run = new WorkflowRun();
        run.setRunId("run-1");

        service.materializeCalls(run);

        verify(governanceMapper).insertLlmCall(any(LlmCall.class));
        java.util.List<LlmCall> captured = org.mockito.ArgumentCaptor.forClass(LlmCall.class).getAllValues();
        LlmCall inserted = captured.get(0);
        assertThat(inserted.getProvider()).isEqualTo("openai");
        assertThat(inserted.getModelName()).isEqualTo("gpt-4");
    }
}