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
}
