package com.marketmind.alpha.domain;

import java.math.BigDecimal;

public class LlmCall {
    private String llmCallId;
    private String workflowRunId;
    private String nodeRunId;
    private String traceId;
    private String provider;
    private String modelName;
    private String status;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;
    private BigDecimal estimatedCostUsd;
    private String startedAt;
    private String completedAt;

    public String getLlmCallId() { return llmCallId; }
    public void setLlmCallId(String llmCallId) { this.llmCallId = llmCallId; }
    public String getWorkflowRunId() { return workflowRunId; }
    public void setWorkflowRunId(String workflowRunId) { this.workflowRunId = workflowRunId; }
    public String getNodeRunId() { return nodeRunId; }
    public void setNodeRunId(String nodeRunId) { this.nodeRunId = nodeRunId; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getPromptTokens() { return promptTokens; }
    public void setPromptTokens(Integer promptTokens) { this.promptTokens = promptTokens; }
    public Integer getCompletionTokens() { return completionTokens; }
    public void setCompletionTokens(Integer completionTokens) { this.completionTokens = completionTokens; }
    public Integer getTotalTokens() { return totalTokens; }
    public void setTotalTokens(Integer totalTokens) { this.totalTokens = totalTokens; }
    public BigDecimal getEstimatedCostUsd() { return estimatedCostUsd; }
    public void setEstimatedCostUsd(BigDecimal estimatedCostUsd) { this.estimatedCostUsd = estimatedCostUsd; }
    public String getStartedAt() { return startedAt; }
    public void setStartedAt(String startedAt) { this.startedAt = startedAt; }
    public String getCompletedAt() { return completedAt; }
    public void setCompletedAt(String completedAt) { this.completedAt = completedAt; }
}
