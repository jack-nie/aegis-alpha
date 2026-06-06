package com.aegis.alpha.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DifyPublishServiceTest {

    @Mock
    private DifyDslService dslService;

    private DifyPublishService service;

    @BeforeEach
    void setUp() {
        service = new DifyPublishService(dslService, "", "");
    }

    @Test
    void publishReturnsFalseWhenConsoleBaseUrlEmpty() {
        Map<String, Object> exported = new LinkedHashMap<>();
        exported.put("yaml", "version: 0.3.0");
        exported.put("workflowKey", "test-wf");
        when(dslService.export("test-wf")).thenReturn(exported);

        Map<String, Object> result = service.publish("test-wf");

        assertThat(result.get("published")).isEqualTo(false);
        assertThat(result.get("reason")).asString().contains("Console URL or token");
    }

    @Test
    void publishReturnsFalseWhenConsoleTokenEmpty() {
        DifyPublishService serviceWithUrl = new DifyPublishService(dslService, "https://dify.example.com", "");
        Map<String, Object> exported = new LinkedHashMap<>();
        exported.put("yaml", "version: 0.3.0");
        when(dslService.export("test-wf")).thenReturn(exported);

        Map<String, Object> result = serviceWithUrl.publish("test-wf");

        assertThat(result.get("published")).isEqualTo(false);
    }

    @Test
    void publishReturnsFalseWhenConsoleBaseUrlWhitespace() {
        DifyPublishService serviceWhitespace = new DifyPublishService(dslService, "   ", "token");
        Map<String, Object> exported = new LinkedHashMap<>();
        exported.put("yaml", "version: 0.3.0");
        when(dslService.export("test-wf")).thenReturn(exported);

        Map<String, Object> result = serviceWhitespace.publish("test-wf");

        assertThat(result.get("published")).isEqualTo(false);
    }

    @Test
    void exportDslDelegatesToDslService() {
        Map<String, Object> exported = new LinkedHashMap<>();
        exported.put("workflowKey", "my-wf");
        exported.put("yaml", "kind: app");
        when(dslService.export("my-wf")).thenReturn(exported);

        Map<String, Object> result = service.exportDsl("my-wf");

        assertThat(result).isSameAs(exported);
        assertThat(result.get("workflowKey")).isEqualTo("my-wf");
    }

    @Test
    void publishIncludesExportedDataInResult() {
        Map<String, Object> exported = new LinkedHashMap<>();
        exported.put("workflowKey", "wf-1");
        exported.put("yaml", "kind: app");
        when(dslService.export("wf-1")).thenReturn(exported);

        Map<String, Object> result = service.publish("wf-1");

        assertThat(result.get("workflowKey")).isEqualTo("wf-1");
        assertThat(result.get("yaml")).isEqualTo("kind: app");
        assertThat(result.get("published")).isEqualTo(false);
    }
}