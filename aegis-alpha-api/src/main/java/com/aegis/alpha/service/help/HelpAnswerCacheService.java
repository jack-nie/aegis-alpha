package com.aegis.alpha.service.help;

import com.aegis.alpha.service.CacheService;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Layer-1 public help answer cache. Private entity queries must never get/put.
 */
@Service
public class HelpAnswerCacheService {
    private static final Duration TTL = Duration.ofMinutes(30);
    private static final String KEY_PREFIX = "help:answer:v1:";

    private final CacheService cacheService;
    /** Local fallback when Redis unavailable (dev/tests). */
    private final ConcurrentHashMap<String, Map<String, Object>> local =
            new ConcurrentHashMap<String, Map<String, Object>>();

    public HelpAnswerCacheService(CacheService cacheService) {
        this.cacheService = cacheService;
    }

    public String normalizeMessage(String message) {
        if (message == null) {
            return "";
        }
        return message.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    public String cacheKey(String normalizedMessage) {
        return KEY_PREFIX + sha256Hex(normalizedMessage);
    }

    /**
     * @return cached chat response map or null
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getIfAllowed(String message, HelpIntent.Result intent) {
        if (!isCacheable(intent, message)) {
            return null;
        }
        String key = cacheKey(normalizeMessage(message));
        try {
            Map<?, ?> fromRedis = cacheService.get(key, Map.class);
            if (fromRedis != null) {
                return copyAsStringObjectMap(fromRedis);
            }
        } catch (Exception ignored) {
            // fall through to local
        }
        Map<String, Object> localHit = local.get(key);
        return localHit == null ? null : new LinkedHashMap<String, Object>(localHit);
    }

    public void putIfAllowed(String message, HelpIntent.Result intent, Map<String, Object> response) {
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
        String key = cacheKey(normalizeMessage(message));
        Map<String, Object> stored = new LinkedHashMap<String, Object>(response);
        stored.put("cacheHit", true);
        try {
            cacheService.put(key, stored, TTL);
        } catch (Exception ignored) {
            // local only
        }
        local.put(key, stored);
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
        local.clear();
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
