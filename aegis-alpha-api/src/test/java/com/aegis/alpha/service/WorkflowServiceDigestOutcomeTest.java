package com.aegis.alpha.service;

import com.aegis.alpha.domain.AgentTemplate;
import com.aegis.alpha.domain.WorkflowDefinition;
import com.aegis.alpha.domain.WorkflowRun;
import com.aegis.alpha.domain.WorkflowVersion;
import com.aegis.alpha.mapper.AgentMapper;
import com.aegis.alpha.mapper.WorkflowMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** P3-16: digest workflows skip recommendation/backtest materialization; default model comes from config. */
class WorkflowServiceDigestOutcomeTest {
    private WorkflowMapper mapper;
    private LangChainGateway gateway;
    private BacktestService backtestService;
    private WorkflowService service;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicReference<WorkflowRun> storedRun = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        mapper = mock(WorkflowMapper.class);
        gateway = mock(LangChainGateway.class);
        backtestService = mock(BacktestService.class);
        TokenService tokenService = mock(TokenService.class);
        when(tokenService.issueServiceDelegation(anyString(), nullable(String.class), nullable(String.class), any(), anyLong()))
                .thenReturn("delegated-test-token");
        service = new WorkflowService(mapper, mock(AgentMapper.class), objectMapper, gateway,
                mock(CacheService.class), backtestService, mock(AgentTraceService.class),
                new WorkflowValidationService(), tokenService, false);
        for (String key : new String[] {"daily", "stock_analysis"}) {
            WorkflowDefinition definition = new WorkflowDefinition();
            definition.setWorkflowKey(key);
            definition.setName(key);
            definition.setEngine("langgraph");
            when(mapper.findDefinition(key)).thenReturn(definition);
        }
        doAnswer(inv -> { storedRun.set(copy(inv.getArgument(0))); return null; }).when(mapper).insertRun(any(WorkflowRun.class));
        doAnswer(inv -> { storedRun.set(copy(inv.getArgument(0))); return null; }).when(mapper).updateRun(any(WorkflowRun.class));
        CasMapperStubs.casOn(mapper, storedRun, this::copy);
        when(mapper.findRun(anyString())).thenAnswer(inv -> storedRun.get() == null ? null : copy(storedRun.get()));
    }

    @Test
    void dailyRunDoesNotCreateRecommendationOrBacktest() {
        when(gateway.isEnabled()).thenReturn(true);
        when(gateway.executeWorkflow(anyMap(), anyString(), anyMap(), nullable(String.class), anyString(), anyBoolean(), any()))
                .thenReturn(okResponse());

        WorkflowRun run = service.start("daily", "global market daily", new LinkedHashMap<String, Object>());

        assertThat(run.getStatus()).isEqualTo("COMPLETED");
        verify(backtestService).materializeRunTrace(any(WorkflowRun.class));
        verify(backtestService, never()).createFromWorkflowRun(any(), any());
    }

    @Test
    void singleTickerRunStillCreatesBacktestAndRecommendation() {
        when(gateway.isEnabled()).thenReturn(true);
        when(gateway.executeWorkflow(anyMap(), anyString(), anyMap(), nullable(String.class), anyString(), anyBoolean(), any()))
                .thenReturn(okResponse());

        service.start("stock_analysis", "AAPL", new LinkedHashMap<String, Object>());

        verify(backtestService).createFromWorkflowRun(any(), any());
        verify(backtestService, never()).materializeRunTrace(any());
    }

    @Test
    void inlineAgentDefaultModelComesFromConfig() {
        when(gateway.isEnabled()).thenReturn(false);
        service.setDefaultModelName("deepseek-v4-pro");
        String layout = "{\"nodes\":["
                + "{\"id\":\"start\",\"type\":\"start\",\"data\":{\"handler\":\"start\",\"nodeType\":\"start\"}},"
                + "{\"id\":\"n1\",\"type\":\"agent\",\"data\":{\"handler\":\"general.agent\",\"nodeType\":\"agent\"}},"
                + "{\"id\":\"end\",\"type\":\"end\",\"data\":{\"handler\":\"end\",\"nodeType\":\"end\"}}],"
                + "\"edges\":[{\"id\":\"e1\",\"source\":\"start\",\"target\":\"n1\"},"
                + "{\"id\":\"e2\",\"source\":\"n1\",\"target\":\"end\"}]}";
        WorkflowVersion version = new WorkflowVersion();
        version.setVersionId("v-custom");
        version.setWorkflowKey("custom");
        version.setLayoutJson(layout);
        when(mapper.findLatestVersion("custom")).thenReturn(version);
        Map<String, Object> nodeOutput = new LinkedHashMap<>();
        nodeOutput.put("ok", true);
        when(gateway.executeNode(any(), anyMap(), anyMap(), anyString(), nullable(String.class))).thenReturn(nodeOutput);

        service.start("custom", "AAPL", new LinkedHashMap<String, Object>());

        ArgumentCaptor<AgentTemplate> agent = ArgumentCaptor.forClass(AgentTemplate.class);
        verify(gateway, atLeastOnce()).executeNode(agent.capture(), anyMap(), anyMap(), anyString(), nullable(String.class));
        assertThat(agent.getAllValues()).anySatisfy(a -> assertThat(a.getModelName()).isEqualTo("deepseek-v4-pro"));
    }

    private static Map<String, Object> okResponse() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", true);
        response.put("final_state", new LinkedHashMap<String, Object>());
        response.put("trace", new ArrayList<Object>());
        response.put("node_outputs", new LinkedHashMap<String, Object>());
        return response;
    }

    private WorkflowRun copy(WorkflowRun source) {
        return objectMapper.convertValue(source, WorkflowRun.class);
    }
}
