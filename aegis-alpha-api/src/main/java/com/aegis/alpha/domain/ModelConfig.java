package com.aegis.alpha.domain;

import java.math.BigDecimal;

public class ModelConfig {
    private String modelConfigId;
    private String provider;
    private String modelName;
    private String status;
    private Integer contextWindow;
    private BigDecimal promptTokenCostUsd;
    private BigDecimal completionTokenCostUsd;
    private String fallbackModel;
    private String createdAt;

    public String getModelConfigId() { return modelConfigId; }
    public void setModelConfigId(String modelConfigId) { this.modelConfigId = modelConfigId; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getContextWindow() { return contextWindow; }
    public void setContextWindow(Integer contextWindow) { this.contextWindow = contextWindow; }
    public BigDecimal getPromptTokenCostUsd() { return promptTokenCostUsd; }
    public void setPromptTokenCostUsd(BigDecimal promptTokenCostUsd) { this.promptTokenCostUsd = promptTokenCostUsd; }
    public BigDecimal getCompletionTokenCostUsd() { return completionTokenCostUsd; }
    public void setCompletionTokenCostUsd(BigDecimal completionTokenCostUsd) { this.completionTokenCostUsd = completionTokenCostUsd; }
    public String getFallbackModel() { return fallbackModel; }
    public void setFallbackModel(String fallbackModel) { this.fallbackModel = fallbackModel; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
}
