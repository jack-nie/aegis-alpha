package com.aegis.alpha.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.aegis.alpha.domain.WorkflowDefinition;
import com.aegis.alpha.domain.WorkflowRun;
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
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.*;

/**
 * Verifies streaming COMPLETED path materializes backtest/recommendation via BacktestService.
 */
class WorkflowServiceStreamingMaterializeTest {
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

        doAnswer(invocation -> {
            WorkflowRun run = invocation.getArgument(0);
            storedRun.set(copyRun(run));
            return null;
        }).when(mapper).insertRun(any(WorkflowRun.class));
        doAnswer(invocation -> {
            WorkflowRun run = invocation.getArgument(0);
            storedRun.set(copyRun(run));
            return null;
        }).when(mapper).updateRun(any(WorkflowRun.class));
        when(mapper.findRun(anyString())).thenAnswer(invocation -> {
            WorkflowRun current = storedRun.get();
            if (current == null) {
                return null;
            }
            if (!current.getRunId().equals(invocation.getArgument(0))) {
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
    void startWithStreamingMaterializesOnWorkflowComplete() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String sseResponse = "event: workflow_complete\n"
                + "data: {\"ok\":true,\"final_state\":{\"summary\":\"BUY AAPL\",\"stock_recommendation\":{\"summary\":\"BUY\"}}}\n"
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

        String streamUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/stream";
        when(langChainGateway.streamWorkflowUrl()).thenReturn(streamUrl);
        when(langChainGateway.buildStreamBody(any(), any(), any(), nullable(String.class))).thenReturn("{}");
        when(langChainGateway.serviceAuthorizationHeader()).thenReturn(null);

        Map<String, Object> inputs = new HashMap<>();
        inputs.put("ticker", "AAPL");
        SseEmitter emitter = new SseEmitter(30_000L);

        WorkflowRun result = service.startWithStreaming("daily", "AAPL analysis", inputs, emitter);

        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo("COMPLETED");
        assertThat(result.getResultJson()).contains("BUY AAPL");

        ArgumentCaptor<WorkflowRun> runCaptor = ArgumentCaptor.forClass(WorkflowRun.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> inputsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(backtestService, times(1)).createFromWorkflowRun(runCaptor.capture(), inputsCaptor.capture());
        assertThat(runCaptor.getValue().getStatus()).isEqualTo("COMPLETED");
        assertThat(runCaptor.getValue().getRunId()).isEqualTo(result.getRunId());
        assertThat(inputsCaptor.getValue()).containsEntry("ticker", "AAPL");
    }

    @Test
    void startWithStreamingMaterializesOnDegradedComplete() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String sseResponse = "event: workflow_complete\n"
                + "data: {\"ok\":true,\"degraded\":true,\"reasons\":[\"timeout\"]}\n"
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

        String streamUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/stream";
        when(langChainGateway.streamWorkflowUrl()).thenReturn(streamUrl);
        when(langChainGateway.buildStreamBody(any(), any(), any(), nullable(String.class))).thenReturn("{}");
        when(langChainGateway.serviceAuthorizationHeader()).thenReturn(null);

        SseEmitter emitter = new SseEmitter(30_000L);
        WorkflowRun result = service.startWithStreaming("daily", "degraded run", new HashMap<>(), emitter);

        assertThat(result.getStatus()).isEqualTo("COMPLETED");
        assertThat(result.getErrorMessage()).isEqualTo("DEGRADED");
        verify(backtestService, times(1)).createFromWorkflowRun(any(WorkflowRun.class), any());
    }

    @Test
    void startWithStreamingDoesNotFailClientWhenMaterializeThrows() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String sseResponse = "event: workflow_complete\n"
                + "data: {\"ok\":true}\n"
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

        String streamUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/stream";
        when(langChainGateway.streamWorkflowUrl()).thenReturn(streamUrl);
        when(langChainGateway.buildStreamBody(any(), any(), any(), nullable(String.class))).thenReturn("{}");
        when(langChainGateway.serviceAuthorizationHeader()).thenReturn(null);
        when(backtestService.createFromWorkflowRun(any(), any()))
                .thenThrow(new RuntimeException("materialize boom"));

        SseEmitter emitter = new SseEmitter(30_000L);
        WorkflowRun result = service.startWithStreaming("daily", "boom", new HashMap<>(), emitter);

        assertThat(result.getStatus()).isEqualTo("COMPLETED");
        verify(mapper, atLeastOnce()).insertRunEvent(argThat(event ->
                event != null && "MATERIALIZE_FAILED".equals(event.getEventType())));
    }

    @Test
    void startWithStreamingDoesNotMaterializeOnHumanInterrupt() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String sseResponse = "event: human_interrupt\n"
                + "data: {\"reason\":\"approval\"}\n"
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

        String streamUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/stream";
        when(langChainGateway.streamWorkflowUrl()).thenReturn(streamUrl);
        when(langChainGateway.buildStreamBody(any(), any(), any(), nullable(String.class))).thenReturn("{}");
        when(langChainGateway.serviceAuthorizationHeader()).thenReturn(null);

        SseEmitter emitter = new SseEmitter(30_000L);
        WorkflowRun result = service.startWithStreaming("daily", "paused", new HashMap<>(), emitter);

        assertThat(result.getStatus()).isEqualTo("PAUSED");
        verify(backtestService, never()).createFromWorkflowRun(any(), any());
    }

    @Test
    void startWithStreamingIssuesDelegatedTokenAndPassesToStreamBody() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String sseResponse = "event: workflow_complete\n"
                + "data: {\"ok\":true}\n"
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

        String streamUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/stream";
        when(langChainGateway.streamWorkflowUrl()).thenReturn(streamUrl);
        when(langChainGateway.buildStreamBody(any(), any(), any(), any())).thenReturn("{}");
        when(langChainGateway.serviceAuthorizationHeader()).thenReturn(null);

        SseEmitter emitter = new SseEmitter(30_000L);
        WorkflowRun result = service.startWithStreaming(
                "daily", "delegated", new HashMap<>(), emitter, "user-42", "tenant-a");

        assertThat(result.getStatus()).isEqualTo("COMPLETED");
        verify(tokenService, times(1)).issueServiceDelegation(
                eq(result.getRunId()),
                eq("user-42"),
                eq("tenant-a"),
                argThat(scopes -> scopes != null && scopes.contains("portfolio:read")),
                eq(WorkflowService.DELEGATED_PORTFOLIO_READ_TTL_MS));
        verify(langChainGateway, times(1)).buildStreamBody(
                any(), eq("delegated"), any(), eq("delegated-test-token"));
    }

    @Test
    void startWithStreamingContinuesWhenDelegationIssueFails() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String sseResponse = "event: workflow_complete\n"
                + "data: {\"ok\":true}\n"
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

        when(tokenService.issueServiceDelegation(anyString(), anyString(), anyString(), any(), anyLong()))
                .thenThrow(new IllegalStateException("cannot issue"));
        String streamUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/stream";
        when(langChainGateway.streamWorkflowUrl()).thenReturn(streamUrl);
        when(langChainGateway.buildStreamBody(any(), any(), any(), nullable(String.class))).thenReturn("{}");
        when(langChainGateway.serviceAuthorizationHeader()).thenReturn(null);

        SseEmitter emitter = new SseEmitter(30_000L);
        WorkflowRun result = service.startWithStreaming("daily", "soft-fail", new HashMap<>(), emitter, "u1", "t1");

        assertThat(result.getStatus()).isEqualTo("COMPLETED");
        verify(langChainGateway, times(1)).buildStreamBody(any(), eq("soft-fail"), any(), isNull());
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
