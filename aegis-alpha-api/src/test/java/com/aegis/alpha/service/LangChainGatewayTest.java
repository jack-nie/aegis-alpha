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
