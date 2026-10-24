package com.aegis.alpha.service;

import com.aegis.alpha.domain.WorkflowNodeRun;
import com.aegis.alpha.domain.WorkflowRun;
import com.aegis.alpha.mapper.WorkflowMapper;
import com.aegis.alpha.security.AuthenticatedPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * P3-10: human-approval gate extracted unchanged from {@link WorkflowService}
 * (approver roles, decide approve/reject with the HUMAN_INTERRUPT CAS, resume-trace materialization).
 * Not a Spring bean: owned by {@link WorkflowService}, which keeps the public delegates.
 */
final class WorkflowApprovalService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowService.class);

    private final WorkflowService host;
    private final WorkflowMapper mapper;
    private final LangChainGateway gateway;

    WorkflowApprovalService(WorkflowService host, WorkflowMapper mapper, LangChainGateway gateway) {
        this.host = host;
        this.mapper = mapper;
        this.gateway = gateway;
    }

    /** Roles allowed to approve/reject (comma separated); injected through {@link WorkflowService#setApproverRoles}. */
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
        Claim claim = claim(runId, approved, comment, approver);
        if (claim.current != null) {
            return claim.current;
        }
        WorkflowRun run = claim.run;
        Map<String, Object> resume = gateway.resumeWorkflow(run.getRunId(), approved,
                LangChainGateway.runOwner(run.getUserId(), run.getTenantId()), claim.delegatedToken);
        return applyResumeOutcome(run, approved, claim.audit, resume);
    }

    /** Result of the HUMAN_INTERRUPT claim: {@code current} is set when this caller does not own the decision. */
    static final class Claim {
        WorkflowRun current;
        WorkflowRun run;
        Map<String, Object> audit;
        String delegatedToken;
    }

    private Claim claim(String runId, boolean approved, String comment, AuthenticatedPrincipal approver) {
        Claim claim = new Claim();
        WorkflowRun run = host.requireRun(runId);
        if (!host.isHumanInterrupt(run)) {
            if (isApprovalDecidedOrInFlight(run)) {
                claim.current = mapper.findRun(runId);
                return claim;
            }
            throw new IllegalStateException("Workflow run is not awaiting human approval: " + runId);
        }
        if (mapper.transitionControlStatus(runId, "HUMAN_INTERRUPT", APPROVAL_IN_PROGRESS) == 0) {
            /* Another request claimed the decision first. */
            claim.current = mapper.findRun(runId);
            return claim;
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
        host.recordRunEvent(run, "RUN_APPROVAL_DECISION", null, null, "PAUSED",
                approved ? "Approval submitted." : "Rejection submitted.", audit, 955000);

        /* P4-5: fresh run-scoped delegation for the run owner (never the approver's identity). */
        String delegatedToken = approved
                ? host.issueDelegatedPortfolioReadToken(run.getRunId(), run.getUserId(), run.getTenantId(), null)
                : null;
        claim.run = run;
        claim.audit = audit;
        claim.delegatedToken = delegatedToken;
        return claim;
    }

    /** Apply an orchestrator resume outcome (sync response or one assembled from the resume stream). */
    private WorkflowRun applyResumeOutcome(WorkflowRun run, boolean approved, Map<String, Object> audit,
                                           Map<String, Object> resume) {
        String runId = run.getRunId();
        if (host.isCancelRequestedNow(runId)) {
            /* D10a: a cancel that landed during the resume wins; never overwrite CANCELLED. */
            host.recordRunEvent(run, "RUN_RESUME_DISCARDED", null, null, "CANCELLED",
                    "Approval resume finished after the run was cancelled; outcome discarded.", null, 960500);
            return mapper.findRun(runId);
        }
        if (isResumeOk(resume)) {
            /* P2-13 / Phase-1 leftover: nodes that ran after the approval gate are materialized
             * as node runs + NODE_* events (same for runs started streaming or non-streaming). */
            int resumedNodes = materializeResumeTrace(run, resume);
            if (resumedNodes > 0) {
                run.setNodeCount((run.getNodeCount() == null ? 0 : run.getNodeCount()) + resumedNodes);
            }
            if (isInterruptedAgain(resume)) {
                run.setStatus("PAUSED");
                run.setControlStatus("HUMAN_INTERRUPT");
                run.setPauseRequested(1);
                mapper.updateRun(run);
                host.recordRunEvent(run, "RUN_PAUSED", null, null, "PAUSED",
                        "Workflow paused for human approval again after resume.", null, 990000);
                return mapper.findRun(runId);
            }
            run.setStatus(approved ? "COMPLETED" : "REJECTED");
            run.setCompletedAt(host.now());
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
            run.setResultJson(host.toJson(result));
            mapper.updateRun(run);
            host.recordRunEvent(run, approved ? "RUN_APPROVED" : "RUN_REJECTED", null, null,
                    approved ? "COMPLETED" : "REJECTED",
                    approved ? "Human approval accepted; workflow resumed."
                            : "Human approval rejected; final report skipped.",
                    result, 1000000);
        } else {
            String error = resume == null ? "" : host.string(resume.get("error"));
            run.setControlStatus("HUMAN_INTERRUPT");
            run.setErrorMessage(error == null || error.trim().isEmpty() ? "resume failed" : error);
            mapper.updateRun(run);
            host.recordRunEvent(run, "RUN_RESUME_FAILED", null, null, "PAUSED",
                    "Human approval resume failed.", resume, 960000);
        }
        return mapper.findRun(runId);
    }

    /**
     * P4-10 (R11): streaming approval. Same claim / audit / run-owner delegation / outcome rules as
     * {@link #decideApproval}, but the orchestrator is resumed through {@code /resume-workflow-stream} and
     * its events are forwarded to {@code emitter}. v2 progress events are not persisted (a finished tool
     * call leaves one TOOL_EXECUTION_END event); the run state is written once from the terminal event,
     * then the terminal event and a {@code run_state} snapshot are sent and the emitter completes.
     * The aggregate answer arrives only as the orchestrator's gated final {@code message_delta}.
     */
    public void decideApprovalStreaming(String runId, boolean approved, String comment, AuthenticatedPrincipal approver,
                                        SseEmitter emitter) {
        Claim claim;
        try {
            claim = claim(runId, approved, comment, approver);
        } catch (IllegalStateException ex) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("ok", false);
            error.put("error", ex.getMessage());
            error.put("code", "NOT_AWAITING_APPROVAL");
            sendQuietly(emitter, "error", host.toJson(error));
            completeQuietly(emitter);
            return;
        }
        if (claim.current != null) {
            sendQuietly(emitter, "run_state", host.toJson(claim.current));
            completeQuietly(emitter);
            return;
        }
        final WorkflowRun run = claim.run;
        final List<Object> trace = new ArrayList<>();
        final AtomicReference<Map<String, Object>> outcome = new AtomicReference<>();
        final String[] terminal = new String[2];
        final AtomicInteger sequence = new AtomicInteger(0);
        /* D10a: cancel checks while the resume stream is open (throttled like the workflow stream). */
        final SseStreamReader.Handle readerHandle = new SseStreamReader.Handle();
        final java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean(false);
        final long[] nextCancelCheckAt = {Long.MIN_VALUE};
        Map<String, Object> resume;
        if (!gateway.isEnabled()) {
            resume = new LinkedHashMap<>();
            resume.put("ok", false);
            resume.put("error", "LangGraph disabled");
        } else {
            String body = gateway.buildResumeStreamBody(run.getRunId(), approved,
                    LangChainGateway.runOwner(run.getUserId(), run.getTenantId()), claim.delegatedToken);
            SseStreamReader.readSse(gateway.resumeWorkflowStreamUrl(), body, gateway.serviceAuthorizationHeader(),
                    new SseStreamReader.SseEventHandler() {
                        private boolean stopIfCancelled(boolean force) {
                            long nowMs = host.cancelCheckNow();
                            if (!force && nowMs < nextCancelCheckAt[0]) {
                                return false;
                            }
                            nextCancelCheckAt[0] = nowMs + host.streamCancelCheckIntervalMs();
                            if (!host.isCancelRequestedNow(runId)) {
                                return false;
                            }
                            cancelled.set(true);
                            outcome.set(resumeOutcome(false, trace, null, "cancelled"));
                            readerHandle.cancel();
                            return true;
                        }

                        @Override public void onEvent(String eventName, String data) {
                            if (outcome.get() != null) {
                                return;
                            }
                            if (stopIfCancelled(isTerminalResumeEvent(eventName))) {
                                return;
                            }
                            String payloadJson = data == null ? "{}" : data;
                            if (WorkflowService.isStreamV2Event(eventName)) {
                                if ("tool_execution_end".equals(eventName)) {
                                    Map<String, Object> audit = WorkflowService.toolExecutionEndAudit(host.parseJsonMap(data));
                                    String nodeId = host.string(audit.get("nodeId"));
                                    host.recordRunEvent(run, "TOOL_EXECUTION_END", null,
                                            nodeId == null || nodeId.isEmpty() ? null : nodeId, "RUNNING",
                                            "Tool finished after approval.", audit, 957000 + sequence.incrementAndGet());
                                }
                                sendQuietly(emitter, eventName, payloadJson);
                            } else if ("node_update".equals(eventName)) {
                                trace.add(host.parseJsonMap(data));
                                sendQuietly(emitter, eventName, payloadJson);
                            } else if (WorkflowService.isMessageQueueEvent(eventName) || "degraded".equals(eventName)) {
                                sendQuietly(emitter, eventName, payloadJson);
                            } else if ("human_interrupt".equals(eventName)) {
                                Map<String, Object> finalState = new LinkedHashMap<>();
                                finalState.put("interrupted", true);
                                outcome.set(resumeOutcome(true, trace, finalState, null));
                                terminal[0] = eventName;
                                terminal[1] = payloadJson;
                            } else if ("workflow_complete".equals(eventName)) {
                                Map<String, Object> payload = host.parseJsonMap(data);
                                Map<String, Object> finalState = payload.get("final_state") instanceof Map
                                        ? castMap(payload.get("final_state")) : new LinkedHashMap<String, Object>();
                                outcome.set(resumeOutcome(true, trace, finalState, null));
                                terminal[0] = eventName;
                                terminal[1] = payloadJson;
                            } else if ("error".equals(eventName) || "cancelled".equals(eventName)) {
                                Map<String, Object> payload = host.parseJsonMap(data);
                                String error = host.string(payload.get("error"));
                                outcome.set(resumeOutcome(false, trace, null,
                                        error == null || error.isEmpty() ? eventName : error));
                                terminal[0] = "error";
                                terminal[1] = payloadJson;
                            }
                        }

                        @Override public void onError(Exception ex) {
                            if (outcome.get() == null && stopIfCancelled(true)) {
                                return;
                            }
                            if (outcome.get() == null) {
                                outcome.set(resumeOutcome(false, trace, null, "Stream error: " + ex.getMessage()));
                            }
                        }

                        @Override public void onComplete() {
                            // terminal handling happens after the reader returns
                        }
                    }, readerHandle);
            resume = outcome.get() != null ? outcome.get()
                    : resumeOutcome(false, trace, null, "resume stream ended without a terminal event");
        }
        if (cancelled.get() || host.isCancelRequestedNow(runId)) {
            finishApprovalAsCancelled(run, emitter);
            return;
        }
        WorkflowRun updated = applyResumeOutcome(run, approved, claim.audit, resume);
        if (terminal[0] != null) {
            sendQuietly(emitter, terminal[0], terminal[1]);
        } else {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("ok", false);
            error.put("error", host.string(resume.get("error")));
            sendQuietly(emitter, "error", host.toJson(error));
        }
        sendQuietly(emitter, "run_state", host.toJson(updated));
        completeQuietly(emitter);
    }

    private static boolean isTerminalResumeEvent(String eventName) {
        return "human_interrupt".equals(eventName) || "workflow_complete".equals(eventName)
                || "error".equals(eventName) || "cancelled".equals(eventName);
    }

    /**
     * D10a: the run was cancelled while its approval resume was streaming. The cancel wins: the run ends
     * CANCELLED (never overwritten by the resume outcome), the orchestrator is asked to stop the resumed
     * graph (best effort), and the client gets {@code workflow_cancelled} + {@code run_state}.
     */
    private void finishApprovalAsCancelled(WorkflowRun run, SseEmitter emitter) {
        WorkflowRun latest = mapper.findRun(run.getRunId());
        if (latest != null && !"CANCELLED".equals(latest.getStatus())) {
            latest.setStatus("CANCELLED");
            latest.setControlStatus("CANCELLED");
            latest.setCancelRequested(1);
            latest.setPauseRequested(0);
            latest.setCompletedAt(host.now());
            if (mapper.updateRunStatusIf(latest, java.util.Arrays.asList("PAUSED", "RUNNING"), false) > 0) {
                host.recordRunEvent(latest, "RUN_CANCELLED", null, null, "CANCELLED",
                        "Workflow run cancelled during approval resume.", null, 1000000);
            }
        }
        host.requestOrchestratorCancel(run);
        sendQuietly(emitter, "workflow_cancelled", "{\"ok\":false,\"cancelled\":true}");
        sendQuietly(emitter, "run_state", host.toJson(mapper.findRun(run.getRunId())));
        completeQuietly(emitter);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    /** Resume outcome in the shape of the sync /resume-workflow response (trace, node_outputs, final_state). */
    private static Map<String, Object> resumeOutcome(boolean ok, List<Object> trace, Map<String, Object> finalState,
                                                     String error) {
        Map<String, Object> resume = new LinkedHashMap<>();
        resume.put("ok", ok);
        resume.put("trace", new ArrayList<Object>(trace));
        if (finalState != null) {
            resume.put("final_state", finalState);
            if (finalState.get("nodes") instanceof Map) {
                resume.put("node_outputs", finalState.get("nodes"));
            }
        }
        if (error != null) {
            resume.put("error", error);
        }
        return resume;
    }

    /* A gone client never stops the resume: the run state must still be written. */
    private static void sendQuietly(SseEmitter emitter, String name, String data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data == null ? "{}" : data));
        } catch (Exception ignored) {
            // client disconnected
        }
    }

    private static void completeQuietly(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception ignored) {
            // already completed
        }
    }

    /** Insert node runs for trace entries of the resumed graph that were not recorded before the pause. */
    @SuppressWarnings("unchecked")
    private int materializeResumeTrace(WorkflowRun run, Map<String, Object> resume) {
        if (resume == null || !(resume.get("trace") instanceof List)) {
            return 0;
        }
        try {
            List<Object> trace = (List<Object>) resume.get("trace");
            Map<String, Object> nodeOutputs = resume.get("node_outputs") instanceof Map
                    ? (Map<String, Object>) resume.get("node_outputs") : new LinkedHashMap<String, Object>();
            java.util.Set<String> recorded = new java.util.HashSet<String>();
            int maxSort = 0;
            List<WorkflowNodeRun> existing = mapper.findNodeRuns(run.getRunId());
            if (existing != null) {
                for (WorkflowNodeRun nodeRun : existing) {
                    if (nodeRun.getNodeId() != null) {
                        recorded.add(nodeRun.getNodeId());
                    }
                    if (nodeRun.getSortOrder() != null) {
                        maxSort = Math.max(maxSort, nodeRun.getSortOrder());
                    }
                }
            }
            Map<String, Object> layout = host.layoutForRun(run.getWorkflowKey(), run.getWorkflowVersionId());
            return host.materializeOrchestratorTrace(run, layout, trace, nodeOutputs, recorded, maxSort, 956000);
        } catch (Exception ex) {
            log.warn("[WorkflowService] resume trace materialization failed runId={} error={}",
                    run.getRunId(), ex.getClass().getSimpleName());
            return 0;
        }
    }

    @SuppressWarnings("unchecked")
    private boolean isInterruptedAgain(Map<String, Object> resume) {
        Object finalState = resume.get("final_state");
        return finalState instanceof Map && Boolean.TRUE.equals(((Map<String, Object>) finalState).get("interrupted"));
    }

    private boolean isApprovalDecidedOrInFlight(WorkflowRun run) {
        if (run == null) {
            return false;
        }
        if (APPROVAL_IN_PROGRESS.equals(run.getControlStatus()) || "REJECTED".equals(run.getStatus())) {
            return true;
        }
        Map<String, Object> result = host.parseJsonMap(run.getResultJson());
        return result.get("approval") != null;
    }

    private boolean isResumeOk(Map<String, Object> resume) {
        if (resume == null) {
            return false;
        }
        Object ok = resume.get("ok");
        if (ok instanceof Boolean) {
            return (Boolean) ok;
        }
        return "true".equalsIgnoreCase(host.string(ok));
    }

    @SuppressWarnings("unchecked")
    static boolean requireApprovalRequested(Map<String, Object> inputs, Map<String, Object> layout) {
        if (isTruthyFlag(inputs, "requireApproval") || isTruthyFlag(inputs, "require_approval")) {
            return true;
        }
        if (layout == null || !(layout.get("metadata") instanceof Map)) {
            return false;
        }
        return isTruthyFlag((Map<String, Object>) layout.get("metadata"), "requireApproval")
                || isTruthyFlag((Map<String, Object>) layout.get("metadata"), "require_approval");
    }

    private static boolean isTruthyFlag(Map<String, Object> source, String key) {
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
}
