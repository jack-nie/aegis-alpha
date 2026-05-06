package com.marketmind.alpha.service;

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

    public TokenService(@Value("${marketmind.token-secret}") String secret, ObjectMapper objectMapper) {
        this.secret = secret;
        this.objectMapper = objectMapper;
    }

    public String issue(String username, String userId, String tenantId) {
        return issue(username, userId, tenantId, Collections.<String>emptyList());
    }

    public String issue(String username, String userId, String tenantId, List<String> roles) {
        try {
            Map<String, Object> payload = new HashMap<String, Object>();
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
