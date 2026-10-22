package com.aegis.alpha.controller;

import com.aegis.alpha.config.AsyncConfig;
import com.aegis.alpha.domain.WorkflowDefinition;
import com.aegis.alpha.domain.WorkflowNodeRun;
import com.aegis.alpha.domain.WorkflowRun;
import com.aegis.alpha.domain.WorkflowRunEvent;
import com.aegis.alpha.domain.WorkflowVersion;
import com.aegis.alpha.security.AuthenticatedPrincipal;
import com.aegis.alpha.service.AuthService;
import com.aegis.alpha.service.TokenService;
import com.aegis.alpha.service.WorkflowNodeExecutionService;
import com.aegis.alpha.service.WorkflowService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

@RestController
@RequestMapping("/_backend")
public class WorkflowController {
    /** Default delegated portfolio:read token lifetime (15 minutes). */
    static final long DEFAULT_DELEGATION_TTL_MS = 15L * 60L * 1000L;
    /** Cap optional client-requested TTL at 1 hour. */
    static final long MAX_DELEGATION_TTL_MS = 60L * 60L * 1000L;

    private final AuthService authService;
    private final WorkflowService workflowService;
    private final WorkflowNodeExecutionService workflowNodeExecutionService;
    private final TokenService tokenService;
    private final Executor workflowExecutor;

    @Autowired
    public WorkflowController(AuthService authService,
                              WorkflowService workflowService,
                              WorkflowNodeExecutionService workflowNodeExecutionService,
                              TokenService tokenService,
                              @Qualifier(AsyncConfig.WORKFLOW_EXECUTOR) Executor workflowExecutor) {
        this.authService = authService;
        this.workflowService = workflowService;
        this.workflowNodeExecutionService = workflowNodeExecutionService;
        this.tokenService = tokenService;
        this.workflowExecutor = workflowExecutor;
    }

    @GetMapping("/workflows")
    public ResponseEntity<List<WorkflowDefinition>> workflows(@RequestHeader(value = "Authorization", required = false) String authorization) {
        if (authService.me(authorization) == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(workflowService.definitions());
    }

    @PostMapping("/workflows")
    public ResponseEntity<WorkflowDefinition> createWorkflow(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                            @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> me = authService.me(authorization);
        if (me == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(workflowService.createDefinition(String.valueOf(me.get("username")), body));
    }

    @PutMapping("/workflows/{workflowKey}")
    public ResponseEntity<WorkflowDefinition> updateWorkflow(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                            @PathVariable String workflowKey,
                                                            @RequestBody Map<String, Object> body) {
        Map<String, Object> me = authService.me(authorization);
        if (me == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(workflowService.updateDefinition(String.valueOf(me.get("username")), workflowKey, body));
    }

    @DeleteMapping("/workflows/{workflowKey}")
    public ResponseEntity<Void> deleteWorkflow(@RequestHeader(value = "Authorization", required = false) String authorization,
                                               @PathVariable String workflowKey) {
        Map<String, Object> me = authService.me(authorization);
        if (me == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        workflowService.deleteDefinition(String.valueOf(me.get("username")), workflowKey);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/workflow/runs")
    public ResponseEntity<List<WorkflowRun>> runs(@RequestHeader(value = "Authorization", required = false) String authorization) {
        AuthenticatedPrincipal principal = authService.principal(authorization);
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(workflowService.runsForPrincipal(principal));
    }

    @GetMapping("/workflow/runs/{runId}")
    public ResponseEntity<WorkflowRun> run(@RequestHeader(value = "Authorization", required = false) String authorization,
                                           @PathVariable String runId) {
        AuthenticatedPrincipal principal = authService.principal(authorization);
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        WorkflowRun run = workflowService.runForPrincipal(runId, principal);
        return run == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(run);
    }

    @GetMapping("/workflow/runs/{runId}/nodes")
    public ResponseEntity<List<WorkflowNodeRun>> nodeRuns(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                          @PathVariable String runId) {
        AuthenticatedPrincipal principal = authService.principal(authorization);
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (workflowService.runForPrincipal(runId, principal) == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(workflowService.nodeRunsForPrincipal(runId, principal));
    }

    @GetMapping("/workflow/runs/{runId}/events")
    public ResponseEntity<List<WorkflowRunEvent>> runEvents(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                            @PathVariable String runId) {
        AuthenticatedPrincipal principal = authService.principal(authorization);
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (workflowService.runForPrincipal(runId, principal) == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(workflowService.runEventsForPrincipal(runId, principal));
    }

    @GetMapping("/workflows/{workflowKey}/layout")
    public ResponseEntity<Map<String, Object>> layout(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                      @PathVariable String workflowKey) {
        if (authService.me(authorization) == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(workflowService.layout(workflowKey));
    }

    @PutMapping("/workflows/{workflowKey}/layout")
    public ResponseEntity<Map<String, Object>> saveLayout(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                          @PathVariable String workflowKey,
                                                          @RequestBody Map<String, Object> body) {
        Map<String, Object> me = authService.me(authorization);
        if (me == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        try {
            return ResponseEntity.ok(workflowService.saveLayoutForUser(
                    String.valueOf(me.get("username")), workflowKey, body));
        } catch (WorkflowService.AccessDeniedException ex) {
            Map<String, Object> error = new LinkedHashMap<String, Object>();
            error.put("ok", false);
            error.put("error", ex.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error);
        }
    }

    @PostMapping("/workflows/{workflowKey}/publish-version")
    public ResponseEntity<WorkflowVersion> publishVersion(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                          @PathVariable String workflowKey) {
        Map<String, Object> me = authService.me(authorization);
        if (me == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        try {
            return ResponseEntity.ok(workflowService.publishVersion(workflowKey, String.valueOf(me.get("username"))));
        } catch (WorkflowService.AccessDeniedException ex) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
    }

    @PostMapping("/workflow/runs")
    public ResponseEntity<WorkflowRun> start(@RequestHeader(value = "Authorization", required = false) String authorization,
                                             @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                             @RequestParam(value = "async", defaultValue = "false") boolean async,
                                             @RequestBody Map<String, Object> body) {
        AuthenticatedPrincipal principal = authService.principal(authorization);
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        String workflowKey = body == null ? null : string(body.get("workflowKey"));
        String subject = body == null ? null : string(body.get("subject"));
        WorkflowRun existing = workflowService.findIdempotentRun(workflowKey, subject, idempotencyKey);
        if (existing != null && workflowService.isRunAccessible(existing, principal)) {
            return ResponseEntity.ok(existing);
        }
        WorkflowRun run = async
                ? workflowService.queueStart(workflowKey, subject, body == null ? null : objectMap(body.get("inputs")), idempotencyKey, principal)
                : workflowService.start(workflowKey, subject, body == null ? null : objectMap(body.get("inputs")), idempotencyKey, principal);
        return ResponseEntity.status(async ? HttpStatus.ACCEPTED : HttpStatus.CREATED).body(run);
    }

    @PostMapping("/workflows/{workflowKey}/run")
    public ResponseEntity<WorkflowRun> startWorkflow(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                     @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                                     @PathVariable String workflowKey,
                                                     @RequestParam(value = "async", defaultValue = "false") boolean async,
                                                     @RequestBody(required = false) Map<String, Object> body) {
        AuthenticatedPrincipal principal = authService.principal(authorization);
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        String subject = body == null ? null : string(body.get("subject"));
        WorkflowRun existing = workflowService.findIdempotentRun(workflowKey, subject, idempotencyKey);
        if (existing != null && workflowService.isRunAccessible(existing, principal)) {
            return ResponseEntity.ok(existing);
        }
        WorkflowRun run = async
                ? workflowService.queueStart(workflowKey, subject, body == null ? null : objectMap(body.get("inputs")), idempotencyKey, principal)
                : workflowService.start(workflowKey, subject, body == null ? null : objectMap(body.get("inputs")), idempotencyKey, principal);
        return ResponseEntity.status(async ? HttpStatus.ACCEPTED : HttpStatus.CREATED).body(run);
    }

    @PostMapping("/workflows/{workflowKey}/run/stream")
    public SseEmitter streamWorkflow(@RequestHeader(value = "Authorization", required = false) String authorization,
                                      @PathVariable String workflowKey,
                                      @RequestBody(required = false) Map<String, Object> body) {
        AuthenticatedPrincipal principal = authService.principal(authorization);
        if (principal == null) {
            SseEmitter rejected = new SseEmitter();
            rejected.completeWithError(new RuntimeException("Unauthorized"));
            return rejected;
        }
        String subject = body == null ? null : string(body.get("subject"));
        Map<String, Object> inputs = body == null ? null : objectMap(body.get("inputs"));
        SseEmitter emitter = new SseEmitter(300000L);
        workflowExecutor.execute(() -> {
            try {
                workflowService.startWithStreaming(workflowKey, subject, inputs, emitter, principal);
            } catch (Exception ex) {
                try { emitter.completeWithError(ex); } catch (Exception ignored) {}
            }
        });
        return emitter;
    }

    @PostMapping("/workflow/runs/{runId}/dispatch")
    public ResponseEntity<WorkflowRun> dispatchRun(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                   @PathVariable String runId) {
        ResponseEntity<WorkflowRun> denied = requireRunAccess(authorization, runId);
        if (denied != null) {
            return denied;
        }
        return ResponseEntity.ok(workflowService.dispatchQueuedRun(runId));
    }

    @PostMapping("/workflow/runs/{runId}/pause")
    public ResponseEntity<WorkflowRun> pauseRun(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                @PathVariable String runId) {
        ResponseEntity<WorkflowRun> denied = requireRunAccess(authorization, runId);
        if (denied != null) {
            return denied;
        }
        return ResponseEntity.ok(workflowService.pauseRun(runId));
    }

    @PostMapping("/workflow/runs/{runId}/resume")
    public ResponseEntity<WorkflowRun> resumeRun(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                 @PathVariable String runId) {
        ResponseEntity<WorkflowRun> denied = requireRunAccess(authorization, runId);
        if (denied != null) {
            return denied;
        }
        return ResponseEntity.ok(workflowService.resumeRun(runId));
    }

    @PostMapping("/workflow/runs/{runId}/cancel")
    public ResponseEntity<WorkflowRun> cancelRun(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                 @PathVariable String runId) {
        ResponseEntity<WorkflowRun> denied = requireRunAccess(authorization, runId);
        if (denied != null) {
            return denied;
        }
        return ResponseEntity.ok(workflowService.cancelRun(runId));
    }

    /**
     * Approve or reject a run paused at the approval gate: body {@code {"approved": boolean, "comment"?: string}}.
     * 401 unauthenticated, 404 run not visible, 403 caller lacks an approver role,
     * 400 missing/non-boolean {@code approved}, 409 run is not awaiting approval.
     * Repeated submissions return the current run without resuming the orchestrator again.
     */
    @PostMapping("/workflow/runs/{runId}/approval")
    public ResponseEntity<Object> decideApproval(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                 @PathVariable String runId,
                                                 @RequestBody(required = false) Map<String, Object> body) {
        AuthenticatedPrincipal principal = authService.principal(authorization);
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (workflowService.runForPrincipal(runId, principal) == null) {
            return ResponseEntity.notFound().build();
        }
        if (!workflowService.isApprover(principal)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(approvalError("approver role required"));
        }
        Object approved = body == null ? null : body.get("approved");
        if (!(approved instanceof Boolean)) {
            return ResponseEntity.badRequest().body(approvalError("'approved' must be a boolean"));
        }
        String comment = body.get("comment") == null ? null : String.valueOf(body.get("comment"));
        try {
            return ResponseEntity.ok(workflowService.decideApproval(runId, (Boolean) approved, comment, principal));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(approvalError(ex.getMessage()));
        }
    }

    private static Map<String, Object> approvalError(String message) {
        Map<String, Object> error = new LinkedHashMap<String, Object>();
        error.put("ok", false);
        error.put("error", message);
        return error;
    }

    /**
     * Returns an error response when the caller is unauthenticated or cannot see the run;
     * null means the caller may proceed. Missing and foreign runs intentionally look identical.
     */
    private ResponseEntity<WorkflowRun> requireRunAccess(String authorization, String runId) {
        AuthenticatedPrincipal principal = authService.principal(authorization);
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (workflowService.runForPrincipal(runId, principal) == null) {
            return ResponseEntity.notFound().build();
        }
        return null;
    }

    @PostMapping("/workflow-nodes/execute")
    public ResponseEntity<Map<String, Object>> executeWorkflowNode(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                                   @RequestBody(required = false) Map<String, Object> body) {
        if (authService.me(authorization) == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(workflowNodeExecutionService.execute(body == null ? new java.util.LinkedHashMap<String, Object>() : body));
    }

    /**
     * Issue a short-lived run-scoped portfolio:read delegation token for the authenticated user.
     * Preferred path for end-to-end agent portfolio reads without sharing the user session token.
     *
     * POST /_backend/workflow-runs/{runId}/delegated-token
     * Optional body: { "ttlMs": 600000 }
     */
    @PostMapping("/workflow-runs/{runId}/delegated-token")
    public ResponseEntity<Map<String, Object>> issueDelegatedToken(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String runId,
            @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> me = authService.me(authorization);
        if (me == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (runId == null || runId.trim().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        /* Only the run owner (or an admin) may mint a delegation bound to that run. */
        WorkflowRun run = workflowService.runForPrincipal(runId, authService.principal(authorization));
        if (run == null) {
            return ResponseEntity.notFound().build();
        }

        long ttlMs = DEFAULT_DELEGATION_TTL_MS;
        if (body != null && body.get("ttlMs") instanceof Number) {
            long requested = ((Number) body.get("ttlMs")).longValue();
            if (requested > 0L) {
                ttlMs = Math.min(requested, MAX_DELEGATION_TTL_MS);
            }
        }

        List<String> scopes = Collections.singletonList(PortfolioController.SCOPE_PORTFOLIO_READ);
        String token = tokenService.issueServiceDelegation(
                runId,
                String.valueOf(me.get("user_id")),
                String.valueOf(me.get("tenant_id")),
                scopes,
                ttlMs);

        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("access_token", token);
        response.put("token_type", "bearer");
        response.put("expires_in", ttlMs / 1000L);
        response.put("runId", runId);
        response.put("scopes", scopes);
        response.put("typ", "delegation");
        return ResponseEntity.ok(response);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> objectMap(Object value) {
        if (value instanceof Map) {
            return (Map<String, Object>) value;
        }
        return null;
    }

    private String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
