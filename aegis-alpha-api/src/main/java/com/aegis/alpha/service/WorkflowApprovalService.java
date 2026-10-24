package com.aegis.alpha.service;

import com.aegis.alpha.domain.WorkflowNodeRun;
import com.aegis.alpha.domain.WorkflowRun;
import com.aegis.alpha.mapper.WorkflowMapper;
import com.aegis.alpha.security.AuthenticatedPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        WorkflowRun run = host.requireRun(runId);
        if (!host.isHumanInterrupt(run)) {
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
        host.recordRunEvent(run, "RUN_APPROVAL_DECISION", null, null, "PAUSED",
                approved ? "Approval submitted." : "Rejection submitted.", audit, 955000);

        /* P4-5: fresh run-scoped delegation for the run owner (never the approver's identity). */
        String delegatedToken = approved
                ? host.issueDelegatedPortfolioReadToken(run.getRunId(), run.getUserId(), run.getTenantId(), null)
                : null;
        Map<String, Object> resume = gateway.resumeWorkflow(run.getRunId(), approved,
                LangChainGateway.runOwner(run.getUserId(), run.getTenantId()), delegatedToken);
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
