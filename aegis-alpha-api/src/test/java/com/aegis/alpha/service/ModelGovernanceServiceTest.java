package com.aegis.alpha.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.aegis.alpha.domain.*;
import com.aegis.alpha.mapper.AgentCallSpanMapper;
import com.aegis.alpha.mapper.GovernanceMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.*;
import java.util.AbstractMap.SimpleEntry;

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

    @SafeVarargs
    private static <K, V> Map<K, V> mapOf(Map.Entry<K, V>... entries) {
        Map<K, V> map = new HashMap<>();
        for (Map.Entry<K, V> entry : entries) {
            map.put(entry.getKey(), entry.getValue());
        }
        return map;
    }

    private static <K, V> Map.Entry<K, V> entry(K key, V value) {
        return new SimpleEntry<>(key, value);
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
        when(governanceMapper.findModelConfigs()).thenReturn(Arrays.asList(new ModelConfig()));

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

        String outputJson = objectMapper.writeValueAsString(mapOf(
                entry("provider", "openai"),
                entry("model", "deepseek-v4-flash"),
                entry("data", mapOf(entry("usage", mapOf(
                        entry("prompt_tokens", 100),
                        entry("completion_tokens", 50),
                        entry("total_tokens", 150)
                ))))
        ));
        span.setOutputJson(outputJson);

        when(agentCallSpanMapper.findByWorkflowRunId("run-1")).thenReturn(Arrays.asList(span));
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

        when(agentCallSpanMapper.findByWorkflowRunId("run-1")).thenReturn(Arrays.asList(span));

        WorkflowRun run = new WorkflowRun();
        run.setRunId("run-1");

        service.materializeCalls(run);

        verify(governanceMapper, never()).insertLlmCall(any());
    }

    @Test
    void materializeCallsStoresRawProviderFromOutput() throws Exception {
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
        span.setOutputJson(objectMapper.writeValueAsString(mapOf(entry("provider", "langchain-openai"))));

        when(agentCallSpanMapper.findByWorkflowRunId("run-1")).thenReturn(Arrays.asList(span));
        when(governanceMapper.findModelConfig("openai", "gpt-4")).thenReturn(null);

        WorkflowRun run = new WorkflowRun();
        run.setRunId("run-1");

        service.materializeCalls(run);

        org.mockito.ArgumentCaptor<LlmCall> captor = org.mockito.ArgumentCaptor.forClass(LlmCall.class);
        verify(governanceMapper).insertLlmCall(captor.capture());
        LlmCall inserted = captor.getValue();
        assertThat(inserted.getProvider()).isEqualTo("langchain-openai");
        assertThat(inserted.getModelName()).isEqualTo("gpt-4");
    }
}