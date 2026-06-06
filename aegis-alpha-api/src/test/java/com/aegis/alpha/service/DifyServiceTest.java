package com.aegis.alpha.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class DifyServiceTest {

    @Test
    void runWorkflowReturnsStubWhenDisabled() {
        DifyService service = new DifyService(false, "https://api.dify.ai/v1", "key", "key");
        Map<String, Object> result = service.runWorkflow("test-wf", Collections.emptyMap(), "user1");
        assertThat(result.get("provider")).isEqualTo("local-stub");
        assertThat(result.get("status")).isEqualTo("queued");
        assertThat(result.get("workflow_key")).isEqualTo("test-wf");
    }

    @Test
    void runWorkflowReturnsStubWhenApiKeyMissing() {
        DifyService service = new DifyService(true, "https://api.dify.ai/v1", "", "chat-key");
        Map<String, Object> result = service.runWorkflow("test-wf", Collections.emptyMap(), "user1");
        assertThat(result.get("provider")).isEqualTo("local-stub");
        assertThat(result.get("status")).isEqualTo("queued");
    }

    @Test
    void runWorkflowReturnsStubWhenApiKeyNull() {
        DifyService service = new DifyService(true, "https://api.dify.ai/v1", null, "chat-key");
        Map<String, Object> result = service.runWorkflow("test-wf", Collections.emptyMap(), "user1");
        assertThat(result.get("provider")).isEqualTo("local-stub");
    }

    @Test
    void runWorkflowPassesInputsWhenEnabledButNoKey() {
        DifyService service = new DifyService(true, "https://api.dify.ai/v1", "  ", "chat-key");
        Map<String, Object> inputs = new HashMap<>();
        inputs.put("ticker", "AAPL");
        Map<String, Object> result = service.runWorkflow("test-wf", inputs, "user1");
        assertThat(result.get("workflow_key")).isEqualTo("test-wf");
    }

    @Test
    void chatReturnsStubWhenDisabled() {
        DifyService service = new DifyService(false, "https://api.dify.ai/v1", "wf-key", "chat-key");
        Map<String, Object> result = service.chat("agent-1", "hello", "user1");
        assertThat(result.get("provider")).isEqualTo("local-stub");
        assertThat(result.get("agent_id")).isEqualTo("agent-1");
        assertThat(result.get("answer")).asString().contains("Dify is disabled");
    }

    @Test
    void chatReturnsStubWhenChatApiKeyMissing() {
        DifyService service = new DifyService(true, "https://api.dify.ai/v1", "wf-key", "");
        Map<String, Object> result = service.chat("agent-1", "hello", "user1");
        assertThat(result.get("provider")).isEqualTo("local-stub");
        assertThat(result.get("answer")).asString().contains("chat API key is missing");
    }

    @Test
    void chatReturnsStubWhenChatApiKeyNull() {
        DifyService service = new DifyService(true, "https://api.dify.ai/v1", "wf-key", null);
        Map<String, Object> result = service.chat("agent-1", "hello", "user1");
        assertThat(result.get("provider")).isEqualTo("local-stub");
    }

    @Test
    void chatReturnsStubWithWhitespaceApiKey() {
        DifyService service = new DifyService(true, "https://api.dify.ai/v1", "wf-key", "   ");
        Map<String, Object> result = service.chat("agent-1", "hello", "user1");
        assertThat(result.get("provider")).isEqualTo("local-stub");
    }

    @Test
    void runWorkflowWithNullInputs() {
        DifyService service = new DifyService(false, "https://api.dify.ai/v1", "key", "key");
        Map<String, Object> result = service.runWorkflow("test-wf", null, "user1");
        assertThat(result.get("workflow_key")).isEqualTo("test-wf");
        assertThat(result.get("status")).isEqualTo("queued");
    }

    @Test
    void chatWithNullMessage() {
        DifyService service = new DifyService(false, "https://api.dify.ai/v1", "key", "key");
        Map<String, Object> result = service.chat("agent-1", null, "user1");
        assertThat(result.get("provider")).isEqualTo("local-stub");
    }
}