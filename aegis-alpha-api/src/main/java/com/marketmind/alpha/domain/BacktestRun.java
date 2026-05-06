package com.marketmind.alpha.domain;

import java.math.BigDecimal;

public class BacktestRun {
    private String id;
    private String runName;
    private String strategy;
    private String status;
    private BigDecimal totalReturnPct;
    private BigDecimal sharpe;
    private String startedAt;
    private String completedAt;
    private String workflowRunId;
    private String traceId;
    private String subject;
    private String symbol;
    private String inputsJson;
    private String resultJson;
    private String errorMessage;
    private Integer nodeCount;
    private String finalRecommendation;
    private BigDecimal confidence;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getRunName() { return runName; }
    public void setRunName(String runName) { this.runName = runName; }
    public String getStrategy() { return strategy; }
    public void setStrategy(String strategy) { this.strategy = strategy; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public BigDecimal getTotalReturnPct() { return totalReturnPct; }
    public void setTotalReturnPct(BigDecimal totalReturnPct) { this.totalReturnPct = totalReturnPct; }
    public BigDecimal getSharpe() { return sharpe; }
    public void setSharpe(BigDecimal sharpe) { this.sharpe = sharpe; }
    public String getStartedAt() { return startedAt; }
    public void setStartedAt(String startedAt) { this.startedAt = startedAt; }
    public String getCompletedAt() { return completedAt; }
    public void setCompletedAt(String completedAt) { this.completedAt = completedAt; }
    public String getWorkflowRunId() { return workflowRunId; }
    public void setWorkflowRunId(String workflowRunId) { this.workflowRunId = workflowRunId; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public String getInputsJson() { return inputsJson; }
    public void setInputsJson(String inputsJson) { this.inputsJson = inputsJson; }
    public String getResultJson() { return resultJson; }
    public void setResultJson(String resultJson) { this.resultJson = resultJson; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public Integer getNodeCount() { return nodeCount; }
    public void setNodeCount(Integer nodeCount) { this.nodeCount = nodeCount; }
    public String getFinalRecommendation() { return finalRecommendation; }
    public void setFinalRecommendation(String finalRecommendation) { this.finalRecommendation = finalRecommendation; }
    public BigDecimal getConfidence() { return confidence; }
    public void setConfidence(BigDecimal confidence) { this.confidence = confidence; }
}
