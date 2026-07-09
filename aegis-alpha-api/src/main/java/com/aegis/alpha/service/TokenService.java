package com.aegis.alpha.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class TokenService {
    private final String secret;
    private final ObjectMapper objectMapper;

    public TokenService(@Value("${aegis.token-secret}") String secret, ObjectMapper objectMapper) {
        this.secret = secret;
        this.objectMapper = objectMapper;
    }

    public String issue(String username, String userId, String tenantId) {
        return issue(username, userId, tenantId, Collections.<String>emptyList());
    }

    public String issue(String username, String userId, String tenantId, List<String> roles) {
        try {
            Map<String, Object> payload = new HashMap<String, Object>();
            // User login tokens intentionally omit typ; consumers treat missing typ as user token.
            payload.put("sub", username);
            payload.put("uid", userId);
            payload.put("tenantId", tenantId);
            payload.put("roles", roles == null ? Collections.<String>emptyList() : roles);
            payload.put("exp", System.currentTimeMillis() + 24L * 60L * 60L * 1000L);
            String encoded = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(objectMapper.writeValueAsBytes(payload));
            return encoded + "." + sign(encoded);
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot issue token", ex);
        }
    }

    /**
     * Issue a short-lived run-scoped service delegation token (HmacSHA256, same format as user tokens).
     * Payload: typ=delegation, runId, uid, tenantId, scopes, exp.
     */
    public String issueServiceDelegation(String runId, String userId, String tenantId, List<String> scopes, long ttlMs) {
        try {
            Map<String, Object> payload = new HashMap<String, Object>();
            payload.put("typ", "delegation");
            payload.put("runId", runId);
            payload.put("uid", userId);
            payload.put("tenantId", tenantId);
            payload.put("scopes", scopes == null ? Collections.<String>emptyList() : scopes);
            // ttlMs may be non-positive for expired-token tests; production callers should pass positive TTL.
            payload.put("exp", System.currentTimeMillis() + ttlMs);
            String encoded = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(objectMapper.writeValueAsBytes(payload));
            return encoded + "." + sign(encoded);
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot issue delegation token", ex);
        }
    }

    /**
     * True when token verifies, typ is delegation, not expired, and scopes contains requiredScope.
     * Fail closed on bad signature / missing fields.
     */
    public boolean hasDelegationScope(String token, String requiredScope) {
        if (token == null || requiredScope == null || requiredScope.isEmpty()) {
            return false;
        }
        Map<String, Object> payload = verify(token);
        if (payload == null) {
            return false;
        }
        if (!"delegation".equals(payload.get("typ"))) {
            return false;
        }
        Object scopesValue = payload.get("scopes");
        if (!(scopesValue instanceof List)) {
            return false;
        }
        return ((List<?>) scopesValue).contains(requiredScope);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> verify(String token) {
        try {
            if (token == null || !token.contains(".")) {
                return null;
            }
            String[] parts = token.split("\\.", 2);
            if (!sign(parts[0]).equals(parts[1])) {
                return null;
            }
            Map<String, Object> payload = objectMapper.readValue(Base64.getUrlDecoder().decode(parts[0]), Map.class);
            Number exp = (Number) payload.get("exp");
            return exp != null && exp.longValue() > System.currentTimeMillis() ? payload : null;
        } catch (Exception ex) {
            return null;
        }
    }

    private String sign(String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    }
}
