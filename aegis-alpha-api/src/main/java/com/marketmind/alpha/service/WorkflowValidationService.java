package com.marketmind.alpha.service;

import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

@Service
public class WorkflowValidationService {
    public void validateLayout(Map<String, Object> layout) {
        List<Map<String, Object>> nodes = castList(layout == null ? null : layout.get("nodes"));
        List<Map<String, Object>> edges = castList(layout == null ? null : layout.get("edges"));
        if (nodes.isEmpty()) {
            throw new IllegalArgumentException("Workflow validation failed: at least one start node is required.");
        }

        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        int startCount = 0;
        for (Map<String, Object> node : nodes) {
            String id = text(node.get("id"));
            if (id.isEmpty()) {
                throw new IllegalArgumentException("Workflow validation failed: node id cannot be empty.");
            }
            if (byId.containsKey(id)) {
                throw new IllegalArgumentException("Workflow validation failed: duplicate node id " + id + ".");
            }
            byId.put(id, node);
            if ("start".equals(nodeType(node))) {
                startCount++;
            }
        }
        if (startCount == 0) {
            throw new IllegalArgumentException("Workflow validation failed: at least one start node is required.");
        }

        Map<String, Integer> indegree = new HashMap<>();
        Map<String, List<String>> outgoing = new HashMap<>();
        for (String id : byId.keySet()) {
            indegree.put(id, 0);
            outgoing.put(id, new ArrayList<String>());
        }
        for (Map<String, Object> edge : edges) {
            String source = text(edge.get("source"));
            String target = text(edge.get("target"));
            if (!byId.containsKey(source)) {
                throw new IllegalArgumentException("Workflow validation failed: edge references unknown source " + source + ".");
            }
            if (!byId.containsKey(target)) {
                throw new IllegalArgumentException("Workflow validation failed: edge references unknown target " + target + ".");
            }
            outgoing.get(source).add(target);
            indegree.put(target, indegree.get(target) + 1);
        }

        boolean hasTerminal = false;
        for (String id : byId.keySet()) {
            if (outgoing.get(id).isEmpty() || "end".equals(nodeType(byId.get(id)))) {
                hasTerminal = true;
                break;
            }
        }
        if (!hasTerminal) {
            throw new IllegalArgumentException("Workflow validation failed: at least one terminal node is required.");
        }

        if (hasCycle(indegree, outgoing, byId.keySet())) {
            throw new IllegalArgumentException("Workflow validation failed: graph contains a cycle.");
        }
    }

    private boolean hasCycle(Map<String, Integer> indegree, Map<String, List<String>> outgoing, Set<String> nodeIds) {
        Queue<String> queue = new ArrayDeque<>();
        Map<String, Integer> remaining = new HashMap<>(indegree);
        for (String id : nodeIds) {
            if (remaining.get(id) == 0) {
                queue.add(id);
            }
        }
        Set<String> seen = new HashSet<>();
        while (!queue.isEmpty()) {
            String id = queue.remove();
            if (!seen.add(id)) {
                continue;
            }
            for (String target : outgoing.get(id)) {
                remaining.put(target, remaining.get(target) - 1);
                if (remaining.get(target) == 0) {
                    queue.add(target);
                }
            }
        }
        return seen.size() != nodeIds.size();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castList(Object value) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (!(value instanceof List)) {
            return result;
        }
        for (Object item : (List<Object>) value) {
            if (item instanceof Map) {
                result.add(new LinkedHashMap<>((Map<String, Object>) item));
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(Map<String, Object> node) {
        Object data = node.get("data");
        if (data instanceof Map) {
            return (Map<String, Object>) data;
        }
        return node;
    }

    private String nodeType(Map<String, Object> node) {
        String type = text(data(node).get("nodeType"));
        if (type.isEmpty()) {
            type = text(node.get("type"));
        }
        if ("workflowNode".equals(type)) {
            return "logic";
        }
        return type;
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
