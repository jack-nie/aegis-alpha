package com.aegis.alpha.service;

import com.aegis.alpha.domain.WorkflowDefinition;
import com.aegis.alpha.domain.WorkflowNodeRun;
import com.aegis.alpha.domain.WorkflowRun;
import com.aegis.alpha.domain.WorkflowRunEvent;
import com.aegis.alpha.mapper.AgentMapper;
import com.aegis.alpha.mapper.WorkflowMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** P4-10: v2 event pass-through, throttled cancel checks, streaming approval resume. */
class WorkflowStreamV2Test {
    private WorkflowMapper mapper;
    private LangChainGateway gateway;
    private WorkflowService service;
    private HttpServer server;
    private final AtomicReference<WorkflowRun> storedRun = new AtomicReference<>();
    private final List<WorkflowRunEvent> events = new CopyOnWriteArrayList<>();
    private final AtomicInteger findRunCalls = new AtomicInteger();

    @BeforeEach
    void setUp() {
        mapper = mock(WorkflowMapper.class);
        gateway = mock(LangChainGateway.class);
        TokenService tokenService = mock(TokenService.class);
        when(tokenService.issueServiceDelegation(anyString(), anyString(), anyString(), any(), anyLong()))
                .thenReturn("delegated-owner-token");
        service = new WorkflowService(mapper, mock(AgentMapper.class), new ObjectMapper(), gateway,
                mock(CacheService.class), mock(BacktestService.class), mock(AgentTraceService.class),
                new WorkflowValidationService(), tokenService, false);
        WorkflowDefinition definition = new WorkflowDefinition();
        definition.setWorkflowKey("daily");
        definition.setName("Daily");
        definition.setEngine("langgraph");
        when(mapper.findDefinition("daily")).thenReturn(definition);
        doAnswer(inv -> { storedRun.set(WorkflowServiceStreamingControlTest.copy(inv.getArgument(0))); return null; })
                .when(mapper).insertRun(any(WorkflowRun.class));
        doAnswer(inv -> { storedRun.set(WorkflowServiceStreamingControlTest.copy(inv.getArgument(0))); return null; })
                .when(mapper).updateRun(any(WorkflowRun.class));
        doAnswer(inv -> { events.add(inv.getArgument(0)); return null; }).when(mapper).insertRunEvent(any(WorkflowRunEvent.class));
        when(mapper.findRun(anyString())).thenAnswer(inv -> {
            findRunCalls.incrementAndGet();
            WorkflowRun current = storedRun.get();
            return current == null || !current.getRunId().equals(inv.getArgument(0))
                    ? null : WorkflowServiceStreamingControlTest.copy(current);
        });
        when(mapper.updateRunStatusIf(any(WorkflowRun.class), org.mockito.ArgumentMatchers.<String>anyList(), anyBoolean()))
                .thenAnswer(inv -> {
                    WorkflowRun update = inv.getArgument(0);
                    List<String> expected = inv.getArgument(1);
                    WorkflowRun current = storedRun.get();
                    if (current == null || !expected.contains(current.getStatus())) {
                        return 0;
                    }
                    storedRun.set(WorkflowServiceStreamingControlTest.copy(update));
                    return 1;
                });
        when(mapper.transitionControlStatus(anyString(), anyString(), anyString())).thenAnswer(inv -> {
            WorkflowRun current = storedRun.get();
            if (current == null || !((String) inv.getArgument(1)).equals(current.getControlStatus())) {
                return 0;
            }
            WorkflowRun moved = WorkflowServiceStreamingControlTest.copy(current);
            moved.setControlStatus((String) inv.getArgument(2));
            storedRun.set(moved);
            return 1;
        });
        when(gateway.buildStreamBody(any(), any(), any(), nullable(String.class), nullable(String.class), anyBoolean(), any()))
                .thenReturn("{}");
        when(gateway.serviceAuthorizationHeader()).thenReturn(null);
        when(gateway.isEnabled()).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String serve(String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/stream";
        when(gateway.streamWorkflowUrl()).thenReturn(url);
        when(gateway.resumeWorkflowStreamUrl()).thenReturn(url);
        return url;
    }

    private static String sse(String name, String data) {
        return "event: " + name + "\ndata: " + data + "\n\n";
    }

    private static final String V2_STREAM =
            sse("node_start", "{\"nodeId\":\"agent\",\"nodeName\":\"A\",\"handler\":\"general.agent\",\"turn\":1}")
            + sse("message_delta", "{\"nodeId\":\"agent\",\"delta\":\"Hel\",\"final\":false}")
            + sse("message_delta", "{\"nodeId\":\"agent\",\"delta\":\"lo\",\"final\":false}")
            + sse("tool_execution_start", "{\"toolCallId\":\"c1\",\"toolName\":\"get_stock_quote\",\"nodeId\":\"agent\",\"args\":{\"symbol\":\"AAPL\"}}")
            + sse("tool_execution_end", "{\"toolCallId\":\"c1\",\"toolName\":\"get_stock_quote\",\"nodeId\":\"agent\",\"isError\":true,\"durationMs\":12,\"truncated\":false}")
            + sse("usage", "{\"nodeId\":\"agent\",\"scope\":\"node\",\"usage\":{\"totalTokens\":42}}")
            + sse("node_update", "{\"nodeId\":\"agent\",\"status\":\"completed\",\"ok\":true}")
            + sse("workflow_complete", "{\"ok\":true,\"final_state\":{}}");

    private static Map<String, Object> inputs() {
        Map<String, Object> inputs = new HashMap<>();
        inputs.put("ticker", "AAPL");
        return inputs;
    }

    private List<String> eventTypes() {
        List<String> types = new java.util.ArrayList<>();
        for (WorkflowRunEvent event : events) {
            types.add(event.getEventType());
        }
        return types;
    }

    @Test
    void v2EventsAreForwardedAndOnlyToolEndIsRecorded() throws Exception {
        serve(V2_STREAM);
        WorkflowServiceStreamingControlTest.RecordingEmitter emitter = new WorkflowServiceStreamingControlTest.RecordingEmitter();

        service.startWithStreaming("daily", "s", inputs(), emitter);

        assertThat(emitter.names).containsExactly("node_start", "message_delta", "message_delta",
                "tool_execution_start", "tool_execution_end", "usage", "node_update", "workflow_complete");
        assertThat(eventTypes()).contains("TOOL_EXECUTION_END", "NODE_COMPLETED", "RUN_COMPLETED")
                .doesNotContain("NODE_START", "MESSAGE_DELTA", "USAGE");
        WorkflowRunEvent toolEnd = events.stream().filter(e -> "TOOL_EXECUTION_END".equals(e.getEventType()))
                .findFirst().orElseThrow(AssertionError::new);
        assertThat(toolEnd.getNodeId()).isEqualTo("agent");
        assertThat(toolEnd.getPayloadJson()).contains("\"isError\":true").doesNotContain("AAPL");
        assertThat(storedRun.get().getStatus()).isEqualTo("COMPLETED");
    }

    @Test
    void cancelFlagIsReadAtMostOncePerIntervalForProgressEvents() throws Exception {
        serve(V2_STREAM);
        service.setCancelCheckClock(() -> 1_000L);
        service.setStreamCancelCheckIntervalMs(0L);
        service.startWithStreaming("daily", "s", inputs(), new WorkflowServiceStreamingControlTest.RecordingEmitter());
        int unthrottled = findRunCalls.getAndSet(0);
        server.stop(0);

        serve(V2_STREAM);
        service.setStreamCancelCheckIntervalMs(500L);
        service.startWithStreaming("daily", "s", inputs(), new WorkflowServiceStreamingControlTest.RecordingEmitter());
        int throttled = findRunCalls.get();

        /* 7 progress events, frozen clock: only the first one reads the flag; workflow_complete always reads. */
        assertThat(unthrottled - throttled).isEqualTo(6);
        assertThat(storedRun.get().getStatus()).isEqualTo("COMPLETED");
    }

    @Test
    void cancelIsStillSeenAfterTheIntervalElapses() throws Exception {
        AtomicReference<Long> clock = new AtomicReference<>(0L);
        service.setCancelCheckClock(clock::get);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        java.util.concurrent.CountDownLatch gate = new java.util.concurrent.CountDownLatch(1);
        server.createContext("/", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(sse("node_start", "{\"nodeId\":\"a\"}").getBytes(StandardCharsets.UTF_8));
                os.flush();
                gate.await(5, java.util.concurrent.TimeUnit.SECONDS);
                os.write(sse("message_delta", "{\"nodeId\":\"a\",\"delta\":\"x\"}").getBytes(StandardCharsets.UTF_8));
                os.flush();
                gate.await(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException | IOException ignored) {
                // closed by the cancel
            }
        });
        server.start();
        when(gateway.streamWorkflowUrl()).thenReturn("http://127.0.0.1:" + server.getAddress().getPort() + "/s");
        WorkflowServiceStreamingControlTest.RecordingEmitter emitter = new WorkflowServiceStreamingControlTest.RecordingEmitter();
        Thread streaming = new Thread(() -> service.startWithStreaming("daily", "s", inputs(), emitter));
        streaming.start();
        long deadline = System.currentTimeMillis() + 5000;
        while (!emitter.names.contains("node_start") && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        WorkflowRun cancelled = WorkflowServiceStreamingControlTest.copy(storedRun.get());
        cancelled.setCancelRequested(1);
        cancelled.setControlStatus("CANCEL_REQUESTED");
        storedRun.set(cancelled);
        clock.set(600L);
        gate.countDown();
        streaming.join(5000);

        assertThat(emitter.names).contains("workflow_cancelled").doesNotContain("message_delta");
    }

    private WorkflowRun paused(String runId) {
        WorkflowRun run = new WorkflowRun();
        run.setRunId(runId);
        run.setWorkflowKey("daily");
        run.setStatus("PAUSED");
        run.setControlStatus("HUMAN_INTERRUPT");
        run.setPauseRequested(1);
        run.setUserId("owner-1");
        run.setTenantId("t1");
        storedRun.set(run);
        return run;
    }

    @Test
    void streamingApprovalForwardsEventsAndCompletesRun() throws Exception {
        paused("run-s");
        when(mapper.findNodeRuns("run-s")).thenReturn(java.util.Collections.<WorkflowNodeRun>emptyList());
        when(gateway.buildResumeStreamBody(eq("run-s"), eq(true), any(), nullable(String.class))).thenReturn("{}");
        serve(sse("node_start", "{\"nodeId\":\"aggregate\",\"turn\":1}")
                + sse("message_delta", "{\"nodeId\":\"aggregate\",\"delta\":\"[HOLD] r\",\"final\":true,\"gatePassed\":true}")
                + sse("node_update", "{\"nodeId\":\"aggregate\",\"nodeName\":\"Report\",\"status\":\"completed\",\"ok\":true}")
                + sse("workflow_complete", "{\"ok\":true,\"final_state\":{\"approval_status\":\"approved\"}}"));
        WorkflowServiceStreamingControlTest.RecordingEmitter emitter = new WorkflowServiceStreamingControlTest.RecordingEmitter();

        service.decideApprovalStreaming("run-s", true, "ok", null, emitter);

        assertThat(emitter.names).containsExactly("node_start", "message_delta", "node_update",
                "workflow_complete", "run_state");
        assertThat(emitter.completed).isTrue();
        assertThat(storedRun.get().getStatus()).isEqualTo("COMPLETED");
        assertThat(eventTypes()).contains("RUN_APPROVAL_DECISION", "NODE_COMPLETED", "RUN_APPROVED");
        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        verify(gateway).buildResumeStreamBody(eq("run-s"), eq(true), any(), token.capture());
        assertThat(token.getValue()).isEqualTo("delegated-owner-token");
        ArgumentCaptor<WorkflowNodeRun> inserted = ArgumentCaptor.forClass(WorkflowNodeRun.class);
        verify(mapper, times(1)).insertNodeRun(inserted.capture());
        assertThat(inserted.getValue().getNodeId()).isEqualTo("aggregate");
        verify(gateway, never()).resumeWorkflow(anyString(), anyBoolean(), any(), nullable(String.class));
    }

    @Test
    void streamingApprovalFailureReturnsRunToHumanInterrupt() throws Exception {
        paused("run-f");
        when(gateway.buildResumeStreamBody(eq("run-f"), eq(true), any(), nullable(String.class))).thenReturn("{}");
        serve(sse("error", "{\"ok\":false,\"error\":\"boom\",\"code\":\"WORKFLOW_ERROR\"}"));
        WorkflowServiceStreamingControlTest.RecordingEmitter emitter = new WorkflowServiceStreamingControlTest.RecordingEmitter();

        service.decideApprovalStreaming("run-f", true, null, null, emitter);

        assertThat(emitter.names).containsExactly("error", "run_state");
        assertThat(storedRun.get().getControlStatus()).isEqualTo("HUMAN_INTERRUPT");
        assertThat(eventTypes()).contains("RUN_RESUME_FAILED");
    }

    @Test
    void streamingApprovalOnRunNotWaitingSendsErrorWithoutResuming() {
        WorkflowRun running = paused("run-x");
        running.setStatus("RUNNING");
        running.setControlStatus("RUNNING");
        storedRun.set(running);
        WorkflowServiceStreamingControlTest.RecordingEmitter emitter = new WorkflowServiceStreamingControlTest.RecordingEmitter();

        service.decideApprovalStreaming("run-x", true, null, null, emitter);

        assertThat(emitter.names).containsExactly("error");
        verify(gateway, never()).buildResumeStreamBody(anyString(), anyBoolean(), any(), nullable(String.class));
    }

    @Test
    void streamingApprovalThatPausesAgainKeepsRunWaiting() throws Exception {
        paused("run-p");
        when(gateway.buildResumeStreamBody(eq("run-p"), eq(true), any(), nullable(String.class))).thenReturn("{}");
        serve(sse("human_interrupt", "{\"ok\":true,\"interrupted\":true}"));
        WorkflowServiceStreamingControlTest.RecordingEmitter emitter = new WorkflowServiceStreamingControlTest.RecordingEmitter();

        service.decideApprovalStreaming("run-p", true, null, null, emitter);

        assertThat(emitter.names).containsExactly("human_interrupt", "run_state");
        assertThat(storedRun.get().getStatus()).isEqualTo("PAUSED");
        assertThat(storedRun.get().getControlStatus()).isEqualTo("HUMAN_INTERRUPT");
    }

    @Test
    void terminalRunEventsTriggerQueueWritebackOthersDoNot() {
        /* P4-11: one choke point (recordRunEvent) covers sync, streaming and approval completion. */
        QueueResultWriteback writeback = org.mockito.Mockito.mock(QueueResultWriteback.class);
        service.setQueueResultWriteback(writeback);
        WorkflowRun run = new WorkflowRun();
        run.setRunId("run-wb");
        service.recordRunEvent(run, "NODE_COMPLETED", null, "agent", "COMPLETED", "n", null, 1);
        service.recordRunEvent(run, "RUN_COMPLETED", null, null, "COMPLETED", "done", null, 1000000);
        org.mockito.Mockito.verify(writeback).onRunEvent(run, "RUN_COMPLETED", null);
        org.mockito.Mockito.verify(writeback, org.mockito.Mockito.never())
                .onRunEvent(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("NODE_COMPLETED"),
                        org.mockito.ArgumentMatchers.any());
    }

    private void cancelStoredRun() {
        WorkflowRun cancelled = WorkflowServiceStreamingControlTest.copy(storedRun.get());
        cancelled.setStatus("CANCELLED");
        cancelled.setControlStatus("CANCELLED");
        cancelled.setCancelRequested(1);
        storedRun.set(cancelled);
    }

    @Test
    void streamingApprovalStopsWhenRunIsCancelledDuringResume() throws Exception {
        /* D10a: a cancel during the approval resume stream wins and is never overwritten. */
        paused("run-c");
        when(gateway.buildResumeStreamBody(eq("run-c"), eq(true), any(), nullable(String.class))).thenReturn("{}");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(sse("node_start", "{\"nodeId\":\"aggregate\",\"turn\":1}").getBytes(StandardCharsets.UTF_8));
                os.flush();
                cancelStoredRun();
                os.write((sse("message_delta", "{\"nodeId\":\"aggregate\",\"delta\":\"x\",\"final\":true}")
                        + sse("workflow_complete", "{\"ok\":true,\"final_state\":{}}")).getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // reader closed the connection after the cancel
            }
        });
        server.start();
        when(gateway.resumeWorkflowStreamUrl()).thenReturn("http://127.0.0.1:" + server.getAddress().getPort() + "/r");
        service.setStreamCancelCheckIntervalMs(0L);
        WorkflowServiceStreamingControlTest.RecordingEmitter emitter = new WorkflowServiceStreamingControlTest.RecordingEmitter();

        service.decideApprovalStreaming("run-c", true, null, null, emitter);

        assertThat(emitter.names).contains("workflow_cancelled", "run_state")
                .doesNotContain("message_delta", "workflow_complete");
        assertThat(emitter.completed).isTrue();
        assertThat(storedRun.get().getStatus()).isEqualTo("CANCELLED");
        assertThat(eventTypes()).doesNotContain("RUN_APPROVED");
        verify(gateway).cancelRun(eq("run-c"), any(), any());
    }

    @Test
    void syncApprovalOutcomeNeverOverwritesACancel() {
        /* D10a: same rule for the non-streaming approval path. */
        paused("run-y");
        when(gateway.resumeWorkflow(eq("run-y"), eq(true), any(), nullable(String.class))).thenAnswer(inv -> {
            cancelStoredRun();
            Map<String, Object> ok = new HashMap<>();
            ok.put("ok", true);
            ok.put("final_state", new HashMap<String, Object>());
            return ok;
        });

        WorkflowRun result = service.decideApproval("run-y", true, null, null);

        assertThat(result.getStatus()).isEqualTo("CANCELLED");
        assertThat(eventTypes()).contains("RUN_RESUME_DISCARDED").doesNotContain("RUN_APPROVED");
    }
}
