package com.aegis.alpha.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DifyDslServiceTest {

    @Mock
    private WorkflowService workflowService;

    private DifyDslService service;

    @BeforeEach
    void setUp() {
        service = new DifyDslService(workflowService, "http://127.0.0.1:5178", "test-token");
    }

    @SafeVarargs
    private static <K, V> Map<K, V> mapOf(Map.Entry<K, V>... entries) {
        Map<K, V> map = new LinkedHashMap<>();
        for (Map.Entry<K, V> entry : entries) {
            map.put(entry.getKey(), entry.getValue());
        }
        return map;
    }

    private static <K, V> Map.Entry<K, V> entry(K key, V value) {
        return new java.util.AbstractMap.SimpleEntry<>(key, value);
    }

    @Test
    void yamlProducesValidYaml() {
        Map<String, Object> dsl = new LinkedHashMap<>();
        dsl.put("version", "0.3.0");
        dsl.put("kind", "app");

        String yaml = service.yaml(dsl);

        assertThat(yaml).contains("version:");
        assertThat(yaml).contains("0.3.0");
        assertThat(yaml).contains("kind: app");
    }

    @Test
    void yamlSerializesNestedStructures() {
        Map<String, Object> dsl = new LinkedHashMap<>();
        dsl.put("version", "0.3.0");
        Map<String, Object> app = new LinkedHashMap<>();
        app.put("name", "Test Workflow");
        app.put("mode", "workflow");
        dsl.put("app", app);

        String yaml = service.yaml(dsl);

        assertThat(yaml).contains("name: Test Workflow");
        assertThat(yaml).contains("mode: workflow");
    }

    @Test
    void toDifyDslConvertsStartNode() {
        Map<String, Object> layout = new LinkedHashMap<>();
        Map<String, Object> startNode = new LinkedHashMap<>();
        startNode.put("id", "start-1");
        startNode.put("type", "start");
        startNode.put("position", mapOf(entry("x", 100), entry("y", 200)));
        startNode.put("data", mapOf(entry("title", "Start"), entry("desc", "Entry point")));
        layout.put("nodes", Arrays.asList(startNode));
        layout.put("edges", Collections.emptyList());

        Map<String, Object> result = service.toDifyDsl("test-wf", layout);

        assertThat(result.get("version")).isEqualTo("0.3.0");
        assertThat(result.get("kind")).isEqualTo("app");
        @SuppressWarnings("unchecked")
        Map<String, Object> app = (Map<String, Object>) result.get("app");
        assertThat(app.get("name")).isEqualTo("Aegis Alpha test-wf");
        assertThat(app.get("mode")).isEqualTo("workflow");

        @SuppressWarnings("unchecked")
        Map<String, Object> workflow = (Map<String, Object>) result.get("workflow");
        @SuppressWarnings("unchecked")
        Map<String, Object> graph = (Map<String, Object>) workflow.get("graph");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) graph.get("nodes");

        assertThat(nodes).hasSize(1);
        Map<String, Object> difyNode = nodes.get(0);
        assertThat(difyNode.get("id")).isEqualTo("start-1");
        assertThat(difyNode.get("type")).isEqualTo("custom");

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) difyNode.get("data");
        assertThat(data.get("type")).isEqualTo("start");
    }

    @Test
    void toDifyDslConvertsEndNode() {
        Map<String, Object> layout = new LinkedHashMap<>();
        Map<String, Object> endNode = new LinkedHashMap<>();
        endNode.put("id", "end-1");
        endNode.put("type", "end");
        endNode.put("position", mapOf(entry("x", 300), entry("y", 200)));
        endNode.put("data", mapOf(entry("title", "End"), entry("desc", "Exit point")));
        layout.put("nodes", Arrays.asList(endNode));
        layout.put("edges", Collections.emptyList());

        Map<String, Object> result = service.toDifyDsl("test-wf", layout);

        @SuppressWarnings("unchecked")
        Map<String, Object> workflow = (Map<String, Object>) result.get("workflow");
        @SuppressWarnings("unchecked")
        Map<String, Object> graph = (Map<String, Object>) workflow.get("graph");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) graph.get("nodes");

        Map<String, Object> difyNode = nodes.get(0);
        assertThat(difyNode.get("id")).isEqualTo("end-1");
        assertThat(difyNode.get("type")).isEqualTo("custom");

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) difyNode.get("data");
        assertThat(data.get("type")).isEqualTo("end");
    }

    @Test
    void toDifyDslConvertsHttpNode() {
        Map<String, Object> layout = new LinkedHashMap<>();
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", "node-1");
        node.put("type", "function");
        node.put("position", mapOf(entry("x", 200), entry("y", 300)));
        node.put("data", mapOf(entry("title", "Fetch Data"), entry("desc", "Calls API"), entry("functionName", "fdb.daily_ohlc")));
        layout.put("nodes", Arrays.asList(node));
        layout.put("edges", Collections.emptyList());

        Map<String, Object> result = service.toDifyDsl("my-wf", layout);

        @SuppressWarnings("unchecked")
        Map<String, Object> workflow = (Map<String, Object>) result.get("workflow");
        @SuppressWarnings("unchecked")
        Map<String, Object> graph = (Map<String, Object>) workflow.get("graph");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) graph.get("nodes");

        Map<String, Object> difyNode = nodes.get(0);
        assertThat(difyNode.get("type")).isEqualTo("custom");

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) difyNode.get("data");
        assertThat(data.get("type")).isEqualTo("http-request");
        assertThat(data.get("method")).isEqualTo("post");
        assertThat(data.get("url")).asString().contains("127.0.0.1:5178");
        assertThat(data.get("headers")).asString().contains("test-token");
    }

    @Test
    void toDifyDslConvertsEdges() {
        Map<String, Object> layout = new LinkedHashMap<>();
        Map<String, Object> edge = new LinkedHashMap<>();
        edge.put("id", "e1");
        edge.put("source", "start-1");
        edge.put("target", "end-1");
        layout.put("nodes", Collections.emptyList());
        layout.put("edges", Arrays.asList(edge));

        Map<String, Object> result = service.toDifyDsl("test-wf", layout);

        @SuppressWarnings("unchecked")
        Map<String, Object> workflow = (Map<String, Object>) result.get("workflow");
        @SuppressWarnings("unchecked")
        Map<String, Object> graph = (Map<String, Object>) workflow.get("graph");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edges = (List<Map<String, Object>>) graph.get("edges");

        assertThat(edges).hasSize(1);
        Map<String, Object> difyEdge = edges.get(0);
        assertThat(difyEdge.get("id")).isEqualTo("e1");
        assertThat(difyEdge.get("source")).isEqualTo("start-1");
        assertThat(difyEdge.get("target")).isEqualTo("end-1");
    }

    @Test
    void exportDelegatesToWorkflowService() {
        Map<String, Object> layout = new LinkedHashMap<>();
        layout.put("nodes", Collections.emptyList());
        layout.put("edges", Collections.emptyList());
        when(workflowService.layout("my-wf")).thenReturn(layout);

        Map<String, Object> result = service.export("my-wf");

        assertThat(result.get("workflowKey")).isEqualTo("my-wf");
        assertThat(result.get("dsl")).isNotNull();
        assertThat(result.get("yaml")).isNotNull();
        assertThat(result.get("yaml")).asString().contains("version:");
    }

    @Test
    void exportIncludesYamlInResult() {
        Map<String, Object> layout = new LinkedHashMap<>();
        layout.put("nodes", Collections.emptyList());
        layout.put("edges", Collections.emptyList());
        when(workflowService.layout("wf-1")).thenReturn(layout);

        Map<String, Object> result = service.export("wf-1");

        assertThat(result.get("yaml")).asString().contains("kind: app");
    }
}