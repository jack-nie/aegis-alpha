package com.marketmind.alpha.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@Service
public class DifyService {
    private final RestTemplate restTemplate = new RestTemplate();
    private final boolean enabled;
    private final String baseUrl;
    private final String workflowApiKey;
    private final String chatApiKey;

    public DifyService(@Value("${marketmind.dify.enabled:false}") boolean enabled,
                       @Value("${marketmind.dify.base-url:https://api.dify.ai/v1}") String baseUrl,
                       @Value("${marketmind.dify.workflow-api-key:}") String workflowApiKey,
                       @Value("${marketmind.dify.chat-api-key:}") String chatApiKey) {
        this.enabled = enabled;
        this.baseUrl = trimRight(baseUrl);
        this.workflowApiKey = workflowApiKey;
        this.chatApiKey = chatApiKey;
    }

    public Map<String, Object> runWorkflow(String workflowKey, Map<String, Object> inputs, String user) {
        if (!enabled || isEmpty(workflowApiKey)) {
            Map<String, Object> stub = new HashMap<String, Object>();
            stub.put("provider", "local-stub");
            stub.put("workflow_key", workflowKey);
            stub.put("status", "queued");
            stub.put("message", "Dify is disabled or workflow API key is missing.");
            return stub;
        }
        Map<String, Object> body = new HashMap<String, Object>();
        body.put("inputs", inputs == null ? new HashMap<String, Object>() : inputs);
        body.put("response_mode", "blocking");
        body.put("user", user);
        return post("/workflows/run", workflowApiKey, body);
    }

    public Map<String, Object> chat(String agentId, String message, String user) {
        if (!enabled || isEmpty(chatApiKey)) {
            Map<String, Object> stub = new HashMap<String, Object>();
            stub.put("provider", "local-stub");
            stub.put("agent_id", agentId);
            stub.put("answer", "Dify is disabled or chat API key is missing.");
            return stub;
        }
        Map<String, Object> body = new HashMap<String, Object>();
        body.put("inputs", new HashMap<String, Object>());
        body.put("query", message == null ? "" : message);
        body.put("response_mode", "blocking");
        body.put("user", user);
        return post("/chat-messages", chatApiKey, body);
    }

    private Map<String, Object> post(String path, String apiKey, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + apiKey);
        return restTemplate.postForObject(baseUrl + path, new HttpEntity<Map<String, Object>>(body, headers), Map.class);
    }

    private static boolean isEmpty(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String trimRight(String value) {
        if (value == null || value.endsWith("/")) {
            return value == null ? "" : value.substring(0, value.length() - 1);
        }
        return value;
    }
}
