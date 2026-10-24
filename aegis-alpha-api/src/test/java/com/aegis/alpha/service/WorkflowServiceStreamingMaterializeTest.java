package com.aegis.alpha.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aegis.alpha.domain.WorkflowNodeRun;
import com.aegis.alpha.domain.WorkflowRunEvent;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
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
        // P3-16: "daily" is a digest workflow now; recommendation/backtest materialization is exercised on stock_analysis.
        WorkflowDefinition stock = new WorkflowDefinition();
        stock.setWorkflowKey("stock_analysis");
        stock.setName("Stock Analysis");
        stock.setEngine("langgraph");
        when(mapper.findDefinition("stock_analysis")).thenReturn(stock);

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
        when(langChainGateway.buildStreamBody(any(), any(), any(), nullable(String.class), nullable(String.class), anyBoolean(), any())).thenReturn("{}");
        when(langChainGateway.serviceAuthorizationHeader()).thenReturn(null);

        Map<String, Object> inputs = new HashMap<>();
        inputs.put("ticker", "AAPL");
        SseEmitter emitter = new SseEmitter(30_000L);

        WorkflowRun result = service.startWithStreaming("stock_analysis", "AAPL analysis", inputs, emitter);

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
        when(langChainGateway.buildStreamBody(any(), any(), any(), nullable(String.class), nullable(String.class), anyBoolean(), any())).thenReturn("{}");
        when(langChainGateway.serviceAuthorizationHeader()).thenReturn(null);

        SseEmitter emitter = new SseEmitter(30_000L);
        WorkflowRun result = service.startWithStreaming("stock_analysis", "degraded run", new HashMap<>(), emitter);

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
        when(langChainGateway.buildStreamBody(any(), any(), any(), nullable(String.class), nullable(String.class), anyBoolean(), any())).thenReturn("{}");
        when(langChainGateway.serviceAuthorizationHeader()).thenReturn(null);
        when(backtestService.createFromWorkflowRun(any(), any()))
                .thenThrow(new RuntimeException("materialize boom"));

        SseEmitter emitter = new SseEmitter(30_000L);
        WorkflowRun result = service.startWithStreaming("stock_analysis", "boom", new HashMap<>(), emitter);

        assertThat(result.getStatus()).isEqualTo("COMPLETED");
        verify(mapper, atLeastOnce()).insertRunEvent(argThat(event ->
                event != null && "MATERIALIZE_FAILED".equals(event.getEventType())));
    }

    @Test
    void startWithStreamingDigestRunMaterializesTraceOnly() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] responseBytes = "event: workflow_complete\ndata: {\"ok\":true}\n\n".getBytes(StandardCharsets.UTF_8);
        server.createContext("/stream", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, responseBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
        });
        server.start();
        when(langChainGateway.streamWorkflowUrl()).thenReturn("http://127.0.0.1:" + server.getAddress().getPort() + "/stream");
        when(langChainGateway.buildStreamBody(any(), any(), any(), nullable(String.class), nullable(String.class), anyBoolean(), any())).thenReturn("{}");
        when(langChainGateway.serviceAuthorizationHeader()).thenReturn(null);

        WorkflowRun result = service.startWithStreaming("daily", "market digest", new HashMap<>(), new SseEmitter(30_000L));

        assertThat(result.getStatus()).isEqualTo("COMPLETED");
        verify(backtestService, times(1)).materializeRunTrace(any(WorkflowRun.class));
        verify(backtestService, never()).createFromWorkflowRun(any(), any());
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
        when(langChainGateway.buildStreamBody(any(), any(), any(), nullable(String.class), nullable(String.class), anyBoolean(), any())).thenReturn("{}");
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
        when(langChainGateway.buildStreamBody(any(), any(), any(), any(), any(), anyBoolean(), any())).thenReturn("{}");
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
                any(),
                eq("delegated"),
                argThat(state -> state != null && "user-42".equals(state.get("userId")) && !state.containsKey("user_id")),
                eq("delegated-test-token"),
                eq(result.getRunId()),
                eq(false),
                argThat(owner -> owner != null && "user-42".equals(owner.get("userId"))
                        && "tenant-a".equals(owner.get("tenantId"))));
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
        when(langChainGateway.buildStreamBody(any(), any(), any(), nullable(String.class), nullable(String.class), anyBoolean(), any())).thenReturn("{}");
        when(langChainGateway.serviceAuthorizationHeader()).thenReturn(null);

        SseEmitter emitter = new SseEmitter(30_000L);
        WorkflowRun result = service.startWithStreaming("daily", "soft-fail", new HashMap<>(), emitter, "u1", "t1");

        assertThat(result.getStatus()).isEqualTo("COMPLETED");
        verify(langChainGateway, times(1)).buildStreamBody(
                any(), eq("soft-fail"), any(), isNull(), eq(result.getRunId()), eq(false), any());
    }

    /** P1-16: replays the SSE contract fixture shared with the Python orchestrator tests. */
    @Test
    void streamingContractFixtureMaterializesNodeRunsAndStableEventOrder() throws Exception {
        JsonNode fixture = objectMapper.readTree(Files.readAllBytes(contractFixture()));
        StringBuilder sse = new StringBuilder();
        List<String> expectedNodeIds = new ArrayList<>();
        for (JsonNode ev : fixture.get("events")) {
            String name = ev.get("event").asText();
            if ("node_update".equals(name)) {
                expectedNodeIds.add(ev.get("data").get("nodeId").asText());
            }
            sse.append("event: ").append(name).append("\n")
                    .append("data: ").append(objectMapper.writeValueAsString(ev.get("data"))).append("\n\n");
        }
        byte[] responseBytes = sse.toString().getBytes(StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/stream", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, responseBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
        });
        server.start();
        when(langChainGateway.streamWorkflowUrl()).thenReturn("http://127.0.0.1:" + server.getAddress().getPort() + "/stream");
        when(langChainGateway.buildStreamBody(any(), any(), any(), nullable(String.class), nullable(String.class), anyBoolean(), any())).thenReturn("{}");
        when(langChainGateway.serviceAuthorizationHeader()).thenReturn(null);

        Map<String, Object> inputs = new HashMap<>();
        inputs.put("ticker", "AAPL");
        WorkflowRun result = service.startWithStreaming("daily", "AAPL analysis", inputs, new SseEmitter(30_000L));

        assertThat(result.getStatus()).isEqualTo("COMPLETED");
        assertThat(result.getResultJson()).isNotBlank().contains("\"recommendation\"").contains("HOLD");
        assertThat(result.getNodeCount()).isEqualTo(expectedNodeIds.size());

        ArgumentCaptor<WorkflowNodeRun> nodeRuns = ArgumentCaptor.forClass(WorkflowNodeRun.class);
        verify(mapper, times(expectedNodeIds.size())).insertNodeRun(nodeRuns.capture());
        List<String> insertedIds = new ArrayList<>();
        int lastSort = -1;
        for (WorkflowNodeRun nodeRun : nodeRuns.getAllValues()) {
            insertedIds.add(nodeRun.getNodeId());
            assertThat(nodeRun.getSortOrder()).isGreaterThan(lastSort);
            lastSort = nodeRun.getSortOrder();
        }
        assertThat(insertedIds).containsExactlyElementsOf(expectedNodeIds);
        verify(agentTraceService, times(expectedNodeIds.size()))
                .recordNodeSpan(any(WorkflowRun.class), any(WorkflowNodeRun.class), anyString(), any(), any());

        ArgumentCaptor<WorkflowRunEvent> events = ArgumentCaptor.forClass(WorkflowRunEvent.class);
        verify(mapper, atLeastOnce()).insertRunEvent(events.capture());
        List<String> eventNodeIds = new ArrayList<>();
        int previous = Integer.MIN_VALUE;
        for (WorkflowRunEvent ev : events.getAllValues()) {
            assertThat(ev.getSortOrder()).as("event order must be stable: %s", ev.getEventType()).isGreaterThan(previous);
            previous = ev.getSortOrder();
            if ("NODE_COMPLETED".equals(ev.getEventType())) {
                eventNodeIds.add(ev.getNodeId());
                assertThat(ev.getNodeRunId()).isNotBlank();
            }
        }
        assertThat(eventNodeIds).containsExactlyElementsOf(expectedNodeIds);
    }

    @Test
    void repeatedNodeUpdateUpdatesExistingNodeRun() {
        Map<String, WorkflowNodeRun> streamed = new java.util.LinkedHashMap<>();
        WorkflowRun run = new WorkflowRun();
        run.setRunId("run-1");
        Map<String, Object> first = new HashMap<>();
        first.put("nodeId", "agent");
        first.put("ok", true);
        WorkflowNodeRun a = service.upsertStreamedNodeRun(run, first, streamed, new HashMap<>(), 1);
        Map<String, Object> second = new HashMap<>(first);
        second.put("ok", false);
        WorkflowNodeRun b = service.upsertStreamedNodeRun(run, second, streamed, new HashMap<>(), 2);
        assertThat(b).isSameAs(a);
        assertThat(b.getStatus()).isEqualTo("FAILED");
        assertThat(b.getSortOrder()).isEqualTo(100);
        verify(mapper, times(1)).insertNodeRun(any(WorkflowNodeRun.class));
        verify(mapper, times(1)).updateNodeRun(any(WorkflowNodeRun.class));
    }

    private static Path contractFixture() {
        Path dir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("docs/superpowers/specs/help-agent-contracts/fixtures/stream_workflow_complete.json");
            if (Files.exists(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("stream_workflow_complete.json contract fixture not found");
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
