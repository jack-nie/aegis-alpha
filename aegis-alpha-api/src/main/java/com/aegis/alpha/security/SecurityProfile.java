package com.aegis.alpha.security;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public class SecurityProfile {
    private final AuthenticatedPrincipal principal;

    public SecurityProfile(AuthenticatedPrincipal principal) {
        this.principal = principal;
    }

    @JsonProperty("user_id")
    public String getUserId() {
        return principal.getUserId();
    }

    public String getUsername() {
        return principal.getUsername();
    }

    @JsonProperty("tenant_id")
    public String getTenantId() {
        return principal.getTenantId();
    }

    public List<String> getRoles() {
        return principal.getRoles();
    }
}
