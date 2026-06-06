package com.aegis.alpha.service;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowValidationServiceTest {
    private final WorkflowValidationService validationService = new WorkflowValidationService();

    @Test
    void rejectsCycle() {
        Map<String, Object> layout = layout(
                Arrays.asList(node("start", "start"), node("middle", "logic"), node("end", "end")),
                Arrays.asList(edge("start", "middle"), edge("middle", "start"), edge("middle", "end"))
        );

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> validationService.validateLayout(layout));

        assertTrue(error.getMessage().contains("cycle"));
    }

    @Test
    void rejectsMissingEdgeTarget() {
        Map<String, Object> layout = layout(
                Arrays.asList(node("start", "start"), node("end", "end")),
                Collections.singletonList(edge("start", "missing"))
        );

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> validationService.validateLayout(layout));

        assertTrue(error.getMessage().contains("unknown target"));
    }

    private Map<String, Object> layout(Object nodes, Object edges) {
        Map<String, Object> layout = new LinkedHashMap<>();
        layout.put("nodes", nodes);
        layout.put("edges", edges);
        return layout;
    }

    private Map<String, Object> node(String id, String nodeType) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", id);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("nodeType", nodeType);
        node.put("data", data);
        return node;
    }

    private Map<String, Object> edge(String source, String target) {
        Map<String, Object> edge = new LinkedHashMap<>();
        edge.put("source", source);
        edge.put("target", target);
        return edge;
    }
}
