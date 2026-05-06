package com.marketmind.alpha.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketmind.alpha.domain.AgentTemplate;
import com.marketmind.alpha.domain.WorkflowDefinition;
import com.marketmind.alpha.domain.WorkflowLayout;
import com.marketmind.alpha.domain.WorkflowNodeRun;
import com.marketmind.alpha.domain.WorkflowRun;
import com.marketmind.alpha.domain.WorkflowRunEvent;
import com.marketmind.alpha.domain.WorkflowVersion;
import com.marketmind.alpha.mapper.AgentMapper;
import com.marketmind.alpha.mapper.WorkflowMapper;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;

@Service
public class WorkflowService {
    private final WorkflowMapper mapper;
    private final AgentMapper agentMapper;
    private final ObjectMapper objectMapper;
    private final LangChainGateway langChainGateway;
    private final CacheService cacheService;
    private final BacktestService backtestService;
    private final AgentTraceService agentTraceService;
    private final WorkflowValidationService validationService;

    public WorkflowService(WorkflowMapper mapper,
                           AgentMapper agentMapper,
                           ObjectMapper objectMapper,
                            LangChainGateway langChainGateway,
                            CacheService cacheService,
                            BacktestService backtestService,
                            AgentTraceService agentTraceService,
                            WorkflowValidationService validationService) {
        this.mapper = mapper;
        this.agentMapper = agentMapper;
        this.objectMapper = objectMapper;
        this.langChainGateway = langChainGateway;
        this.cacheService = cacheService;
        this.backtestService = backtestService;
        this.agentTraceService = agentTraceService;
        this.validationService = validationService;
    }

    public List<WorkflowDefinition> definitions() {
        return mapper.findDefinitions();
    }

    public WorkflowDefinition createDefinition(String username, Map<String, Object> body) {
        WorkflowDefinition definition = new WorkflowDefinition();
        definition.setWorkflowKey(safeKey(text(body, "workflowKey", text(body, "name", "custom-workflow"))));
        if (mapper.findDefinition(definition.getWorkflowKey()) != null) {
            definition.setWorkflowKey(definition.getWorkflowKey() + "-" + UUID.randomUUID().toString().substring(0, 8));
        }
        definition.setName(text(body, "name", "Custom Workflow"));
        definition.setDescription(text(body, "description", "User-defined agent orchestration workflow."));
        definition.setEngine(text(body, "engine", "langgraph"));
        definition.setVersion(number(body, "version", 1));
        definition.setNodes(0);
        definition.setEdges(0);
        definition.setReadonlyFlag(false);
        definition.setOwnerUsername(username);
        definition.setUpdatedAt(now());
        mapper.insertDefinition(definition);
        saveLayout(definition.getWorkflowKey(), defaultLayout(definition));
        return mapper.findDefinition(definition.getWorkflowKey());
    }

    public WorkflowDefinition updateDefinition(String username, String workflowKey, Map<String, Object> body) {
        WorkflowDefinition definition = requireDefinition(workflowKey);
        if (definition.isReadonlyFlag()) {
            throw new IllegalArgumentException("Readonly system workflows cannot be edited.");
        }
        if (definition.getOwnerUsername() != null && !definition.getOwnerUsername().equals(username)) {
            throw new IllegalArgumentException("Workflow belongs to another user.");
        }
        definition.setName(text(body, "name", definition.getName()));
        definition.setDescription(text(body, "description", definition.getDescription()));
        definition.setEngine(text(body, "engine", definition.getEngine()));
        definition.setVersion(number(body, "version", definition.getVersion() == null ? 1 : definition.getVersion()));
        definition.setUpdatedAt(now());
        mapper.updateDefinition(definition);
        return mapper.findDefinition(workflowKey);
    }

    public void deleteDefinition(String username, String workflowKey) {
        WorkflowDefinition definition = requireDefinition(workflowKey);
        if (definition.isReadonlyFlag()) {
            throw new IllegalArgumentException("Readonly system workflows cannot be deleted.");
        }
        if (definition.getOwnerUsername() != null && !definition.getOwnerUsername().equals(username)) {
            throw new IllegalArgumentException("Workflow belongs to another user.");
        }
        mapper.deleteLayout(workflowKey);
        mapper.deleteEditableDefinition(workflowKey);
    }

    public List<WorkflowRun> runs() {
        List<WorkflowRun> runs = mapper.findRuns();
        for (WorkflowRun run : runs) {
            hydrateRunContract(run);
        }
        return runs;
    }

    public WorkflowRun run(String runId) {
        WorkflowRun run = mapper.findRun(runId);
        hydrateRunContract(run);
        return run;
    }

    public List<WorkflowNodeRun> nodeRuns(String runId) {
        return mapper.findNodeRuns(runId);
    }

    public List<WorkflowRunEvent> runEvents(String runId) {
        return mapper.findRunEvents(runId);
    }

    private void hydrateRunContract(WorkflowRun run) {
        if (run == null) {
            return;
        }
        run.setAvailableActions(availableActions(run.getStatus()));
        run.setActionReasons(actionReasons(run.getStatus()));
        run.setPriority(priority(run));
        run.setLastEvent(lastEvent(run.getRunId()));
    }

    private List<String> availableActions(String status) {
        List<String> actions = new ArrayList<String>();
        if ("QUEUED".equals(status)) {
            actions.add("dispatch");
            actions.add("cancel");
        } else if ("RUNNING".equals(status)) {
            actions.add("pause");
            actions.add("cancel");
        } else if ("PAUSED".equals(status)) {
            actions.add("resume");
            actions.add("cancel");
        }
        return actions;
    }

    private Map<String, String> actionReasons(String status) {
        Map<String, String> reasons = new LinkedHashMap<String, String>();
        reasons.put("dispatch", "QUEUED".equals(status) ? "" : "Only queued runs can be dispatched.");
        reasons.put("pause", "QUEUED".equals(status) || "RUNNING".equals(status) ? "" : "Only queued or running runs can be paused.");
        reasons.put("resume", "PAUSED".equals(status) ? "" : "Only paused runs can be resumed.");
        reasons.put("cancel", "QUEUED".equals(status) || "RUNNING".equals(status) || "PAUSED".equals(status) ? "" : "Completed, failed, and cancelled runs cannot be cancelled.");
        return reasons;
    }

    private String priority(WorkflowRun run) {
        if ("FAILED".equals(run.getStatus())) {
            return "high";
        }
        if ("QUEUED".equals(run.getStatus()) || "RUNNING".equals(run.getStatus())) {
            return "normal";
        }
        return "low";
    }

    private Map<String, Object> lastEvent(String runId) {
        List<WorkflowRunEvent> events = mapper.findRunEvents(runId);
        if (events.isEmpty()) {
            return null;
        }
        WorkflowRunEvent event = events.get(events.size() - 1);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("eventId", event.getEventId());
        result.put("eventType", event.getEventType());
        result.put("status", event.getStatus());
        result.put("message", event.getMessage());
        result.put("createdAt", event.getCreatedAt());
        return result;
    }

    public WorkflowVersion publishVersion(String workflowKey, String username) {
        WorkflowDefinition definition = requireDefinition(workflowKey);
        Map<String, Object> currentLayout = layout(workflowKey);
        validationService.validateLayout(currentLayout);
        WorkflowVersion version = new WorkflowVersion();
        version.setVersionId(UUID.randomUUID().toString());
        version.setWorkflowKey(workflowKey);
        version.setVersion(mapper.maxVersion(workflowKey) + 1);
        version.setLayoutJson(toJson(currentLayout));
        Map<String, Object> validation = new LinkedHashMap<>();
        validation.put("ok", true);
        validation.put("workflowKey", workflowKey);
        validation.put("nodes", countList(currentLayout.get("nodes")));
        validation.put("edges", countList(currentLayout.get("edges")));
        version.setValidationJson(toJson(validation));
        version.setPublishedBy(username);
        version.setPublishedAt(now());
        mapper.insertVersion(version);
        definition.setVersion(version.getVersion());
        definition.setNodes(countList(currentLayout.get("nodes")));
        definition.setEdges(countList(currentLayout.get("edges")));
        definition.setUpdatedAt(version.getPublishedAt());
        mapper.updateDefinition(definition);
        return version;
    }

    public Map<String, Object> layout(String workflowKey) {
        WorkflowLayout layout = mapper.findLayout(workflowKey);
        if (layout == null || layout.getLayoutJson() == null) {
            WorkflowDefinition definition = mapper.findDefinition(workflowKey);
            Map<String, Object> fallback = definition == null ? emptyLayout(workflowKey) : defaultLayout(definition);
            fallback.put("updatedAt", null);
            return fallback;
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(layout.getLayoutJson(), new TypeReference<Map<String, Object>>() {});
            parsed.put("workflowKey", workflowKey);
            parsed.put("updatedAt", layout.getUpdatedAt());
            return parsed;
        } catch (Exception ex) {
            throw new IllegalStateException("Invalid workflow layout JSON for " + workflowKey, ex);
        }
    }

    public Map<String, Object> saveLayout(String workflowKey, Map<String, Object> body) {
        if (mapper.findDefinition(workflowKey) == null) {
            WorkflowDefinition definition = new WorkflowDefinition();
            definition.setWorkflowKey(workflowKey);
            definition.setName(text(body, "name", workflowKey));
            definition.setDescription("Workflow created from layout save.");
            definition.setEngine(text(body, "engine", "langgraph"));
            definition.setVersion(number(body, "version", 1));
            definition.setNodes(0);
            definition.setEdges(0);
            definition.setReadonlyFlag(false);
            definition.setUpdatedAt(now());
            mapper.insertDefinition(definition);
        }
        Map<String, Object> layout = new LinkedHashMap<>(body);
        layout.put("workflowKey", workflowKey);
        if (!layout.containsKey("engine")) {
            layout.put("engine", "langgraph");
        }
        validationService.validateLayout(layout);
        int nodeCount = countList(layout.get("nodes"));
        int edgeCount = countList(layout.get("edges"));
        String timestamp = now();
        try {
            WorkflowLayout record = new WorkflowLayout();
            record.setWorkflowKey(workflowKey);
            record.setLayoutJson(objectMapper.writeValueAsString(layout));
            record.setUpdatedAt(timestamp);
            if (mapper.findLayout(workflowKey) == null) {
                mapper.insertLayout(record);
            } else {
                mapper.updateLayout(record);
            }
            mapper.updateDefinitionCountsWithTime(workflowKey, nodeCount, edgeCount, timestamp);
            cacheService.evict("marketmind:workflow:layout:" + workflowKey);
            layout.put("updatedAt", timestamp);
            return layout;
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to save workflow layout for " + workflowKey, ex);
        }
    }

    public WorkflowRun start(String workflowKey, String subject) {
        return start(workflowKey, subject, new LinkedHashMap<String, Object>(), null);
    }

    public WorkflowRun start(String workflowKey, String subject, Map<String, Object> inputs) {
        return start(workflowKey, subject, inputs, null);
    }

    public WorkflowRun start(String workflowKey, String subject, Map<String, Object> inputs, String idempotencyKey) {
        String key = workflowKey == null || workflowKey.trim().isEmpty() ? "daily" : workflowKey.trim();
        String normalizedIdempotencyKey = normalizeIdempotencyKey(idempotencyKey);
        WorkflowRun existing = findIdempotentRun(key, subject, normalizedIdempotencyKey);
        if (existing != null) {
            return existing;
        }
        WorkflowVersion version = mapper.findLatestVersion(key);
        Map<String, Object> runLayout = layoutForRun(key, version);
        validationService.validateLayout(runLayout);
        Map<String, Object> safeInputs = inputs == null ? new LinkedHashMap<String, Object>() : new LinkedHashMap<>(inputs);
        WorkflowRun run = new WorkflowRun();
        run.setRunId(UUID.randomUUID().toString());
        run.setWorkflowKey(key);
        run.setTraceId(UUID.randomUUID().toString());
        run.setStatus("RUNNING");
        run.setSubject(subject);
        run.setStartedAt(now());
        run.setNodeCount(0);
        run.setIdempotencyKey(normalizedIdempotencyKey);
        run.setWorkflowVersionId(version == null ? null : version.getVersionId());
        run.setInputsJson(toJson(safeInputs));
        run.setControlStatus("ACTIVE");
        run.setPauseRequested(0);
        run.setCancelRequested(0);
        mapper.insertRun(run);
        recordRunEvent(run, "RUN_CREATED", null, null, "RUNNING", "Workflow run created.", null, 0);

        try {
            execute(run, safeInputs, runLayout);
            WorkflowRun completed = mapper.findRun(run.getRunId());
            backtestService.createFromWorkflowRun(completed, inputs);
            return completed;
        } catch (WorkflowStoppedException stopped) {
            return mapper.findRun(run.getRunId());
        } catch (Exception ex) {
            run.setStatus("FAILED");
            run.setCompletedAt(now());
            run.setErrorMessage(ex.getMessage());
            run.setControlStatus("FAILED");
            mapper.updateRun(run);
            recordRunEvent(run, "RUN_FAILED", null, null, "FAILED", ex.getMessage(), null, 1000000);
            WorkflowRun failed = mapper.findRun(run.getRunId());
            backtestService.createFromWorkflowRun(failed, inputs);
            return failed;
        }
    }

    public WorkflowRun queueStart(String workflowKey, String subject, Map<String, Object> inputs, String idempotencyKey) {
        String key = workflowKey == null || workflowKey.trim().isEmpty() ? "daily" : workflowKey.trim();
        String normalizedIdempotencyKey = normalizeIdempotencyKey(idempotencyKey);
        WorkflowRun existing = findIdempotentRun(key, subject, normalizedIdempotencyKey);
        if (existing != null) {
            return existing;
        }
        WorkflowVersion version = mapper.findLatestVersion(key);
        Map<String, Object> runLayout = layoutForRun(key, version);
        validationService.validateLayout(runLayout);
        Map<String, Object> safeInputs = inputs == null ? new LinkedHashMap<String, Object>() : new LinkedHashMap<>(inputs);
        String timestamp = now();
        WorkflowRun run = new WorkflowRun();
        run.setRunId(UUID.randomUUID().toString());
        run.setWorkflowKey(key);
        run.setTraceId(UUID.randomUUID().toString());
        run.setStatus("QUEUED");
        run.setSubject(subject);
        run.setStartedAt(timestamp);
        run.setQueuedAt(timestamp);
        run.setNodeCount(0);
        run.setIdempotencyKey(normalizedIdempotencyKey);
        run.setWorkflowVersionId(version == null ? null : version.getVersionId());
        run.setInputsJson(toJson(safeInputs));
        run.setControlStatus("QUEUED");
        run.setPauseRequested(0);
        run.setCancelRequested(0);
        mapper.insertRun(run);
        recordRunEvent(run, "RUN_CREATED", null, null, "QUEUED", "Workflow run queued.", safeInputs, 0);
        return mapper.findRun(run.getRunId());
    }

    public WorkflowRun dispatchQueuedRun(String runId) {
        WorkflowRun run = mapper.findRun(runId);
        if (run == null) {
            throw new IllegalArgumentException("Workflow run not found: " + runId);
        }
        if (!"QUEUED".equals(run.getStatus())) {
            return run;
        }
        run.setStatus("RUNNING");
        run.setControlStatus("ACTIVE");
        run.setPauseRequested(0);
        run.setCancelRequested(0);
        run.setStartedAt(now());
        run.setCompletedAt(null);
        mapper.updateRun(run);
        recordRunEvent(run, "RUN_DISPATCHED", null, null, "RUNNING", "Workflow run dispatched.", null, 1);
        Map<String, Object> inputs = parseJsonMap(run.getInputsJson());
        Map<String, Object> runLayout = layoutForRun(run.getWorkflowKey(), run.getWorkflowVersionId());
        try {
            execute(run, inputs, runLayout);
            WorkflowRun completed = mapper.findRun(run.getRunId());
            backtestService.createFromWorkflowRun(completed, inputs);
            return completed;
        } catch (WorkflowStoppedException stopped) {
            return mapper.findRun(run.getRunId());
        } catch (Exception ex) {
            run.setStatus("FAILED");
            run.setCompletedAt(now());
            run.setErrorMessage(ex.getMessage());
            run.setControlStatus("FAILED");
            mapper.updateRun(run);
            recordRunEvent(run, "RUN_FAILED", null, null, "FAILED", ex.getMessage(), null, 1000000);
            WorkflowRun failed = mapper.findRun(run.getRunId());
            backtestService.createFromWorkflowRun(failed, inputs);
            return failed;
        }
    }

    public WorkflowRun pauseRun(String runId) {
        WorkflowRun run = requireRun(runId);
        if ("QUEUED".equals(run.getStatus())) {
            run.setStatus("PAUSED");
            run.setControlStatus("PAUSED");
            run.setPauseRequested(1);
            mapper.updateRun(run);
            recordRunEvent(run, "RUN_PAUSED", null, null, "PAUSED", "Workflow run paused before dispatch.", null, 2);
        } else if ("RUNNING".equals(run.getStatus())) {
            run.setPauseRequested(1);
            run.setControlStatus("PAUSE_REQUESTED");
            mapper.updateRun(run);
            recordRunEvent(run, "RUN_PAUSE_REQUESTED", null, null, "RUNNING", "Workflow run pause requested.", null, 2);
        }
        return mapper.findRun(runId);
    }

    public WorkflowRun resumeRun(String runId) {
        WorkflowRun run = requireRun(runId);
        if ("PAUSED".equals(run.getStatus())) {
            run.setStatus("QUEUED");
            run.setControlStatus("QUEUED");
            run.setPauseRequested(0);
            run.setCompletedAt(null);
            mapper.updateRun(run);
            recordRunEvent(run, "RUN_RESUMED", null, null, "QUEUED", "Workflow run resumed into queue.", null, 3);
        }
        return mapper.findRun(runId);
    }

    public WorkflowRun cancelRun(String runId) {
        WorkflowRun run = requireRun(runId);
        if ("QUEUED".equals(run.getStatus()) || "PAUSED".equals(run.getStatus())) {
            run.setStatus("CANCELLED");
            run.setControlStatus("CANCELLED");
            run.setCancelRequested(1);
            run.setCompletedAt(now());
            mapper.updateRun(run);
            recordRunEvent(run, "RUN_CANCELLED", null, null, "CANCELLED", "Workflow run cancelled before dispatch.", null, 4);
        } else if ("RUNNING".equals(run.getStatus())) {
            run.setCancelRequested(1);
            run.setControlStatus("CANCEL_REQUESTED");
            mapper.updateRun(run);
            recordRunEvent(run, "RUN_CANCEL_REQUESTED", null, null, "RUNNING", "Workflow run cancellation requested.", null, 4);
        }
        return mapper.findRun(runId);
    }

    public WorkflowRun findIdempotentRun(String workflowKey, String subject, String idempotencyKey) {
        String key = normalizeIdempotencyKey(idempotencyKey);
        if (key == null) {
            return null;
        }
        String workflow = workflowKey == null || workflowKey.trim().isEmpty() ? "daily" : workflowKey.trim();
        return mapper.findRunByIdempotencyKey(workflow, subject, key);
    }

    @SuppressWarnings("unchecked")
    private void execute(WorkflowRun run, Map<String, Object> inputs, Map<String, Object> layout) throws Exception {
        List<Map<String, Object>> nodes = castList(layout.get("nodes"));
        List<Map<String, Object>> edges = castList(layout.get("edges"));
        List<Map<String, Object>> orderedNodes = executionOrder(nodes, edges);
        Map<String, Object> state = new LinkedHashMap<>(inputs);
        state.put("workflowKey", run.getWorkflowKey());
        state.put("runId", run.getRunId());
        state.put("subject", run.getSubject());

        int index = 0;
        for (Map<String, Object> node : orderedNodes) {
            enforceRunControl(run);
            index += 1;
            Map<String, Object> retryPolicy = retryPolicy(node);
            int maxAttempts = Math.max(1, Math.min(number(retryPolicy, "maxAttempts", 1), 5));
            int backoffMs = Math.max(0, Math.min(number(retryPolicy, "backoffMs", 0), 5000));
            int timeoutMs = Math.max(0, number(retryPolicy, "timeoutMs", number(data(node), "timeoutMs", 0)));
            Exception lastFailure = null;

            for (int attempt = 1; attempt <= maxAttempts; attempt += 1) {
                enforceRunControl(run);
                WorkflowNodeRun nodeRun = new WorkflowNodeRun();
                nodeRun.setNodeRunId(UUID.randomUUID().toString());
                nodeRun.setRunId(run.getRunId());
                nodeRun.setNodeId(nodeId(node));
                nodeRun.setNodeName(nodeName(node));
                nodeRun.setNodeType(nodeType(node));
                nodeRun.setAgentId(agentId(node));
                nodeRun.setStatus("RUNNING");
                nodeRun.setAttempt(attempt);
                nodeRun.setMaxAttempts(maxAttempts);
                nodeRun.setRetryPolicyJson(retryPolicy.isEmpty() ? null : toJson(retryPolicy));
                nodeRun.setTimeoutMs(timeoutMs == 0 ? null : timeoutMs);
                Map<String, Object> inputSnapshot = new LinkedHashMap<>(state);
                nodeRun.setInputJson(toJson(inputSnapshot));
                nodeRun.setStartedAt(now());
                nodeRun.setSortOrder(index * 100 + attempt);
                mapper.insertNodeRun(nodeRun);
                recordRunEvent(run, "NODE_STARTED", nodeRun, nodeRun.getNodeId(), "RUNNING", "Node started.", null, index * 100 + attempt * 10);

                try {
                    long started = System.currentTimeMillis();
                    Map<String, Object> output = executeNode(node, state, run);
                    long elapsed = System.currentTimeMillis() - started;
                    if (timeoutMs > 0 && elapsed > timeoutMs) {
                        throw new IllegalStateException("Node timed out after " + elapsed + "ms; timeout=" + timeoutMs + "ms");
                    }
                    state.put(nodeRun.getNodeId(), output);
                    mergeOutputKeys(node, output, state);
                    nodeRun.setStatus("COMPLETED");
                    nodeRun.setOutputJson(toJson(output));
                    nodeRun.setCompletedAt(now());
                    mapper.updateNodeRun(nodeRun);
                    agentTraceService.recordNodeSpan(run, nodeRun, handler(node), inputSnapshot, output);
                    recordRunEvent(run, "NODE_COMPLETED", nodeRun, nodeRun.getNodeId(), "COMPLETED", "Node completed.", output, index * 100 + attempt * 10 + 1);
                    lastFailure = null;
                    break;
                } catch (WorkflowStoppedException stopped) {
                    throw stopped;
                } catch (Exception ex) {
                    lastFailure = ex;
                    Map<String, Object> output = new LinkedHashMap<>();
                    output.put("ok", false);
                    output.put("error", ex.getMessage());
                    output.put("attempt", attempt);
                    output.put("maxAttempts", maxAttempts);
                    nodeRun.setStatus("FAILED");
                    nodeRun.setErrorMessage(ex.getMessage());
                    nodeRun.setOutputJson(toJson(output));
                    nodeRun.setCompletedAt(now());
                    mapper.updateNodeRun(nodeRun);
                    agentTraceService.recordNodeSpan(run, nodeRun, handler(node), inputSnapshot, output);
                    recordRunEvent(run, "NODE_FAILED", nodeRun, nodeRun.getNodeId(), "FAILED", ex.getMessage(), output, index * 100 + attempt * 10 + 1);
                    if (attempt < maxAttempts) {
                        recordRunEvent(run, "NODE_RETRYING", nodeRun, nodeRun.getNodeId(), "RETRYING", "Retrying node after failure.", output, index * 100 + attempt * 10 + 2);
                        sleepBackoff(backoffMs);
                    }
                }
            }
            if (lastFailure != null) {
                throw lastFailure;
            }
        }

        run.setStatus("COMPLETED");
        run.setCompletedAt(now());
        run.setResultJson(toJson(state));
        run.setNodeCount(orderedNodes.size());
        run.setControlStatus("COMPLETED");
        run.setPauseRequested(0);
        run.setCancelRequested(0);
        mapper.updateRun(run);
        recordRunEvent(run, "RUN_COMPLETED", null, null, "COMPLETED", "Workflow run completed.", state, 1000000);
        cacheService.put("marketmind:workflow:run:" + run.getRunId(), run, Duration.ofHours(2));
    }

    private void enforceRunControl(WorkflowRun run) {
        WorkflowRun latest = mapper.findRun(run.getRunId());
        if (latest == null) {
            throw new WorkflowStoppedException("CANCELLED");
        }
        if (Boolean.TRUE.equals(flag(latest.getCancelRequested())) || "CANCELLED".equals(latest.getStatus())) {
            latest.setStatus("CANCELLED");
            latest.setControlStatus("CANCELLED");
            latest.setCancelRequested(1);
            latest.setCompletedAt(now());
            mapper.updateRun(latest);
            recordRunEvent(latest, "RUN_CANCELLED", null, null, "CANCELLED", "Workflow run cancelled at node boundary.", null, 999998);
            throw new WorkflowStoppedException("CANCELLED");
        }
        if (Boolean.TRUE.equals(flag(latest.getPauseRequested())) || "PAUSED".equals(latest.getStatus())) {
            latest.setStatus("PAUSED");
            latest.setControlStatus("PAUSED");
            latest.setPauseRequested(1);
            mapper.updateRun(latest);
            recordRunEvent(latest, "RUN_PAUSED", null, null, "PAUSED", "Workflow run paused at node boundary.", null, 999997);
            throw new WorkflowStoppedException("PAUSED");
        }
    }

    private Boolean flag(Integer value) {
        return value != null && value != 0;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> retryPolicy(Map<String, Object> node) {
        Object policy = data(node).get("retryPolicy");
        if (!(policy instanceof Map)) {
            policy = data(node).get("retry");
        }
        if (policy instanceof Map) {
            return new LinkedHashMap<>((Map<String, Object>) policy);
        }
        return new LinkedHashMap<>();
    }

    private void sleepBackoff(int backoffMs) {
        if (backoffMs <= 0) {
            return;
        }
        try {
            Thread.sleep(backoffMs);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new WorkflowStoppedException("CANCELLED");
        }
    }

    private Map<String, Object> layoutForRun(String workflowKey, WorkflowVersion version) {
        if (version == null || version.getLayoutJson() == null) {
            return layout(workflowKey);
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(version.getLayoutJson(), new TypeReference<Map<String, Object>>() {});
            parsed.put("workflowKey", workflowKey);
            parsed.put("workflowVersionId", version.getVersionId());
            return parsed;
        } catch (Exception ex) {
            throw new IllegalStateException("Invalid workflow version JSON for " + workflowKey, ex);
        }
    }

    private Map<String, Object> layoutForRun(String workflowKey, String workflowVersionId) {
        if (workflowVersionId == null || workflowVersionId.trim().isEmpty()) {
            return layout(workflowKey);
        }
        WorkflowVersion version = mapper.findVersion(workflowVersionId);
        return layoutForRun(workflowKey, version);
    }

    private Map<String, Object> parseJsonMap(String json) {
        if (json == null || json.trim().isEmpty()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception ex) {
            return new LinkedHashMap<>();
        }
    }

    private WorkflowRun requireRun(String runId) {
        WorkflowRun run = mapper.findRun(runId);
        if (run == null) {
            throw new IllegalArgumentException("Workflow run not found: " + runId);
        }
        return run;
    }

    private Map<String, Object> executeNode(Map<String, Object> node, Map<String, Object> state, WorkflowRun run) {
        String type = nodeType(node);
        String handler = handler(node);
        if ("agent".equals(type) || isExternalResearchHandler(handler)) {
            AgentTemplate agent = resolveAgent(node);
            return langChainGateway.executeNode(agent, state, node, run.getSubject());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", type);
        result.put("handler", handler);
        result.put("summary", simulatedNodeMessage(type, handler, run.getSubject()));
        result.put("message", result.get("summary"));
        result.put("ok", true);
        return result;
    }

    private boolean isExternalResearchHandler(String handler) {
        return handler != null && (handler.startsWith("finance.")
                || "general.web_search".equals(handler)
                || "general.fetch_news".equals(handler)
                || "general.get_market_share".equals(handler)
                || "general.get_sector_news".equals(handler)
                || "general.get_tech_breakthroughs".equals(handler)
                || "general.stock_screener_agent".equals(handler));
    }

    private AgentTemplate resolveAgent(Map<String, Object> node) {
        String agentId = agentId(node);
        AgentTemplate agent = agentId == null ? null : agentMapper.findById(agentId);
        if (agent != null) {
            return agent;
        }
        AgentTemplate inline = new AgentTemplate();
        inline.setAgentId(agentId == null ? "inline-" + nodeId(node) : agentId);
        inline.setName(nodeName(node));
        inline.setDescription("Inline workflow agent");
        inline.setCategory("inline");
        inline.setTags("inline,workflow");
        inline.setPrompt(text(data(node), "prompt", text(data(node), "systemPrompt", "Execute this workflow agent node.")));
        inline.setModelName(text(data(node), "modelName", "deepseek-v4-flash"));
        inline.setToolsJson(text(data(node), "toolsJson", "[]"));
        return inline;
    }

    private String simulatedNodeMessage(String type, String handler, String subject) {
        if ("start".equals(type)) {
            return "Workflow started for " + subject;
        }
        if ("condition".equals(type)) {
            return "Condition evaluated as pass.";
        }
        if ("end".equals(type)) {
            return "Workflow completed.";
        }
        if (handler != null && handler.contains("portfolio")) {
            return "Loaded portfolio context for " + subject;
        }
        if (handler != null && handler.contains("news")) {
            return "Fetched recent news context.";
        }
        if (handler != null && handler.contains("notification")) {
            return "Notification event prepared.";
        }
        return "Executed " + (handler == null ? type : handler) + ".";
    }

    @SuppressWarnings("unchecked")
    private void mergeOutputKeys(Map<String, Object> node, Map<String, Object> output, Map<String, Object> state) {
        Object raw = data(node).get("outputKeys");
        if (!(raw instanceof List)) {
            raw = data(node).get("output_keys");
        }
        if (raw instanceof List) {
            for (Object key : (List<Object>) raw) {
                if (key != null) {
                    state.put(String.valueOf(key), output);
                }
            }
        }
    }

    private List<Map<String, Object>> executionOrder(List<Map<String, Object>> nodes, List<Map<String, Object>> edges) {
        if (nodes.isEmpty() || edges.isEmpty()) {
            return nodes;
        }
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        Map<String, Integer> indegree = new HashMap<>();
        Map<String, List<String>> outgoing = new HashMap<>();
        for (Map<String, Object> node : nodes) {
            String id = nodeId(node);
            byId.put(id, node);
            indegree.put(id, 0);
            outgoing.put(id, new ArrayList<String>());
        }
        for (Map<String, Object> edge : edges) {
            String source = string(edge.get("source"));
            String target = string(edge.get("target"));
            if (byId.containsKey(source) && byId.containsKey(target)) {
                outgoing.get(source).add(target);
                indegree.put(target, indegree.get(target) + 1);
            }
        }
        Queue<String> queue = new ArrayDeque<>();
        for (String id : indegree.keySet()) {
            if (indegree.get(id) == 0) {
                queue.add(id);
            }
        }
        List<Map<String, Object>> ordered = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        while (!queue.isEmpty()) {
            String id = queue.remove();
            if (!seen.add(id)) {
                continue;
            }
            ordered.add(byId.get(id));
            for (String target : outgoing.get(id)) {
                indegree.put(target, indegree.get(target) - 1);
                if (indegree.get(target) == 0) {
                    queue.add(target);
                }
            }
        }
        if (ordered.size() != nodes.size()) {
            return nodes;
        }
        return ordered;
    }

    private Map<String, Object> defaultLayout(WorkflowDefinition definition) {
        Map<String, Object> layout = emptyLayout(definition.getWorkflowKey());
        layout.put("name", definition.getName());
        layout.put("engine", definition.getEngine() == null ? "langgraph" : definition.getEngine());
        if ("stock_recommendation_research".equals(definition.getWorkflowKey())) {
            layout.put("nodes", stockRecommendationNodes());
            layout.put("edges", stockRecommendationEdges());
            return layout;
        }
        List<Map<String, Object>> nodes = new ArrayList<>();
        nodes.add(flowNode("start", "Start", "start", 80, 260, "scheduler.manual", null));
        nodes.add(flowNode("context", "Load Context", "logic", 320, 260, "portfolio.get_context", null));
        nodes.add(flowNode("analyst", "Research Agent", "agent", 590, 260, "general.agent", "agent-preset-value-investing"));
        nodes.add(flowNode("end", "End", "end", 860, 260, "workflow.end", null));
        List<Map<String, Object>> edges = new ArrayList<>();
        edges.add(edge("start", "context"));
        edges.add(edge("context", "analyst"));
        edges.add(edge("analyst", "end"));
        layout.put("nodes", nodes);
        layout.put("edges", edges);
        return layout;
    }

    private List<Map<String, Object>> stockRecommendationNodes() {
        List<Map<String, Object>> nodes = new ArrayList<>();
        nodes.add(flowNode("start", "Start", "start", 80, 260, "scheduler.manual", null));
        nodes.add(flowNode("market_analysis", "市场分析", "logic", 320, 120, "finance.market_analysis", null));
        nodes.add(flowNode("industry_share", "行业份额", "logic", 320, 260, "finance.industry_share", null));
        nodes.add(flowNode("sentiment_monitor", "舆情监测", "logic", 320, 400, "finance.sentiment_monitor", null));
        nodes.add(flowNode("tech_breakthrough", "技术突破", "logic", 590, 120, "finance.tech_breakthrough", null));
        nodes.add(flowNode("industry_news", "行业新闻", "logic", 590, 260, "finance.industry_news", null));
        nodes.add(flowNode("web_search", "网页搜索", "logic", 590, 400, "general.web_search", null));
        nodes.add(flowNode("financial_interpretation", "财务解读", "logic", 860, 260, "finance.financial_interpretation", null));
        nodes.add(flowNode("recommendation", "股票推荐聚合", "agent", 1130, 260, "finance.stock_recommendation_aggregate", null));
        nodes.add(flowNode("end", "End", "end", 1400, 260, "workflow.end", null));
        return nodes;
    }

    private List<Map<String, Object>> stockRecommendationEdges() {
        List<Map<String, Object>> edges = new ArrayList<>();
        edges.add(edge("start", "market_analysis"));
        edges.add(edge("market_analysis", "industry_share"));
        edges.add(edge("industry_share", "sentiment_monitor"));
        edges.add(edge("sentiment_monitor", "tech_breakthrough"));
        edges.add(edge("tech_breakthrough", "industry_news"));
        edges.add(edge("industry_news", "web_search"));
        edges.add(edge("web_search", "financial_interpretation"));
        edges.add(edge("financial_interpretation", "recommendation"));
        edges.add(edge("recommendation", "end"));
        return edges;
    }

    private Map<String, Object> emptyLayout(String workflowKey) {
        Map<String, Object> empty = new LinkedHashMap<>();
        empty.put("workflowKey", workflowKey);
        empty.put("engine", "langgraph");
        empty.put("nodes", Collections.emptyList());
        empty.put("edges", Collections.emptyList());
        return empty;
    }

    private Map<String, Object> flowNode(String id, String label, String nodeType, int x, int y, String handler, String agentId) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", id);
        node.put("type", "workflowNode");
        Map<String, Object> position = new LinkedHashMap<>();
        position.put("x", x);
        position.put("y", y);
        node.put("position", position);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("label", label);
        data.put("nodeType", nodeType);
        data.put("handler", handler);
        data.put("agentId", agentId);
        data.put("prompt", "");
        data.put("inputKeys", Collections.emptyList());
        data.put("outputKeys", Collections.emptyList());
        node.put("data", data);
        return node;
    }

    private Map<String, Object> edge(String source, String target) {
        Map<String, Object> edge = new LinkedHashMap<>();
        edge.put("id", source + "-" + target);
        edge.put("source", source);
        edge.put("target", target);
        edge.put("animated", true);
        return edge;
    }

    private WorkflowDefinition requireDefinition(String workflowKey) {
        WorkflowDefinition definition = mapper.findDefinition(workflowKey);
        if (definition == null) {
            throw new IllegalArgumentException("Workflow not found: " + workflowKey);
        }
        return definition;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castList(Object value) {
        if (!(value instanceof List)) {
            return new ArrayList<>();
        }
        List<Map<String, Object>> result = new ArrayList<>();
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

    private String nodeId(Map<String, Object> node) {
        return text(node, "id", UUID.randomUUID().toString());
    }

    private String nodeName(Map<String, Object> node) {
        Map<String, Object> data = data(node);
        return text(data, "label", text(data, "title", nodeId(node)));
    }

    private String nodeType(Map<String, Object> node) {
        Map<String, Object> data = data(node);
        String type = text(data, "nodeType", text(node, "type", "logic"));
        if ("workflowNode".equals(type)) {
            return "logic";
        }
        return type;
    }

    private String handler(Map<String, Object> node) {
        Map<String, Object> data = data(node);
        return text(data, "handler", text(data, "functionName", nodeType(node)));
    }

    private String agentId(Map<String, Object> node) {
        Map<String, Object> data = data(node);
        String agentId = text(data, "agentId", "");
        if (agentId.isEmpty()) {
            agentId = text(data, "agent_id", "");
        }
        return agentId.isEmpty() ? null : agentId;
    }

    private int countList(Object value) {
        return value instanceof List ? ((List<?>) value).size() : 0;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private void recordRunEvent(WorkflowRun run,
                                String eventType,
                                WorkflowNodeRun nodeRun,
                                String nodeId,
                                String status,
                                String message,
                                Object payload,
                                int sortOrder) {
        WorkflowRunEvent event = new WorkflowRunEvent();
        event.setEventId(UUID.randomUUID().toString());
        event.setRunId(run.getRunId());
        event.setEventType(eventType);
        event.setNodeRunId(nodeRun == null ? null : nodeRun.getNodeRunId());
        event.setNodeId(nodeId);
        event.setStatus(status);
        event.setMessage(message);
        event.setPayloadJson(payload == null ? null : toJson(payload));
        event.setCreatedAt(now());
        event.setSortOrder(sortOrder);
        mapper.insertRunEvent(event);
    }

    private String normalizeIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null) {
            return null;
        }
        String trimmed = idempotencyKey.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String safeKey(String value) {
        String key = value == null ? "workflow" : value.trim().toLowerCase();
        key = key.replaceAll("[^a-z0-9_-]+", "-").replaceAll("^-+|-+$", "");
        return key.isEmpty() ? "workflow-" + UUID.randomUUID().toString().substring(0, 8) : key;
    }

    private String text(Map<String, Object> body, String key, String fallback) {
        if (body == null || !body.containsKey(key) || body.get(key) == null) {
            return fallback;
        }
        String value = String.valueOf(body.get(key)).trim();
        return value.isEmpty() ? fallback : value;
    }

    private int number(Map<String, Object> body, String key, int fallback) {
        if (body == null || body.get(key) == null) {
            return fallback;
        }
        Object value = body.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ex) {
            return fallback;
        }
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String now() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    private static class WorkflowStoppedException extends RuntimeException {
        WorkflowStoppedException(String status) {
            super(status);
        }
    }
}
