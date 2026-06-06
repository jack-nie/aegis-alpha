package com.aegis.alpha.domain;

public class AgentTemplate {
    private String agentId;
    private String name;
    private String description;
    private String category;
    private String tags;
    private String prompt;
    private String modelName;
    private String toolsJson;
    private String status;
    private String scheduleCron;
    private String lastRunAt;
    private int inputCount;
    private int outputCount;
    private int toolCount;
    private boolean systemPreset;
    private boolean readonlyFlag;
    private String ownerUsername;
    private int sortOrder;
    private String updatedAt;

    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }
    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }
    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }
    public String getToolsJson() { return toolsJson; }
    public void setToolsJson(String toolsJson) { this.toolsJson = toolsJson; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getScheduleCron() { return scheduleCron; }
    public void setScheduleCron(String scheduleCron) { this.scheduleCron = scheduleCron; }
    public String getLastRunAt() { return lastRunAt; }
    public void setLastRunAt(String lastRunAt) { this.lastRunAt = lastRunAt; }
    public int getInputCount() { return inputCount; }
    public void setInputCount(int inputCount) { this.inputCount = inputCount; }
    public int getOutputCount() { return outputCount; }
    public void setOutputCount(int outputCount) { this.outputCount = outputCount; }
    public int getToolCount() { return toolCount; }
    public void setToolCount(int toolCount) { this.toolCount = toolCount; }
    public boolean isSystemPreset() { return systemPreset; }
    public void setSystemPreset(boolean systemPreset) { this.systemPreset = systemPreset; }
    public boolean isReadonlyFlag() { return readonlyFlag; }
    public void setReadonlyFlag(boolean readonlyFlag) { this.readonlyFlag = readonlyFlag; }
    public String getOwnerUsername() { return ownerUsername; }
    public void setOwnerUsername(String ownerUsername) { this.ownerUsername = ownerUsername; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
}
