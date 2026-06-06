package com.aegis.alpha.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class DifyDslService {
    private final WorkflowService workflowService;
    private final String callbackBaseUrl;
    private final String nodeExecutionToken;

    public DifyDslService(WorkflowService workflowService,
                          @Value("${aegis.dify.node-callback-base-url:http://127.0.0.1:5178}") String callbackBaseUrl,
                          @Value("${aegis.dify.node-execution-token:}") String nodeExecutionToken) {
        this.workflowService = workflowService;
        this.callbackBaseUrl = trimRight(callbackBaseUrl);
        this.nodeExecutionToken = nodeExecutionToken;
    }

    public Map<String, Object> export(String workflowKey) {
        Map<String, Object> layout = workflowService.layout(workflowKey);
        Map<String, Object> dsl = toDifyDsl(workflowKey, layout);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("workflowKey", workflowKey);
        result.put("dsl", dsl);
        result.put("yaml", yaml(dsl));
        return result;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> toDifyDsl(String workflowKey, Map<String, Object> layout) {
        List<Map<String, Object>> localNodes = asList(layout.get("nodes"));
        List<Map<String, Object>> localEdges = asList(layout.get("edges"));

        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("version", "0.3.0");
        root.put("kind", "app");

        Map<String, Object> app = new LinkedHashMap<String, Object>();
        app.put("name", "Aegis Alpha " + workflowKey);
        app.put("mode", "workflow");
        app.put("description", "Generated from Aegis Alpha local workflow DSL.");
        app.put("icon", "chart-line");
        app.put("icon_background", "#FFFFFF");
        root.put("app", app);

        Map<String, Object> workflow = new LinkedHashMap<String, Object>();
        workflow.put("conversation_variables", new ArrayList<Object>());
        workflow.put("environment_variables", new ArrayList<Object>());
        workflow.put("features", defaultFeatures());

        Map<String, Object> graph = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> difyNodes = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> localNode : localNodes) {
            difyNodes.add(toDifyNode(workflowKey, localNode));
        }
        List<Map<String, Object>> difyEdges = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> localEdge : localEdges) {
            difyEdges.add(toDifyEdge(localEdge));
        }
        graph.put("nodes", difyNodes);
        graph.put("edges", difyEdges);
        graph.put("viewport", map("x", 0, "y", 0, "zoom", 0.8));
        workflow.put("graph", graph);
        root.put("workflow", workflow);
        return root;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toDifyNode(String workflowKey, Map<String, Object> localNode) {
        String id = string(localNode.get("id"));
        String type = string(localNode.get("type"));
        Map<String, Object> position = localNode.get("position") instanceof Map ? (Map<String, Object>) localNode.get("position") : new LinkedHashMap<String, Object>();
        Map<String, Object> data = localNode.get("data") instanceof Map ? (Map<String, Object>) localNode.get("data") : new LinkedHashMap<String, Object>();
        String title = valueOrDefault(data.get("title"), id);
        String desc = valueOrDefault(data.get("desc"), "");
        String functionName = valueOrDefault(data.get("functionName"), "custom.node");

        Map<String, Object> node = new LinkedHashMap<String, Object>();
        node.put("id", id);
        node.put("position", map("x", number(position.get("x"), 0), "y", number(position.get("y"), 0)));
        node.put("sourcePosition", "right");
        node.put("targetPosition", "left");

        Map<String, Object> nodeData = new LinkedHashMap<String, Object>();
        if ("start".equals(type)) {
            node.put("type", "custom");
            nodeData.put("type", "start");
            nodeData.put("title", title);
            nodeData.put("desc", desc);
            nodeData.put("variables", new ArrayList<Object>());
        } else if ("end".equals(type)) {
            node.put("type", "custom");
            nodeData.put("type", "end");
            nodeData.put("title", title);
            nodeData.put("desc", desc);
            nodeData.put("outputs", new ArrayList<Object>());
        } else {
            node.put("type", "custom");
            nodeData.put("type", "http-request");
            nodeData.put("title", title);
            nodeData.put("desc", desc);
            nodeData.put("method", "post");
            nodeData.put("url", callbackBaseUrl + "/_backend/internal/workflow-nodes/execute");
            nodeData.put("headers", "X-Aegis-Workflow-Token: " + nodeExecutionToken);
            nodeData.put("body", jsonBody(workflowKey, id, functionName));
            nodeData.put("body_type", "json");
            nodeData.put("timeout", map("connect", 10, "read", 60, "write", 20));
            nodeData.put("authorization", map("type", "no-auth"));
            nodeData.put("variables", new ArrayList<Object>());
        }
        node.put("data", nodeData);
        return node;
    }

    private Map<String, Object> toDifyEdge(Map<String, Object> localEdge) {
        String source = string(localEdge.get("source"));
        String target = string(localEdge.get("target"));
        Map<String, Object> edge = new LinkedHashMap<String, Object>();
        edge.put("id", valueOrDefault(localEdge.get("id"), source + "-" + target));
        edge.put("source", source);
        edge.put("target", target);
        edge.put("type", "custom");
        edge.put("sourceHandle", "source");
        edge.put("targetHandle", "target");
        edge.put("data", map("isInIteration", false, "sourceType", "http-request", "targetType", "http-request"));
        return edge;
    }

    public String yaml(Map<String, Object> dsl) {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setAllowUnicode(true);
        options.setIndent(2);
        return new Yaml(options).dump(dsl);
    }

    private Map<String, Object> defaultFeatures() {
        Map<String, Object> features = new LinkedHashMap<String, Object>();
        features.put("file_upload", map("enabled", false));
        features.put("opening_statement", "");
        features.put("retriever_resource", map("enabled", false));
        features.put("sensitive_word_avoidance", map("enabled", false));
        features.put("speech_to_text", map("enabled", false));
        features.put("suggested_questions", new ArrayList<Object>());
        features.put("suggested_questions_after_answer", map("enabled", false));
        features.put("text_to_speech", map("enabled", false, "language", "", "voice", ""));
        return features;
    }

    private String jsonBody(String workflowKey, String nodeId, String functionName) {
        return "{\"workflowKey\":\"" + escape(workflowKey) + "\",\"nodeId\":\"" + escape(nodeId) + "\",\"functionName\":\"" + escape(functionName) + "\",\"context\":\"{{#sys.query#}}\"}";
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> asList(Object value) {
        if (value instanceof List) {
            return (List<Map<String, Object>>) value;
        }
        return new ArrayList<Map<String, Object>>();
    }

    private Map<String, Object> map(Object... values) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < values.length; i += 2) {
            map.put(String.valueOf(values[i]), values[i + 1]);
        }
        return map;
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String valueOrDefault(Object value, String fallback) {
        String string = string(value);
        return string.trim().isEmpty() ? fallback : string;
    }

    private double number(Object value, double fallback) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (Exception ex) {
            return fallback;
        }
    }

    private String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String trimRight(String value) {
        if (value == null) return "";
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }
}
