package com.aegis.alpha.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class SseStreamReaderTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void readSseParsesEvents() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String sseResponse = "event: result\n" +
                "data: {\"summary\":\"test result\"}\n" +
                "\n" +
                "event: done\n" +
                "data: [DONE]\n" +
                "\n";
        byte[] responseBytes = sseResponse.getBytes(StandardCharsets.UTF_8);
        server.createContext("/stream", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, responseBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
        });
        server.start();

        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/stream";
        AtomicReference<String> resultEvent = new AtomicReference<>();
        AtomicReference<String> resultData = new AtomicReference<>();
        AtomicReference<String> doneEvent = new AtomicReference<>();
        AtomicReference<String> doneData = new AtomicReference<>();
        AtomicReference<Boolean> completed = new AtomicReference<>(false);

        SseStreamReader.SseEventHandler handler = new SseStreamReader.SseEventHandler() {
            @Override
            public void onEvent(String eventName, String data) {
                if ("result".equals(eventName)) {
                    resultEvent.set(eventName);
                    resultData.set(data);
                } else if ("done".equals(eventName)) {
                    doneEvent.set(eventName);
                    doneData.set(data);
                }
            }
            @Override
            public void onError(Exception ex) {}
            @Override
            public void onComplete() {
                completed.set(true);
            }
        };

        SseStreamReader.readSse(url, "{}", handler);

        assertThat(resultEvent.get()).isEqualTo("result");
        assertThat(resultData.get()).isEqualTo("{\"summary\":\"test result\"}");
        assertThat(doneEvent.get()).isEqualTo("done");
        assertThat(doneData.get()).isEqualTo("[DONE]");
        assertThat(completed.get()).isTrue();
    }

    @Test
    void readSseHandlesHttpError() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/error", exchange -> {
            byte[] body = "Internal Server Error".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();

        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/error";
        AtomicReference<Exception> error = new AtomicReference<>();

        SseStreamReader.SseEventHandler handler = new SseStreamReader.SseEventHandler() {
            @Override
            public void onEvent(String eventName, String data) {}
            @Override
            public void onError(Exception ex) {
                error.set(ex);
            }
            @Override
            public void onComplete() {}
        };

        SseStreamReader.readSse(url, "{}", handler);

        assertThat(error.get()).isNotNull();
        assertThat(error.get().getMessage()).contains("HTTP 500");
    }

    @Test
    void readSseIgnoresComments() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String sseResponse = ": this is a comment\n" +
                "event: ping\n" +
                "data: pong\n" +
                "\n";
        byte[] responseBytes = sseResponse.getBytes(StandardCharsets.UTF_8);
        server.createContext("/stream", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, responseBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
        });
        server.start();

        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/stream";
        AtomicReference<String> eventData = new AtomicReference<>();
        AtomicReference<Boolean> completed = new AtomicReference<>(false);

        SseStreamReader.SseEventHandler handler = new SseStreamReader.SseEventHandler() {
            @Override
            public void onEvent(String eventName, String data) {
                eventData.set(data);
            }
            @Override
            public void onError(Exception ex) {}
            @Override
            public void onComplete() {
                completed.set(true);
            }
        };

        SseStreamReader.readSse(url, "{}", handler);

        assertThat(eventData.get()).isEqualTo("pong");
        assertThat(completed.get()).isTrue();
    }
}