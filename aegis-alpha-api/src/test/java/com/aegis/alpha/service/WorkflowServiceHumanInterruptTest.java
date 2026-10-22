package com.aegis.alpha.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.aegis.alpha.domain.WorkflowDefinition;
import com.aegis.alpha.domain.WorkflowRun;
import com.aegis.alpha.domain.WorkflowRunEvent;
import com.aegis.alpha.mapper.AgentMapper;
import com.aegis.alpha.mapper.WorkflowMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Durable human-approval pause uses the workflow run id as the orchestrator thread id.
 */
class WorkflowServiceHumanInterruptTest {
    private WorkflowMapper mapper;
    private AgentMapper agentMapper;
    private ObjectMapper objectMapper;
    private LangChainGateway langChainGateway;
    private CacheService cacheService;
    private BacktestService backtestService;
    private AgentTraceService agentTraceService;
    private WorkflowValidationService validationService;
    private TokenService tokenService;
    private WorkflowService service;
    private HttpServer server;
    private final AtomicReference<WorkflowRun> storedRun = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        mapper = mock(WorkflowMapper.class);
        agentMapper = mock(AgentMapper.class);
        objectMapper = new ObjectMapper();
        langChainGateway = mock(LangChainGateway.class);
        cacheService = mock(CacheService.class);
        backtestService = mock(BacktestService.class);
        agentTraceService = mock(AgentTraceService.class);
        validationService = new WorkflowValidationService();
        tokenService = mock(TokenService.class);
        when(tokenService.issueServiceDelegation(anyString(), anyString(), anyString(), any(), anyLong()))
                .thenReturn("delegated-test-token");
        service = new WorkflowService(
                mapper, agentMapper, objectMapper, langChainGateway,
                cacheService, backtestService, agentTraceService, validationService,
                tokenService, false);

        when(mapper.findLatestVersion("daily")).thenReturn(null);
        WorkflowDefinition definition = new WorkflowDefinition();
        definition.setWorkflowKey("daily");
        definition.setName("Daily");
        definition.setEngine("langgraph");
        when(mapper.findDefinition("daily")).thenReturn(definition);
        when(mapper.findLayout("daily")).thenReturn(null);

        doInsertAndUpdate();
        /* P2-17: conditional run update honours the stored status and cancel flag. */
        when(mapper.updateRunStatusIf(any(WorkflowRun.class), org.mockito.ArgumentMatchers.<String>anyList(), anyBoolean()))
                .thenAnswer(invocation -> {
                    WorkflowRun update = invocation.getArgument(0);
                    java.util.List<String> expected = invocation.getArgument(1);
                    boolean requireNoCancel = invocation.getArgument(2);
                    WorkflowRun current = storedRun.get();
                    if (current == null || !current.getRunId().equals(update.getRunId())
                            || !expected.contains(current.getStatus())
                            || (requireNoCancel && current.getCancelRequested() != null && current.getCancelRequested() == 1)) {
                        return 0;
                    }
                    storedRun.set(copyRun(update));
                    return 1;
                });
        when(mapper.transitionControlStatus(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            WorkflowRun current = storedRun.get();
            if (current == null || !current.getRunId().equals(invocation.getArgument(0))
                    || !((String) invocation.getArgument(1)).equals(current.getControlStatus())) {
                return 0;
            }
            WorkflowRun moved = copyRun(current);
            moved.setControlStatus((String) invocation.getArgument(2));
            storedRun.set(moved);
            return 1;
        });
        when(mapper.findRun(anyString())).thenAnswer(invocation -> {
            WorkflowRun current = storedRun.get();
            if (current == null || !current.getRunId().equals(invocation.getArgument(0))) {
                return null;
            }
            return copyRun(current);
        });
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void startWithStreamingPassesRunIdAndRequireApproval() throws Exception {
        stubCompletedStream();
        Map<String, Object> inputs = new HashMap<>();
        inputs.put("ticker", "AAPL");
        inputs.put("requireApproval", true);

        service.startWithStreaming("daily", "needs approval", inputs, new SseEmitter(30_000L));

        ArgumentCaptor<WorkflowRun> inserted = ArgumentCaptor.forClass(WorkflowRun.class);
        verify(mapper).insertRun(inserted.capture());
        String runId = inserted.getValue().getRunId();
        assertThat(runId).isNotBlank();
        verify(langChainGateway, times(1)).buildStreamBody(
                any(), eq("needs approval"), any(), any(), eq(runId), eq(true));
    }

    @Test
    void humanInterruptStoresPausedRunAndInterruptPayload() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String sseResponse = "event: human_interrupt\n"
                + "data: {\"reason\":\"approval\",\"threadId\":\"should-echo\"}\n"
                + "\n";
        byte[] responseBytes = sseResponse.getBytes(StandardCharsets.UTF_8);
        server.createContext("/stream", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, responseBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
        });
        server.start();
        when(langChainGateway.streamWorkflowUrl())
                .thenReturn("http://127.0.0.1:" + server.getAddress().getPort() + "/stream");
        when(langChainGateway.buildStreamBody(any(), any(), any(), nullable(String.class), nullable(String.class), anyBoolean()))
                .thenReturn("{}");
        when(langChainGateway.serviceAuthorizationHeader()).thenReturn(null);

        WorkflowRun result = service.startWithStreaming("daily", "paused", new HashMap<>(), new SseEmitter(30_000L));

        assertThat(result.getStatus()).isEqualTo("PAUSED");
        assertThat(result.getControlStatus()).isEqualTo("HUMAN_INTERRUPT");
        assertThat(result.getPauseRequested()).isEqualTo(1);
        assertThat(result.getResultJson()).contains("approval");
        verify(backtestService, never()).createFromWorkflowRun(any(), any());

        ArgumentCaptor<WorkflowRunEvent> events = ArgumentCaptor.forClass(WorkflowRunEvent.class);
        verify(mapper, atLeastOnce()).insertRunEvent(events.capture());
        assertThat(events.getAllValues()).anyMatch(event ->
                "RUN_PAUSED".equals(event.getEventType())
                        && event.getPayloadJson() != null
                        && event.getPayloadJson().contains("approval"));
    }

    @Test
    void decideApprovalResumesHumanInterruptWithoutRestartingStream() {
        WorkflowRun paused = new WorkflowRun();
        paused.setRunId("run-hitl");
        paused.setStatus("PAUSED");
        paused.setControlStatus("HUMAN_INTERRUPT");
        paused.setPauseRequested(1);
        storedRun.set(paused);
        Map<String, Object> resume = new LinkedHashMap<>();
        resume.put("ok", true);
        resume.put("final_state", new LinkedHashMap<String, Object>());
        when(langChainGateway.resumeWorkflow("run-hitl", true)).thenReturn(resume);

        WorkflowRun completed = service.decideApproval("run-hitl", true);

        assertThat(completed.getStatus()).isEqualTo("COMPLETED");
        assertThat(completed.getControlStatus()).isEqualTo("COMPLETED");
        verify(langChainGateway, times(1)).resumeWorkflow("run-hitl", true);
        verify(langChainGateway, never()).streamWorkflowUrl();
        verify(langChainGateway, never()).buildStreamBody(any(), any(), any());
        verify(langChainGateway, never()).buildStreamBody(any(), any(), any(), nullable(String.class));
        verify(langChainGateway, never()).buildStreamBody(
                any(), any(), any(), nullable(String.class), nullable(String.class), anyBoolean());
    }

    @Test
    void decideApprovalRejectsRunThatIsNotWaiting() {
        WorkflowRun queued = new WorkflowRun();
        queued.setRunId("run-queued");
        queued.setStatus("QUEUED");
        queued.setControlStatus("QUEUED");
        storedRun.set(queued);

        assertThatThrownBy(() -> service.decideApproval("run-queued", true))
                .isInstanceOf(IllegalStateException.class);
        verify(langChainGateway, never()).resumeWorkflow(anyString(), anyBoolean());
    }

    @Test
    void pauseThenResumeQueuedRunRequeuesWithoutResumeWorkflow() {
        WorkflowRun queued = new WorkflowRun();
        queued.setRunId("run-op");
        queued.setStatus("QUEUED");
        queued.setControlStatus("QUEUED");
        queued.setPauseRequested(0);
        storedRun.set(queued);

        WorkflowRun paused = service.pauseRun("run-op");
        assertThat(paused.getStatus()).isEqualTo("PAUSED");
        assertThat(paused.getControlStatus()).isEqualTo("PAUSED");

        WorkflowRun resumed = service.resumeRun("run-op");
        assertThat(resumed.getStatus()).isEqualTo("QUEUED");
        assertThat(resumed.getControlStatus()).isEqualTo("QUEUED");
        verify(langChainGateway, never()).resumeWorkflow(anyString(), anyBoolean());
    }

    @Test
    void resumeRunLeavesHumanInterruptPaused() {
        WorkflowRun paused = new WorkflowRun();
        paused.setRunId("run-hitl-stay");
        paused.setStatus("PAUSED");
        paused.setControlStatus("HUMAN_INTERRUPT");
        storedRun.set(paused);

        WorkflowRun stayed = service.resumeRun("run-hitl-stay");

        assertThat(stayed.getStatus()).isEqualTo("PAUSED");
        assertThat(stayed.getControlStatus()).isEqualTo("HUMAN_INTERRUPT");
        verify(langChainGateway, never()).resumeWorkflow(anyString(), anyBoolean());
    }

    private void stubCompletedStream() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String sseResponse = "event: workflow_complete\ndata: {\"ok\":true}\n\n";
        byte[] responseBytes = sseResponse.getBytes(StandardCharsets.UTF_8);
        server.createContext("/stream", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, responseBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
        });
        server.start();
        when(langChainGateway.streamWorkflowUrl())
                .thenReturn("http://127.0.0.1:" + server.getAddress().getPort() + "/stream");
        when(langChainGateway.buildStreamBody(any(), any(), any(), nullable(String.class), nullable(String.class), anyBoolean()))
                .thenReturn("{}");
        when(langChainGateway.serviceAuthorizationHeader()).thenReturn(null);
    }

    private void doInsertAndUpdate() {
        org.mockito.stubbing.Answer<Void> remember = invocation -> {
            storedRun.set(copyRun(invocation.getArgument(0)));
            return null;
        };
        org.mockito.Mockito.doAnswer(remember).when(mapper).insertRun(any(WorkflowRun.class));
        org.mockito.Mockito.doAnswer(remember).when(mapper).updateRun(any(WorkflowRun.class));
    }

    private static WorkflowRun copyRun(WorkflowRun source) {
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
        return copy;
    }

    /* ---------------- P1-5: approval decision closure ---------------- */

    @Test
    void rejectionEndsRunAsRejectedAndAudits() {
        WorkflowRun paused = pausedRun("run-reject");
        storedRun.set(paused);
        Map<String, Object> resume = new LinkedHashMap<>();
        resume.put("ok", true);
        when(langChainGateway.resumeWorkflow("run-reject", false)).thenReturn(resume);
        com.aegis.alpha.security.AuthenticatedPrincipal desk = new com.aegis.alpha.security.AuthenticatedPrincipal(
                "u-desk", "desk.lead", "t-1", java.util.Collections.singletonList("desk"));

        WorkflowRun rejected = service.decideApproval("run-reject", false, "evidence too thin", desk);

        assertThat(rejected.getStatus()).isEqualTo("REJECTED");
        assertThat(rejected.getResultJson()).contains("\"approval\":\"rejected\"").contains("u-desk");
        verify(mapper, org.mockito.Mockito.atLeastOnce()).insertRunEvent(org.mockito.ArgumentMatchers.argThat(
                event -> "RUN_APPROVAL_DECISION".equals(event.getEventType())
                        && event.getPayloadJson().contains("evidence too thin")));
    }

    @Test
    void repeatedApprovalResumesOrchestratorOnlyOnce() {
        storedRun.set(pausedRun("run-twice"));
        Map<String, Object> resume = new LinkedHashMap<>();
        resume.put("ok", true);
        when(langChainGateway.resumeWorkflow("run-twice", true)).thenReturn(resume);

        WorkflowRun first = service.decideApproval("run-twice", true);
        WorkflowRun second = service.decideApproval("run-twice", true);

        assertThat(first.getStatus()).isEqualTo("COMPLETED");
        assertThat(second.getStatus()).isEqualTo("COMPLETED");
        verify(langChainGateway, times(1)).resumeWorkflow(anyString(), anyBoolean());
    }

    @Test
    void lostCompareAndSetDoesNotResume() {
        storedRun.set(pausedRun("run-race"));
        when(mapper.transitionControlStatus("run-race", "HUMAN_INTERRUPT", "APPROVAL_IN_PROGRESS")).thenReturn(0);

        service.decideApproval("run-race", true);

        verify(langChainGateway, never()).resumeWorkflow(anyString(), anyBoolean());
    }

    @Test
    void failedResumeReturnsRunToHumanInterrupt() {
        storedRun.set(pausedRun("run-fail"));
        Map<String, Object> resume = new LinkedHashMap<>();
        resume.put("ok", false);
        resume.put("error", "orchestrator down");
        when(langChainGateway.resumeWorkflow("run-fail", true)).thenReturn(resume);

        WorkflowRun after = service.decideApproval("run-fail", true);

        assertThat(after.getControlStatus()).isEqualTo("HUMAN_INTERRUPT");
        assertThat(after.getStatus()).isEqualTo("PAUSED");
    }

    @Test
    void approverRolesDefaultToDeskAndAdmin() {
        assertThat(service.isApprover(new com.aegis.alpha.security.AuthenticatedPrincipal(
                "u", "u", "t", java.util.Collections.singletonList("desk")))).isTrue();
        assertThat(service.isApprover(new com.aegis.alpha.security.AuthenticatedPrincipal(
                "u", "u", "t", java.util.Collections.singletonList("admin")))).isTrue();
        assertThat(service.isApprover(new com.aegis.alpha.security.AuthenticatedPrincipal(
                "u", "u", "t", java.util.Collections.singletonList("research_user")))).isFalse();
    }

    /* ---------------- P2-13 / Phase-1 leftover (a): resume trace materialization ---------------- */

    private static Map<String, Object> traceEntry(String nodeId, boolean ok) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("nodeId", nodeId);
        entry.put("nodeName", nodeId);
        entry.put("ok", ok);
        entry.put("status", ok ? "completed" : "failed");
        return entry;
    }

    private Map<String, Object> resumeWithTrace(String... nodeIds) {
        Map<String, Object> resume = new LinkedHashMap<>();
        resume.put("ok", true);
        resume.put("final_state", new LinkedHashMap<String, Object>());
        java.util.List<Object> trace = new java.util.ArrayList<Object>();
        Map<String, Object> outputs = new LinkedHashMap<>();
        for (String id : nodeIds) {
            trace.add(traceEntry(id, true));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("summary", "out-" + id);
            outputs.put(id, out);
        }
        resume.put("trace", trace);
        resume.put("node_outputs", outputs);
        return resume;
    }

    private static com.aegis.alpha.domain.WorkflowNodeRun recordedNode(String nodeId, int sort) {
        com.aegis.alpha.domain.WorkflowNodeRun nodeRun = new com.aegis.alpha.domain.WorkflowNodeRun();
        nodeRun.setNodeId(nodeId);
        nodeRun.setSortOrder(sort);
        nodeRun.setStatus("COMPLETED");
        return nodeRun;
    }

    @Test
    void approvalMaterializesNodesThatRanAfterResume() {
        WorkflowRun paused = pausedRun("run-mat");
        paused.setWorkflowKey("daily");
        paused.setNodeCount(2);
        storedRun.set(paused);
        /* Nodes before the gate were recorded (streaming or non-streaming start). */
        when(mapper.findNodeRuns("run-mat")).thenReturn(java.util.Arrays.asList(
                recordedNode("start", 100), recordedNode("market", 200)));
        /* The orchestrator returns the cumulative trace, including pre-pause nodes. */
        when(langChainGateway.resumeWorkflow("run-mat", true))
                .thenReturn(resumeWithTrace("start", "market", "approval_gate", "aggregate", "aggregate"));

        WorkflowRun completed = service.decideApproval("run-mat", true);

        ArgumentCaptor<com.aegis.alpha.domain.WorkflowNodeRun> inserted =
                ArgumentCaptor.forClass(com.aegis.alpha.domain.WorkflowNodeRun.class);
        verify(mapper, times(2)).insertNodeRun(inserted.capture());
        assertThat(inserted.getAllValues()).extracting("nodeId").containsExactly("approval_gate", "aggregate");
        assertThat(inserted.getAllValues()).extracting("sortOrder").containsExactly(300, 400);
        assertThat(inserted.getAllValues().get(1).getOutputJson()).contains("out-aggregate");
        verify(mapper, times(2)).insertRunEvent(org.mockito.ArgumentMatchers.argThat(
                event -> "NODE_COMPLETED".equals(event.getEventType())));
        assertThat(completed.getStatus()).isEqualTo("COMPLETED");
        assertThat(completed.getNodeCount()).isEqualTo(4);
    }

    @Test
    void rejectionAlsoMaterializesResumedNodesWithoutDuplicates() {
        storedRun.set(pausedRun("run-mat-r"));
        when(mapper.findNodeRuns("run-mat-r")).thenReturn(java.util.Arrays.asList(recordedNode("start", 100)));
        when(langChainGateway.resumeWorkflow("run-mat-r", false)).thenReturn(resumeWithTrace("start", "approval_gate"));

        service.decideApproval("run-mat-r", false);

        ArgumentCaptor<com.aegis.alpha.domain.WorkflowNodeRun> inserted =
                ArgumentCaptor.forClass(com.aegis.alpha.domain.WorkflowNodeRun.class);
        verify(mapper, times(1)).insertNodeRun(inserted.capture());
        assertThat(inserted.getValue().getNodeId()).isEqualTo("approval_gate");
    }

    @Test
    void resumeThatPausesAgainKeepsRunWaiting() {
        storedRun.set(pausedRun("run-again"));
        Map<String, Object> resume = resumeWithTrace("approval_gate");
        Map<String, Object> finalState = new LinkedHashMap<>();
        finalState.put("interrupted", true);
        resume.put("final_state", finalState);
        when(langChainGateway.resumeWorkflow("run-again", true)).thenReturn(resume);

        WorkflowRun after = service.decideApproval("run-again", true);

        assertThat(after.getStatus()).isEqualTo("PAUSED");
        assertThat(after.getControlStatus()).isEqualTo("HUMAN_INTERRUPT");
    }

    private static WorkflowRun pausedRun(String runId) {
        WorkflowRun paused = new WorkflowRun();
        paused.setRunId(runId);
        paused.setStatus("PAUSED");
        paused.setControlStatus("HUMAN_INTERRUPT");
        paused.setPauseRequested(1);
        return paused;
    }
}
