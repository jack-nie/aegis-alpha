package com.aegis.alpha.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

@Service
public class WorkflowValidationService {
    /** Max characters for user-authored data.prompt / data.systemPrompt (待确认: default 8000). */
    @Value("${aegis.workflow.max-prompt-chars:8000}")
    private int maxPromptChars = 8000;

    /** Comma-separated allowed data.modelName values; empty = no whitelist (待确认). */
    @Value("${aegis.workflow.allowed-model-names:}")
    private String allowedModelNames = "";

    /** P4-8: node-level data.maxToolRounds bounds (orchestrator clamps to the same range; default 3). */
    static final int MIN_TOOL_ROUNDS = 1;
    static final int MAX_TOOL_ROUNDS = 8;

    private static boolean isToolRoundsInRange(Object value) {
        long rounds;
        if (value instanceof Integer || value instanceof Long || value instanceof Short) {
            rounds = ((Number) value).longValue();
        } else if (value instanceof String && ((String) value).trim().matches("\\d{1,3}")) {
            rounds = Long.parseLong(((String) value).trim());
        } else {
            return false;
        }
        return rounds >= MIN_TOOL_ROUNDS && rounds <= MAX_TOOL_ROUNDS;
    }

    void setMaxPromptChars(int maxPromptChars) {
        this.maxPromptChars = maxPromptChars;
    }

    void setAllowedModelNames(String allowedModelNames) {
        this.allowedModelNames = allowedModelNames == null ? "" : allowedModelNames;
    }

    /**
     * Executable-field checks for user-authored layouts (save/publish by a user).
     * Not applied to system seeding or run-time layout loads, so existing stored layouts keep running.
     */
    public void validateExecutableFields(Map<String, Object> layout) {
        Set<String> allowedModels = new HashSet<>();
        for (String name : Arrays.asList(allowedModelNames.split(","))) {
            if (!name.trim().isEmpty()) {
                allowedModels.add(name.trim());
            }
        }
        for (Map<String, Object> node : castList(layout == null ? null : layout.get("nodes"))) {
            Object rawData = node.get("data");
            if (!(rawData instanceof Map)) {
                continue;
            }
            Map<?, ?> data = (Map<?, ?>) rawData;
            String id = text(node.get("id"));
            for (String key : new String[] {"prompt", "systemPrompt"}) {
                Object value = data.get(key);
                if (value != null && !(value instanceof String)) {
                    throw new WorkflowValidationException("Workflow validation failed: node " + id + " " + key + " must be a string.");
                }
                if (value != null && ((String) value).length() > maxPromptChars) {
                    throw new WorkflowValidationException("Workflow validation failed: node " + id + " " + key
                            + " exceeds " + maxPromptChars + " characters.");
                }
            }
            Object rounds = data.get("maxToolRounds");
            if (rounds != null && !isToolRoundsInRange(rounds)) {
                throw new WorkflowValidationException("Workflow validation failed: node " + id
                        + " maxToolRounds must be an integer between " + MIN_TOOL_ROUNDS + " and " + MAX_TOOL_ROUNDS + ".");
            }
            Object model = data.get("modelName");
            if (model != null && !text(model).isEmpty() && !allowedModels.isEmpty() && !allowedModels.contains(text(model))) {
                throw new WorkflowValidationException("Workflow validation failed: node " + id + " modelName is not allowed.");
            }
        }
    }

    public void validateLayout(Map<String, Object> layout) {
        List<Map<String, Object>> nodes = castList(layout == null ? null : layout.get("nodes"));
        List<Map<String, Object>> edges = castList(layout == null ? null : layout.get("edges"));
        if (nodes.isEmpty()) {
            throw new WorkflowValidationException("Workflow validation failed: at least one start node is required.");
        }

        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        int startCount = 0;
        for (Map<String, Object> node : nodes) {
            String id = text(node.get("id"));
            if (id.isEmpty()) {
                throw new WorkflowValidationException("Workflow validation failed: node id cannot be empty.");
            }
            if (byId.containsKey(id)) {
                throw new WorkflowValidationException("Workflow validation failed: duplicate node id " + id + ".");
            }
            byId.put(id, node);
            if ("start".equals(nodeType(node))) {
                startCount++;
            }
        }
        if (startCount == 0) {
            throw new WorkflowValidationException("Workflow validation failed: at least one start node is required.");
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
                throw new WorkflowValidationException("Workflow validation failed: edge references unknown source " + source + ".");
            }
            if (!byId.containsKey(target)) {
                throw new WorkflowValidationException("Workflow validation failed: edge references unknown target " + target + ".");
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
            throw new WorkflowValidationException("Workflow validation failed: at least one terminal node is required.");
        }

        if (hasCycle(indegree, outgoing, byId.keySet())) {
            throw new WorkflowValidationException("Workflow validation failed: graph contains a cycle.");
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
