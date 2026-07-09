package com.aegis.alpha.service;

import com.aegis.alpha.audit.AuditService;
import com.aegis.alpha.domain.User;
import com.aegis.alpha.mapper.UserMapper;
import com.aegis.alpha.security.AuthenticatedPrincipal;
import com.aegis.alpha.security.SecurityProfile;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class AuthService {
    private final UserMapper userMapper;
    private final TokenService tokenService;
    private final AuditService auditService;

    public AuthService(UserMapper userMapper, TokenService tokenService, AuditService auditService) {
        this.userMapper = userMapper;
        this.tokenService = tokenService;
        this.auditService = auditService;
    }

    public Map<String, Object> login(String username, String password) {
        User user = userMapper.findByUsername(username);
        if (user == null || !user.getPasswordHash().equals(hash(password))) {
            auditService.recordLoginFailure(username, user == null ? null : user.getTenantId(), user == null ? null : user.getUserId());
            throw new IllegalArgumentException("\u8d26\u53f7\u6216\u5bc6\u7801\u9519\u8bef\u3002");
        }
        AuthenticatedPrincipal principal = toPrincipal(user);
        auditService.recordPrincipalAction(principal, "auth.login.success", "user", user.getUsername());

        Map<String, Object> response = new HashMap<String, Object>();
        response.put("access_token", tokenService.issue(user.getUsername(), user.getUserId(), user.getTenantId(), principal.getRoles()));
        response.put("token_type", "bearer");
        response.put("tenant_id", user.getTenantId());
        response.put("roles", principal.getRoles());
        return response;
    }

    public Map<String, Object> me(String bearerToken) {
        AuthenticatedPrincipal principal = principal(bearerToken);
        if (principal == null) {
            return null;
        }
        Map<String, Object> response = new HashMap<String, Object>();
        response.put("user_id", principal.getUserId());
        response.put("username", principal.getUsername());
        response.put("tenant_id", principal.getTenantId());
        response.put("roles", principal.getRoles());
        return response;
    }

    public AuthenticatedPrincipal principal(String bearerToken) {
        Map<String, Object> payload = tokenService.verify(extractToken(bearerToken));
        if (payload == null) {
            return null;
        }
        // Delegation tokens must not authenticate as interactive users (no typ = user token).
        if ("delegation".equals(payload.get("typ"))) {
            return null;
        }
        User user = userMapper.findByUsername(String.valueOf(payload.get("sub")));
        return user == null ? null : toPrincipal(user);
    }

    public SecurityProfile profile(String bearerToken) {
        AuthenticatedPrincipal principal = principal(bearerToken);
        return principal == null ? null : new SecurityProfile(principal);
    }

    public static String hash(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte b : bytes) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private AuthenticatedPrincipal toPrincipal(User user) {
        return new AuthenticatedPrincipal(user.getUserId(), user.getUsername(), user.getTenantId(), roles(user.getRoles()));
    }

    private List<String> roles(String value) {
        List<String> roles = new ArrayList<String>();
        if (value == null || value.trim().isEmpty()) {
            return roles;
        }
        String[] parts = value.split(",");
        for (String part : parts) {
            String role = part == null ? "" : part.trim();
            if (!role.isEmpty()) {
                roles.add(role);
            }
        }
        return roles;
    }

    private String extractToken(String bearerToken) {
        return bearerToken != null && bearerToken.startsWith("Bearer ") ? bearerToken.substring(7) : "";
    }
}
