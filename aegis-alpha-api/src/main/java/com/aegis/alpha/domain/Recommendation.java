package com.aegis.alpha.domain;

import java.math.BigDecimal;

public class Recommendation {
    private String recommendationId;
    private String workflowRunId;
    private String backtestRunId;
    private String traceId;
    private String symbol;
    private String recommendation;
    private BigDecimal confidence;
    private String timeHorizon;
    private String rationaleJson;
    private String riskJson;
    private String missingDataJson;
    private String disclaimer;
    private String approvalStatus;
    private String createdAt;

    public String getRecommendationId() { return recommendationId; }
    public void setRecommendationId(String recommendationId) { this.recommendationId = recommendationId; }
    public String getWorkflowRunId() { return workflowRunId; }
    public void setWorkflowRunId(String workflowRunId) { this.workflowRunId = workflowRunId; }
    public String getBacktestRunId() { return backtestRunId; }
    public void setBacktestRunId(String backtestRunId) { this.backtestRunId = backtestRunId; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public String getRecommendation() { return recommendation; }
    public void setRecommendation(String recommendation) { this.recommendation = recommendation; }
    public BigDecimal getConfidence() { return confidence; }
    public void setConfidence(BigDecimal confidence) { this.confidence = confidence; }
    public String getTimeHorizon() { return timeHorizon; }
    public void setTimeHorizon(String timeHorizon) { this.timeHorizon = timeHorizon; }
    public String getRationaleJson() { return rationaleJson; }
    public void setRationaleJson(String rationaleJson) { this.rationaleJson = rationaleJson; }
    public String getRiskJson() { return riskJson; }
    public void setRiskJson(String riskJson) { this.riskJson = riskJson; }
    public String getMissingDataJson() { return missingDataJson; }
    public void setMissingDataJson(String missingDataJson) { this.missingDataJson = missingDataJson; }
    public String getDisclaimer() { return disclaimer; }
    public void setDisclaimer(String disclaimer) { this.disclaimer = disclaimer; }
    public String getApprovalStatus() { return approvalStatus; }
    public void setApprovalStatus(String approvalStatus) { this.approvalStatus = approvalStatus; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
}
