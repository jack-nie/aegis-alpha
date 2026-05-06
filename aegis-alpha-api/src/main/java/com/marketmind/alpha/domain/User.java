package com.marketmind.alpha.domain;

public class User {
    private String userId;
    private String username;
    private String passwordHash;
    private String tenantId;
    private String roles;

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getRoles() { return roles; }
    public void setRoles(String roles) { this.roles = roles; }
}
