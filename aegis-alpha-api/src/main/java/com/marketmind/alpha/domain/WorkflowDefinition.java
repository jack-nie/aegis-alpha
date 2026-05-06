package com.marketmind.alpha.domain;

public class WorkflowDefinition {
    private String workflowKey;
    private String name;
    private String description;
    private String engine;
    private Integer version;
    private Integer nodes;
    private Integer edges;
    private boolean readonlyFlag;
    private String ownerUsername;
    private String updatedAt;

    public String getWorkflowKey() { return workflowKey; }
    public void setWorkflowKey(String workflowKey) { this.workflowKey = workflowKey; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getEngine() { return engine; }
    public void setEngine(String engine) { this.engine = engine; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public Integer getNodes() { return nodes; }
    public void setNodes(Integer nodes) { this.nodes = nodes; }
    public Integer getEdges() { return edges; }
    public void setEdges(Integer edges) { this.edges = edges; }
    public boolean isReadonlyFlag() { return readonlyFlag; }
    public void setReadonlyFlag(boolean readonlyFlag) { this.readonlyFlag = readonlyFlag; }
    public String getOwnerUsername() { return ownerUsername; }
    public void setOwnerUsername(String ownerUsername) { this.ownerUsername = ownerUsername; }
    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
}
