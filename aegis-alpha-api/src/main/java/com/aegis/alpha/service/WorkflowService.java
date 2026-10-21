package com.aegis.alpha.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aegis.alpha.config.AsyncConfig;
import com.aegis.alpha.domain.AgentTemplate;
import com.aegis.alpha.domain.WorkflowDefinition;
import com.aegis.alpha.domain.WorkflowLayout;
import com.aegis.alpha.domain.WorkflowNodeRun;
import com.aegis.alpha.domain.WorkflowRun;
import com.aegis.alpha.domain.WorkflowRunEvent;
import com.aegis.alpha.domain.WorkflowVersion;
import com.aegis.alpha.mapper.AgentMapper;
import com.aegis.alpha.mapper.WorkflowMapper;
import com.aegis.alpha.security.AuthenticatedPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class WorkflowService {
    private static final Logger log = LoggerFactory.getLogger(WorkflowService.class);

    /** Bump when research default graphs change so stale DB layouts are skipped on read. */
    static final int CURRENT_RESEARCH_LAYOUT_VERSION = 2;
    /** Short-lived portfolio:read delegation for orchestrator tool calls (15 minutes). */
    static final long DELEGATED_PORTFOLIO_READ_TTL_MS = 15L * 60L * 1000L;
    static final String SCOPE_PORTFOLIO_READ = "portfolio:read";

    private static final Set<String> RESEARCH_LAYOUT_KEYS = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
            "stock_analysis",
            "earnings_reaction",
            "watchlist_digest"
    )));

    private final WorkflowMapper mapper;
    private final AgentMapper agentMapper;
    private final ObjectMapper objectMapper;
    private final CacheService cacheService;
    private final BacktestService backtestService;
    private final AgentTraceService agentTraceService;
    private final WorkflowValidationService validationService;
    private final LangChainGateway langChainGateway;
    private final TokenService tokenService;
    /** When true, read-path refresh also async-writes the code default back to workflow_layout. */
    private final boolean refreshDefaultLayouts;
    /**
     * Best-effort background writes. Direct execution is only a non-Spring fallback;
     * Spring injects the bounded {@code workflowExecutor} bean through the setter below.
     */
    private Executor workflowExecutor = Runnable::run;

    public WorkflowService(WorkflowMapper mapper,
                           AgentMapper agentMapper,
                           ObjectMapper objectMapper,
                            LangChainGateway langChainGateway,
                            CacheService cacheService,
                            BacktestService backtestService,
                            AgentTraceService agentTraceService,
                            WorkflowValidationService validationService,
                            TokenService tokenService,
                            @Value("${aegis.workflow.refresh-default-layouts:false}") boolean refreshDefaultLayouts) {
        this.mapper = mapper;
        this.agentMapper = agentMapper;
        this.objectMapper = objectMapper;
        this.langChainGateway = langChainGateway;
        this.cacheService = cacheService;
        this.backtestService = backtestService;
        this.agentTraceService = agentTraceService;
        this.validationService = validationService;
        this.tokenService = tokenService;
        this.refreshDefaultLayouts = refreshDefaultLayouts;
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

    /**
     * Owner-scoped run list. A null principal keeps the legacy unscoped list for
     * internal/test callers; HTTP callers always pass a principal.
     */
    public List<WorkflowRun> runsForPrincipal(AuthenticatedPrincipal principal) {
        List<WorkflowRun> runs = runs();
        if (principal == null) {
            return runs;
        }
        List<WorkflowRun> visible = new ArrayList<WorkflowRun>();
        for (WorkflowRun run : runs) {
            if (canAccess(run, principal)) {
                visible.add(run);
            }
        }
        return visible;
    }

    public WorkflowRun run(String runId) {
        WorkflowRun run = mapper.findRun(runId);
        hydrateRunContract(run);
        return run;
    }

    /**
     * Owner-scoped read. Returns null when the run is missing, when the caller is not the owner,
     * or when the run has no recorded owner and the caller is not an admin.
     */
    public WorkflowRun runForPrincipal(String runId, AuthenticatedPrincipal principal) {
        WorkflowRun run = ownedRun(runId, principal);
        hydrateRunContract(run);
        return run;
    }

    public List<WorkflowNodeRun> nodeRuns(String runId) {
        return mapper.findNodeRuns(runId);
    }

    public List<WorkflowNodeRun> nodeRunsForPrincipal(String runId, AuthenticatedPrincipal principal) {
        if (ownedRun(runId, principal) == null) {
            return Collections.emptyList();
        }
        List<WorkflowNodeRun> nodes = mapper.findNodeRuns(runId);
        return nodes == null ? Collections.<WorkflowNodeRun>emptyList() : nodes;
    }

    public List<WorkflowRunEvent> runEvents(String runId) {
        return mapper.findRunEvents(runId);
    }

    public List<WorkflowRunEvent> runEventsForPrincipal(String runId, AuthenticatedPrincipal principal) {
        if (ownedRun(runId, principal) == null) {
            return Collections.emptyList();
        }
        List<WorkflowRunEvent> events = mapper.findRunEvents(runId);
        return events == null ? Collections.<WorkflowRunEvent>emptyList() : events;
    }

    /** True when the principal may read the run (owner or admin). */
    public boolean isRunAccessible(WorkflowRun run, AuthenticatedPrincipal principal) {
        return canAccess(run, principal);
    }

    /** Loads the run and returns it only when the principal is allowed to read it. */
    private WorkflowRun ownedRun(String runId, AuthenticatedPrincipal principal) {
        if (runId == null || runId.trim().isEmpty() || principal == null) {
            return null;
        }
        WorkflowRun run = mapper.findRun(runId);
        return canAccess(run, principal) ? run : null;
    }

    private boolean canAccess(WorkflowRun run, AuthenticatedPrincipal principal) {
        if (run == null || principal == null) {
            return false;
        }
        if (principal.hasRole("admin")) {
            return true;
        }
        // Legacy/system runs without an owner are only visible to admins.
        if (run.getUserId() == null || run.getUserId().trim().isEmpty()) {
            return false;
        }
        if (!run.getUserId().equals(principal.getUserId())) {
            return false;
        }
        return run.getTenantId() != null && run.getTenantId().equals(principal.getTenantId());
    }

    /** Copies the caller identity onto a new run (no-op for internal/test callers). */
    private void applyRunOwner(WorkflowRun run, AuthenticatedPrincipal principal) {
        if (run == null || principal == null) {
            return;
        }
        run.setUserId(principal.getUserId());
        run.setTenantId(principal.getTenantId());
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

    /** Authorization failure for workflow edits; deliberately distinct from validation errors. */
    public static class AccessDeniedException extends IllegalArgumentException {
        public AccessDeniedException(String message) {
            super(message);
        }
    }

    public WorkflowVersion publishVersion(String workflowKey, String username) {
        WorkflowDefinition definition = requireDefinition(workflowKey);
        requireEditable(definition, username);
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
            if (shouldRefreshResearchLayout(workflowKey, parsed)) {
                WorkflowDefinition definition = mapper.findDefinition(workflowKey);
                Map<String, Object> refreshed = definition == null ? emptyLayout(workflowKey) : defaultLayout(definition);
                refreshed.put("updatedAt", layout.getUpdatedAt());
                maybePersistRefreshedLayout(workflowKey, refreshed);
                return refreshed;
            }
            parsed.put("workflowKey", workflowKey);
            parsed.put("updatedAt", layout.getUpdatedAt());
            return parsed;
        } catch (Exception ex) {
            throw new IllegalStateException("Invalid workflow layout JSON for " + workflowKey, ex);
        }
    }

    /**
     * Layout write for an authenticated user. Readonly system workflows and workflows
     * owned by somebody else are rejected before any mutation.
     */
    public Map<String, Object> saveLayoutForUser(String username, String workflowKey, Map<String, Object> body) {
        requireEditable(mapper.findDefinition(workflowKey), username);
        return saveLayoutInternal(workflowKey, body, username);
    }

    /** Internal/system layout write (seeding, best-effort refresh). No user checks. */
    public Map<String, Object> saveLayout(String workflowKey, Map<String, Object> body) {
        return saveLayoutInternal(workflowKey, body, null);
    }

    private void requireEditable(WorkflowDefinition definition, String username) {
        if (definition == null) {
            return;
        }
        if (definition.isReadonlyFlag()) {
            throw new AccessDeniedException("Readonly system workflows cannot be edited.");
        }
        if (definition.getOwnerUsername() != null
                && !definition.getOwnerUsername().equals(username)) {
            throw new AccessDeniedException("Workflow belongs to another user.");
        }
    }

    private Map<String, Object> saveLayoutInternal(String workflowKey, Map<String, Object> body, String username) {
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
            definition.setOwnerUsername(username);
            definition.setUpdatedAt(now());
            mapper.insertDefinition(definition);
        }
        Map<String, Object> layout = new LinkedHashMap<>(body);
        layout.put("workflowKey", workflowKey);
        if (!layout.containsKey("engine")) {
            layout.put("engine", "langgraph");
        }
        ensureLayoutVersionMetadata(layout, workflowKey);
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
            cacheService.evict("aegis:workflow:layout:" + workflowKey);
            layout.put("updatedAt", timestamp);
            return layout;
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to save workflow layout for " + workflowKey, ex);
        }
    }

    public WorkflowRun createRun(String workflowKey, String subject, Map<String, Object> inputs) {
        return createRun(workflowKey, subject, inputs, null);
    }

    public WorkflowRun createRun(String workflowKey, String subject, Map<String, Object> inputs,
                                 AuthenticatedPrincipal principal) {
        String key = workflowKey == null || workflowKey.trim().isEmpty() ? "daily" : workflowKey.trim();
        WorkflowVersion version = mapper.findLatestVersion(key);
        Map<String, Object> runLayout = layoutForRun(key, version);
        validationService.validateLayout(runLayout);
        Map<String, Object> safeInputs = inputs == null ? new LinkedHashMap<String, Object>() : new LinkedHashMap<>(inputs);
        applyAuthenticatedUserId(safeInputs, principal == null ? null : principal.getUserId());
        WorkflowRun run = new WorkflowRun();
        run.setRunId(UUID.randomUUID().toString());
        run.setWorkflowKey(key);
        run.setTraceId(UUID.randomUUID().toString());
        run.setStatus("RUNNING");
        run.setSubject(subject);
        run.setStartedAt(now());
        run.setNodeCount(0);
        run.setWorkflowVersionId(version == null ? null : version.getVersionId());
        run.setInputsJson(toJson(safeInputs));
        run.setControlStatus("ACTIVE");
        run.setPauseRequested(0);
        run.setCancelRequested(0);
        applyRunOwner(run, principal);
        mapper.insertRun(run);
        recordRunEvent(run, "RUN_CREATED", null, null, "RUNNING", "Workflow run created.", null, 0);
        return run;
    }

    public void executeAsync(WorkflowRun run, Map<String, Object> inputs, String workflowKey) {
        WorkflowVersion version = mapper.findLatestVersion(workflowKey);
        Map<String, Object> runLayout = layoutForRun(workflowKey, version);
        try {
            runWorkflow(run, inputs, runLayout);
            backtestService.createFromWorkflowRun(mapper.findRun(run.getRunId()), inputs);
        } catch (WorkflowStoppedException stopped) {
            // run already updated by execute()
        } catch (Exception ex) {
            run.setStatus("FAILED");
            run.setCompletedAt(now());
            run.setErrorMessage(ex.getMessage());
            run.setControlStatus("FAILED");
            mapper.updateRun(run);
            recordRunEvent(run, "RUN_FAILED", null, null, "FAILED", ex.getMessage(), null, 1000000);
        }
    }
    public WorkflowRun start(String workflowKey, String subject) {
        return start(workflowKey, subject, new LinkedHashMap<String, Object>(), (String) null, null);
    }

    public WorkflowRun start(String workflowKey, String subject, Map<String, Object> inputs) {
        return start(workflowKey, subject, inputs, (String) null, null);
    }

    public WorkflowRun start(String workflowKey, String subject, Map<String, Object> inputs, String idempotencyKey) {
        return start(workflowKey, subject, inputs, idempotencyKey, null);
    }

    public WorkflowRun start(String workflowKey, String subject, Map<String, Object> inputs, String idempotencyKey,
                             AuthenticatedPrincipal principal) {
        String key = workflowKey == null || workflowKey.trim().isEmpty() ? "daily" : workflowKey.trim();
        String normalizedIdempotencyKey = normalizeIdempotencyKey(idempotencyKey);
        WorkflowRun existing = findIdempotentRun(key, subject, normalizedIdempotencyKey);
        if (existing != null && (principal == null || isRunAccessible(existing, principal))) {
            return existing;
        }
        WorkflowVersion version = mapper.findLatestVersion(key);
        Map<String, Object> runLayout = layoutForRun(key, version);
        validationService.validateLayout(runLayout);
        Map<String, Object> safeInputs = inputs == null ? new LinkedHashMap<String, Object>() : new LinkedHashMap<>(inputs);
        applyAuthenticatedUserId(safeInputs, principal == null ? null : principal.getUserId());
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
        applyRunOwner(run, principal);
        mapper.insertRun(run);
        recordRunEvent(run, "RUN_CREATED", null, null, "RUNNING", "Workflow run created.", null, 0);

        try {
            runWorkflow(run, safeInputs, runLayout);
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

    public WorkflowRun startWithStreaming(String workflowKey, String subject, Map<String, Object> inputs,
                                          org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter) {
        return startWithStreaming(workflowKey, subject, inputs, emitter, null, null);
    }

    public WorkflowRun startWithStreaming(String workflowKey, String subject, Map<String, Object> inputs,
                                          org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter,
                                          AuthenticatedPrincipal principal) {
        return startWithStreaming(workflowKey, subject, inputs, emitter,
                principal == null ? null : principal.getUserId(),
                principal == null ? null : principal.getTenantId());
    }

    public WorkflowRun startWithStreaming(String workflowKey, String subject, Map<String, Object> inputs,
                                          org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter,
                                          String userId, String tenantId) {
        String key = workflowKey == null || workflowKey.trim().isEmpty() ? "daily" : workflowKey.trim();
        WorkflowVersion version = mapper.findLatestVersion(key);
        Map<String, Object> runLayout = layoutForRun(key, version);
        validationService.validateLayout(runLayout);
        Map<String, Object> safeInputs = inputs == null ? new LinkedHashMap<String, Object>() : new LinkedHashMap<String, Object>(inputs);
        applyAuthenticatedUserId(safeInputs, userId);
        final WorkflowRun run = new WorkflowRun();
        run.setRunId(UUID.randomUUID().toString());
        run.setWorkflowKey(key);
        run.setTraceId(UUID.randomUUID().toString());
        run.setStatus("RUNNING");
        run.setSubject(subject);
        run.setStartedAt(now());
        run.setNodeCount(0);
        run.setWorkflowVersionId(version == null ? null : version.getVersionId());
        run.setInputsJson(toJson(safeInputs));
        run.setControlStatus("ACTIVE");
        run.setPauseRequested(0);
        run.setCancelRequested(0);
        if (hasText(userId)) {
            run.setUserId(userId.trim());
        }
        if (hasText(tenantId)) {
            run.setTenantId(tenantId.trim());
        }
        mapper.insertRun(run);
        recordRunEvent(run, "RUN_CREATED", null, null, "RUNNING", "Workflow run created (streaming).", null, 0);
        String streamUrl = langChainGateway.streamWorkflowUrl();
        String delegatedToken = issueDelegatedPortfolioReadToken(run.getRunId(), userId, tenantId, safeInputs);
        boolean requireApproval = requireApprovalRequested(safeInputs, runLayout);
        String streamBody = langChainGateway.buildStreamBody(
                runLayout, subject, safeInputs, delegatedToken, run.getRunId(), requireApproval);
        final String runId = run.getRunId();
        // P1-16: monotonic sort order for streamed node events; node_update upserts workflow_node_run.
        final AtomicInteger streamSequence = new AtomicInteger(0);
        final Map<String, WorkflowNodeRun> streamedNodeRuns = new LinkedHashMap<>();
        final Map<String, Map<String, Object>> streamLayoutNodes = new LinkedHashMap<>();
        for (Map<String, Object> layoutNode : castList(runLayout.get("nodes"))) {
            streamLayoutNodes.put(nodeId(layoutNode), layoutNode);
        }
        SseStreamReader.readSse(streamUrl, streamBody, langChainGateway.serviceAuthorizationHeader(),
                new SseStreamReader.SseEventHandler() {
            @Override public void onEvent(String eventName, String data) {
                try {
                    if ("node_update".equals(eventName)) {
                        Map<String, Object> nodePayload = parseJsonMap(data);
                        WorkflowNodeRun nodeRun = upsertStreamedNodeRun(run, nodePayload, streamedNodeRuns,
                                streamLayoutNodes, streamSequence.incrementAndGet());
                        boolean nodeOk = "COMPLETED".equals(nodeRun.getStatus());
                        recordRunEvent(run, nodeOk ? "NODE_COMPLETED" : "NODE_FAILED", nodeRun, nodeRun.getNodeId(),
                                nodeRun.getStatus(), (nodeOk ? "Node completed: " : "Node failed: ") + nodeRun.getNodeName(),
                                nodePayload, nodeRun.getSortOrder() + 1);
                        recordStreamedNodeSpan(run, nodeRun, nodePayload);
                        emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event().name("node_update").data(data));
                    } else if ("degraded".equals(eventName)) {
                        Map<String, Object> payload = parseJsonMap(data);
                        recordRunEvent(run, "RUN_DEGRADED", null, null, "RUNNING",
                                "Workflow run degraded (streaming).", payload, 900000);
                        emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event()
                                .name("degraded").data(data == null ? "{}" : data));
                    } else if ("human_interrupt".equals(eventName)) {
                        Map<String, Object> payload = parseJsonMap(data);
                        run.setStatus("PAUSED");
                        run.setControlStatus("HUMAN_INTERRUPT");
                        run.setPauseRequested(1);
                        run.setResultJson(data == null ? "{}" : data);
                        mapper.updateRun(run);
                        recordRunEvent(run, "RUN_PAUSED", null, null, "PAUSED",
                                "Workflow paused for human interrupt (streaming).", payload, 950000);
                        emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event()
                                .name("human_interrupt").data(data == null ? "{}" : data));
                    } else if ("workflow_complete".equals(eventName)) {
                        if ("HUMAN_INTERRUPT".equals(run.getControlStatus())) {
                            return;
                        }
                        Map<String, Object> payload = parseJsonMap(data);
                        boolean degraded = Boolean.TRUE.equals(payload.get("degraded"))
                                || "true".equalsIgnoreCase(String.valueOf(payload.get("degraded")));
                        run.setStatus("COMPLETED");
                        run.setCompletedAt(now());
                        run.setControlStatus("COMPLETED");
                        run.setPauseRequested(0);
                        run.setCancelRequested(0);
                        run.setNodeCount(streamedNodeRuns.size());
                        Map<String, Object> result = buildStreamingCompleteResult(payload, degraded);
                        if (degraded) {
                            if (run.getErrorMessage() == null || run.getErrorMessage().trim().isEmpty()) {
                                run.setErrorMessage("DEGRADED");
                            }
                            run.setResultJson(toJson(result));
                            recordRunEvent(run, "RUN_DEGRADED", null, null, "COMPLETED",
                                    "Workflow completed with degradation (streaming).", payload, 999999);
                        } else if (result != null && !result.isEmpty()) {
                            run.setResultJson(toJson(result));
                        }
                        mapper.updateRun(run);
                        recordRunEvent(run, "RUN_COMPLETED", null, null, "COMPLETED",
                                degraded ? "Workflow run completed degraded (streaming)." : "Workflow run completed (streaming).",
                                payload, 1000000);
                        materializeStreamingCompletedRun(runId, safeInputs);
                        String completeData = data == null || data.trim().isEmpty()
                                ? (degraded ? "{\"ok\":true,\"degraded\":true}" : "{\"ok\":true}")
                                : data;
                        emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event()
                                .name("workflow_complete").data(completeData));
                        emitter.complete();
                    } else if ("error".equals(eventName)) {
                        JsonNode en = objectMapper.readTree(data);
                        run.setStatus("FAILED");
                        run.setCompletedAt(now());
                        run.setErrorMessage(en.has("error") ? en.get("error").asText() : "Unknown error");
                        run.setControlStatus("FAILED");
                        mapper.updateRun(run);
                        recordRunEvent(run, "RUN_FAILED", null, null, "FAILED", run.getErrorMessage(), null, 1000000);
                        emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event().name("error").data(data));
                        emitter.complete();
                    }
                } catch (Exception ex) {
                    try { run.setStatus("FAILED"); run.setCompletedAt(now()); run.setControlStatus("FAILED"); mapper.updateRun(run); } catch (Exception ignored) {}
                    try { emitter.completeWithError(ex); } catch (Exception ignored) {}
                }
            }
            @Override public void onError(Exception ex) {
                try {
                    run.setStatus("FAILED"); run.setCompletedAt(now());
                    run.setErrorMessage("Stream error: " + ex.getMessage());
                    run.setControlStatus("FAILED");
                    mapper.updateRun(run);
                    recordRunEvent(run, "RUN_FAILED", null, null, "FAILED", run.getErrorMessage(), null, 1000000);
                    emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event().name("error").data("{\"ok\":false}"));
                } catch (Exception ignored) {}
                try { emitter.completeWithError(ex); } catch (Exception ignored) {}
            }
            @Override public void onComplete() {
                WorkflowRun latest = mapper.findRun(runId);
                if (latest != null && "RUNNING".equals(latest.getStatus())) {
                    latest.setStatus("COMPLETED"); latest.setCompletedAt(now());
                    latest.setControlStatus("COMPLETED");
                    mapper.updateRun(latest);
                    recordRunEvent(latest, "RUN_COMPLETED", null, null, "COMPLETED",
                            "Workflow run completed (streaming stream end).", null, 1000000);
                    materializeStreamingCompletedRun(runId, safeInputs);
                }
            }
        });
        return mapper.findRun(runId);
    }

    /**
     * Upsert one workflow_node_run per streamed node id (repeat updates, e.g. agent/tool loops, update the row).
     * sortOrder = sequence * 100 so streamed rows/events keep the arrival order.
     */
    WorkflowNodeRun upsertStreamedNodeRun(WorkflowRun run, Map<String, Object> payload,
                                          Map<String, WorkflowNodeRun> streamedNodeRuns,
                                          Map<String, Map<String, Object>> layoutNodes, int sequence) {
        String nodeId = firstNonBlank(string(payload.get("nodeId")), null, "unknown");
        boolean ok = !Boolean.FALSE.equals(payload.get("ok"))
                && !"failed".equalsIgnoreCase(String.valueOf(payload.get("status")));
        WorkflowNodeRun nodeRun = streamedNodeRuns.get(nodeId);
        boolean exists = nodeRun != null;
        if (!exists) {
            Map<String, Object> layoutNode = layoutNodes.get(nodeId);
            nodeRun = new WorkflowNodeRun();
            nodeRun.setNodeRunId(UUID.randomUUID().toString());
            nodeRun.setRunId(run.getRunId());
            nodeRun.setNodeId(nodeId);
            String nodeName = string(payload.get("nodeName"));
            nodeRun.setNodeName(nodeName == null || nodeName.isEmpty()
                    ? (layoutNode == null ? nodeId : nodeName(layoutNode)) : nodeName);
            nodeRun.setNodeType(layoutNode == null ? null : nodeType(layoutNode));
            nodeRun.setAgentId(layoutNode == null ? null : agentId(layoutNode));
            nodeRun.setAttempt(1);
            nodeRun.setMaxAttempts(1);
            nodeRun.setStartedAt(firstNonBlank(string(payload.get("startedAt")), null, now()));
            nodeRun.setSortOrder(sequence * 100);
        } else {
            nodeRun.setAttempt((nodeRun.getAttempt() == null ? 1 : nodeRun.getAttempt()) + 1);
        }
        nodeRun.setStatus(ok ? "COMPLETED" : "FAILED");
        nodeRun.setOutputJson(toJson(payload));
        nodeRun.setCompletedAt(firstNonBlank(string(payload.get("completedAt")), null, now()));
        if (exists) {
            mapper.updateNodeRun(nodeRun);
        } else {
            mapper.insertNodeRun(nodeRun);
            streamedNodeRuns.put(nodeId, nodeRun);
        }
        return nodeRun;
    }

    private void recordStreamedNodeSpan(WorkflowRun run, WorkflowNodeRun nodeRun, Map<String, Object> payload) {
        if (agentTraceService == null) {
            return;
        }
        try {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("nodeId", nodeRun.getNodeId());
            input.put("source", "stream");
            agentTraceService.recordNodeSpan(run, nodeRun, string(payload.get("handler")), input, payload);
        } catch (Exception ignored) {
            // span recording must never break the client stream
        }
    }

    private void materializeStreamingCompletedRun(String runId, Map<String, Object> inputs) {
        try {
            WorkflowRun completed = mapper.findRun(runId);
            if (completed == null || !"COMPLETED".equals(completed.getStatus())) {
                return;
            }
            backtestService.createFromWorkflowRun(completed, inputs);
        } catch (Exception ex) {
            try {
                WorkflowRun latest = mapper.findRun(runId);
                if (latest == null) {
                    return;
                }
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("error", ex.getMessage());
                recordRunEvent(latest, "MATERIALIZE_FAILED", null, null, latest.getStatus(),
                        "Failed to materialize recommendation/evidence from streaming run.",
                        payload, 1000001);
            } catch (Exception ignored) {
                // isolation: materialize failure must not affect streaming client completion
            }
        }
    }

    /**
     * Issue short-lived portfolio:read delegation for orchestrator tools.
     * Fail soft: returns null on any error so market tools still run.
     * userId: param → inputs.userId → "system"; tenantId: param → inputs.tenantId → "default".
     */
    String issueDelegatedPortfolioReadToken(String runId, String userId, String tenantId, Map<String, Object> inputs) {
        if (tokenService == null) {
            return null;
        }
        try {
            String resolvedUserId = firstNonBlank(userId, text(inputs, "userId", null), "system");
            String resolvedTenantId = firstNonBlank(tenantId, text(inputs, "tenantId", null), "default");
            return tokenService.issueServiceDelegation(
                    runId,
                    resolvedUserId,
                    resolvedTenantId,
                    Collections.singletonList(SCOPE_PORTFOLIO_READ),
                    DELEGATED_PORTFOLIO_READ_TTL_MS);
        } catch (Exception ex) {
            log.warn("[WorkflowService] Failed to issue portfolio:read delegation for runId={}: {}",
                    runId, ex.getMessage());
            return null;
        }
    }

    /**
     * The orchestrator loads preferences from final_state.userId / user_id.
     * Streaming used to send inputs only, so an authenticated caller was dropped.
     */
    static void applyAuthenticatedUserId(Map<String, Object> safeInputs, String userId) {
        if (userId == null || userId.trim().isEmpty() || safeInputs == null) {
            return;
        }
        /* The authenticated id always wins: a client-supplied userId/user_id would otherwise select
         * another user's memory and preferences in the orchestrator. */
        safeInputs.remove("user_id");
        safeInputs.put("userId", userId.trim());
    }

    private static boolean hasText(Object value) {
        return value != null && !String.valueOf(value).trim().isEmpty();
    }

    private String firstNonBlank(String first, String second, String fallback) {
        if (first != null && !first.trim().isEmpty()) {
            return first.trim();
        }
        if (second != null && !second.trim().isEmpty()) {
            return second.trim();
        }
        return fallback;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildStreamingCompleteResult(Map<String, Object> payload, boolean degraded) {
        Map<String, Object> result = new LinkedHashMap<>();
        Object finalState = payload == null ? null : payload.get("final_state");
        if (finalState instanceof Map) {
            result.putAll((Map<String, Object>) finalState);
        }
        if (degraded) {
            result.put("ok", true);
            result.put("degraded", true);
            if (payload != null && payload.get("reasons") != null) {
                result.put("reasons", payload.get("reasons"));
            }
            return result;
        }
        if (!result.isEmpty()) {
            result.putIfAbsent("ok", true);
            return result;
        }
        return null;
    }


    public WorkflowRun queueStart(String workflowKey, String subject, Map<String, Object> inputs, String idempotencyKey) {
        return queueStart(workflowKey, subject, inputs, idempotencyKey, null);
    }

    public WorkflowRun queueStart(String workflowKey, String subject, Map<String, Object> inputs, String idempotencyKey,
                                  AuthenticatedPrincipal principal) {
        String key = workflowKey == null || workflowKey.trim().isEmpty() ? "daily" : workflowKey.trim();
        String normalizedIdempotencyKey = normalizeIdempotencyKey(idempotencyKey);
        WorkflowRun existing = findIdempotentRun(key, subject, normalizedIdempotencyKey);
        if (existing != null && (principal == null || isRunAccessible(existing, principal))) {
            return existing;
        }
        WorkflowVersion version = mapper.findLatestVersion(key);
        Map<String, Object> runLayout = layoutForRun(key, version);
        validationService.validateLayout(runLayout);
        Map<String, Object> safeInputs = inputs == null ? new LinkedHashMap<String, Object>() : new LinkedHashMap<>(inputs);
        applyAuthenticatedUserId(safeInputs, principal == null ? null : principal.getUserId());
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
        applyRunOwner(run, principal);
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
            runWorkflow(run, inputs, runLayout);
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
            // Approval pauses resume through decideApproval, not a full requeue.
            if ("HUMAN_INTERRUPT".equals(run.getControlStatus())) {
                return mapper.findRun(runId);
            }
            if (run.getControlStatus() == null || "PAUSED".equals(run.getControlStatus())) {
                run.setStatus("QUEUED");
                run.setControlStatus("QUEUED");
                run.setPauseRequested(0);
                run.setCompletedAt(null);
                mapper.updateRun(run);
                recordRunEvent(run, "RUN_RESUMED", null, null, "QUEUED", "Workflow run resumed into queue.", null, 3);
            }
        }
        return mapper.findRun(runId);
    }

    /** Roles allowed to approve/reject runs paused at the approval gate (comma separated). */
    @Value("${aegis.workflow.approver-roles:desk,admin}")
    private String approverRoles = "desk,admin";

    /** True when the principal holds one of the configured approver roles. */
    public boolean isApprover(AuthenticatedPrincipal principal) {
        if (principal == null || approverRoles == null) {
            return false;
        }
        for (String role : approverRoles.split(",")) {
            if (!role.trim().isEmpty() && principal.hasRole(role.trim())) {
                return true;
            }
        }
        return false;
    }

    void setApproverRoles(String approverRoles) {
        this.approverRoles = approverRoles;
    }

    /** Control status held while one request owns the approval decision (CAS from HUMAN_INTERRUPT). */
    static final String APPROVAL_IN_PROGRESS = "APPROVAL_IN_PROGRESS";

    /**
     * Continue a run paused at the orchestrator approval gate. Does not start a new execution.
     */
    public WorkflowRun decideApproval(String runId, boolean approved) {
        return decideApproval(runId, approved, null, null);
    }

    /**
     * Approve or reject a run paused at the approval gate.
     *
     * <ul>
     *   <li>Only one caller wins the {@code HUMAN_INTERRUPT → APPROVAL_IN_PROGRESS} compare-and-set,
     *       so the orchestrator is resumed at most once; repeated submissions for an already
     *       decided run return its current state.</li>
     *   <li>Approval completes the run ({@code COMPLETED}); rejection ends it as {@code REJECTED}
     *       (status name 待确认 with product) and the orchestrator skips the aggregate node.</li>
     *   <li>A failed resume returns the run to {@code HUMAN_INTERRUPT} so it can be retried.</li>
     * </ul>
     *
     * @throws IllegalStateException when the run never waited for approval
     */
    public WorkflowRun decideApproval(String runId, boolean approved, String comment, AuthenticatedPrincipal approver) {
        WorkflowRun run = requireRun(runId);
        if (!isHumanInterrupt(run)) {
            if (isApprovalDecidedOrInFlight(run)) {
                return mapper.findRun(runId);
            }
            throw new IllegalStateException("Workflow run is not awaiting human approval: " + runId);
        }
        if (mapper.transitionControlStatus(runId, "HUMAN_INTERRUPT", APPROVAL_IN_PROGRESS) == 0) {
            /* Another request claimed the decision first. */
            return mapper.findRun(runId);
        }
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("approved", approved);
        if (approver != null) {
            audit.put("approverUserId", approver.getUserId());
            audit.put("approverUsername", approver.getUsername());
        }
        if (comment != null && !comment.trim().isEmpty()) {
            String trimmed = comment.trim();
            audit.put("comment", trimmed.length() > 500 ? trimmed.substring(0, 500) : trimmed);
        }
        recordRunEvent(run, "RUN_APPROVAL_DECISION", null, null, "PAUSED",
                approved ? "Approval submitted." : "Rejection submitted.", audit, 955000);

        Map<String, Object> resume = langChainGateway.resumeWorkflow(run.getRunId(), approved);
        if (isResumeOk(resume)) {
            run.setStatus(approved ? "COMPLETED" : "REJECTED");
            run.setCompletedAt(now());
            run.setControlStatus(approved ? "COMPLETED" : "REJECTED");
            run.setPauseRequested(0);
            Map<String, Object> result = new LinkedHashMap<>();
            if (resume != null) {
                result.putAll(resume);
            }
            result.putAll(audit);
            if (approved) {
                result.put("approval", "approved");
            } else {
                result.put("approval", "rejected");
                result.put("rejected", true);
                if (run.getErrorMessage() == null || run.getErrorMessage().trim().isEmpty()) {
                    run.setErrorMessage("REJECTED");
                }
            }
            run.setResultJson(toJson(result));
            mapper.updateRun(run);
            recordRunEvent(run, approved ? "RUN_APPROVED" : "RUN_REJECTED", null, null,
                    approved ? "COMPLETED" : "REJECTED",
                    approved ? "Human approval accepted; workflow resumed."
                            : "Human approval rejected; final report skipped.",
                    result, 1000000);
        } else {
            String error = resume == null ? "" : string(resume.get("error"));
            run.setControlStatus("HUMAN_INTERRUPT");
            run.setErrorMessage(error == null || error.trim().isEmpty() ? "resume failed" : error);
            mapper.updateRun(run);
            recordRunEvent(run, "RUN_RESUME_FAILED", null, null, "PAUSED",
                    "Human approval resume failed.", resume, 960000);
        }
        return mapper.findRun(runId);
    }

    private boolean isApprovalDecidedOrInFlight(WorkflowRun run) {
        if (run == null) {
            return false;
        }
        if (APPROVAL_IN_PROGRESS.equals(run.getControlStatus()) || "REJECTED".equals(run.getStatus())) {
            return true;
        }
        Map<String, Object> result = parseJsonMap(run.getResultJson());
        return result.get("approval") != null;
    }

    private boolean isHumanInterrupt(WorkflowRun run) {
        return run != null && "HUMAN_INTERRUPT".equals(run.getControlStatus());
    }

    private boolean isResumeOk(Map<String, Object> resume) {
        if (resume == null) {
            return false;
        }
        Object ok = resume.get("ok");
        if (ok instanceof Boolean) {
            return (Boolean) ok;
        }
        return "true".equalsIgnoreCase(string(ok));
    }

    @SuppressWarnings("unchecked")
    private boolean requireApprovalRequested(Map<String, Object> inputs, Map<String, Object> layout) {
        if (isTruthyFlag(inputs, "requireApproval") || isTruthyFlag(inputs, "require_approval")) {
            return true;
        }
        if (layout == null || !(layout.get("metadata") instanceof Map)) {
            return false;
        }
        return isTruthyFlag((Map<String, Object>) layout.get("metadata"), "requireApproval")
                || isTruthyFlag((Map<String, Object>) layout.get("metadata"), "require_approval");
    }

    private boolean isTruthyFlag(Map<String, Object> source, String key) {
        if (source == null || key == null || !source.containsKey(key)) {
            return false;
        }
        Object value = source.get(key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue() != 0;
        }
        if (value == null) {
            return false;
        }
        String text = String.valueOf(value).trim();
        return "true".equalsIgnoreCase(text) || "1".equals(text) || "yes".equalsIgnoreCase(text);
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
    /** Result marker for the engine that produced a run. */
    static final String ENGINE_LANGGRAPH = "langgraph";
    static final String ENGINE_JAVA_FALLBACK = "java-fallback";

    /**
     * Single execution entry for sync start, queued dispatch and async (copilot) runs.
     * The LangGraph orchestrator executes the whole graph (same engine as streaming, with the
     * approval gate); the node-by-node Java loop only runs when the orchestrator is explicitly
     * disabled ({@code aegis.langchain.enabled=false}) and marks the result {@code engine=java-fallback}.
     */
    private void runWorkflow(WorkflowRun run, Map<String, Object> inputs, Map<String, Object> layout) throws Exception {
        if (langChainGateway != null && langChainGateway.isEnabled()) {
            executeViaOrchestrator(run, inputs, layout);
            return;
        }
        execute(run, inputs, layout);
    }

    @SuppressWarnings("unchecked")
    private void executeViaOrchestrator(WorkflowRun run, Map<String, Object> inputs, Map<String, Object> layout) {
        enforceRunControl(run);
        Map<String, Object> state = new LinkedHashMap<>(inputs == null ? new LinkedHashMap<String, Object>() : inputs);
        state.put("workflowKey", run.getWorkflowKey());
        state.put("runId", run.getRunId());
        state.put("subject", run.getSubject());
        String delegatedToken = issueDelegatedPortfolioReadToken(run.getRunId(), run.getUserId(), run.getTenantId(), inputs);
        boolean requireApproval = requireApprovalRequested(inputs == null ? new LinkedHashMap<String, Object>() : inputs, layout);
        Map<String, Object> response = langChainGateway.executeWorkflow(
                layout, run.getSubject(), state, delegatedToken, run.getRunId(), requireApproval);
        if (response == null || Boolean.FALSE.equals(response.get("ok"))) {
            String error = response == null ? "" : string(response.get("error"));
            throw new IllegalStateException(error == null || error.trim().isEmpty()
                    ? "Orchestrator workflow failed" : "Orchestrator workflow failed: " + error);
        }
        Map<String, Object> finalState = response.get("final_state") instanceof Map
                ? new LinkedHashMap<>((Map<String, Object>) response.get("final_state"))
                : new LinkedHashMap<String, Object>();
        Map<String, Object> nodeOutputs = response.get("node_outputs") instanceof Map
                ? (Map<String, Object>) response.get("node_outputs") : new LinkedHashMap<String, Object>();
        List<Object> trace = response.get("trace") instanceof List
                ? (List<Object>) response.get("trace") : new ArrayList<Object>();
        int materialized = materializeOrchestratorTrace(run, layout, trace, nodeOutputs);
        boolean degraded = false;
        for (Object entry : trace) {
            if (entry instanceof Map && Boolean.TRUE.equals(((Map<String, Object>) entry).get("degraded"))) {
                degraded = true;
            }
        }

        Map<String, Object> result = new LinkedHashMap<>(finalState);
        result.put("engine", ENGINE_LANGGRAPH);
        run.setNodeCount(materialized);
        run.setResultJson(toJson(result));
        if (Boolean.TRUE.equals(finalState.get("interrupted"))) {
            run.setStatus("PAUSED");
            run.setControlStatus("HUMAN_INTERRUPT");
            run.setPauseRequested(1);
            mapper.updateRun(run);
            recordRunEvent(run, "RUN_PAUSED", null, null, "PAUSED",
                    "Workflow paused for human approval.", null, 950000);
            return;
        }
        run.setStatus("COMPLETED");
        run.setCompletedAt(now());
        run.setControlStatus("COMPLETED");
        run.setPauseRequested(0);
        run.setCancelRequested(0);
        if (degraded && (run.getErrorMessage() == null || run.getErrorMessage().trim().isEmpty())) {
            run.setErrorMessage("DEGRADED");
        }
        mapper.updateRun(run);
        if (degraded) {
            recordRunEvent(run, "RUN_DEGRADED", null, null, "COMPLETED",
                    "Workflow completed with degraded nodes.", null, 999999);
        }
        recordRunEvent(run, "RUN_COMPLETED", null, null, "COMPLETED", "Workflow run completed.", null, 1000000);
        cacheService.put("aegis:workflow:run:" + run.getRunId(), run, Duration.ofHours(2));
    }

    /** Persist one node run + NODE_COMPLETED/NODE_FAILED event per orchestrator trace entry, in order. */
    @SuppressWarnings("unchecked")
    private int materializeOrchestratorTrace(WorkflowRun run, Map<String, Object> layout, List<Object> trace,
                                             Map<String, Object> nodeOutputs) {
        Map<String, Map<String, Object>> layoutNodes = new LinkedHashMap<>();
        for (Map<String, Object> node : castList(layout.get("nodes"))) {
            layoutNodes.put(nodeId(node), node);
        }
        int sort = 0;
        for (Object raw : trace) {
            if (!(raw instanceof Map)) {
                continue;
            }
            Map<String, Object> entry = (Map<String, Object>) raw;
            String nodeId = string(entry.get("nodeId"));
            if (nodeId == null || nodeId.isEmpty()) {
                nodeId = string(entry.get("node_id"));
            }
            sort += 1;
            Map<String, Object> layoutNode = layoutNodes.get(nodeId);
            boolean ok = !Boolean.FALSE.equals(entry.get("ok"));
            Object output = nodeOutputs.get(nodeId);
            WorkflowNodeRun nodeRun = new WorkflowNodeRun();
            nodeRun.setNodeRunId(UUID.randomUUID().toString());
            nodeRun.setRunId(run.getRunId());
            nodeRun.setNodeId(nodeId);
            String nodeName = string(entry.get("nodeName"));
            nodeRun.setNodeName(nodeName == null || nodeName.isEmpty()
                    ? (layoutNode == null ? nodeId : nodeName(layoutNode)) : nodeName);
            nodeRun.setNodeType(layoutNode == null ? null : nodeType(layoutNode));
            nodeRun.setAgentId(layoutNode == null ? null : agentId(layoutNode));
            nodeRun.setStatus(ok ? "COMPLETED" : "FAILED");
            nodeRun.setAttempt(1);
            nodeRun.setMaxAttempts(1);
            nodeRun.setOutputJson(toJson(output == null ? entry : output));
            nodeRun.setStartedAt(firstNonBlank(string(entry.get("startedAt")), null, now()));
            nodeRun.setCompletedAt(firstNonBlank(string(entry.get("completedAt")), null, now()));
            nodeRun.setSortOrder(sort * 100);
            mapper.insertNodeRun(nodeRun);
            recordRunEvent(run, ok ? "NODE_COMPLETED" : "NODE_FAILED", nodeRun, nodeId,
                    ok ? "COMPLETED" : "FAILED", ok ? "Node completed." : "Node failed.", entry, sort * 100 + 1);
        }
        return sort;
    }

    private void execute(WorkflowRun run, Map<String, Object> inputs, Map<String, Object> layout) throws Exception {
        List<Map<String, Object>> nodes = castList(layout.get("nodes"));
        List<Map<String, Object>> edges = castList(layout.get("edges"));
        List<Map<String, Object>> orderedNodes = executionOrder(nodes, edges);
        Map<String, Object> state = new LinkedHashMap<>(inputs);
        state.put("workflowKey", run.getWorkflowKey());
        state.put("runId", run.getRunId());
        state.put("subject", run.getSubject());
        // Sync path: same portfolio:read delegation as streaming (fail soft)
        String delegatedToken = issueDelegatedPortfolioReadToken(run.getRunId(), null, null, inputs);
        List<String> degradedNodes = new ArrayList<>();

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
                    Map<String, Object> output = executeNode(node, state, run, delegatedToken);
                    if (output != null && Boolean.FALSE.equals(output.get("ok"))) {
                        /* Orchestrator reported failure: retry per retryPolicy instead of storing it as success. */
                        Object error = output.get("error") != null ? output.get("error") : output.get("summary");
                        throw new IllegalStateException("Node returned ok=false"
                                + (error == null ? "" : ": " + String.valueOf(error)));
                    }
                    if (output != null && Boolean.TRUE.equals(output.get("degraded"))) {
                        degradedNodes.add(nodeId(node));
                    }
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

        Map<String, Object> result = new LinkedHashMap<>(state);
        result.put("engine", ENGINE_JAVA_FALLBACK);
        run.setStatus("COMPLETED");
        run.setCompletedAt(now());
        run.setResultJson(toJson(result));
        run.setNodeCount(orderedNodes.size());
        run.setControlStatus("COMPLETED");
        run.setPauseRequested(0);
        run.setCancelRequested(0);
        if (!degradedNodes.isEmpty() && (run.getErrorMessage() == null || run.getErrorMessage().trim().isEmpty())) {
            run.setErrorMessage("DEGRADED");
        }
        mapper.updateRun(run);
        if (!degradedNodes.isEmpty()) {
            Map<String, Object> degradedPayload = new LinkedHashMap<>();
            degradedPayload.put("nodes", degradedNodes);
            recordRunEvent(run, "RUN_DEGRADED", null, null, "COMPLETED",
                    "Workflow completed with degraded (local) node results.", degradedPayload, 999999);
        }
        recordRunEvent(run, "RUN_COMPLETED", null, null, "COMPLETED", "Workflow run completed.", state, 1000000);
        cacheService.put("aegis:workflow:run:" + run.getRunId(), run, Duration.ofHours(2));
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
            if (shouldRefreshResearchLayout(workflowKey, parsed)) {
                Map<String, Object> refreshed = layout(workflowKey);
                refreshed.put("workflowVersionId", version.getVersionId());
                return refreshed;
            }
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
        return executeNode(node, state, run, null);
    }

    private Map<String, Object> executeNode(Map<String, Object> node, Map<String, Object> state, WorkflowRun run,
                                           String delegatedToken) {
        String type = nodeType(node);
        String handler = handler(node);
        if (isControlFlowNode(type, handler)) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("type", type);
            result.put("handler", handler);
            result.put("summary", simulatedNodeMessage(type, handler, run.getSubject()));
            result.put("message", result.get("summary"));
            result.put("ok", true);
            return result;
        }
        AgentTemplate agent = resolveAgent(node);
        return langChainGateway.executeNode(agent, state, node, run.getSubject(), delegatedToken);
    }

    private boolean isControlFlowNode(String type, String handler) {
        if ("start".equals(type) || "end".equals(type) || "condition".equals(type)) {
            return true;
        }
        if (handler == null) {
            return true;
        }
        // Only truly deterministic control-flow handlers skip LLM
        return handler.equals("workflow.end") || handler.equals("scheduler.manual") || handler.equals("scheduler.daily");
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
        if ("stock_analysis".equals(definition.getWorkflowKey())) {
            layout.put("nodes", stockAnalysisNodes());
            layout.put("edges", stockAnalysisEdges());
            attachResearchLayoutMetadata(layout, "fan_in_aggregate");
            return layout;
        }
        if ("daily".equals(definition.getWorkflowKey())) {
            layout.put("nodes", dailyNodes());
            layout.put("edges", dailyEdges());
            return layout;
        }
        if ("deep_dive".equals(definition.getWorkflowKey())) {
            layout.put("nodes", deepDiveNodes());
            layout.put("edges", deepDiveEdges());
            return layout;
        }
        if ("exit_workflow".equals(definition.getWorkflowKey())) {
            layout.put("nodes", exitWorkflowNodes());
            layout.put("edges", exitWorkflowEdges());
            return layout;
        }
        if ("portfolio_workflow".equals(definition.getWorkflowKey())) {
            layout.put("nodes", portfolioWorkflowNodes());
            layout.put("edges", portfolioWorkflowEdges());
            return layout;
        }
        if ("position_workflow".equals(definition.getWorkflowKey())) {
            layout.put("nodes", positionWorkflowNodes());
            layout.put("edges", positionWorkflowEdges());
            return layout;
        }
        if ("sector-analyst-workflow".equals(definition.getWorkflowKey())) {
            layout.put("nodes", sectorAnalystNodes());
            layout.put("edges", sectorAnalystEdges());
            return layout;
        }
        if ("telegram_hourly_news_digest".equals(definition.getWorkflowKey())) {
            layout.put("nodes", telegramDigestNodes());
            layout.put("edges", telegramDigestEdges());
            return layout;
        }
        if ("earnings_reaction".equals(definition.getWorkflowKey())) {
            layout.put("nodes", earningsReactionNodes());
            layout.put("edges", earningsReactionEdges());
            attachResearchLayoutMetadata(layout, "earnings_reaction");
            return layout;
        }
        if ("watchlist_digest".equals(definition.getWorkflowKey())) {
            layout.put("nodes", watchlistDigestNodes());
            layout.put("edges", watchlistDigestEdges());
            attachResearchLayoutMetadata(layout, "watchlist_digest");
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

    private void attachResearchLayoutMetadata(Map<String, Object> layout, String pattern) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("layoutVersion", CURRENT_RESEARCH_LAYOUT_VERSION);
        metadata.put("pattern", pattern);
        metadata.put("custom", false);
        layout.put("metadata", metadata);
    }

    private boolean isResearchLayoutKey(String workflowKey) {
        return workflowKey != null && RESEARCH_LAYOUT_KEYS.contains(workflowKey.trim());
    }

    /**
     * Research keys without layoutVersion / with version &lt; CURRENT are treated as stale
     * code defaults. User-marked custom layouts and version-stamped saves are never auto-refreshed.
     */
    @SuppressWarnings("unchecked")
    private boolean shouldRefreshResearchLayout(String workflowKey, Map<String, Object> layout) {
        if (!isResearchLayoutKey(workflowKey) || layout == null) {
            return false;
        }
        Object metaObj = layout.get("metadata");
        if (!(metaObj instanceof Map)) {
            return true;
        }
        Map<String, Object> metadata = (Map<String, Object>) metaObj;
        if (isCustomLayout(metadata)) {
            return false;
        }
        return layoutVersionOf(metadata) < CURRENT_RESEARCH_LAYOUT_VERSION;
    }

    private boolean isCustomLayout(Map<String, Object> metadata) {
        Object custom = metadata.get("custom");
        if (custom instanceof Boolean) {
            return Boolean.TRUE.equals(custom);
        }
        return custom != null && "true".equalsIgnoreCase(String.valueOf(custom).trim());
    }

    private int layoutVersionOf(Map<String, Object> metadata) {
        Object version = metadata.get("layoutVersion");
        if (version == null) {
            return 0;
        }
        if (version instanceof Number) {
            return ((Number) version).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(version).trim());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    @SuppressWarnings("unchecked")
    private void ensureLayoutVersionMetadata(Map<String, Object> layout, String workflowKey) {
        Object metaObj = layout.get("metadata");
        Map<String, Object> metadata;
        if (metaObj instanceof Map) {
            metadata = new LinkedHashMap<>((Map<String, Object>) metaObj);
        } else {
            metadata = new LinkedHashMap<>();
        }
        metadata.put("layoutVersion", CURRENT_RESEARCH_LAYOUT_VERSION);
        if (isResearchLayoutKey(workflowKey) && !metadata.containsKey("pattern")) {
            metadata.put("pattern", researchPattern(workflowKey));
        }
        layout.put("metadata", metadata);
    }

    private String researchPattern(String workflowKey) {
        if ("stock_analysis".equals(workflowKey)) {
            return "fan_in_aggregate";
        }
        if ("earnings_reaction".equals(workflowKey)) {
            return "earnings_reaction";
        }
        if ("watchlist_digest".equals(workflowKey)) {
            return "watchlist_digest";
        }
        return workflowKey;
    }

    /** Optional setter injection keeps the existing constructor contract for tests. */
    @Autowired(required = false)
    public void setWorkflowExecutor(@Qualifier(AsyncConfig.WORKFLOW_EXECUTOR) Executor workflowExecutor) {
        if (workflowExecutor != null) {
            this.workflowExecutor = workflowExecutor;
        }
    }

    private void maybePersistRefreshedLayout(String workflowKey, Map<String, Object> refreshed) {
        if (!refreshDefaultLayouts) {
            return;
        }
        Map<String, Object> snapshot = new LinkedHashMap<>(refreshed);
        workflowExecutor.execute(() -> {
            try {
                saveLayout(workflowKey, snapshot);
            } catch (Exception ignored) {
                // best-effort write-back; read-path already returns the code default
            }
        });
    }

    private List<Map<String, Object>> stockRecommendationNodes() {
        List<Map<String, Object>> nodes = new ArrayList<>();
        nodes.add(flowNode("start", "Start", "start", 80, 260, "scheduler.manual", null));
        nodes.add(flowNode("market_analysis", "市场分析", "agent", 320, 120, "finance.market_analysis", null));
        nodes.add(flowNode("industry_share", "行业份额", "agent", 320, 260, "finance.industry_share", null));
        nodes.add(flowNode("sentiment_monitor", "舆情监测", "agent", 320, 400, "finance.sentiment_monitor", null));
        nodes.add(flowNode("tech_breakthrough", "技术突破", "agent", 590, 120, "finance.tech_breakthrough", null));
        nodes.add(flowNode("industry_news", "行业新闻", "agent", 590, 260, "finance.industry_news", null));
        nodes.add(flowNode("web_search", "网页搜索", "agent", 590, 400, "general.web_search", null));
        nodes.add(flowNode("financial_interpretation", "财务解读", "agent", 860, 260, "finance.financial_interpretation", null));
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

    // Aligned with research_graph.build_single_name_research_layout (sequential multi-specialist).
    private List<Map<String, Object>> stockAnalysisNodes() {
        List<Map<String, Object>> nodes = new ArrayList<>();
        nodes.add(flowNode("start", "Start", "start", 80, 260, "scheduler.manual", null));
        nodes.add(flowNode("fundamentals", "Fundamentals", "agent", 320, 260, "finance.fundamental_analysis", null));
        nodes.add(flowNode("news", "Industry News", "agent", 560, 260, "finance.industry_news", null));
        nodes.add(flowNode("valuation", "Valuation", "agent", 800, 260, "finance.valuation_analysis", null));
        nodes.add(flowNode("risk", "Risk Assessment", "agent", 1040, 260, "finance.risk_assessment", null));
        nodes.add(flowNode("aggregate", "Recommendation Aggregate", "agent", 1280, 260, "finance.stock_recommendation_aggregate", null));
        nodes.add(flowNode("end", "End", "end", 1520, 260, "workflow.end", null));
        return nodes;
    }

    private List<Map<String, Object>> stockAnalysisEdges() {
        List<Map<String, Object>> edges = new ArrayList<>();
        edges.add(edge("start", "fundamentals"));
        edges.add(edge("fundamentals", "news"));
        edges.add(edge("news", "valuation"));
        edges.add(edge("valuation", "risk"));
        edges.add(edge("risk", "aggregate"));
        edges.add(edge("aggregate", "end"));
        return edges;
    }

    // ===== daily =====
    private List<Map<String, Object>> dailyNodes() {
        List<Map<String, Object>> nodes = new ArrayList<>();
        nodes.add(flowNode("start", "Start", "start", 80, 260, "scheduler.manual", null));
        nodes.add(flowNode("market_overview", "Market Overview", "agent", 320, 120, "finance.market_analysis", null));
        nodes.add(flowNode("sector_rotation", "Sector Rotation", "agent", 320, 400, "finance.industry_share", null));
        nodes.add(flowNode("sentiment_pulse", "Sentiment Pulse", "agent", 600, 120, "finance.sentiment_monitor", null));
        nodes.add(flowNode("key_indicators", "Key Indicators", "agent", 600, 400, "finance.financial_interpretation", null));
        nodes.add(flowNode("daily_summary", "Daily Briefing", "agent", 880, 260, "finance.stock_recommendation_aggregate", null));
        nodes.add(flowNode("end", "End", "end", 1140, 260, "workflow.end", null));
        return nodes;
    }
    private List<Map<String, Object>> dailyEdges() {
        List<Map<String, Object>> edges = new ArrayList<>();
        edges.add(edge("start", "market_overview"));
        edges.add(edge("start", "sector_rotation"));
        edges.add(edge("market_overview", "sentiment_pulse"));
        edges.add(edge("sector_rotation", "key_indicators"));
        edges.add(edge("sentiment_pulse", "daily_summary"));
        edges.add(edge("key_indicators", "daily_summary"));
        edges.add(edge("daily_summary", "end"));
        return edges;
    }

    // ===== deep_dive =====
    private List<Map<String, Object>> deepDiveNodes() {
        List<Map<String, Object>> nodes = new ArrayList<>();
        nodes.add(flowNode("start", "Start", "start", 80, 360, "scheduler.manual", null));
        nodes.add(flowNode("fundamental", "Fundamental", "agent", 260, 100, "finance.fundamental_analysis", "agent-preset-fundamental"));
        nodes.add(flowNode("technical", "Technical", "agent", 260, 280, "finance.technical_analysis", "agent-preset-technical"));
        nodes.add(flowNode("valuation", "Valuation", "agent", 260, 460, "finance.valuation_analysis", "agent-preset-value-investing"));
        nodes.add(flowNode("money_flow", "Money Flow", "agent", 480, 100, "finance.money_flow_analysis", null));
        nodes.add(flowNode("industry", "Industry", "agent", 480, 280, "finance.industry_share", null));
        nodes.add(flowNode("sentiment", "Sentiment", "agent", 480, 460, "finance.sentiment_monitor", null));
        nodes.add(flowNode("news", "News", "agent", 700, 100, "finance.industry_news", null));
        nodes.add(flowNode("tech_break", "Tech Breakthrough", "agent", 700, 280, "finance.tech_breakthrough", null));
        nodes.add(flowNode("risk", "Risk Assessment", "agent", 700, 460, "finance.risk_assessment", "agent-preset-risk-exit"));
        nodes.add(flowNode("peer_comp", "Peer Comparison", "agent", 920, 100, "finance.peer_comparison", null));
        nodes.add(flowNode("catalysts", "Catalyst Analysis", "agent", 920, 280, "finance.catalyst_analysis", null));
        nodes.add(flowNode("thesis", "Thesis Builder", "agent", 920, 460, "finance.thesis_builder", null));
        nodes.add(flowNode("risk_reward", "Risk-Reward", "agent", 1140, 180, "finance.risk_reward_analysis", null));
        nodes.add(flowNode("entry", "Entry Strategy", "agent", 1140, 380, "finance.entry_strategy", "agent-preset-price-action"));
        nodes.add(flowNode("recommendation", "Recommendation", "agent", 1360, 280, "finance.stock_recommendation_aggregate", null));
        nodes.add(flowNode("end", "End", "end", 1580, 280, "workflow.end", null));
        return nodes;
    }
    private List<Map<String, Object>> deepDiveEdges() {
        List<Map<String, Object>> edges = new ArrayList<>();
        edges.add(edge("start", "fundamental"));
        edges.add(edge("start", "technical"));
        edges.add(edge("start", "valuation"));
        edges.add(edge("fundamental", "money_flow"));
        edges.add(edge("technical", "industry"));
        edges.add(edge("valuation", "sentiment"));
        edges.add(edge("money_flow", "news"));
        edges.add(edge("industry", "tech_break"));
        edges.add(edge("sentiment", "risk"));
        edges.add(edge("news", "peer_comp"));
        edges.add(edge("tech_break", "catalysts"));
        edges.add(edge("risk", "thesis"));
        edges.add(edge("peer_comp", "risk_reward"));
        edges.add(edge("catalysts", "risk_reward"));
        edges.add(edge("thesis", "entry"));
        edges.add(edge("risk_reward", "recommendation"));
        edges.add(edge("entry", "recommendation"));
        edges.add(edge("recommendation", "end"));
        return edges;
    }

    // ===== exit_workflow =====
    private List<Map<String, Object>> exitWorkflowNodes() {
        List<Map<String, Object>> nodes = new ArrayList<>();
        nodes.add(flowNode("start", "Start", "start", 80, 260, "scheduler.manual", null));
        nodes.add(flowNode("position_check", "Position Check", "logic", 300, 140, "portfolio.get_positions", null));
        nodes.add(flowNode("pnl_analysis", "P&L Analysis", "agent", 300, 380, "finance.market_analysis", null));
        nodes.add(flowNode("stop_loss", "Stop Loss Review", "agent", 540, 140, "general.agent", null));
        nodes.add(flowNode("take_profit", "Take Profit Review", "agent", 540, 380, "general.agent", null));
        nodes.add(flowNode("signal_decay", "Signal Decay", "agent", 780, 140, "finance.risk_assessment", null));
        nodes.add(flowNode("news_risk", "News Risk Scan", "agent", 780, 380, "finance.industry_news", null));
        nodes.add(flowNode("exit_decision", "Exit Decision", "agent", 1020, 260, "finance.stock_recommendation_aggregate", null));
        nodes.add(flowNode("end", "End", "end", 1260, 260, "workflow.end", null));
        return nodes;
    }
    private List<Map<String, Object>> exitWorkflowEdges() {
        List<Map<String, Object>> edges = new ArrayList<>();
        edges.add(edge("start", "position_check"));
        edges.add(edge("start", "pnl_analysis"));
        edges.add(edge("position_check", "stop_loss"));
        edges.add(edge("pnl_analysis", "take_profit"));
        edges.add(edge("stop_loss", "signal_decay"));
        edges.add(edge("take_profit", "news_risk"));
        edges.add(edge("signal_decay", "exit_decision"));
        edges.add(edge("news_risk", "exit_decision"));
        edges.add(edge("exit_decision", "end"));
        return edges;
    }

    // ===== portfolio_workflow =====
    private List<Map<String, Object>> portfolioWorkflowNodes() {
        List<Map<String, Object>> nodes = new ArrayList<>();
        nodes.add(flowNode("start", "Start", "start", 80, 260, "scheduler.manual", null));
        nodes.add(flowNode("holdings", "Holdings Overview", "logic", 320, 140, "portfolio.get_context", null));
        nodes.add(flowNode("market_scan", "Market Scan", "agent", 320, 380, "finance.market_analysis", null));
        nodes.add(flowNode("sector_exposure", "Sector Exposure", "agent", 580, 140, "finance.industry_share", null));
        nodes.add(flowNode("risk_metrics", "Risk Metrics", "agent", 580, 380, "finance.risk_assessment", null));
        nodes.add(flowNode("rebalance", "Rebalancing Plan", "agent", 840, 260, "finance.stock_recommendation_aggregate", null));
        nodes.add(flowNode("end", "End", "end", 1100, 260, "workflow.end", null));
        return nodes;
    }
    private List<Map<String, Object>> portfolioWorkflowEdges() {
        List<Map<String, Object>> edges = new ArrayList<>();
        edges.add(edge("start", "holdings"));
        edges.add(edge("start", "market_scan"));
        edges.add(edge("holdings", "sector_exposure"));
        edges.add(edge("market_scan", "risk_metrics"));
        edges.add(edge("sector_exposure", "rebalance"));
        edges.add(edge("risk_metrics", "rebalance"));
        edges.add(edge("rebalance", "end"));
        return edges;
    }

    // ===== position_workflow =====
    private List<Map<String, Object>> positionWorkflowNodes() {
        List<Map<String, Object>> nodes = new ArrayList<>();
        nodes.add(flowNode("start", "Start", "start", 80, 340, "scheduler.manual", null));
        nodes.add(flowNode("holdings", "Load Holdings", "logic", 260, 340, "portfolio.get_positions", null));
        nodes.add(flowNode("pnl", "P&L Analysis", "agent", 440, 140, "finance.market_analysis", null));
        nodes.add(flowNode("cost_basis", "Cost Basis", "agent", 440, 340, "finance.financial_interpretation", null));
        nodes.add(flowNode("duration", "Duration Analysis", "agent", 440, 540, "finance.technical_analysis", null));
        nodes.add(flowNode("correlation", "Correlation", "agent", 640, 140, "finance.valuation_analysis", null));
        nodes.add(flowNode("concentration", "Concentration Risk", "agent", 640, 340, "finance.risk_assessment", null));
        nodes.add(flowNode("sector_break", "Sector Breakdown", "agent", 640, 540, "finance.industry_share", null));
        nodes.add(flowNode("money_flow", "Cash Flow", "agent", 840, 140, "finance.money_flow_analysis", null));
        nodes.add(flowNode("sentiment", "Position Sentiment", "agent", 840, 340, "finance.sentiment_monitor", null));
        nodes.add(flowNode("tax_impact", "Tax Impact", "agent", 840, 540, "general.agent", null));
        nodes.add(flowNode("hedge", "Hedge Ideas", "agent", 1040, 140, "general.agent", null));
        nodes.add(flowNode("action_items", "Action Items", "agent", 1040, 440, "general.agent", null));
        nodes.add(flowNode("aggregate", "Portfolio Summary", "agent", 1260, 340, "finance.stock_recommendation_aggregate", null));
        nodes.add(flowNode("end", "End", "end", 1480, 340, "workflow.end", null));
        return nodes;
    }
    private List<Map<String, Object>> positionWorkflowEdges() {
        List<Map<String, Object>> edges = new ArrayList<>();
        edges.add(edge("start", "holdings"));
        edges.add(edge("holdings", "pnl"));
        edges.add(edge("holdings", "cost_basis"));
        edges.add(edge("holdings", "duration"));
        edges.add(edge("pnl", "correlation"));
        edges.add(edge("cost_basis", "concentration"));
        edges.add(edge("duration", "sector_break"));
        edges.add(edge("correlation", "money_flow"));
        edges.add(edge("concentration", "sentiment"));
        edges.add(edge("sector_break", "tax_impact"));
        edges.add(edge("money_flow", "hedge"));
        edges.add(edge("sentiment", "action_items"));
        edges.add(edge("tax_impact", "action_items"));
        edges.add(edge("hedge", "aggregate"));
        edges.add(edge("action_items", "aggregate"));
        edges.add(edge("aggregate", "end"));
        return edges;
    }

    // ===== sector-analyst-workflow =====
    private List<Map<String, Object>> sectorAnalystNodes() {
        List<Map<String, Object>> nodes = new ArrayList<>();
        nodes.add(flowNode("start", "Start", "start", 80, 340, "scheduler.manual", null));
        nodes.add(flowNode("macro", "Macro Environment", "agent", 280, 100, "general.agent", null));
        nodes.add(flowNode("industry_chain", "Industry Chain", "agent", 280, 340, "finance.industry_share", null));
        nodes.add(flowNode("policy", "Policy Impact", "agent", 280, 580, "general.agent", null));
        nodes.add(flowNode("competitive", "Competitive Map", "agent", 520, 100, "finance.industry_news", null));
        nodes.add(flowNode("top_players", "Top Players", "agent", 520, 340, "finance.fundamental_analysis", null));
        nodes.add(flowNode("tech_trends", "Tech Trends", "agent", 520, 580, "finance.tech_breakthrough", null));
        nodes.add(flowNode("valuation_band", "Valuation Band", "agent", 760, 100, "finance.valuation_analysis", null));
        nodes.add(flowNode("sentiment", "Sector Sentiment", "agent", 760, 340, "finance.sentiment_monitor", null));
        nodes.add(flowNode("money_flow", "Capital Flow", "agent", 760, 580, "finance.money_flow_analysis", null));
        nodes.add(flowNode("risk", "Sector Risk", "agent", 1000, 100, "finance.risk_assessment", null));
        nodes.add(flowNode("catalysts", "Catalyst Calendar", "agent", 1000, 340, "general.agent", null));
        nodes.add(flowNode("rotation", "Rotation Signal", "agent", 1000, 580, "finance.market_analysis", null));
        nodes.add(flowNode("recommendation", "Sector Call", "agent", 1240, 340, "finance.stock_recommendation_aggregate", null));
        nodes.add(flowNode("end", "End", "end", 1480, 340, "workflow.end", null));
        return nodes;
    }
    private List<Map<String, Object>> sectorAnalystEdges() {
        List<Map<String, Object>> edges = new ArrayList<>();
        edges.add(edge("start", "macro"));
        edges.add(edge("start", "industry_chain"));
        edges.add(edge("start", "policy"));
        edges.add(edge("macro", "competitive"));
        edges.add(edge("industry_chain", "top_players"));
        edges.add(edge("policy", "tech_trends"));
        edges.add(edge("competitive", "valuation_band"));
        edges.add(edge("top_players", "sentiment"));
        edges.add(edge("tech_trends", "money_flow"));
        edges.add(edge("valuation_band", "risk"));
        edges.add(edge("sentiment", "catalysts"));
        edges.add(edge("money_flow", "rotation"));
        edges.add(edge("risk", "recommendation"));
        edges.add(edge("catalysts", "recommendation"));
        edges.add(edge("rotation", "recommendation"));
        edges.add(edge("recommendation", "end"));
        return edges;
    }

    // ===== telegram_hourly_news_digest =====
    private List<Map<String, Object>> telegramDigestNodes() {
        List<Map<String, Object>> nodes = new ArrayList<>();
        nodes.add(flowNode("start", "Start", "start", 80, 200, "scheduler.manual", null));
        nodes.add(flowNode("news_fetch", "Fetch News", "agent", 320, 200, "general.web_search", null));
        nodes.add(flowNode("sentiment_filter", "Sentiment Filter", "agent", 560, 200, "finance.sentiment_monitor", null));
        nodes.add(flowNode("end", "End", "end", 800, 200, "workflow.end", null));
        return nodes;
    }
    private List<Map<String, Object>> telegramDigestEdges() {
        List<Map<String, Object>> edges = new ArrayList<>();
        edges.add(edge("start", "news_fetch"));
        edges.add(edge("news_fetch", "sentiment_filter"));
        edges.add(edge("sentiment_filter", "end"));
        return edges;
    }

    // Aligned with research_graph.build_earnings_reaction_layout.
    private List<Map<String, Object>> earningsReactionNodes() {
        List<Map<String, Object>> nodes = new ArrayList<>();
        nodes.add(flowNode("start", "Start", "start", 80, 260, "scheduler.manual", null));
        nodes.add(flowNode("market_analysis", "Market Reaction", "agent", 320, 260, "finance.market_analysis", null));
        nodes.add(flowNode("financial_interpretation", "Earnings Interpretation", "agent", 560, 260, "finance.financial_interpretation", null));
        nodes.add(flowNode("industry_news", "Industry & Guidance News", "agent", 800, 260, "finance.industry_news", null));
        nodes.add(flowNode("aggregate", "Earnings Recommendation", "agent", 1040, 260, "finance.stock_recommendation_aggregate", null));
        nodes.add(flowNode("end", "End", "end", 1280, 260, "workflow.end", null));
        return nodes;
    }
    private List<Map<String, Object>> earningsReactionEdges() {
        List<Map<String, Object>> edges = new ArrayList<>();
        edges.add(edge("start", "market_analysis"));
        edges.add(edge("market_analysis", "financial_interpretation"));
        edges.add(edge("financial_interpretation", "industry_news"));
        edges.add(edge("industry_news", "aggregate"));
        edges.add(edge("aggregate", "end"));
        return edges;
    }

    // Aligned with research_graph.build_watchlist_digest_layout (lightweight, no aggregate).
    private List<Map<String, Object>> watchlistDigestNodes() {
        List<Map<String, Object>> nodes = new ArrayList<>();
        nodes.add(flowNode("start", "Start", "start", 80, 260, "scheduler.manual", null));
        nodes.add(flowNode("market_analysis", "Watchlist Market Snapshot", "agent", 320, 260, "finance.market_analysis", null));
        nodes.add(flowNode("industry_news", "Watchlist News", "agent", 560, 260, "finance.industry_news", null));
        nodes.add(flowNode("risk_assessment", "Digest Risk Summary", "agent", 800, 260, "finance.risk_assessment", null));
        nodes.add(flowNode("end", "End", "end", 1040, 260, "workflow.end", null));
        return nodes;
    }
    private List<Map<String, Object>> watchlistDigestEdges() {
        List<Map<String, Object>> edges = new ArrayList<>();
        edges.add(edge("start", "market_analysis"));
        edges.add(edge("market_analysis", "industry_news"));
        edges.add(edge("industry_news", "risk_assessment"));
        edges.add(edge("risk_assessment", "end"));
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
