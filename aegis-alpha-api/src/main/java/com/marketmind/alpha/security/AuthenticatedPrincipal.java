package com.marketmind.alpha.security;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class AuthenticatedPrincipal {
    private final String userId;
    private final String username;
    private final String tenantId;
    private final List<String> roles;

    public AuthenticatedPrincipal(String userId, String username, String tenantId, List<String> roles) {
        this.userId = userId;
        this.username = username;
        this.tenantId = tenantId;
        this.roles = roles == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(roles));
    }

    public String getUserId() {
        return userId;
    }

    public String getUsername() {
        return username;
    }

    public String getTenantId() {
        return tenantId;
    }

    public List<String> getRoles() {
        return roles;
    }

    public boolean hasRole(String role) {
        return role != null && roles.contains(role);
    }
}
