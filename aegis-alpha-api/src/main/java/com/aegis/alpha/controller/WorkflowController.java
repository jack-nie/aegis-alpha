package com.aegis.alpha.controller;

import com.aegis.alpha.domain.WorkflowDefinition;
import com.aegis.alpha.domain.WorkflowNodeRun;
import com.aegis.alpha.domain.WorkflowRun;
import com.aegis.alpha.domain.WorkflowRunEvent;
import com.aegis.alpha.domain.WorkflowVersion;
import com.aegis.alpha.service.AuthService;
import com.aegis.alpha.service.TokenService;
import com.aegis.alpha.service.WorkflowNodeExecutionService;
import com.aegis.alpha.service.WorkflowService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

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

    public WorkflowController(AuthService authService,
                              WorkflowService workflowService,
                              WorkflowNodeExecutionService workflowNodeExecutionService,
                              TokenService tokenService) {
        this.authService = authService;
        this.workflowService = workflowService;
        this.workflowNodeExecutionService = workflowNodeExecutionService;
        this.tokenService = tokenService;
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
        if (authService.me(authorization) == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(workflowService.runs());
    }

    @GetMapping("/workflow/runs/{runId}")
    public ResponseEntity<WorkflowRun> run(@RequestHeader(value = "Authorization", required = false) String authorization,
                                           @PathVariable String runId) {
        if (authService.me(authorization) == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        WorkflowRun run = workflowService.run(runId);
        return run == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(run);
    }

    @GetMapping("/workflow/runs/{runId}/nodes")
    public ResponseEntity<List<WorkflowNodeRun>> nodeRuns(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                          @PathVariable String runId) {
        if (authService.me(authorization) == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(workflowService.nodeRuns(runId));
    }

    @GetMapping("/workflow/runs/{runId}/events")
    public ResponseEntity<List<WorkflowRunEvent>> runEvents(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                            @PathVariable String runId) {
        if (authService.me(authorization) == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(workflowService.runEvents(runId));
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
        if (authService.me(authorization) == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(workflowService.saveLayout(workflowKey, body));
    }

    @PostMapping("/workflows/{workflowKey}/publish-version")
    public ResponseEntity<WorkflowVersion> publishVersion(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                          @PathVariable String workflowKey) {
        Map<String, Object> me = authService.me(authorization);
        if (me == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(workflowService.publishVersion(workflowKey, String.valueOf(me.get("username"))));
    }

    @PostMapping("/workflow/runs")
    public ResponseEntity<WorkflowRun> start(@RequestHeader(value = "Authorization", required = false) String authorization,
                                             @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                             @RequestParam(value = "async", defaultValue = "false") boolean async,
                                             @RequestBody Map<String, Object> body) {
        if (authService.me(authorization) == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        String workflowKey = body == null ? null : string(body.get("workflowKey"));
        String subject = body == null ? null : string(body.get("subject"));
        WorkflowRun existing = workflowService.findIdempotentRun(workflowKey, subject, idempotencyKey);
        if (existing != null) {
            return ResponseEntity.ok(existing);
        }
        WorkflowRun run = async
                ? workflowService.queueStart(workflowKey, subject, body == null ? null : objectMap(body.get("inputs")), idempotencyKey)
                : workflowService.start(workflowKey, subject, body == null ? null : objectMap(body.get("inputs")), idempotencyKey);
        return ResponseEntity.status(async ? HttpStatus.ACCEPTED : HttpStatus.CREATED).body(run);
    }

    @PostMapping("/workflows/{workflowKey}/run")
    public ResponseEntity<WorkflowRun> startWorkflow(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                     @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                                     @PathVariable String workflowKey,
                                                     @RequestParam(value = "async", defaultValue = "false") boolean async,
                                                     @RequestBody(required = false) Map<String, Object> body) {
        if (authService.me(authorization) == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        String subject = body == null ? null : string(body.get("subject"));
        WorkflowRun existing = workflowService.findIdempotentRun(workflowKey, subject, idempotencyKey);
        if (existing != null) {
            return ResponseEntity.ok(existing);
        }
        WorkflowRun run = async
                ? workflowService.queueStart(workflowKey, subject, body == null ? null : objectMap(body.get("inputs")), idempotencyKey)
                : workflowService.start(workflowKey, subject, body == null ? null : objectMap(body.get("inputs")), idempotencyKey);
        return ResponseEntity.status(async ? HttpStatus.ACCEPTED : HttpStatus.CREATED).body(run);
    }

    @PostMapping("/workflows/{workflowKey}/run/stream")
    public SseEmitter streamWorkflow(@RequestHeader(value = "Authorization", required = false) String authorization,
                                      @PathVariable String workflowKey,
                                      @RequestBody(required = false) Map<String, Object> body) {
        if (authService.me(authorization) == null) {
            SseEmitter rejected = new SseEmitter();
            rejected.completeWithError(new RuntimeException("Unauthorized"));
            return rejected;
        }
        String subject = body == null ? null : string(body.get("subject"));
        Map<String, Object> inputs = body == null ? null : objectMap(body.get("inputs"));
        SseEmitter emitter = new SseEmitter(300000L);
        CompletableFuture.runAsync(() -> {
            try {
                workflowService.startWithStreaming(workflowKey, subject, inputs, emitter);
            } catch (Exception ex) {
                try { emitter.completeWithError(ex); } catch (Exception ignored) {}
            }
        });
        return emitter;
    }

    @PostMapping("/workflow/runs/{runId}/dispatch")
    public ResponseEntity<WorkflowRun> dispatchRun(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                   @PathVariable String runId) {
        if (authService.me(authorization) == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(workflowService.dispatchQueuedRun(runId));
    }

    @PostMapping("/workflow/runs/{runId}/pause")
    public ResponseEntity<WorkflowRun> pauseRun(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                @PathVariable String runId) {
        if (authService.me(authorization) == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(workflowService.pauseRun(runId));
    }

    @PostMapping("/workflow/runs/{runId}/resume")
    public ResponseEntity<WorkflowRun> resumeRun(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                 @PathVariable String runId) {
        if (authService.me(authorization) == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(workflowService.resumeRun(runId));
    }

    @PostMapping("/workflow/runs/{runId}/cancel")
    public ResponseEntity<WorkflowRun> cancelRun(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                 @PathVariable String runId) {
        if (authService.me(authorization) == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(workflowService.cancelRun(runId));
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
        WorkflowRun run = workflowService.run(runId);
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
