package com.aegis.alpha.service.help.trace;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One in-memory trace of a Help request (P1 observability).
 *
 * <p>A trace is created by {@link HelpTraceService#start(String, String)}, enriched while the
 * request is in flight ({@code markRoute}/{@code markProvider}/{@code markCacheHit}/
 * {@code markEscalated}/{@code markRetrievalRounds}/{@code addStep}) and sealed by
 * {@link HelpTraceService#finish(HelpTrace)}, which stamps {@code latencyMs} and
 * {@code finishedAt} before the trace enters the ring buffer.</p>
 *
 * <p>Scalar fields are {@code volatile} so a trace can be read from another thread once the
 * request completes; the step list is guarded by its own lock. {@link #toMap()} returns a
 * point-in-time copy suitable for JSON serialisation in metrics endpoints.</p>
 */
public final class HelpTrace {

    private final String traceId;
    private final String threadId;

    private volatile String domain;
    private volatile String routeAction;
    private volatile String provider;
    private volatile boolean cacheHit;
    private volatile boolean escalated;
    private volatile int retrievalRounds;
    private volatile long latencyMs;
    private volatile long startedAt;
    private volatile long finishedAt;

    private final List<Map<String, Object>> steps = new ArrayList<Map<String, Object>>();
    private final Object stepsLock = new Object();

    public HelpTrace(String traceId, String threadId) {
        this(traceId, threadId, System.currentTimeMillis());
    }

    /** Visible for tests / replay: explicit start timestamp (epoch millis). */
    public HelpTrace(String traceId, String threadId, long startedAt) {
        this.traceId = traceId;
        this.threadId = threadId;
        this.startedAt = startedAt;
    }

    public String getTraceId() {
        return traceId;
    }

    public String getThreadId() {
        return threadId;
    }

    public String getDomain() {
        return domain;
    }

    public void setDomain(String domain) {
        this.domain = domain;
    }

    public String getRouteAction() {
        return routeAction;
    }

    public void setRouteAction(String routeAction) {
        this.routeAction = routeAction;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public boolean isCacheHit() {
        return cacheHit;
    }

    public void setCacheHit(boolean cacheHit) {
        this.cacheHit = cacheHit;
    }

    public boolean isEscalated() {
        return escalated;
    }

    public void setEscalated(boolean escalated) {
        this.escalated = escalated;
    }

    public int getRetrievalRounds() {
        return retrievalRounds;
    }

    public void setRetrievalRounds(int retrievalRounds) {
        this.retrievalRounds = retrievalRounds;
    }

    public long getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(long latencyMs) {
        this.latencyMs = latencyMs;
    }

    public long getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(long startedAt) {
        this.startedAt = startedAt;
    }

    public long getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(long finishedAt) {
        this.finishedAt = finishedAt;
    }

    /** True once {@link HelpTraceService#finish(HelpTrace)} has stamped the trace. */
    public boolean isFinished() {
        return finishedAt > 0L;
    }

    /**
     * Appends a timeline step stamped with the current epoch millis.
     *
     * @param name   step name, e.g. {@code route}, {@code retrieval}, {@code escalate}
     * @param detail optional free-form detail (string, number, nested map)
     */
    public void addStep(String name, Object detail) {
        Map<String, Object> step = new LinkedHashMap<String, Object>();
        step.put("name", name == null || name.trim().isEmpty() ? "unnamed" : name.trim());
        step.put("at", System.currentTimeMillis());
        step.put("detail", detail);
        synchronized (stepsLock) {
            steps.add(step);
        }
    }

    /** Copy of the recorded steps, oldest first. The maps are shared (read-only use). */
    public List<Map<String, Object>> getSteps() {
        synchronized (stepsLock) {
            return Collections.unmodifiableList(new ArrayList<Map<String, Object>>(steps));
        }
    }

    public int getStepCount() {
        synchronized (stepsLock) {
            return steps.size();
        }
    }

    /** Flat, JSON-friendly view: {@code traceId ... steps}. Timestamps are epoch millis. */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("traceId", traceId);
        map.put("threadId", threadId);
        map.put("domain", domain);
        map.put("routeAction", routeAction);
        map.put("provider", provider);
        map.put("cacheHit", cacheHit);
        map.put("escalated", escalated);
        map.put("retrievalRounds", retrievalRounds);
        map.put("latencyMs", latencyMs);
        map.put("startedAt", startedAt);
        map.put("finishedAt", finishedAt);
        map.put("steps", getSteps());
        return map;
    }

    @Override
    public String toString() {
        return "HelpTrace{traceId=" + traceId
                + ", threadId=" + threadId
                + ", domain=" + domain
                + ", routeAction=" + routeAction
                + ", provider=" + provider
                + ", cacheHit=" + cacheHit
                + ", escalated=" + escalated
                + ", retrievalRounds=" + retrievalRounds
                + ", latencyMs=" + latencyMs
                + '}';
    }
}
