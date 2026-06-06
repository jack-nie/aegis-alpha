package com.aegis.alpha.domain;

import java.util.List;
import java.util.Map;

public class WorkflowRun {
    private String runId;
    private String workflowKey;
    private String traceId;
    private String status;
    private String subject;
    private String startedAt;
    private String completedAt;
    private String resultJson;
    private String errorMessage;
    private Integer nodeCount;
    private String idempotencyKey;
    private String workflowVersionId;
    private String inputsJson;
    private String controlStatus;
    private Integer pauseRequested;
    private Integer cancelRequested;
    private String queuedAt;
    private List<String> availableActions;
    private Map<String, String> actionReasons;
    private Map<String, Object> lastEvent;
    private String priority;

    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public String getWorkflowKey() { return workflowKey; }
    public void setWorkflowKey(String workflowKey) { this.workflowKey = workflowKey; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }
    public String getStartedAt() { return startedAt; }
    public void setStartedAt(String startedAt) { this.startedAt = startedAt; }
    public String getCompletedAt() { return completedAt; }
    public void setCompletedAt(String completedAt) { this.completedAt = completedAt; }
    public String getResultJson() { return resultJson; }
    public void setResultJson(String resultJson) { this.resultJson = resultJson; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public Integer getNodeCount() { return nodeCount; }
    public void setNodeCount(Integer nodeCount) { this.nodeCount = nodeCount; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public String getWorkflowVersionId() { return workflowVersionId; }
    public void setWorkflowVersionId(String workflowVersionId) { this.workflowVersionId = workflowVersionId; }
    public String getInputsJson() { return inputsJson; }
    public void setInputsJson(String inputsJson) { this.inputsJson = inputsJson; }
    public String getControlStatus() { return controlStatus; }
    public void setControlStatus(String controlStatus) { this.controlStatus = controlStatus; }
    public Integer getPauseRequested() { return pauseRequested; }
    public void setPauseRequested(Integer pauseRequested) { this.pauseRequested = pauseRequested; }
    public Integer getCancelRequested() { return cancelRequested; }
    public void setCancelRequested(Integer cancelRequested) { this.cancelRequested = cancelRequested; }
    public String getQueuedAt() { return queuedAt; }
    public void setQueuedAt(String queuedAt) { this.queuedAt = queuedAt; }
    public List<String> getAvailableActions() { return availableActions; }
    public void setAvailableActions(List<String> availableActions) { this.availableActions = availableActions; }
    public Map<String, String> getActionReasons() { return actionReasons; }
    public void setActionReasons(Map<String, String> actionReasons) { this.actionReasons = actionReasons; }
    public Map<String, Object> getLastEvent() { return lastEvent; }
    public void setLastEvent(Map<String, Object> lastEvent) { this.lastEvent = lastEvent; }
    public String getPriority() { return priority; }
    public void setPriority(String priority) { this.priority = priority; }
}
