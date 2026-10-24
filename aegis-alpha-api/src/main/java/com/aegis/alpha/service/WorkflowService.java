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
import org.springframework.dao.DuplicateKeyException;
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

    /** P3-10: default layouts and their version metadata live in {@link DefaultWorkflowLayouts}. */
    static final int CURRENT_RESEARCH_LAYOUT_VERSION = DefaultWorkflowLayouts.CURRENT_RESEARCH_LAYOUT_VERSION;
    static final String RECOMMENDATION_AGGREGATE_HANDLER = DefaultWorkflowLayouts.RECOMMENDATION_AGGREGATE_HANDLER;
    static final String DIGEST_AGGREGATE_HANDLER = DefaultWorkflowLayouts.DIGEST_AGGREGATE_HANDLER;
    static final String DIGEST_LAYOUT_PATTERN = DefaultWorkflowLayouts.DIGEST_LAYOUT_PATTERN;
    /** Short-lived portfolio:read delegation for orchestrator tool calls (15 minutes). */
    static final long DELEGATED_PORTFOLIO_READ_TTL_MS = 15L * 60L * 1000L;
    static final String SCOPE_PORTFOLIO_READ = "portfolio:read";

    /** P3-16: default model for inline agents without data.modelName (待确认: falls back to aegis.langchain.model). */
    @Value("${aegis.agent.default-model:deepseek-v4-flash}")
    private String defaultModelName = "deepseek-v4-flash";

    void setDefaultModelName(String defaultModelName) {
        if (defaultModelName != null && !defaultModelName.trim().isEmpty()) {
            this.defaultModelName = defaultModelName.trim();
        }
    }

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
    /** P3-10: approval gate (decide / approver roles); plain collaborator so the constructor contract is unchanged. */
    private final WorkflowApprovalService approval;

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
        this.approval = new WorkflowApprovalService(this, mapper, langChainGateway);
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
        saveLayout(definition.getWorkflowKey(), DefaultWorkflowLayouts.defaultLayout(definition));
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

    /** P2-23: default and maximum page size of the run list. */
    static final int DEFAULT_RUN_PAGE_SIZE = 50;
    static final int MAX_RUN_PAGE_SIZE = 200;

    public List<WorkflowRun> runs() {
        return runs(null, null);
    }

    /** Unscoped page (internal/test callers). */
    public List<WorkflowRun> runs(Integer limit, Integer offset) {
        return hydrate(mapper.findRunsPage(null, null, true, pageLimit(limit), pageOffset(offset)));
    }

    /**
     * Owner-scoped run list. A null principal keeps the legacy unscoped list for
     * internal/test callers; HTTP callers always pass a principal.
     */
    public List<WorkflowRun> runsForPrincipal(AuthenticatedPrincipal principal) {
        return runsForPrincipal(principal, null, null);
    }

    /** P2-23: paged (default 50, max 200), newest first; owners are filtered in SQL so pages are exact. */
    public List<WorkflowRun> runsForPrincipal(AuthenticatedPrincipal principal, Integer limit, Integer offset) {
        if (principal == null) {
            return runs(limit, offset);
        }
        boolean admin = principal.hasRole("admin");
        List<WorkflowRun> page = mapper.findRunsPage(principal.getUserId(), principal.getTenantId(), admin,
                pageLimit(limit), pageOffset(offset));
        List<WorkflowRun> visible = new ArrayList<WorkflowRun>();
        for (WorkflowRun run : page == null ? Collections.<WorkflowRun>emptyList() : page) {
            if (canAccess(run, principal)) {
                visible.add(run);
            }
        }
        return hydrate(visible);
    }

    private List<WorkflowRun> hydrate(List<WorkflowRun> runs) {
        if (runs == null) {
            return new ArrayList<WorkflowRun>();
        }
        for (WorkflowRun run : runs) {
            hydrateRunContract(run);
        }
        return runs;
    }

    static int pageLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_RUN_PAGE_SIZE;
        }
        return Math.min(limit, MAX_RUN_PAGE_SIZE);
    }

    static int pageOffset(Integer offset) {
        return offset == null || offset < 0 ? 0 : offset;
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
        WorkflowRunEvent event = mapper.findLastRunEvent(runId);
        if (event == null) {
            return null;
        }
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
        validationService.validateExecutableFields(currentLayout);
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
            Map<String, Object> fallback = definition == null ? DefaultWorkflowLayouts.emptyLayout(workflowKey) : DefaultWorkflowLayouts.defaultLayout(definition);
            fallback.put("updatedAt", null);
            return fallback;
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(layout.getLayoutJson(), new TypeReference<Map<String, Object>>() {});
            if (DefaultWorkflowLayouts.shouldRefreshResearchLayout(workflowKey, parsed)) {
                WorkflowDefinition definition = mapper.findDefinition(workflowKey);
                Map<String, Object> refreshed = definition == null ? DefaultWorkflowLayouts.emptyLayout(workflowKey) : DefaultWorkflowLayouts.defaultLayout(definition);
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
        validationService.validateExecutableFields(body);
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
        DefaultWorkflowLayouts.ensureLayoutVersionMetadata(layout, workflowKey);
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
        String key = workflowKeyOrDefault(workflowKey);
        WorkflowVersion version = mapper.findLatestVersion(key);
        Map<String, Object> runLayout = layoutForRun(key, version);
        validationService.validateLayout(runLayout);
        Map<String, Object> safeInputs = inputs == null ? new LinkedHashMap<String, Object>() : new LinkedHashMap<>(inputs);
        applyAuthenticatedUserId(safeInputs, principal == null ? null : principal.getUserId());
        WorkflowRun run = newRun(key, subject, version, safeInputs, false, null);
        applyRunOwner(run, principal);
        mapper.insertRun(run);
        recordRunEvent(run, "RUN_CREATED", null, null, "RUNNING", "Workflow run created.", null, 0);
        return run;
    }

    /**
     * Executes a run created by {@link #createRun} (Copilot dispatch). P3-13: the layout comes from the
     * version recorded on the run ({@code workflowVersionId}), never from whatever is latest now, so a
     * version published between create and execute does not change what runs. Success/failure handling
     * (including the backtest record) is shared with {@link #start} and {@link #dispatchQueuedRun}.
     */
    public void executeAsync(WorkflowRun run, Map<String, Object> inputs, String workflowKey) {
        String key = run.getWorkflowKey() == null || run.getWorkflowKey().trim().isEmpty()
                ? workflowKeyOrDefault(workflowKey) : run.getWorkflowKey();
        Map<String, Object> runLayout;
        try {
            runLayout = layoutForRun(key, run.getWorkflowVersionId());
        } catch (Exception ex) {
            markRunFailed(run, ex);
            materializeRunOutcome(mapper.findRun(run.getRunId()), inputs, null);
            return;
        }
        executeAndFinish(run, inputs, runLayout, inputs);
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
        String key = workflowKeyOrDefault(workflowKey);
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
        WorkflowRun run = newRun(key, subject, version, safeInputs, false, normalizedIdempotencyKey);
        applyRunOwner(run, principal);
        WorkflowRun raced = insertRunOrExisting(run, principal);
        if (raced != null) {
            return raced;
        }
        recordRunEvent(run, "RUN_CREATED", null, null, "RUNNING", "Workflow run created.", null, 0);
        return executeAndFinish(run, safeInputs, runLayout, inputs);
    }

    /**
     * P3-14: inserts an idempotent run. When a concurrent request with the same
     * (workflow_key, subject, idempotency_key) won the unique index, returns that run instead
     * (null means this run was inserted). A key already used by a run the caller cannot access is
     * rejected (待确认: previously a second run was created).
     */
    WorkflowRun insertRunOrExisting(WorkflowRun run, AuthenticatedPrincipal principal) {
        try {
            mapper.insertRun(run);
            return null;
        } catch (DuplicateKeyException duplicate) {
            if (run.getIdempotencyKey() == null) {
                throw duplicate;
            }
            WorkflowRun existing = mapper.findRunByIdempotencyKey(run.getWorkflowKey(), run.getSubject(), run.getIdempotencyKey());
            if (existing == null) {
                throw duplicate;
            }
            if (principal != null && !isRunAccessible(existing, principal)) {
                throw new IllegalArgumentException("Idempotency key already used by another run.");
            }
            log.info("Idempotent run race resolved: runId={} existingRunId={}", run.getRunId(), existing.getRunId());
            return existing;
        }
    }

    /**
     * P3-13: the one place a new {@code workflow_run} row is built (create/start/streaming/queue).
     * Callers still apply the owner and insert; status is RUNNING, or QUEUED when {@code queued}.
     */
    private WorkflowRun newRun(String key, String subject, WorkflowVersion version, Map<String, Object> safeInputs,
                               boolean queued, String idempotencyKey) {
        String timestamp = now();
        WorkflowRun run = new WorkflowRun();
        run.setRunId(UUID.randomUUID().toString());
        run.setWorkflowKey(key);
        run.setTraceId(UUID.randomUUID().toString());
        run.setStatus(queued ? "QUEUED" : "RUNNING");
        run.setSubject(subject);
        run.setStartedAt(timestamp);
        if (queued) {
            run.setQueuedAt(timestamp);
        }
        run.setNodeCount(0);
        run.setIdempotencyKey(idempotencyKey);
        run.setWorkflowVersionId(version == null ? null : version.getVersionId());
        run.setInputsJson(toJson(safeInputs));
        run.setControlStatus(queued ? "QUEUED" : "ACTIVE");
        run.setPauseRequested(0);
        run.setCancelRequested(0);
        return run;
    }

    private static String workflowKeyOrDefault(String workflowKey) {
        return workflowKey == null || workflowKey.trim().isEmpty() ? "daily" : workflowKey.trim();
    }

    /**
     * P3-13: shared execution + terminal handling for {@link #start}, {@link #dispatchQueuedRun} and
     * {@link #executeAsync}. A backtest record is created for both completed and failed runs (the
     * behaviour {@code start()} already had); stopped (paused/cancelled) runs are returned as stored.
     */
    private WorkflowRun executeAndFinish(WorkflowRun run, Map<String, Object> executionInputs,
                                         Map<String, Object> runLayout, Map<String, Object> backtestInputs) {
        try {
            runWorkflow(run, executionInputs, runLayout);
            WorkflowRun completed = mapper.findRun(run.getRunId());
            materializeRunOutcome(completed, backtestInputs, runLayout);
            return completed;
        } catch (WorkflowStoppedException stopped) {
            return mapper.findRun(run.getRunId());
        } catch (Exception ex) {
            markRunFailed(run, ex);
            WorkflowRun failed = mapper.findRun(run.getRunId());
            materializeRunOutcome(failed, backtestInputs, runLayout);
            return failed;
        }
    }

    /**
     * P3-16: single-ticker workflows get a backtest + recommendation record; digest layouts
     * (closer = {@link #DIGEST_AGGREGATE_HANDLER}, no recommendation aggregate) only get their
     * model-call / evidence trace materialized, never a BUY/SELL recommendation or backtest row.
     */
    private void materializeRunOutcome(WorkflowRun run, Map<String, Object> inputs, Map<String, Object> layout) {
        if (run == null) {
            return;
        }
        if (isDigestLayout(layout)) {
            backtestService.materializeRunTrace(run);
            return;
        }
        backtestService.createFromWorkflowRun(run, inputs);
    }

    boolean isDigestLayout(Map<String, Object> layout) {
        boolean digest = false;
        for (Map<String, Object> node : castList(layout == null ? null : layout.get("nodes"))) {
            String handler = handler(node);
            if (RECOMMENDATION_AGGREGATE_HANDLER.equals(handler)) {
                return false;
            }
            if (DIGEST_AGGREGATE_HANDLER.equals(handler)) {
                digest = true;
            }
        }
        return digest;
    }

    /**
     * P3-13 / P4-4: single FAILED write for the non-streaming paths. Conditional (CAS) on RUNNING/QUEUED and
     * no cancel request, so a failure observed after a user cancel (e.g. the orchestrator stopped the
     * graph) never overwrites CANCELLED; a cancel requested meanwhile ends the run as CANCELLED instead.
     */
    private void markRunFailed(WorkflowRun run, Exception ex) {
        run.setStatus("FAILED");
        run.setCompletedAt(now());
        run.setErrorMessage(ex.getMessage());
        run.setControlStatus("FAILED");
        if (mapper.updateRunStatusIf(run, RUNNING_OR_QUEUED, true) > 0) {
            recordRunEvent(run, "RUN_FAILED", null, null, "FAILED", ex.getMessage(), null, 1000000);
            return;
        }
        WorkflowRun latest = mapper.findRun(run.getRunId());
        if (isCancelRequested(latest)) {
            finishSyncAsCancelled(latest, "Workflow run cancelled; the orchestrator run was stopped.");
        }
    }

    static final List<String> RUNNING_OR_QUEUED = Arrays.asList("RUNNING", "QUEUED");

    /**
     * P4-4: terminal / paused writes of the synchronous orchestrator path only apply while the run is
     * still RUNNING and not cancelled (CAS). Losing the race means the user cancelled (finish as
     * CANCELLED) or something else moved the run on; either way the caller stops.
     */
    private void writeSyncRunIfRunning(WorkflowRun run) {
        if (mapper.updateRunStatusIf(run, RUNNING_ONLY, true) > 0) {
            return;
        }
        WorkflowRun latest = mapper.findRun(run.getRunId());
        if (isCancelRequested(latest)) {
            finishSyncAsCancelled(latest, "Workflow run cancelled while the orchestrator was running it.");
            throw new WorkflowStoppedException("CANCELLED");
        }
        throw new WorkflowStoppedException(latest == null ? "UNKNOWN" : String.valueOf(latest.getStatus()));
    }

    private void finishSyncAsCancelled(WorkflowRun latest, String message) {
        if (latest == null || "CANCELLED".equals(latest.getStatus())) {
            return;
        }
        latest.setStatus("CANCELLED");
        latest.setControlStatus("CANCELLED");
        latest.setCancelRequested(1);
        latest.setCompletedAt(now());
        if (mapper.updateRunStatusIf(latest, Arrays.asList("RUNNING", "PAUSED", "QUEUED"), false) > 0) {
            recordRunEvent(latest, "RUN_CANCELLED", null, null, "CANCELLED", message, null, 1000000);
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
        String key = workflowKeyOrDefault(workflowKey);
        WorkflowVersion version = mapper.findLatestVersion(key);
        Map<String, Object> runLayout = layoutForRun(key, version);
        validationService.validateLayout(runLayout);
        Map<String, Object> safeInputs = inputs == null ? new LinkedHashMap<String, Object>() : new LinkedHashMap<String, Object>(inputs);
        applyAuthenticatedUserId(safeInputs, userId);
        final WorkflowRun run = newRun(key, subject, version, safeInputs, false, null);
        if (hasText(userId)) {
            run.setUserId(userId.trim());
        }
        if (hasText(tenantId)) {
            run.setTenantId(tenantId.trim());
        }
        mapper.insertRun(run);
        recordRunEvent(run, "RUN_CREATED", null, null, "RUNNING", "Workflow run created (streaming).", null, 0);
        String streamUrl = langChainGateway.streamWorkflowUrl();
        String delegatedToken = delegatedTokenFor(run, safeInputs);
        boolean requireApproval = WorkflowApprovalService.requireApprovalRequested(safeInputs, runLayout);
        String streamBody = langChainGateway.buildStreamBody(
                runLayout, subject, safeInputs, delegatedToken, run.getRunId(), requireApproval,
                LangChainGateway.runOwner(run.getUserId(), run.getTenantId()));
        final String runId = run.getRunId();
        // P1-16: monotonic sort order for streamed node events; node_update upserts workflow_node_run.
        final AtomicInteger streamSequence = new AtomicInteger(0);
        final Map<String, WorkflowNodeRun> streamedNodeRuns = new LinkedHashMap<>();
        final Map<String, Map<String, Object>> streamLayoutNodes = new LinkedHashMap<>();
        for (Map<String, Object> layoutNode : castList(runLayout.get("nodes"))) {
            streamLayoutNodes.put(nodeId(layoutNode), layoutNode);
        }
        final SseStreamReader.Handle readerHandle = new SseStreamReader.Handle();
        final java.util.concurrent.atomic.AtomicBoolean emitterClosed = new java.util.concurrent.atomic.AtomicBoolean(false);
        final java.util.concurrent.atomic.AtomicBoolean clientGone = new java.util.concurrent.atomic.AtomicBoolean(false);
        /* P2-19: the client stream ending on its own (timeout, network error, browser closed). */
        final Runnable onClientGone = () -> {
            if (emitterClosed.get() || !clientGone.compareAndSet(false, true)) {
                return;
            }
            recordClientDisconnected(runId);
            if (cancelStreamOnDisconnect && emitterClosed.compareAndSet(false, true)) {
                finishStreamingAsCancelled(run, emitter, readerHandle);
            }
        };
        emitter.onTimeout(onClientGone);
        emitter.onError(ex -> onClientGone.run());
        emitter.onCompletion(onClientGone);
        SseStreamReader.readSse(streamUrl, streamBody, langChainGateway.serviceAuthorizationHeader(),
                new SseStreamReader.SseEventHandler() {
            /* P2-18: every terminal or paused outcome ends the client stream exactly once. */
            private void closeEmitter() {
                if (emitterClosed.compareAndSet(false, true)) {
                    try {
                        emitter.complete();
                    } catch (Exception ignored) {
                        // already completed by the container
                    }
                }
            }

            /* P2-19: a failed send means the client went away; the run itself keeps going. */
            private void send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder event) {
                if (clientGone.get() || emitterClosed.get()) {
                    return;
                }
                try {
                    emitter.send(event);
                } catch (java.io.IOException | IllegalStateException ex) {
                    onClientGone.run();
                }
            }

            /* P4-10: next time the cancel flag may be re-read (high-rate v2 events are throttled). */
            private long nextCancelCheckAt = Long.MIN_VALUE;

            /* P2-17: re-read before acting; a cancel requested meanwhile ends the stream as CANCELLED.
             * P4-10: at most one DB read per cancel-check interval unless {@code force} (terminal events). */
            private boolean stopIfCancelled(boolean force) {
                long nowMs = cancelCheckClock.getAsLong();
                if (!force && nowMs < nextCancelCheckAt) {
                    return false;
                }
                nextCancelCheckAt = nowMs + streamCancelCheckIntervalMs;
                WorkflowRun latest = mapper.findRun(runId);
                if (!isCancelRequested(latest)) {
                    return false;
                }
                emitterClosed.set(true);
                finishStreamingAsCancelled(run, emitter, readerHandle);
                return true;
            }

            /* Terminal/paused writes only apply while the run is still RUNNING and not cancelled. */
            private boolean writeIfRunning() {
                if (mapper.updateRunStatusIf(run, RUNNING_ONLY, true) > 0) {
                    return true;
                }
                WorkflowRun latest = mapper.findRun(runId);
                if (isCancelRequested(latest)) {
                    emitterClosed.set(true);
                    finishStreamingAsCancelled(run, emitter, readerHandle);
                }
                return false;
            }

            @Override public void onEvent(String eventName, String data) {
                try {
                    if (stopIfCancelled(isTerminalStreamEvent(eventName))) {
                        return;
                    }
                    if (isStreamV2Event(eventName)) {
                        /* P4-10: v2 progress events are forwarded, not persisted; only a finished tool call
                         * leaves a run event (no args, no result body). */
                        if ("tool_execution_end".equals(eventName)) {
                            Map<String, Object> audit = toolExecutionEndAudit(parseJsonMap(data));
                            recordRunEvent(run, "TOOL_EXECUTION_END", null,
                                    hasText(string(audit.get("nodeId"))) ? string(audit.get("nodeId")) : null, "RUNNING",
                                    toolExecutionEndMessage(audit), audit, streamSequence.incrementAndGet() * 100);
                        }
                        send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event()
                                .name(eventName).data(data == null ? "{}" : data));
                    } else if ("node_update".equals(eventName)) {
                        Map<String, Object> nodePayload = parseJsonMap(data);
                        WorkflowNodeRun nodeRun = upsertStreamedNodeRun(run, nodePayload, streamedNodeRuns,
                                streamLayoutNodes, streamSequence.incrementAndGet());
                        boolean nodeOk = "COMPLETED".equals(nodeRun.getStatus());
                        recordRunEvent(run, nodeOk ? "NODE_COMPLETED" : "NODE_FAILED", nodeRun, nodeRun.getNodeId(),
                                nodeRun.getStatus(), (nodeOk ? "Node completed: " : "Node failed: ") + nodeRun.getNodeName(),
                                nodePayload, nodeRun.getSortOrder() + 1);
                        recordStreamedNodeSpan(run, nodeRun, nodePayload);
                        send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event().name("node_update").data(data));
                    } else if (isMessageQueueEvent(eventName)) {
                        Map<String, Object> payload = parseJsonMap(data);
                        String eventType = messageQueueRunEventType(eventName);
                        if (eventType != null) {
                            recordRunEvent(run, eventType, null,
                                    hasText(string(payload.get("nodeId"))) ? string(payload.get("nodeId")) : null, "RUNNING",
                                    messageQueueEventMessage(eventName, payload), payload,
                                    streamSequence.incrementAndGet() * 100);
                        }
                        send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event()
                                .name(eventName).data(data == null ? "{}" : data));
                    } else if ("degraded".equals(eventName)) {
                        Map<String, Object> payload = parseJsonMap(data);
                        recordRunEvent(run, "RUN_DEGRADED", null, null, "RUNNING",
                                "Workflow run degraded (streaming).", payload, 900000);
                        send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event()
                                .name("degraded").data(data == null ? "{}" : data));
                    } else if ("human_interrupt".equals(eventName)) {
                        Map<String, Object> payload = parseJsonMap(data);
                        run.setStatus("PAUSED");
                        run.setControlStatus("HUMAN_INTERRUPT");
                        run.setPauseRequested(1);
                        run.setResultJson(data == null ? "{}" : data);
                        if (!writeIfRunning()) {
                            return;
                        }
                        recordRunEvent(run, "RUN_PAUSED", null, null, "PAUSED",
                                "Workflow paused for human interrupt (streaming).", payload, 950000);
                        send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event()
                                .name("human_interrupt").data(data == null ? "{}" : data));
                        /* The run waits for approval (resumed via decideApproval), not on this stream. */
                        closeEmitter();
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
                        } else if (result != null && !result.isEmpty()) {
                            run.setResultJson(toJson(result));
                        }
                        if (!writeIfRunning()) {
                            return;
                        }
                        if (degraded) {
                            recordRunEvent(run, "RUN_DEGRADED", null, null, "COMPLETED",
                                    "Workflow completed with degradation (streaming).", payload, 999999);
                        }
                        recordRunEvent(run, "RUN_COMPLETED", null, null, "COMPLETED",
                                degraded ? "Workflow run completed degraded (streaming)." : "Workflow run completed (streaming).",
                                payload, 1000000);
                        materializeStreamingCompletedRun(runId, safeInputs, runLayout);
                        String completeData = data == null || data.trim().isEmpty()
                                ? (degraded ? "{\"ok\":true,\"degraded\":true}" : "{\"ok\":true}")
                                : data;
                        send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event()
                                .name("workflow_complete").data(completeData));
                        closeEmitter();
                    } else if ("error".equals(eventName)) {
                        JsonNode en = objectMapper.readTree(data);
                        run.setStatus("FAILED");
                        run.setCompletedAt(now());
                        run.setErrorMessage(en.has("error") ? en.get("error").asText() : "Unknown error");
                        run.setControlStatus("FAILED");
                        if (!writeIfRunning()) {
                            return;
                        }
                        recordRunEvent(run, "RUN_FAILED", null, null, "FAILED", run.getErrorMessage(), null, 1000000);
                        send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event().name("error").data(data));
                        closeEmitter();
                    }
                } catch (Exception ex) {
                    try {
                        run.setStatus("FAILED");
                        run.setCompletedAt(now());
                        run.setControlStatus("FAILED");
                        writeIfRunning();
                    } catch (Exception ignored) {
                        // best effort
                    }
                    if (emitterClosed.compareAndSet(false, true)) {
                    try { emitter.completeWithError(ex); } catch (Exception ignored) {}
                }
                }
            }
            @Override public void onError(Exception ex) {
                try {
                    run.setStatus("FAILED"); run.setCompletedAt(now());
                    run.setErrorMessage("Stream error: " + ex.getMessage());
                    run.setControlStatus("FAILED");
                    if (writeIfRunning()) {
                        recordRunEvent(run, "RUN_FAILED", null, null, "FAILED", run.getErrorMessage(), null, 1000000);
                        send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event().name("error").data("{\"ok\":false}"));
                    }
                } catch (Exception ignored) {}
                if (emitterClosed.compareAndSet(false, true)) {
                    try { emitter.completeWithError(ex); } catch (Exception ignored) {}
                }
            }
            @Override public void onComplete() {
                WorkflowRun latest = mapper.findRun(runId);
                if (isCancelRequested(latest)) {
                    emitterClosed.set(true);
                    finishStreamingAsCancelled(run, emitter, readerHandle);
                    return;
                }
                /* P2-18: PAUSED / HUMAN_INTERRUPT and any other final state also end the client stream. */
                if (latest != null && "RUNNING".equals(latest.getStatus())) {
                    latest.setStatus("COMPLETED"); latest.setCompletedAt(now());
                    latest.setControlStatus("COMPLETED");
                    if (mapper.updateRunStatusIf(latest, RUNNING_ONLY, true) > 0) {
                        recordRunEvent(latest, "RUN_COMPLETED", null, null, "COMPLETED",
                                "Workflow run completed (streaming stream end).", null, 1000000);
                        materializeStreamingCompletedRun(runId, safeInputs, runLayout);
                    }
                }
                closeEmitter();
            }
        }, readerHandle);
        return mapper.findRun(runId);
    }

    static final List<String> RUNNING_ONLY = Collections.singletonList("RUNNING");

    private void recordClientDisconnected(String runId) {
        try {
            WorkflowRun latest = mapper.findRun(runId);
            if (latest != null) {
                recordRunEvent(latest, "CLIENT_DISCONNECTED", null, null, latest.getStatus(),
                        cancelStreamOnDisconnect
                                ? "Streaming client disconnected; run will be cancelled."
                                : "Streaming client disconnected; run continues in the background.",
                        null, 990500);
            }
        } catch (Exception ex) {
            log.warn("[WorkflowService] CLIENT_DISCONNECTED event failed runId={} error={}",
                    runId, ex.getClass().getSimpleName());
        }
    }

    private static boolean isCancelRequested(WorkflowRun run) {
        return run != null && ((run.getCancelRequested() != null && run.getCancelRequested() == 1)
                || "CANCEL_REQUESTED".equals(run.getControlStatus())
                || "CANCELLED".equals(run.getStatus()));
    }

    /**
     * End a streaming run that was cancelled while the orchestrator stream was open (P2-17).
     * The orchestrator has no cancel API, so the upstream read is stopped (connection closed),
     * the run is marked CANCELLED and the client stream is ended.
     */
    private void finishStreamingAsCancelled(WorkflowRun run, org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter,
                                            SseStreamReader.Handle readerHandle) {
        readerHandle.cancel();
        WorkflowRun latest = mapper.findRun(run.getRunId());
        if (latest != null && !"CANCELLED".equals(latest.getStatus())) {
            latest.setStatus("CANCELLED");
            latest.setControlStatus("CANCELLED");
            latest.setCancelRequested(1);
            latest.setCompletedAt(now());
            if (mapper.updateRunStatusIf(latest, Arrays.asList("RUNNING", "PAUSED"), false) > 0) {
                recordRunEvent(latest, "RUN_CANCELLED", null, null, "CANCELLED",
                        "Workflow run cancelled during streaming; upstream stream reading stopped.", null, 1000000);
            }
        }
        try {
            emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event()
                    .name("workflow_cancelled").data("{\"ok\":false,\"cancelled\":true}"));
        } catch (Exception ignored) {
            // client already gone
        }
        try {
            emitter.complete();
        } catch (Exception ignored) {
            // already completed
        }
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

    private void materializeStreamingCompletedRun(String runId, Map<String, Object> inputs, Map<String, Object> layout) {
        try {
            WorkflowRun completed = mapper.findRun(runId);
            if (completed == null || !"COMPLETED".equals(completed.getStatus())) {
                return;
            }
            materializeRunOutcome(completed, inputs, layout);
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
    /**
     * P2-21: the delegation carries the authenticated identity only (principal, or the run owner on
     * replays/dispatch). Client-supplied {@code inputs.userId/tenantId} are ignored; without an
     * identity no token is issued (the caller records DELEGATION_SKIPPED).
     */
    String issueDelegatedPortfolioReadToken(String runId, String userId, String tenantId, Map<String, Object> inputs) {
        if (tokenService == null) {
            return null;
        }
        if (!hasText(userId)) {
            log.info("[WorkflowService] portfolio:read delegation not issued: no authenticated identity runId={}", runId);
            return null;
        }
        try {
            String resolvedUserId = userId.trim();
            String resolvedTenantId = firstNonBlank(tenantId, null, "default");
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

    /** Delegation for the run owner; records DELEGATION_SKIPPED when the run has no identity. */
    private String delegatedTokenFor(WorkflowRun run, Map<String, Object> inputs) {
        String token = issueDelegatedPortfolioReadToken(run.getRunId(), run.getUserId(), run.getTenantId(), inputs);
        if (token == null && tokenService != null && !hasText(run.getUserId())) {
            recordRunEvent(run, "DELEGATION_SKIPPED", null, null, run.getStatus(),
                    "No authenticated identity; portfolio:read delegation not issued.", null, 2);
        }
        return token;
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
        String key = workflowKeyOrDefault(workflowKey);
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
        WorkflowRun run = newRun(key, subject, version, safeInputs, true, normalizedIdempotencyKey);
        applyRunOwner(run, principal);
        WorkflowRun raced = insertRunOrExisting(run, principal);
        if (raced != null) {
            return raced;
        }
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
        return executeAndFinish(run, inputs, runLayout, inputs);
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

    /** P4-10 (待确认): minimum interval between cancel-flag DB reads while streaming (terminal events always check). */
    @Value("${aegis.workflow.stream.cancel-check-interval-ms:500}")
    private long streamCancelCheckIntervalMs = 500L;

    private java.util.function.LongSupplier cancelCheckClock = System::currentTimeMillis;

    void setStreamCancelCheckIntervalMs(long intervalMs) {
        this.streamCancelCheckIntervalMs = Math.max(0L, intervalMs);
    }

    void setCancelCheckClock(java.util.function.LongSupplier clock) {
        this.cancelCheckClock = clock == null ? System::currentTimeMillis : clock;
    }

    /** P4-10: orchestrator event protocol v2 events forwarded to the client (P4-9). */
    static boolean isStreamV2Event(String eventName) {
        return "node_start".equals(eventName) || "message_delta".equals(eventName) || "token".equals(eventName)
                || "tool_execution_start".equals(eventName) || "tool_execution_end".equals(eventName)
                || "usage".equals(eventName);
    }

    static boolean isTerminalStreamEvent(String eventName) {
        return "workflow_complete".equals(eventName) || "error".equals(eventName)
                || "human_interrupt".equals(eventName) || "cancelled".equals(eventName);
    }

    /** Persisted subset of a tool_execution_end event: identity, outcome and timing only. */
    static Map<String, Object> toolExecutionEndAudit(Map<String, Object> payload) {
        Map<String, Object> audit = new LinkedHashMap<>();
        for (String key : new String[] {"toolCallId", "toolName", "nodeId", "isError", "durationMs", "truncated"}) {
            if (payload != null && payload.get(key) != null) {
                audit.put(key, payload.get(key));
            }
        }
        return audit;
    }

    private static String toolExecutionEndMessage(Map<String, Object> audit) {
        String name = audit.get("toolName") == null ? "tool" : String.valueOf(audit.get("toolName"));
        return (Boolean.TRUE.equals(audit.get("isError")) ? "Tool failed: " : "Tool completed: ") + name;
    }

    /** P2-19 (待确认): stop reading and cancel the run when the SSE client goes away. */
    @Value("${aegis.workflow.stream.cancel-on-disconnect:false}")
    private boolean cancelStreamOnDisconnect = false;

    void setCancelStreamOnDisconnect(boolean cancelStreamOnDisconnect) {
        this.cancelStreamOnDisconnect = cancelStreamOnDisconnect;
    }

    /** P3-10: approval-gate logic lives in {@link WorkflowApprovalService}; these delegates keep the public API. */
    static final String APPROVAL_IN_PROGRESS = WorkflowApprovalService.APPROVAL_IN_PROGRESS;

    /** True when the principal holds one of the configured approver roles. */
    public boolean isApprover(AuthenticatedPrincipal principal) {
        return approval.isApprover(principal);
    }

    /** Roles allowed to approve/reject runs paused at the approval gate (comma separated). */
    @Value("${aegis.workflow.approver-roles:desk,admin}")
    void setApproverRoles(String approverRoles) {
        approval.setApproverRoles(approverRoles);
    }

    /** Continue a run paused at the orchestrator approval gate. Does not start a new execution. */
    public WorkflowRun decideApproval(String runId, boolean approved) {
        return approval.decideApproval(runId, approved, null, null);
    }

    /** See {@link WorkflowApprovalService#decideApproval}. */
    public WorkflowRun decideApproval(String runId, boolean approved, String comment, AuthenticatedPrincipal approver) {
        return approval.decideApproval(runId, approved, comment, approver);
    }

    /** P4-10 (R11): streaming approval; see {@link WorkflowApprovalService#decideApprovalStreaming}. */
    public void decideApprovalStreaming(String runId, boolean approved, String comment, AuthenticatedPrincipal approver,
                                        org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter) {
        approval.decideApprovalStreaming(runId, approved, comment, approver, emitter);
    }

    boolean isHumanInterrupt(WorkflowRun run) {
        return run != null && "HUMAN_INTERRUPT".equals(run.getControlStatus());
    }

    public WorkflowRun cancelRun(String runId) {
        WorkflowRun run = requireRun(runId);
        boolean pausedAtApproval = isHumanInterrupt(run);
        if ("QUEUED".equals(run.getStatus()) || "PAUSED".equals(run.getStatus())) {
            run.setStatus("CANCELLED");
            run.setControlStatus("CANCELLED");
            run.setCancelRequested(1);
            run.setCompletedAt(now());
            mapper.updateRun(run);
            recordRunEvent(run, "RUN_CANCELLED", null, null, "CANCELLED", "Workflow run cancelled before dispatch.", null, 4);
            if (pausedAtApproval) {
                clearQueuedRunMessages(run);
            }
        } else if ("RUNNING".equals(run.getStatus())) {
            run.setCancelRequested(1);
            run.setControlStatus("CANCEL_REQUESTED");
            /* P4-4: CAS so a run that completed meanwhile is not rewritten to RUNNING/CANCEL_REQUESTED. */
            if (mapper.updateRunStatusIf(run, RUNNING_ONLY, false) > 0) {
                recordRunEvent(run, "RUN_CANCEL_REQUESTED", null, null, "RUNNING", "Workflow run cancellation requested.", null, 4);
                clearQueuedRunMessages(run);
                requestOrchestratorCancel(run);
            }
        }
        return mapper.findRun(runId);
    }

    /**
     * P4-4: ask the orchestrator to stop the graph of a RUNNING run (best effort). The orchestrator answers
     * the in-flight execute/stream call with a cancelled outcome; Java records CANCELLED via its CAS writes.
     */
    private void requestOrchestratorCancel(WorkflowRun run) {
        if (langChainGateway == null || !langChainGateway.isEnabled()) {
            return;
        }
        try {
            Map<String, Object> response = langChainGateway.cancelRun(run.getRunId(), run.getUserId(), run.getTenantId());
            if (response != null && !Boolean.TRUE.equals(response.get("ok"))) {
                log.info("[WorkflowService] orchestrator cancel not applied runId={} code={}", run.getRunId(),
                        response.get("code"));
            }
        } catch (RuntimeException ex) {
            log.warn("[WorkflowService] orchestrator cancel failed runId={} type={}", run.getRunId(),
                    ex.getClass().getSimpleName());
        }
    }

    /** Cancelling a running / approval-paused run also drops its pending steering / follow-up messages (best effort). */
    private void clearQueuedRunMessages(WorkflowRun run) {
        if (langChainGateway == null || !langChainGateway.isEnabled()) {
            return;
        }
        try {
            langChainGateway.clearRunMessageQueue(run.getRunId(), run.getUserId(), run.getTenantId());
        } catch (RuntimeException ex) {
            log.warn("[WorkflowService] clear message queue failed runId={} type={}", run.getRunId(),
                    ex.getClass().getSimpleName());
        }
    }

    /** Orchestrator message-queue kinds accepted from clients (pi steer / followUp). */
    public static final String QUEUE_KIND_STEER = "steer";
    public static final String QUEUE_KIND_FOLLOW_UP = "follow_up";

    /** Normalize a client delivery/kind value; null when it is not a queue kind. */
    public static String normalizeQueueKind(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim().toLowerCase(java.util.Locale.ROOT).replace('-', '_');
        if ("steer".equals(v) || "steering".equals(v)) {
            return QUEUE_KIND_STEER;
        }
        if ("follow_up".equals(v) || "followup".equals(v)) {
            return QUEUE_KIND_FOLLOW_UP;
        }
        return null;
    }

    /**
     * Queue a user message into the caller's own RUNNING orchestrator run.
     * Only the run owner (same userId + tenantId) may steer it; admins can read but not steer.
     * Always returns a map with {@code ok}; failures carry {@code code} + {@code httpStatus}:
     * NOT_FOUND (404, missing or not owned), RUN_NOT_RUNNING (409), INVALID_KIND (422) or the
     * orchestrator codes (NOT_ACTIVE / RUN_PAUSED 409, QUEUE_FULL / DELIVERY_LIMIT 429, ...).
     */
    public Map<String, Object> enqueueRunMessage(String runId, AuthenticatedPrincipal principal,
                                                 String kind, String message) {
        return enqueueRunMessage(runId, principal, kind, message, null);
    }

    /** {@code agentId} (optional) targets one agent of a parallel fan-out; null = first ready agent. */
    public Map<String, Object> enqueueRunMessage(String runId, AuthenticatedPrincipal principal,
                                                 String kind, String message, String agentId) {
        WorkflowRun run = runForPrincipal(runId, principal);
        if (run == null || !isRunOwner(run, principal)) {
            return queueFailure("NOT_FOUND", 404, "Run not found");
        }
        String normalized = normalizeQueueKind(kind);
        if (normalized == null) {
            return queueFailure("INVALID_KIND", 422, "kind must be steer or follow_up");
        }
        if (!"RUNNING".equals(run.getStatus()) || "CANCEL_REQUESTED".equals(run.getControlStatus())) {
            return queueFailure("RUN_NOT_RUNNING", 409, "Run is not running");
        }
        if (langChainGateway == null || !langChainGateway.isEnabled()) {
            return queueFailure("DISABLED", 409, "Orchestrator disabled");
        }
        Map<String, Object> result = langChainGateway.enqueueRunMessage(
                run.getRunId(), normalized, message, agentId, run.getUserId(), run.getTenantId());
        Map<String, Object> out = result == null ? queueFailure("ORCHESTRATOR_UNAVAILABLE", 502, null)
                : new LinkedHashMap<String, Object>(result);
        out.put("runId", run.getRunId());
        out.put("kind", normalized);
        if (agentId != null && !agentId.trim().isEmpty()) {
            out.put("agentId", agentId.trim());
        }
        return out;
    }

    private static boolean isRunOwner(WorkflowRun run, AuthenticatedPrincipal principal) {
        return run != null && principal != null
                && run.getUserId() != null && run.getUserId().equals(principal.getUserId())
                && run.getTenantId() != null && run.getTenantId().equals(principal.getTenantId());
    }

    private static Map<String, Object> queueFailure(String code, int httpStatus, String error) {
        Map<String, Object> failed = new LinkedHashMap<>();
        failed.put("ok", false);
        failed.put("code", code);
        failed.put("httpStatus", httpStatus);
        if (error != null) {
            failed.put("error", error);
        }
        return failed;
    }

    static boolean isMessageQueueEvent(String eventName) {
        return "message_queued".equals(eventName) || "message_dequeued".equals(eventName)
                || "tool_skipped".equals(eventName) || "message_queue_closed".equals(eventName)
                || "message_queue_cleared".equals(eventName);
    }

    /** Run-event type persisted for a streamed queue event (message_queued is not persisted: it carries no new state). */
    static String messageQueueRunEventType(String eventName) {
        if ("message_dequeued".equals(eventName)) {
            return "MESSAGE_DELIVERED";
        }
        if ("tool_skipped".equals(eventName)) {
            return "TOOL_SKIPPED";
        }
        if ("message_queue_closed".equals(eventName)) {
            return "MESSAGE_QUEUE_CLOSED";
        }
        if ("message_queue_cleared".equals(eventName)) {
            return "MESSAGE_QUEUE_CLEARED";
        }
        return null;
    }

    private String messageQueueEventMessage(String eventName, Map<String, Object> payload) {
        if ("message_dequeued".equals(eventName)) {
            return "Queued " + string(payload.get("kind")) + " message delivered to " + string(payload.get("nodeId")) + ".";
        }
        if ("tool_skipped".equals(eventName)) {
            return "Tool call " + string(payload.get("toolName")) + " skipped due to queued user message.";
        }
        if ("message_queue_closed".equals(eventName)) {
            Object undelivered = payload.get("undelivered");
            int n = undelivered instanceof List ? ((List<?>) undelivered).size() : 0;
            return "Message queue closed (" + string(payload.get("reason")) + "), undelivered=" + n + ".";
        }
        return "Message queue cleared.";
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
        String delegatedToken = delegatedTokenFor(run, inputs);
        boolean requireApproval = WorkflowApprovalService.requireApprovalRequested(inputs == null ? new LinkedHashMap<String, Object>() : inputs, layout);
        Map<String, Object> response = langChainGateway.executeWorkflow(
                layout, run.getSubject(), state, delegatedToken, run.getRunId(), requireApproval,
                LangChainGateway.runOwner(run.getUserId(), run.getTenantId()));
        if (response == null || Boolean.FALSE.equals(response.get("ok"))) {
            /* P4-4: the orchestrator stopped the graph because the user cancelled -> CANCELLED, not FAILED. */
            WorkflowRun latest = mapper.findRun(run.getRunId());
            if (isCancelRequested(latest)) {
                finishSyncAsCancelled(latest, "Workflow run cancelled; the orchestrator run was stopped.");
                throw new WorkflowStoppedException("CANCELLED");
            }
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
            writeSyncRunIfRunning(run);
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
        writeSyncRunIfRunning(run);
        if (degraded) {
            recordRunEvent(run, "RUN_DEGRADED", null, null, "COMPLETED",
                    "Workflow completed with degraded nodes.", null, 999999);
        }
        recordRunEvent(run, "RUN_COMPLETED", null, null, "COMPLETED", "Workflow run completed.", null, 1000000);
        cacheService.put("aegis:workflow:run:" + run.getRunId(), run, Duration.ofHours(2));
    }

    /** Persist one node run + NODE_COMPLETED/NODE_FAILED event per orchestrator trace entry, in order. */
    private int materializeOrchestratorTrace(WorkflowRun run, Map<String, Object> layout, List<Object> trace,
                                             Map<String, Object> nodeOutputs) {
        return materializeOrchestratorTrace(run, layout, trace, nodeOutputs, null, 0, 0);
    }

    /**
     * Same as above. When {@code skipNodeIds} is non-null, node ids in it and repeated ids within the
     * trace are skipped (resume path: only nodes that ran after the pause); node sort orders start
     * after {@code sortBase}, event sequences after {@code eventBase}. Returns the number inserted.
     */
    @SuppressWarnings("unchecked")
    int materializeOrchestratorTrace(WorkflowRun run, Map<String, Object> layout, List<Object> trace,
                                     Map<String, Object> nodeOutputs, java.util.Set<String> skipNodeIds,
                                             int sortBase, int eventBase) {
        Map<String, Map<String, Object>> layoutNodes = new LinkedHashMap<>();
        for (Map<String, Object> node : castList(layout == null ? null : layout.get("nodes"))) {
            layoutNodes.put(nodeId(node), node);
        }
        java.util.Set<String> skip = skipNodeIds == null ? null : new java.util.HashSet<String>(skipNodeIds);
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
            if (skip != null && nodeId != null && !skip.add(nodeId)) {
                continue;
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
            nodeRun.setSortOrder(sortBase + sort * 100);
            mapper.insertNodeRun(nodeRun);
            recordRunEvent(run, ok ? "NODE_COMPLETED" : "NODE_FAILED", nodeRun, nodeId,
                    ok ? "COMPLETED" : "FAILED", ok ? "Node completed." : "Node failed.", entry,
                    eventBase + sort * 100 + 1);
        }
        return sort;
    }

    private void execute(WorkflowRun run, Map<String, Object> inputs, Map<String, Object> layout) throws Exception {
        List<Map<String, Object>> nodes = castList(layout.get("nodes"));
        List<Map<String, Object>> edges = castList(layout.get("edges"));
        /* P3-15: fail fast on graphs the Java fallback cannot run faithfully instead of silently skipping. */
        validateFallbackNodes(nodes);
        List<Map<String, Object>> orderedNodes = executionOrder(nodes, edges);
        recordUnevaluatedConditions(run, orderedNodes);
        Map<String, Object> state = new LinkedHashMap<>(inputs);
        state.put("workflowKey", run.getWorkflowKey());
        state.put("runId", run.getRunId());
        state.put("subject", run.getSubject());
        // Sync path: same portfolio:read delegation as streaming (fail soft)
        String delegatedToken = delegatedTokenFor(run, inputs);
        List<String> degradedNodes = new ArrayList<>();
        /* P2-20: each node sees the run inputs plus only what it declares (inputKeys) or its direct upstreams. */
        Set<String> baseKeys = new HashSet<>(state.keySet());
        Map<String, List<String>> upstream = upstreamIds(edges);
        int retriesLeft = Math.max(0, runRetryBudget);

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
                final Map<String, Object> inputSnapshot = nodeInput(node, state, baseKeys, upstream);
                nodeRun.setInputJson(cappedInputJson(inputSnapshot));
                nodeRun.setStartedAt(now());
                nodeRun.setSortOrder(index * 100 + attempt);
                mapper.insertNodeRun(nodeRun);
                recordRunEvent(run, "NODE_STARTED", nodeRun, nodeRun.getNodeId(), "RUNNING", "Node started.", null, index * 100 + attempt * 10);

                try {
                    final Map<String, Object> callNode = node;
                    Map<String, Object> output = callWithTimeout(
                            () -> executeNode(callNode, inputSnapshot, run, delegatedToken), timeoutMs);
                    if (output != null && Boolean.FALSE.equals(output.get("ok"))) {
                        /* Orchestrator reported failure: retry per retryPolicy instead of storing it as success. */
                        Object error = output.get("error") != null ? output.get("error") : output.get("summary");
                        throw new IllegalStateException("Node returned ok=false"
                                + (error == null ? "" : ": " + String.valueOf(error)));
                    }
                    if (output != null && Boolean.TRUE.equals(output.get("degraded"))) {
                        degradedNodes.add(nodeId(node));
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
                    if (attempt < maxAttempts && retriesLeft <= 0) {
                        recordRunEvent(run, "RETRY_BUDGET_EXHAUSTED", nodeRun, nodeRun.getNodeId(), "FAILED",
                                "Run retry budget exhausted; node not retried.", null, index * 100 + attempt * 10 + 2);
                        break;
                    }
                    if (attempt < maxAttempts) {
                        retriesLeft -= 1;
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

    /** P2-20: cap on the stored workflow_node_run.input_json (characters). */
    static final int MAX_NODE_INPUT_JSON_CHARS = 32000;
    private static final String[] UPSTREAM_SUMMARY_KEYS = {"ok", "summary", "signals", "label", "confidence", "degraded"};

    /** P2-20 (待确认): node retries allowed per run across all nodes (the only retry layer). */
    @Value("${aegis.workflow.run-retry-budget:4}")
    private int runRetryBudget = 4;

    void setRunRetryBudget(int runRetryBudget) {
        this.runRetryBudget = runRetryBudget;
    }

    private static final java.util.concurrent.ExecutorService NODE_CALL_EXECUTOR =
            java.util.concurrent.Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "workflow-node-call");
                thread.setDaemon(true);
                return thread;
            });

    private static Map<String, List<String>> upstreamIds(List<Map<String, Object>> edges) {
        Map<String, List<String>> upstream = new HashMap<>();
        for (Map<String, Object> edge : edges) {
            Object source = edge.get("source");
            Object target = edge.get("target");
            if (source == null || target == null) {
                continue;
            }
            List<String> sources = upstream.get(String.valueOf(target));
            if (sources == null) {
                sources = new ArrayList<>();
                upstream.put(String.valueOf(target), sources);
            }
            sources.add(String.valueOf(source));
        }
        return upstream;
    }

    /**
     * Input for one node: run inputs (workflowKey/runId/subject and the request inputs), plus the
     * state keys the node declares in {@code inputKeys}; without a declaration, a compact view
     * (summary/signals/...) of each direct upstream output. Keeps the request size independent of chain length.
     */
    @SuppressWarnings("unchecked")
    Map<String, Object> nodeInput(Map<String, Object> node, Map<String, Object> state, Set<String> baseKeys,
                                  Map<String, List<String>> upstream) {
        Map<String, Object> input = new LinkedHashMap<>();
        for (String key : baseKeys) {
            if (state.containsKey(key)) {
                input.put(key, state.get(key));
            }
        }
        Object declared = data(node).get("inputKeys");
        if (!(declared instanceof List) || ((List<Object>) declared).isEmpty()) {
            declared = data(node).get("input_keys");
        }
        if (declared instanceof List && !((List<Object>) declared).isEmpty()) {
            for (Object key : (List<Object>) declared) {
                if (key != null && state.containsKey(String.valueOf(key))) {
                    input.put(String.valueOf(key), state.get(String.valueOf(key)));
                }
            }
            return input;
        }
        List<String> sources = upstream.get(nodeId(node));
        if (sources != null) {
            for (String source : sources) {
                Object value = state.get(source);
                if (value instanceof Map) {
                    Map<String, Object> compact = new LinkedHashMap<>();
                    for (String key : UPSTREAM_SUMMARY_KEYS) {
                        if (((Map<String, Object>) value).containsKey(key)) {
                            compact.put(key, ((Map<String, Object>) value).get(key));
                        }
                    }
                    input.put(source, compact);
                } else if (value != null) {
                    input.put(source, value);
                }
            }
        }
        return input;
    }

    /** Stored input_json is capped; an oversized snapshot keeps only its keys and size. */
    String cappedInputJson(Map<String, Object> input) {
        String json = toJson(input);
        if (json == null || json.length() <= MAX_NODE_INPUT_JSON_CHARS) {
            return json;
        }
        Map<String, Object> marker = new LinkedHashMap<>();
        marker.put("truncated", true);
        marker.put("originalChars", json.length());
        marker.put("keys", new ArrayList<>(input.keySet()));
        return toJson(marker);
    }

    /** Enforce the node timeout for real: the call is abandoned (interrupted) once timeoutMs elapses. */
    private static Map<String, Object> callWithTimeout(java.util.concurrent.Callable<Map<String, Object>> call,
                                                       int timeoutMs) throws Exception {
        if (timeoutMs <= 0) {
            return call.call();
        }
        java.util.concurrent.Future<Map<String, Object>> future = NODE_CALL_EXECUTOR.submit(call);
        try {
            return future.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException ex) {
            future.cancel(true);
            throw new IllegalStateException("Node timed out; timeout=" + timeoutMs + "ms");
        } catch (java.util.concurrent.ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw ex;
        } catch (InterruptedException ex) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new WorkflowStoppedException("CANCELLED");
        }
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
            if (DefaultWorkflowLayouts.shouldRefreshResearchLayout(workflowKey, parsed)) {
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

    Map<String, Object> layoutForRun(String workflowKey, String workflowVersionId) {
        if (workflowVersionId == null || workflowVersionId.trim().isEmpty()) {
            return layout(workflowKey);
        }
        WorkflowVersion version = mapper.findVersion(workflowVersionId);
        return layoutForRun(workflowKey, version);
    }

    Map<String, Object> parseJsonMap(String json) {
        if (json == null || json.trim().isEmpty()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception ex) {
            return new LinkedHashMap<>();
        }
    }

    WorkflowRun requireRun(String runId) {
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
            if ("condition".equals(type)) {
                result.put("evaluated", false);
            }
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
        // Only truly deterministic control-flow handlers skip LLM. handler(node) never returns null
        // (it falls back to functionName/nodeType); nodes without any executable definition are
        // rejected up front by validateFallbackNodes (P3-15) instead of being skipped here.
        return "workflow.end".equals(handler) || "scheduler.manual".equals(handler) || "scheduler.daily".equals(handler);
    }

    static final String CONDITION_NOT_EVALUATED_MESSAGE =
            "Condition not evaluated by the Java fallback engine; all downstream branches run.";

    /**
     * P3-15: a non start/end/condition node needs a handler, functionName, agentId or prompt; otherwise
     * the Java fallback would send a bare node type to the gateway. Rejected as a validation error.
     */
    void validateFallbackNodes(List<Map<String, Object>> nodes) {
        List<String> invalid = new ArrayList<>();
        for (Map<String, Object> node : nodes) {
            String type = nodeType(node);
            if ("start".equals(type) || "end".equals(type) || "condition".equals(type)) {
                continue;
            }
            Map<String, Object> data = data(node);
            boolean executable = !text(data, "handler", "").isEmpty()
                    || !text(data, "functionName", "").isEmpty()
                    || !text(data, "agentId", "").isEmpty()
                    || !text(data, "prompt", "").isEmpty()
                    || !text(data, "systemPrompt", "").isEmpty();
            if (!executable) {
                invalid.add(nodeId(node));
            }
        }
        if (!invalid.isEmpty()) {
            throw new IllegalArgumentException("Workflow validation failed: nodes without handler: " + invalid);
        }
    }

    private void recordUnevaluatedConditions(WorkflowRun run, List<Map<String, Object>> orderedNodes) {
        List<String> conditions = new ArrayList<>();
        for (Map<String, Object> node : orderedNodes) {
            if ("condition".equals(nodeType(node))) {
                conditions.add(nodeId(node));
            }
        }
        if (conditions.isEmpty()) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("nodes", conditions);
        payload.put("engine", ENGINE_JAVA_FALLBACK);
        recordRunEvent(run, "CONDITION_NOT_EVALUATED", null, null, "RUNNING",
                CONDITION_NOT_EVALUATED_MESSAGE, payload, 50);
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
        inline.setModelName(text(data(node), "modelName", defaultModelName));
        inline.setToolsJson(text(data(node), "toolsJson", "[]"));
        return inline;
    }

    private String simulatedNodeMessage(String type, String handler, String subject) {
        if ("start".equals(type)) {
            return "Workflow started for " + subject;
        }
        if ("condition".equals(type)) {
            return CONDITION_NOT_EVALUATED_MESSAGE;
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

    List<Map<String, Object>> executionOrder(List<Map<String, Object>> nodes, List<Map<String, Object>> edges) {
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
            /* P3-15: previously fell back to declaration order silently. */
            throw new IllegalArgumentException("Workflow validation failed: graph contains a cycle.");
        }
        return ordered;
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

    String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return "{}";
        }
    }

    void recordRunEvent(WorkflowRun run,
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

    String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    String now() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    private static class WorkflowStoppedException extends RuntimeException {
        WorkflowStoppedException(String status) {
            super(status);
        }
    }
}
