package com.aegis.alpha.service.help;

import com.aegis.alpha.security.AuthenticatedPrincipal;
import com.aegis.alpha.service.CacheService;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Layer-1 help answer cache. Private entity queries must never get/put.
 *
 * <p>Entries are partitioned by tenant and by the caller's (sorted) role set, so an answer that
 * was assembled from role-restricted documents can never be served to a caller with fewer roles.
 * Answers built from known non-public sources are not cached at all. The in-process fallback
 * layer is bounded (LRU) and honours the same TTL as Redis.</p>
 */
@Service
public class HelpAnswerCacheService {
    private static final Duration TTL = Duration.ofMinutes(30);
    private static final String KEY_PREFIX = "help:answer:v2:";
    static final int LOCAL_MAX_ENTRIES = 1000;
    /** Per-request diagnostics that must not be replayed to other callers. */
    private static final List<String> DIAGNOSTIC_FIELDS = Arrays.asList(
            "helpLoop", "knowledge", "appliedFilter", "threadId", "traceId");

    private final CacheService cacheService;
    /** Local fallback when Redis unavailable (dev/tests): bounded LRU with TTL. */
    private final LinkedHashMap<String, LocalEntry> local = new LinkedHashMap<String, LocalEntry>(16, 0.75f, true);
    private LongSupplier clock = System::currentTimeMillis;

    public HelpAnswerCacheService(CacheService cacheService) {
        this.cacheService = cacheService;
    }

    public String normalizeMessage(String message) {
        if (message == null) {
            return "";
        }
        return message.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    /** Partitioned key: {@code help:answer:v2:{tenant}:{sha256(sorted roles)}:{sha256(message)}}. */
    public String cacheKey(String normalizedMessage, AuthenticatedPrincipal principal) {
        String tenant = principal == null || principal.getTenantId() == null || principal.getTenantId().trim().isEmpty()
                ? "-"
                : principal.getTenantId().trim();
        List<String> roles = new ArrayList<String>();
        if (principal != null && principal.getRoles() != null) {
            for (String role : principal.getRoles()) {
                if (role != null && !role.trim().isEmpty()) {
                    roles.add(role.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        Collections.sort(roles);
        return KEY_PREFIX + sha256Hex(tenant).substring(0, 16) + ":" + sha256Hex(String.join(",", roles)) + ":"
                + sha256Hex(normalizedMessage);
    }

    /**
     * @return cached chat response map for this caller's tenant/role partition, or null
     */
    public Map<String, Object> getIfAllowed(String message, HelpIntent.Result intent, AuthenticatedPrincipal principal) {
        if (!isCacheable(intent, message)) {
            return null;
        }
        String key = cacheKey(normalizeMessage(message), principal);
        try {
            Map<?, ?> fromRedis = cacheService.get(key, Map.class);
            if (fromRedis != null) {
                return copyAsStringObjectMap(fromRedis);
            }
        } catch (Exception ignored) {
            // fall through to local
        }
        synchronized (local) {
            LocalEntry entry = local.get(key);
            if (entry == null) {
                return null;
            }
            if (clock.getAsLong() >= entry.expiresAt) {
                local.remove(key);
                return null;
            }
            return new LinkedHashMap<String, Object>(entry.value);
        }
    }

    public void putIfAllowed(String message, HelpIntent.Result intent, Map<String, Object> response,
                             AuthenticatedPrincipal principal) {
        if (!isCacheable(intent, message) || response == null) {
            return;
        }
        // Never cache diagnosis / private-looking responses
        if (Boolean.TRUE.equals(response.get("escalateHuman"))
                || response.get("runId") != null
                || "help-diagnosis".equals(response.get("provider"))
                || "help-safety".equals(response.get("provider"))) {
            return;
        }
        if (!publicSourcesOnly(response, principal)) {
            return;
        }
        String key = cacheKey(normalizeMessage(message), principal);
        Map<String, Object> stored = new LinkedHashMap<String, Object>(response);
        for (String field : DIAGNOSTIC_FIELDS) {
            stored.remove(field);
        }
        stored.put("cacheHit", true);
        try {
            cacheService.put(key, stored, TTL);
        } catch (Exception ignored) {
            // local only
        }
        synchronized (local) {
            local.put(key, new LocalEntry(stored, clock.getAsLong() + TTL.toMillis()));
            while (local.size() > LOCAL_MAX_ENTRIES) {
                local.remove(local.keySet().iterator().next());
            }
        }
    }

    /**
     * True when the answer is known to come only from public material. Knowledge hits carry their
     * visibility; a remote help-loop answer for a role-bearing caller has unknown provenance.
     */
    @SuppressWarnings("unchecked")
    static boolean publicSourcesOnly(Map<String, Object> response, AuthenticatedPrincipal principal) {
        Object knowledge = response.get("knowledge");
        if (knowledge instanceof Map) {
            Object hits = ((Map<String, Object>) knowledge).get("hits");
            if (hits instanceof List) {
                for (Object hit : (List<Object>) hits) {
                    if (hit instanceof Map) {
                        Object visibility = ((Map<String, Object>) hit).get("visibility");
                        if (visibility != null && !"public".equalsIgnoreCase(String.valueOf(visibility).trim())) {
                            return false;
                        }
                    }
                }
            }
        }
        boolean elevated = principal != null && principal.getRoles() != null && !principal.getRoles().isEmpty();
        return !(elevated && "help-loop-orch".equals(response.get("provider")));
    }

    public boolean isCacheable(HelpIntent.Result intent, String message) {
        if (intent != null && !intent.isCacheable()) {
            return false;
        }
        if (intent != null && intent.getEntities() != null) {
            HelpIntent.Entities e = intent.getEntities();
            if (notEmpty(e.getRunId()) || notEmpty(e.getPortfolioId()) || notEmpty(e.getRecommendationId())) {
                return false;
            }
        }
        String text = message == null ? "" : message.toLowerCase(Locale.ROOT);
        if (text.contains("run_id") || text.contains("run-id") || text.contains("持仓")
                || text.contains("组合明细") || text.contains("我的仓位") || text.contains("portfolio")) {
            return false;
        }
        // UUID-like run ids in free text
        if (text.matches("(?s).*[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}.*")) {
            return false;
        }
        return intent == null || intent.getDomain() == HelpIntent.Domain.PRODUCT_HELP;
    }

    /** Test helper: clear local cache layer. */
    public void clearLocalForTest() {
        synchronized (local) {
            local.clear();
        }
    }

    int localSizeForTest() {
        synchronized (local) {
            return local.size();
        }
    }

    void setClock(LongSupplier clock) {
        this.clock = clock;
    }

    private static final class LocalEntry {
        private final Map<String, Object> value;
        private final long expiresAt;

        private LocalEntry(Map<String, Object> value, long expiresAt) {
            this.value = value;
            this.expiresAt = expiresAt;
        }
    }

    private static boolean notEmpty(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static Map<String, Object> copyAsStringObjectMap(Map<?, ?> raw) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> e : raw.entrySet()) {
            if (e.getKey() != null) {
                out.put(String.valueOf(e.getKey()), e.getValue());
            }
        }
        return out;
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception ex) {
            return Integer.toHexString((value == null ? "" : value).hashCode());
        }
    }
}
