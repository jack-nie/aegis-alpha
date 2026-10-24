package com.aegis.alpha.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.aegis.alpha.domain.AgentTemplate;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

class LangChainGatewayTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void runAgentKeepsLegacyContentFieldsWhenLangGraphReturnsSummaryOnly() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/execute-node", this::summaryOnlyResponse);
        server.start();

        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        LangChainGateway gateway = new LangChainGateway(
                new ObjectMapper(),
                true,
                url,
                "openai",
                "deepseek-v4-flash",
                "test-key",
                "http://example.test/v1"
        );

        AgentTemplate agent = new AgentTemplate();
        agent.setAgentId("test-agent");
        agent.setName("Test Agent");

        Map<String, Object> result = gateway.runAgent(
                agent,
                new LinkedHashMap<String, Object>(),
                new LinkedHashMap<String, Object>(),
                "copilot chat"
        );

        assertThat(result.get("summary"), is("Real LLM summary"));
        assertThat(result.get("content"), is("Real LLM summary"));
        assertThat(result.get("message"), is("Real LLM summary"));
    }

    @Test
    void buildStreamBodyIncludesDelegatedTokenWhenPresent() throws Exception {
        LangChainGateway gateway = new LangChainGateway(
                new ObjectMapper(),
                false,
                "http://127.0.0.1:8787",
                "openai",
                "deepseek-v4-flash",
                "test-key",
                "http://example.test/v1"
        );
        Map<String, Object> layout = new LinkedHashMap<String, Object>();
        layout.put("nodes", java.util.Collections.emptyList());
        layout.put("edges", java.util.Collections.emptyList());
        Map<String, Object> inputs = new LinkedHashMap<String, Object>();
        inputs.put("ticker", "AAPL");

        String withToken = gateway.buildStreamBody(layout, "subject", inputs, "deleg-token-1");
        assertThat(withToken.contains("\"delegatedToken\":\"deleg-token-1\""), is(true));

        String withoutToken = gateway.buildStreamBody(layout, "subject", inputs, null);
        assertThat(withoutToken.contains("delegatedToken"), is(false));
        assertThat(withoutToken.contains("\"requireApproval\":false"), is(true));
    }

    @Test
    void buildStreamBodyIncludesThreadIdAndRequireApproval() throws Exception {
        LangChainGateway gateway = new LangChainGateway(
                new ObjectMapper(),
                false,
                "http://127.0.0.1:8787",
                "openai",
                "deepseek-v4-flash",
                "test-key",
                "http://example.test/v1"
        );
        Map<String, Object> layout = new LinkedHashMap<String, Object>();
        layout.put("nodes", java.util.Collections.emptyList());
        layout.put("edges", java.util.Collections.emptyList());
        Map<String, Object> inputs = new LinkedHashMap<String, Object>();
        inputs.put("ticker", "AAPL");

        String body = gateway.buildStreamBody(layout, "subject", inputs, "deleg-token-1", "thread-9", true);
        assertThat(body.contains("\"threadId\":\"thread-9\""), is(true));
        assertThat(body.contains("\"requireApproval\":true"), is(true));
        assertThat(body.contains("\"delegatedToken\":\"deleg-token-1\""), is(true));

        String withoutThread = gateway.buildStreamBody(layout, "subject", inputs, null, "  ", false);
        assertThat(withoutThread.contains("threadId"), is(false));
        assertThat(withoutThread.contains("\"requireApproval\":false"), is(true));
    }

    @Test
    void resumeWorkflowPostsDecisionWhenEnabled() throws Exception {
        final java.util.concurrent.atomic.AtomicReference<String> seenBody =
                new java.util.concurrent.atomic.AtomicReference<String>();
        final java.util.concurrent.atomic.AtomicReference<String> seenPath =
                new java.util.concurrent.atomic.AtomicReference<String>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/resume-workflow", exchange -> {
            seenPath.set(exchange.getRequestURI().getPath());
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[1024];
            int read;
            java.io.InputStream requestStream = exchange.getRequestBody();
            while ((read = requestStream.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            seenBody.set(new String(buffer.toByteArray(), StandardCharsets.UTF_8));
            byte[] response = "{\"ok\":true,\"final_state\":{\"approval_status\":\"approved\"}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();

        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        LangChainGateway gateway = new LangChainGateway(
                new ObjectMapper(),
                true,
                url,
                "openai",
                "deepseek-v4-flash",
                "test-key",
                "http://example.test/v1"
        );

        Map<String, Object> result = gateway.resumeWorkflow("run-1", true);
        assertThat(seenPath.get(), is("/resume-workflow"));
        assertThat(seenBody.get().contains("\"threadId\":\"run-1\""), is(true));
        assertThat(seenBody.get().contains("\"approved\":true"), is(true));
        assertThat(result.get("ok"), is(true));
    }

    @Test
    void resumeWorkflowReturnsOkFalseWhenDisabled() {
        LangChainGateway gateway = new LangChainGateway(
                new ObjectMapper(),
                false,
                "http://127.0.0.1:8787",
                "openai",
                "deepseek-v4-flash",
                "test-key",
                "http://example.test/v1"
        );
        Map<String, Object> result = gateway.resumeWorkflow("run-1", false);
        assertThat(result.get("ok"), is(false));
    }

    // P2-10: model credentials are never forwarded in orchestrator request bodies.
    @Test
    void requestBodiesNeverCarryApiKeyOrBaseUrl() throws Exception {
        final java.util.List<String> bodies = java.util.Collections.synchronizedList(new java.util.ArrayList<String>());
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        for (String path : new String[] {"/execute-node", "/classify-intent", "/help/execute", "/execute-workflow"}) {
            server.createContext(path, exchange -> {
                java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
                byte[] chunk = new byte[1024];
                int read;
                java.io.InputStream in = exchange.getRequestBody();
                while ((read = in.read(chunk)) != -1) {
                    buffer.write(chunk, 0, read);
                }
                bodies.add(new String(buffer.toByteArray(), StandardCharsets.UTF_8));
                byte[] response = "{\"ok\":true,\"summary\":\"s\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                try (OutputStream output = exchange.getResponseBody()) {
                    output.write(response);
                }
            });
        }
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        LangChainGateway gateway = new LangChainGateway(new ObjectMapper(), true, url, "openai",
                "deepseek-v4-flash", "sk-secret-test-key", "http://secret-base.test/v1");

        gateway.runAgent(new AgentTemplate(), new LinkedHashMap<String, Object>(),
                new LinkedHashMap<String, Object>(), "subject");
        gateway.classifyIntent("hello", java.util.Collections.<Map<String, String>>emptyList());
        gateway.executeHelp("how", java.util.Collections.<String>emptyList(), null,
                java.util.Collections.<Map<String, Object>>emptyList());
        Map<String, Object> layout = new LinkedHashMap<String, Object>();
        layout.put("nodes", java.util.Collections.emptyList());
        layout.put("edges", java.util.Collections.emptyList());
        String streamBody = gateway.buildStreamBody(layout, "subject", new LinkedHashMap<String, Object>(),
                null, "run-1", false);
        bodies.add(streamBody);

        assertThat(bodies.size() >= 4, is(true));
        for (String body : bodies) {
            assertThat(body, body.contains("apiKey"), is(false));
            assertThat(body, body.contains("baseUrl"), is(false));
            assertThat(body, body.contains("sk-secret-test-key"), is(false));
            assertThat(body, body.contains("secret-base.test"), is(false));
        }
    }

    private void summaryOnlyResponse(HttpExchange exchange) throws IOException {
        byte[] response = "{\"ok\":true,\"summary\":\"Real LLM summary\",\"provider\":\"langchain-openai\"}"
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(response);
        }
    }

    @Test
    void buildStreamBodyCarriesRunOwnerOnlyWhenPresent() throws Exception {
        LangChainGateway gateway = gateway("http://127.0.0.1:8787", false);
        Map<String, Object> layout = new LinkedHashMap<String, Object>();
        layout.put("nodes", java.util.Collections.emptyList());
        layout.put("edges", java.util.Collections.emptyList());

        String withOwner = gateway.buildStreamBody(layout, "s", new LinkedHashMap<String, Object>(), null, "run-1", false,
                LangChainGateway.runOwner("u-1", "t-1"));
        Map<?, ?> parsed = new ObjectMapper().readValue(withOwner, Map.class);
        assertThat(((Map<?, ?>) parsed.get("owner")).get("userId"), is((Object) "u-1"));
        assertThat(((Map<?, ?>) parsed.get("owner")).get("tenantId"), is((Object) "t-1"));

        String legacy = gateway.buildStreamBody(layout, "s", new LinkedHashMap<String, Object>(), null, "run-1", false);
        assertThat(new ObjectMapper().readValue(legacy, Map.class).containsKey("owner"), is(false));
        assertThat(LangChainGateway.runOwner(" ", "t-1") == null, is(true));
    }

    @Test
    void enqueueRunMessagePostsKindMessageAndOwnerToThreadQueue() throws Exception {
        final java.util.concurrent.atomic.AtomicReference<String> path = new java.util.concurrent.atomic.AtomicReference<String>();
        final java.util.concurrent.atomic.AtomicReference<String> body = new java.util.concurrent.atomic.AtomicReference<String>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/message-queue/", exchange -> {
            path.set(exchange.getRequestURI().getRawPath());
            body.set(new String(readAll(exchange), StandardCharsets.UTF_8));
            respond(exchange, 202, "{\"ok\":true,\"threadId\":\"run-1\",\"position\":1,\"message\":{\"id\":\"m-1\"}}");
        });
        server.start();
        LangChainGateway gateway = gateway("http://127.0.0.1:" + server.getAddress().getPort(), true);

        Map<String, Object> result = gateway.enqueueRunMessage("run-1", "steer", "use MSFT", null, "u-1", "t-1");

        assertThat(result.get("ok"), is((Object) true));
        assertThat(result.get("position"), is((Object) 1));
        assertThat(path.get(), is("/message-queue/run-1/messages"));
        Map<?, ?> sent = new ObjectMapper().readValue(body.get(), Map.class);
        assertThat(sent.get("kind"), is((Object) "steer"));
        assertThat(sent.get("message"), is((Object) "use MSFT"));
        assertThat(((Map<?, ?>) sent.get("owner")).get("userId"), is((Object) "u-1"));
        assertThat(sent.containsKey("agentId"), is(false));
    }

    @Test
    void cancelRunPostsThreadAndOwnerAndMapsNotActive() throws Exception {
        final java.util.concurrent.atomic.AtomicReference<String> body = new java.util.concurrent.atomic.AtomicReference<String>();
        final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/cancel-run", exchange -> {
            body.set(new String(readAll(exchange), StandardCharsets.UTF_8));
            if (calls.incrementAndGet() == 1) {
                respond(exchange, 200, "{\"ok\":true,\"threadId\":\"run-1\",\"cancelled\":true}");
            } else {
                respond(exchange, 404, "{\"ok\":false,\"code\":\"NOT_ACTIVE\",\"error\":\"no active run\"}");
            }
        });
        server.start();
        LangChainGateway gateway = gateway("http://127.0.0.1:" + server.getAddress().getPort(), true);

        Map<String, Object> first = gateway.cancelRun("run-1", "u-1", "t-1");
        Map<String, Object> second = gateway.cancelRun("run-1", "u-1", "t-1");

        assertThat(first.get("ok"), is((Object) true));
        Map<?, ?> sent = new ObjectMapper().readValue(body.get(), Map.class);
        assertThat(sent.get("threadId"), is((Object) "run-1"));
        assertThat(((Map<?, ?>) sent.get("owner")).get("userId"), is((Object) "u-1"));
        assertThat(second.get("ok"), is((Object) false));
        assertThat(second.get("code"), is((Object) "NOT_ACTIVE"));
    }

    @Test
    void cancelRunNeverThrowsWhenOrchestratorIsUnreachable() {
        LangChainGateway gateway = gateway("http://127.0.0.1:1", true);

        Map<String, Object> result = gateway.cancelRun("run-1", "u-1", "t-1");

        assertThat(result.get("ok"), is((Object) false));
        assertThat(result.get("code"), is((Object) "ORCHESTRATOR_UNAVAILABLE"));
    }

    @Test
    void workflowCallsUseTheirOwnReadTimeout() {
        LangChainGateway gateway = gateway("http://127.0.0.1:1", true);
        assertThat(gateway.workflowReadTimeoutMs(), is(LangChainGateway.DEFAULT_WORKFLOW_READ_TIMEOUT_MS));

        gateway.configureWorkflowTimeouts(1000, 45000);

        assertThat(gateway.workflowReadTimeoutMs(), is(45000));
    }

    @Test
    void enqueueRunMessageForwardsOptionalAgentTarget() throws Exception {
        final java.util.concurrent.atomic.AtomicReference<String> body = new java.util.concurrent.atomic.AtomicReference<String>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/message-queue/", exchange -> {
            body.set(new String(readAll(exchange), StandardCharsets.UTF_8));
            respond(exchange, 202, "{\"ok\":true,\"message\":{\"id\":\"m-1\",\"target\":\"val\"}}");
        });
        server.start();
        LangChainGateway gateway = gateway("http://127.0.0.1:" + server.getAddress().getPort(), true);

        Map<String, Object> result = gateway.enqueueRunMessage("run-1", "follow_up", "x", " val ", "u-1", "t-1");

        assertThat(result.get("ok"), is((Object) true));
        Map<?, ?> sent = new ObjectMapper().readValue(body.get(), Map.class);
        assertThat(sent.get("agentId"), is((Object) "val"));
    }

    @Test
    void enqueueRunMessageMapsOrchestratorErrorCodes() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/message-queue/", exchange -> {
            readAll(exchange);
            respond(exchange, 429, "{\"ok\":false,\"code\":\"QUEUE_FULL\",\"error\":\"full\"}");
        });
        server.start();
        LangChainGateway gateway = gateway("http://127.0.0.1:" + server.getAddress().getPort(), true);

        Map<String, Object> result = gateway.enqueueRunMessage("run-1", "follow_up", "x", null, "u-1", "t-1");

        assertThat(result.get("ok"), is((Object) false));
        assertThat(result.get("code"), is((Object) "QUEUE_FULL"));
        assertThat(result.get("httpStatus"), is((Object) 429));
    }

    @Test
    void enqueueRunMessageNeverThrowsWhenDisabledOrUnreachable() {
        Map<String, Object> disabled = gateway("http://127.0.0.1:1", false)
                .enqueueRunMessage("run-1", "steer", "x", null, "u-1", "t-1");
        assertThat(disabled.get("code"), is((Object) "DISABLED"));

        Map<String, Object> down = gateway("http://127.0.0.1:1", true)
                .enqueueRunMessage("run-1", "steer", "x", null, "u-1", "t-1");
        assertThat(down.get("ok"), is((Object) false));
        assertThat(down.get("code"), is((Object) "ORCHESTRATOR_UNAVAILABLE"));

        Map<String, Object> cleared = gateway("http://127.0.0.1:1", true).clearRunMessageQueue("run-1", "u-1", "t-1");
        assertThat(cleared.get("ok"), is((Object) false));
    }

    private static LangChainGateway gateway(String url, boolean enabled) {
        return new LangChainGateway(new ObjectMapper(), enabled, url, "openai", "deepseek-v4-flash",
                "test-key", "http://example.test/v1");
    }

    private static byte[] readAll(HttpExchange exchange) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        java.io.InputStream in = exchange.getRequestBody();
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static void respond(HttpExchange exchange, int status, String json) throws IOException {
        byte[] response = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, response.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(response);
        }
    }
}
