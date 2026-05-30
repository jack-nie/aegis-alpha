package com.marketmind.alpha.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketmind.alpha.domain.AgentTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class LangChainGateway {
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final String engineUrl;
    private final String provider;
    private final String defaultModel;
    private final String apiKey;
    private final String baseUrl;

    @Autowired
    public LangChainGateway(ObjectMapper objectMapper,
                            @Value("${marketmind.langchain.enabled:false}") boolean enabled,
                            @Value("${marketmind.langchain.engine-url:http://127.0.0.1:8787}") String engineUrl,
                            @Value("${marketmind.langchain.provider:openai}") String provider,
                            @Value("${marketmind.langchain.model:deepseek-v4-flash}") String defaultModel,
                            @Value("${marketmind.langchain.api-key:}") String apiKey,
                            @Value("${marketmind.langchain.base-url:}") String baseUrl,
                            @Value("${marketmind.langchain.connect-timeout-ms:3000}") int connectTimeoutMs,
                            @Value("${marketmind.langchain.read-timeout-ms:30000}") int readTimeoutMs) {
        this.objectMapper = objectMapper;
        this.restTemplate = restTemplate(connectTimeoutMs, readTimeoutMs);
        this.shortTimeoutRestTemplate = restTemplate(2000, 5000);
        this.enabled = enabled;
        this.engineUrl = engineUrl;
        this.provider = provider;
        this.defaultModel = defaultModel;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
    }

    public LangChainGateway(ObjectMapper objectMapper,
                            boolean enabled,
                            String engineUrl,
                            String provider,
                            String defaultModel,
                            String apiKey,
                            String baseUrl) {
        this(objectMapper, enabled, engineUrl, provider, defaultModel, apiKey, baseUrl, 3000, 30000);
    }

    public Map<String, Object> runAgent(AgentTemplate agent, Map<String, Object> state, Map<String, Object> node, String subject) {
        return withLegacyContent(executeNode(agent, state, node, subject));
    }

    public Map<String, Object> executeNode(AgentTemplate agent, Map<String, Object> state, Map<String, Object> node, String subject) {
        if (enabled) {
            try {
                return callLangGraph(agent, state, node, subject, "/execute-node");
            } catch (Exception ex) {
                return localStructuredNode(agent, state, node, subject, "LangGraph call failed: " + ex.getMessage());
            }
        }
        return localStructuredNode(agent, state, node, subject, "LangGraph disabled. Set MARKETMIND_LANGCHAIN_ENABLED=true to use the external engine.");
    }

    private Map<String, Object> withLegacyContent(Map<String, Object> result) {
        if (result == null) {
            return new LinkedHashMap<String, Object>();
        }
        String content = string(result.get("content"));
        String message = string(result.get("message"));
        String summary = string(result.get("summary"));
        if (content.isEmpty()) {
            content = first(message, summary);
            if (!content.isEmpty()) {
                result.put("content", content);
            }
        }
        if (message.isEmpty()) {
            message = first(content, summary);
            if (!message.isEmpty()) {
                result.put("message", message);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> callLangGraph(AgentTemplate agent, Map<String, Object> state, Map<String, Object> node, String subject, String path) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("provider", provider);
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            body.put("apiKey", apiKey);
        }
        if (baseUrl != null && !baseUrl.trim().isEmpty()) {
            body.put("baseUrl", baseUrl);
        }
        body.put("model", resolveModel(agent));
        body.put("agent", agent);
        body.put("state", state);
        body.put("node", node);
        body.put("subject", subject);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        Object response = restTemplate.postForObject(trimSlash(engineUrl) + path, request, Object.class);
        if (response instanceof Map) {
            return (Map<String, Object>) response;
        }
        Map<String, Object> wrapped = new LinkedHashMap<>();
        wrapped.put("content", response);
        wrapped.put("provider", provider);
        return wrapped;
    }

    public String streamWorkflowUrl() {
        return trimSlash(engineUrl) + "/stream-workflow";
    }

    public String buildStreamBody(Map<String, Object> layout, String subject, Map<String, Object> inputs) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            body.put("apiKey", apiKey);
        }
        if (baseUrl != null && !baseUrl.trim().isEmpty()) {
            body.put("baseUrl", baseUrl);
        }
        body.put("provider", provider);
        body.put("model", defaultModel);
        body.put("nodes", layout.get("nodes"));
        body.put("edges", layout.get("edges"));
        body.put("subject", subject);
        Map<String, Object> state = new LinkedHashMap<>(inputs);
        state.put("subject", subject);
        body.put("state", state);
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to build stream body", ex);
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> classifyIntent(String message, java.util.List<Map<String, String>> workflows) {
        if (!enabled) {
            return null;
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("message", message);
            body.put("workflows", workflows);
            if (apiKey != null && !apiKey.trim().isEmpty()) {
                body.put("apiKey", apiKey);
            }
            if (baseUrl != null && !baseUrl.trim().isEmpty()) {
                body.put("baseUrl", baseUrl);
            }
            body.put("model", defaultModel);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
            Object response = shortTimeoutRestTemplate.postForObject(trimSlash(engineUrl) + "/classify-intent", request, Object.class);
            if (response instanceof Map) {
                return (Map<String, Object>) response;
            }
            return null;
        } catch (Exception ex) {
            return null;
        }
    }

    private Map<String, Object> localStructuredNode(AgentTemplate agent, Map<String, Object> state, Map<String, Object> node, String subject, String reason) {
        String handler = handler(node);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("status", "local-mock");
        result.put("degraded", true);
        result.put("provider", "local-mock");
        result.put("model", resolveModel(agent));
        result.put("handler", handler);
        result.put("nodeId", nodeId(node));
        result.put("nodeName", nodeName(node));
        result.put("subject", subject);
        result.put("summary", localSummary(handler, subject, state));
        result.put("signals", java.util.Arrays.asList(signal("handler", handler), signal("subject", subject)));
        result.put("sources", java.util.Collections.singletonList(source("Local deterministic workflow fallback", "local")));
        result.put("confidence", "finance.stock_recommendation_aggregate".equals(handler) ? 0.67 : 0.58);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reason", reason);
        data.put("fallback", true);
        data.put("fallbackPolicy", "local_deterministic_degraded");
        data.put("agentId", agent == null ? agentId(node) : agent.getAgentId());
        data.put("agentName", agent == null ? nodeName(node) : agent.getName());
        data.put("stateSize", state == null ? 0 : state.size());
        result.put("data", data);
        result.put("content", result.get("summary"));
        return result;
    }

    private Map<String, Object> signal(String name, String value) {
        Map<String, Object> signal = new LinkedHashMap<>();
        signal.put("name", name);
        signal.put("value", value);
        signal.put("weight", 0.5);
        return signal;
    }

    private Map<String, Object> source(String title, String type) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("title", title);
        source.put("url", "");
        source.put("type", type);
        return source;
    }

    private String localSummary(String handler, String subject, Map<String, Object> state) {
        if ("finance.stock_recommendation_aggregate".equals(handler)) {
            int upstream = state == null ? 0 : state.size();
            return "Local aggregate recommendation for " + subject + " based on " + upstream + " workflow state entries.";
        }
        return "Local structured " + handler + " result for " + subject + ".";
    }

    private Map<String, Object> unavailable(AgentTemplate agent, Map<String, Object> state, String subject, String reason) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", false);
        result.put("provider", "llm-unavailable");
        result.put("model", resolveModel(agent));
        result.put("agentId", agent.getAgentId());
        result.put("agentName", agent.getName());
        result.put("subject", subject);
        result.put("reason", reason);
        result.put("content", "Real LLM did not run: " + reason);
        result.put("stateSize", state == null ? 0 : state.size());
        return result;
    }

    private String handler(Map<String, Object> node) {
        Map<String, Object> data = data(node);
        return first(first(string(data.get("handler")), string(data.get("functionName"))), "logic");
    }

    private String nodeId(Map<String, Object> node) {
        return first(string(node == null ? null : node.get("id")), "inline");
    }

    private String nodeName(Map<String, Object> node) {
        Map<String, Object> data = data(node);
        return first(first(string(data.get("label")), string(data.get("title"))), nodeId(node));
    }

    private String agentId(Map<String, Object> node) {
        Map<String, Object> data = data(node);
        return first(string(data.get("agentId")), string(data.get("agent_id")));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(Map<String, Object> node) {
        if (node != null && node.get("data") instanceof Map) {
            return (Map<String, Object>) node.get("data");
        }
        return node == null ? new LinkedHashMap<String, Object>() : node;
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String first(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private String resolveModel(AgentTemplate agent) {
        String requested = first(agent == null ? null : agent.getModelName(), defaultModel);
        if (usesDeepSeekCompatibleEndpoint() && !isSupportedDeepSeekModel(requested)) {
            return deepSeekDefaultModel();
        }
        return requested;
    }

    private boolean usesDeepSeekCompatibleEndpoint() {
        return isSupportedDeepSeekModel(defaultModel) || (baseUrl != null && !baseUrl.trim().isEmpty());
    }

    private boolean isSupportedDeepSeekModel(String model) {
        return "deepseek-v4-pro".equals(model) || "deepseek-v4-flash".equals(model);
    }

    private String deepSeekDefaultModel() {
        return isSupportedDeepSeekModel(defaultModel) ? defaultModel : "deepseek-v4-flash";
    }

    private final RestTemplate shortTimeoutRestTemplate;

    private RestTemplate restTemplate(int connectTimeoutMs, int readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Math.max(1, connectTimeoutMs));
        factory.setReadTimeout(Math.max(1, readTimeoutMs));
        return new RestTemplate(factory);
    }

    private String trimSlash(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "http://127.0.0.1:8787";
        }
        String trimmed = value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
