package com.aegis.alpha.service;

import com.aegis.alpha.domain.WorkflowDefinition;
import com.aegis.alpha.domain.WorkflowNodeRun;
import com.aegis.alpha.domain.WorkflowRun;
import com.aegis.alpha.mapper.AgentMapper;
import com.aegis.alpha.mapper.WorkflowMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P1-14: sync start / queued dispatch run the whole graph on the orchestrator (/execute-workflow);
 * the Java node loop is only the explicit-disable fallback and is marked engine=java-fallback.
 */
class WorkflowServiceEngineRoutingTest {
    private WorkflowMapper mapper;
    private LangChainGateway gateway;
    private WorkflowService service;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicReference<WorkflowRun> storedRun = new AtomicReference<>();
    private final List<WorkflowNodeRun> nodeRuns = new ArrayList<>();

    @BeforeEach
    void setUp() {
        mapper = mock(WorkflowMapper.class);
        gateway = mock(LangChainGateway.class);
        TokenService tokenService = mock(TokenService.class);
        when(tokenService.issueServiceDelegation(anyString(), nullable(String.class), nullable(String.class), any(), anyLong()))
                .thenReturn("delegated-test-token");
        service = new WorkflowService(mapper, mock(AgentMapper.class), objectMapper, gateway,
                mock(CacheService.class), mock(BacktestService.class), mock(AgentTraceService.class),
                new WorkflowValidationService(), tokenService, false);
        WorkflowDefinition definition = new WorkflowDefinition();
        definition.setWorkflowKey("daily");
        definition.setName("Daily");
        definition.setEngine("langgraph");
        when(mapper.findDefinition("daily")).thenReturn(definition);
        doAnswer(inv -> { storedRun.set(copy(inv.getArgument(0))); return null; }).when(mapper).insertRun(any(WorkflowRun.class));
        doAnswer(inv -> { storedRun.set(copy(inv.getArgument(0))); return null; }).when(mapper).updateRun(any(WorkflowRun.class));
        doAnswer(inv -> { nodeRuns.add(inv.getArgument(0)); return null; }).when(mapper).insertNodeRun(any(WorkflowNodeRun.class));
        when(mapper.findRun(anyString())).thenAnswer(inv -> storedRun.get() == null ? null : copy(storedRun.get()));
    }

    @Test
    void startWithApprovalCallsExecuteWorkflowAndPausesOnInterrupt() {
        when(gateway.isEnabled()).thenReturn(true);
        Map<String, Object> finalState = new LinkedHashMap<>();
        finalState.put("interrupted", true);
        when(gateway.executeWorkflow(anyMap(), anyString(), anyMap(), nullable(String.class), anyString(), anyBoolean()))
                .thenReturn(response(true, finalState, trace("start")));
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("requireApproval", true);

        WorkflowRun run = service.start("daily", "AAPL", inputs);

        ArgumentCaptor<String> threadId = ArgumentCaptor.forClass(String.class);
        verify(gateway).executeWorkflow(anyMap(), eq("AAPL"), anyMap(), nullable(String.class), threadId.capture(), eq(true));
        assertThat(threadId.getValue()).isEqualTo(run.getRunId());
        verify(gateway, never()).executeNode(any(), anyMap(), anyMap(), anyString(), nullable(String.class));
        assertThat(run.getStatus()).isEqualTo("PAUSED");
        assertThat(run.getControlStatus()).isEqualTo("HUMAN_INTERRUPT");
        assertThat(run.getResultJson()).contains("\"engine\":\"langgraph\"");
    }

    @Test
    void completedOrchestratorRunMaterializesOneNodeRunPerTraceEntryInOrder() {
        when(gateway.isEnabled()).thenReturn(true);
        Map<String, Object> finalState = new LinkedHashMap<>();
        finalState.put("summary", "done");
        when(gateway.executeWorkflow(anyMap(), anyString(), anyMap(), nullable(String.class), anyString(), anyBoolean()))
                .thenReturn(response(true, finalState, trace("start", "market", "end")));

        WorkflowRun run = service.start("daily", "AAPL", new LinkedHashMap<String, Object>());

        assertThat(run.getStatus()).isEqualTo("COMPLETED");
        assertThat(nodeRuns).hasSize(3);
        assertThat(nodeRuns.get(0).getNodeId()).isEqualTo("start");
        assertThat(nodeRuns.get(0).getSortOrder()).isLessThan(nodeRuns.get(1).getSortOrder());
        assertThat(nodeRuns.get(1).getSortOrder()).isLessThan(nodeRuns.get(2).getSortOrder());
        assertThat(run.getNodeCount()).isEqualTo(3);
    }

    @Test
    void orchestratorOkFalseFailsTheRun() {
        when(gateway.isEnabled()).thenReturn(true);
        Map<String, Object> failed = new LinkedHashMap<>();
        failed.put("ok", false);
        failed.put("error", "boom");
        when(gateway.executeWorkflow(anyMap(), anyString(), anyMap(), nullable(String.class), anyString(), anyBoolean()))
                .thenReturn(failed);

        WorkflowRun run = service.start("daily", "AAPL", new LinkedHashMap<String, Object>());

        assertThat(run.getStatus()).isEqualTo("FAILED");
        assertThat(run.getErrorMessage()).contains("boom");
    }

    @Test
    void queuedDispatchAlsoUsesOrchestrator() {
        when(gateway.isEnabled()).thenReturn(true);
        when(gateway.executeWorkflow(anyMap(), anyString(), anyMap(), nullable(String.class), anyString(), anyBoolean()))
                .thenReturn(response(true, new LinkedHashMap<String, Object>(), trace("start")));
        WorkflowRun queued = service.queueStart("daily", "AAPL", new LinkedHashMap<String, Object>(), null);

        WorkflowRun dispatched = service.dispatchQueuedRun(queued.getRunId());

        assertThat(dispatched.getStatus()).isEqualTo("COMPLETED");
        verify(gateway, atLeastOnce()).executeWorkflow(anyMap(), anyString(), anyMap(), nullable(String.class),
                eq(queued.getRunId()), anyBoolean());
    }

    @Test
    void disabledOrchestratorFallsBackToJavaLoopAndMarksEngine() {
        when(gateway.isEnabled()).thenReturn(false);
        Map<String, Object> nodeOutput = new LinkedHashMap<>();
        nodeOutput.put("ok", true);
        nodeOutput.put("summary", "local");
        when(gateway.executeNode(any(), anyMap(), anyMap(), anyString(), nullable(String.class))).thenReturn(nodeOutput);

        WorkflowRun run = service.start("daily", "AAPL", new LinkedHashMap<String, Object>());

        verify(gateway, never()).executeWorkflow(anyMap(), anyString(), anyMap(), nullable(String.class), anyString(), anyBoolean());
        assertThat(run.getStatus()).isEqualTo("COMPLETED");
        assertThat(run.getResultJson()).contains("\"engine\":\"java-fallback\"");
    }

    private static Map<String, Object> response(boolean ok, Map<String, Object> finalState, List<Object> trace) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", ok);
        response.put("final_state", finalState);
        response.put("trace", trace);
        response.put("node_outputs", new LinkedHashMap<String, Object>());
        return response;
    }

    private static List<Object> trace(String... nodeIds) {
        List<Object> trace = new ArrayList<>();
        for (String nodeId : nodeIds) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("nodeId", nodeId);
            entry.put("nodeName", nodeId);
            entry.put("ok", true);
            entry.put("status", "completed");
            trace.add(entry);
        }
        return trace;
    }

    private static WorkflowRun copy(WorkflowRun source) {
        WorkflowRun copy = new WorkflowRun();
        copy.setRunId(source.getRunId());
        copy.setWorkflowKey(source.getWorkflowKey());
        copy.setTraceId(source.getTraceId());
        copy.setStatus(source.getStatus());
        copy.setSubject(source.getSubject());
        copy.setStartedAt(source.getStartedAt());
        copy.setCompletedAt(source.getCompletedAt());
        copy.setResultJson(source.getResultJson());
        copy.setErrorMessage(source.getErrorMessage());
        copy.setNodeCount(source.getNodeCount());
        copy.setWorkflowVersionId(source.getWorkflowVersionId());
        copy.setInputsJson(source.getInputsJson());
        copy.setControlStatus(source.getControlStatus());
        copy.setPauseRequested(source.getPauseRequested());
        copy.setCancelRequested(source.getCancelRequested());
        copy.setUserId(source.getUserId());
        copy.setTenantId(source.getTenantId());
        return copy;
    }
}
