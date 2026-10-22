package com.aegis.alpha.service;

import com.aegis.alpha.domain.WorkflowDefinition;
import com.aegis.alpha.domain.WorkflowRun;
import com.aegis.alpha.domain.WorkflowRunEvent;
import com.aegis.alpha.mapper.AgentMapper;
import com.aegis.alpha.mapper.WorkflowMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Streaming run control: cancel race (P2-17), SSE end on approval pause (P2-18), client disconnect (P2-19). */
class WorkflowServiceStreamingControlTest {
    private WorkflowMapper mapper;
    private LangChainGateway langChainGateway;
    private WorkflowService service;
    private HttpServer server;
    private final AtomicReference<WorkflowRun> storedRun = new AtomicReference<>();
    private final List<WorkflowRunEvent> events = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        mapper = mock(WorkflowMapper.class);
        langChainGateway = mock(LangChainGateway.class);
        TokenService tokenService = mock(TokenService.class);
        when(tokenService.issueServiceDelegation(anyString(), anyString(), anyString(), any(), anyLong()))
                .thenReturn("delegated-test-token");
        service = new WorkflowService(mapper, mock(AgentMapper.class), new ObjectMapper(), langChainGateway,
                mock(CacheService.class), mock(BacktestService.class), mock(AgentTraceService.class),
                new WorkflowValidationService(), tokenService, false);
        WorkflowDefinition definition = new WorkflowDefinition();
        definition.setWorkflowKey("daily");
        definition.setName("Daily");
        definition.setEngine("langgraph");
        when(mapper.findDefinition("daily")).thenReturn(definition);
        doAnswer(inv -> { storedRun.set(copy(inv.getArgument(0))); return null; }).when(mapper).insertRun(any(WorkflowRun.class));
        doAnswer(inv -> { storedRun.set(copy(inv.getArgument(0))); return null; }).when(mapper).updateRun(any(WorkflowRun.class));
        doAnswer(inv -> { events.add(inv.getArgument(0)); return null; }).when(mapper).insertRunEvent(any(WorkflowRunEvent.class));
        when(mapper.findRun(anyString())).thenAnswer(inv -> {
            WorkflowRun current = storedRun.get();
            return current == null || !current.getRunId().equals(inv.getArgument(0)) ? null : copy(current);
        });
        when(mapper.updateRunStatusIf(any(WorkflowRun.class), org.mockito.ArgumentMatchers.<String>anyList(), anyBoolean()))
                .thenAnswer(inv -> {
                    synchronized (storedRun) {
                        WorkflowRun update = inv.getArgument(0);
                        List<String> expected = inv.getArgument(1);
                        boolean requireNoCancel = inv.getArgument(2);
                        WorkflowRun current = storedRun.get();
                        if (current == null || !expected.contains(current.getStatus())
                                || (requireNoCancel && current.getCancelRequested() != null && current.getCancelRequested() == 1)) {
                            return 0;
                        }
                        storedRun.set(copy(update));
                        return 1;
                    }
                });
        when(langChainGateway.buildStreamBody(any(), any(), any(), nullable(String.class), nullable(String.class), anyBoolean()))
                .thenReturn("{}");
        when(langChainGateway.serviceAuthorizationHeader()).thenReturn(null);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void serve(String firstChunk, CountDownLatch gate, String secondChunk) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/stream", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(firstChunk.getBytes(StandardCharsets.UTF_8));
                os.flush();
                if (gate != null) {
                    try {
                        gate.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                }
                if (secondChunk != null) {
                    os.write(secondChunk.getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }
            } catch (IOException ignored) {
                // client closed the connection (cancel)
            }
        });
        server.start();
        when(langChainGateway.streamWorkflowUrl()).thenReturn("http://127.0.0.1:" + server.getAddress().getPort() + "/stream");
    }

    private static Map<String, Object> inputs() {
        Map<String, Object> inputs = new HashMap<>();
        inputs.put("ticker", "AAPL");
        return inputs;
    }

    private boolean hasEvent(String type) {
        for (WorkflowRunEvent event : events) {
            if (type.equals(event.getEventType())) {
                return true;
            }
        }
        return false;
    }

    /* ---------------- P2-17 ---------------- */

    @Test
    void cancelDuringStreamingEndsCancelledAndIsNotOverwrittenByComplete() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        serve("event: node_update\ndata: {\"nodeId\":\"market\",\"status\":\"completed\",\"ok\":true}\n\n", gate,
                "event: workflow_complete\ndata: {\"ok\":true,\"final_state\":{}}\n\n");
        RecordingEmitter emitter = new RecordingEmitter();
        Thread streaming = new Thread(() -> service.startWithStreaming("daily", "s", inputs(), emitter));
        streaming.start();
        long deadline = System.currentTimeMillis() + 5000;
        while (!emitter.names.contains("node_update") && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertThat(emitter.names).contains("node_update");

        service.cancelRun(storedRun.get().getRunId());
        gate.countDown();
        streaming.join(5000);

        assertThat(storedRun.get().getStatus()).isEqualTo("CANCELLED");
        assertThat(storedRun.get().getControlStatus()).isEqualTo("CANCELLED");
        assertThat(hasEvent("RUN_CANCELLED")).isTrue();
        assertThat(hasEvent("RUN_COMPLETED")).isFalse();
        assertThat(emitter.names).contains("workflow_cancelled").doesNotContain("workflow_complete");
        assertThat(emitter.completed).isTrue();
    }

    @Test
    void cancelLandingBetweenReadAndWriteStillWins() throws Exception {
        serve("event: workflow_complete\ndata: {\"ok\":true,\"final_state\":{}}\n\n", null, null);
        /* The cancel lands after the pre-write re-read: findRun reports RUNNING once, then the CAS sees it. */
        final java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
        when(mapper.findRun(anyString())).thenAnswer(inv -> {
            WorkflowRun current = storedRun.get();
            if (current != null && "RUNNING".equals(current.getStatus()) && reads.incrementAndGet() == 1) {
                WorkflowRun snapshot = copy(current);
                WorkflowRun cancelled = copy(current);
                cancelled.setCancelRequested(1);
                cancelled.setControlStatus("CANCEL_REQUESTED");
                storedRun.set(cancelled);
                return snapshot;
            }
            return current == null ? null : copy(current);
        });
        RecordingEmitter emitter = new RecordingEmitter();

        service.startWithStreaming("daily", "s", inputs(), emitter);

        assertThat(storedRun.get().getStatus()).isEqualTo("CANCELLED");
        assertThat(hasEvent("RUN_COMPLETED")).isFalse();
    }

    /* ---------------- P2-18 ---------------- */

    @Test
    void humanInterruptCompletesEmitterAndRunStaysWaiting() throws Exception {
        serve("event: node_update\ndata: {\"nodeId\":\"market\",\"status\":\"completed\",\"ok\":true}\n\n"
                + "event: human_interrupt\ndata: {\"ok\":true,\"interrupted\":true}\n\n", null, null);
        RecordingEmitter emitter = new RecordingEmitter();

        service.startWithStreaming("daily", "s", inputs(), emitter);

        assertThat(emitter.names).containsExactly("node_update", "human_interrupt");
        assertThat(emitter.completed).isTrue();
        assertThat(emitter.completeCalls.get()).isEqualTo(1);
        assertThat(storedRun.get().getStatus()).isEqualTo("PAUSED");
        assertThat(storedRun.get().getControlStatus()).isEqualTo("HUMAN_INTERRUPT");
        assertThat(hasEvent("RUN_COMPLETED")).isFalse();
    }

    @Test
    void streamEndingWithoutTerminalEventStillEndsClientStream() throws Exception {
        serve("event: node_update\ndata: {\"nodeId\":\"market\",\"status\":\"completed\",\"ok\":true}\n\n", null, null);
        RecordingEmitter emitter = new RecordingEmitter();

        service.startWithStreaming("daily", "s", inputs(), emitter);

        assertThat(emitter.completed).isTrue();
        assertThat(storedRun.get().getStatus()).isEqualTo("COMPLETED");
    }

    /* ---------------- P2-19 ---------------- */

    private long eventCount(String type) {
        long count = 0;
        for (WorkflowRunEvent event : events) {
            if (type.equals(event.getEventType())) {
                count++;
            }
        }
        return count;
    }

    @Test
    void failingClientSendsDoNotFailTheRun() throws Exception {
        serve("event: node_update\ndata: {\"nodeId\":\"market\",\"status\":\"completed\",\"ok\":true}\n\n"
                + "event: node_update\ndata: {\"nodeId\":\"news\",\"status\":\"completed\",\"ok\":true}\n\n"
                + "event: workflow_complete\ndata: {\"ok\":true,\"final_state\":{}}\n\n", null, null);
        RecordingEmitter emitter = new RecordingEmitter();
        emitter.failSends = true;

        service.startWithStreaming("daily", "s", inputs(), emitter);

        assertThat(storedRun.get().getStatus()).isEqualTo("COMPLETED");
        assertThat(eventCount("CLIENT_DISCONNECTED")).isEqualTo(1);
        assertThat(hasEvent("RUN_FAILED")).isFalse();
        assertThat(hasEvent("RUN_COMPLETED")).isTrue();
    }

    @Test
    void clientTimeoutByDefaultLetsTheRunFinish() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        serve("event: node_update\ndata: {\"nodeId\":\"market\",\"status\":\"completed\",\"ok\":true}\n\n", gate,
                "event: workflow_complete\ndata: {\"ok\":true,\"final_state\":{}}\n\n");
        RecordingEmitter emitter = new RecordingEmitter();
        Thread streaming = new Thread(() -> service.startWithStreaming("daily", "s", inputs(), emitter));
        streaming.start();
        awaitName(emitter, "node_update");

        emitter.timeoutCallback.run();
        gate.countDown();
        streaming.join(5000);

        assertThat(storedRun.get().getStatus()).isEqualTo("COMPLETED");
        assertThat(eventCount("CLIENT_DISCONNECTED")).isEqualTo(1);
        assertThat(emitter.names).doesNotContain("workflow_complete");
    }

    @Test
    void clientTimeoutWithCancelOnDisconnectCancelsTheRun() throws Exception {
        service.setCancelStreamOnDisconnect(true);
        CountDownLatch gate = new CountDownLatch(1);
        serve("event: node_update\ndata: {\"nodeId\":\"market\",\"status\":\"completed\",\"ok\":true}\n\n", gate,
                "event: workflow_complete\ndata: {\"ok\":true,\"final_state\":{}}\n\n");
        RecordingEmitter emitter = new RecordingEmitter();
        Thread streaming = new Thread(() -> service.startWithStreaming("daily", "s", inputs(), emitter));
        streaming.start();
        awaitName(emitter, "node_update");

        emitter.timeoutCallback.run();
        gate.countDown();
        streaming.join(5000);

        assertThat(storedRun.get().getStatus()).isEqualTo("CANCELLED");
        assertThat(hasEvent("CLIENT_DISCONNECTED")).isTrue();
        assertThat(hasEvent("RUN_CANCELLED")).isTrue();
        assertThat(hasEvent("RUN_COMPLETED")).isFalse();
    }

    @Test
    void normalCompletionIsNotRecordedAsDisconnect() throws Exception {
        serve("event: workflow_complete\ndata: {\"ok\":true,\"final_state\":{}}\n\n", null, null);
        RecordingEmitter emitter = new RecordingEmitter();

        service.startWithStreaming("daily", "s", inputs(), emitter);
        emitter.completionCallback.run(); // container fires onCompletion after our complete()

        assertThat(hasEvent("CLIENT_DISCONNECTED")).isFalse();
    }

    private static void awaitName(RecordingEmitter emitter, String name) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (!emitter.names.contains(name) && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertThat(emitter.names).contains(name);
    }

    static WorkflowRun copy(WorkflowRun source) {
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
        copy.setInputsJson(source.getInputsJson());
        copy.setControlStatus(source.getControlStatus());
        copy.setPauseRequested(source.getPauseRequested());
        copy.setCancelRequested(source.getCancelRequested());
        return copy;
    }

    /** Records event names and completion; optionally fails every send like a closed client socket. */
    static class RecordingEmitter extends SseEmitter {
        final List<String> names = new CopyOnWriteArrayList<>();
        volatile boolean completed;
        volatile boolean failSends;
        final java.util.concurrent.atomic.AtomicInteger completeCalls = new java.util.concurrent.atomic.AtomicInteger();

        volatile Runnable timeoutCallback = () -> { };
        volatile Runnable completionCallback = () -> { };

        RecordingEmitter() {
            super(30_000L);
        }

        @Override
        public synchronized void onTimeout(Runnable callback) {
            timeoutCallback = callback;
        }

        @Override
        public synchronized void onCompletion(Runnable callback) {
            completionCallback = callback;
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            if (failSends) {
                throw new IOException("Broken pipe");
            }
            for (org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter.DataWithMediaType item : builder.build()) {
                String text = String.valueOf(item.getData());
                if (text.startsWith("event:")) {
                    names.add(text.substring(6).trim().split("\n")[0]);
                }
            }
        }

        @Override
        public synchronized void complete() {
            completed = true;
            completeCalls.incrementAndGet();
        }

        @Override
        public synchronized void completeWithError(Throwable ex) {
            completed = true;
        }
    }
}
