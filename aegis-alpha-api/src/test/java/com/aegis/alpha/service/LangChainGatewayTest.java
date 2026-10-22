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
}
