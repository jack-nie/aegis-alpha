package com.aegis.alpha.service.help;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * P2-4: one structured log line per routing decision (logger {@code aegis.routing}).
 * Never logs message text: only its length and a short SHA-256 prefix.
 * The project has no actuator/Micrometer, so metrics stay in logs + HelpTraceService counters.
 */
public final class RoutingTelemetry {
    static final String LOGGER_NAME = "aegis.routing";
    private static final Logger log = LoggerFactory.getLogger(LOGGER_NAME);

    /** Per-routing-call flags set by IntentRouterService while classifying on this thread. */
    public static final class Call {
        boolean llmUsed;
        boolean cacheHit;

        public boolean isLlmUsed() { return llmUsed; }
        public boolean isCacheHit() { return cacheHit; }
    }

    private static final ThreadLocal<Call> CURRENT = new ThreadLocal<Call>();

    private RoutingTelemetry() {
    }

    public static Call begin() {
        Call call = new Call();
        CURRENT.set(call);
        return call;
    }

    public static void end() {
        CURRENT.remove();
    }

    /** The remote LLM classifier was called for this routing decision. */
    public static void markLlmCall() {
        Call call = CURRENT.get();
        if (call != null) {
            call.llmUsed = true;
        }
    }

    /** A cached LLM classification was reused for this routing decision. */
    public static void markCacheHit() {
        Call call = CURRENT.get();
        if (call != null) {
            call.cacheHit = true;
        }
    }

    public static void logRoute(String traceId, String domain, String routeAction, String source,
                                double confidence, long latencyMs, boolean llmUsed, boolean cacheHit, String message) {
        String resolvedTraceId = traceId != null ? traceId : MDC.get("traceId");
        log.info("route traceId={} domain={} routeAction={} source={} confidence={} latencyMs={} llmUsed={} cacheHit={} msgLen={} msgHash={}",
                resolvedTraceId == null ? "-" : resolvedTraceId,
                domain, routeAction, source,
                String.format(java.util.Locale.ROOT, "%.2f", confidence),
                latencyMs, llmUsed, cacheHit,
                message == null ? 0 : message.length(), hash(message));
    }

    static String hash(String message) {
        if (message == null) {
            return "-";
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(message.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (Exception ex) {
            return "-";
        }
    }
}
