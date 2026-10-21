package com.aegis.alpha.service.help.trace;

import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory observability for Help requests (P1).
 *
 * <p>Collects per-request {@link HelpTrace}es into a bounded ring buffer and maintains
 * counters plus a latency histogram. Everything lives in the JVM heap — no Redis, no
 * database, no extra dependencies. All public methods are null-safe and thread-safe.</p>
 *
 * <h3>Wiring recipe (for ChatService, not wired yet)</h3>
 * <pre>{@code
 * HelpTrace trace = helpTraceService.start(traceId, threadId);
 * helpTraceService.markRoute(trace, intent.getDomain().name(), intent.getRouteAction().name());
 * try {
 *     helpTraceService.markCacheHit(trace);        // cache layer 1 hit
 *     helpTraceService.markRetrievalRounds(trace, rounds);
 *     helpTraceService.addStep(trace, "playbook", playbookId);
 *     helpTraceService.markProvider(trace, provider);
 *     helpTraceService.markEscalated(trace);       // human hand-off
 *     return response;
 * } finally {
 *     helpTraceService.finish(trace);              // always finish, even on failure
 * }
 * }</pre>
 *
 * <h3>Counters</h3>
 * <ul>
 *   <li>{@code route.<domain>.<routeAction>} — one per {@link #markRoute} call</li>
 *   <li>{@code provider.<provider>} — one per {@link #markProvider} call</li>
 *   <li>{@code cacheHit} — one per trace whose {@link #markCacheHit} succeeded</li>
 *   <li>{@code cacheMiss} — one per finished trace that never reported a cache hit</li>
 *   <li>{@code escalated} — one per trace whose {@link #markEscalated} succeeded</li>
 *   <li>{@code total} — every finished trace, exactly once</li>
 * </ul>
 *
 * <h3>Latency histogram</h3>
 * Buckets are upper-bound inclusive: {@code <=50, <=100, <=200, <=500, <=1000, <=3000, >3000} ms.
 * A value is counted in the first bucket whose bound it does not exceed.
 */
@Service
public class HelpTraceService {

    /** Finished traces kept for {@link #snapshot()}. */
    public static final int RING_CAPACITY = 200;
    /** Traces returned by {@link #snapshot()} under {@code recentTraces}. */
    public static final int RECENT_TRACE_LIMIT = 20;

    /** Placeholder used when a caller passes a blank domain / route action / provider. */
    public static final String UNKNOWN = "unknown";

    public static final String COUNTER_TOTAL = "total";
    public static final String COUNTER_CACHE_HIT = "cacheHit";
    public static final String COUNTER_CACHE_MISS = "cacheMiss";
    public static final String COUNTER_ESCALATED = "escalated";

    public static final String ROUTE_COUNTER_PREFIX = "route.";
    public static final String PROVIDER_COUNTER_PREFIX = "provider.";

    /** Inclusive upper bounds of the latency histogram, in milliseconds. */
    private static final long[] BUCKET_UPPER_BOUNDS = {50L, 100L, 200L, 500L, 1000L, 3000L};
    /** Bucket labels in histogram order; the last bucket is the overflow bucket ({@code >3000}). */
    public static final List<String> LATENCY_BUCKET_LABELS = Collections.unmodifiableList(Arrays.asList(
            "<=50", "<=100", "<=200", "<=500", "<=1000", "<=3000", ">3000"));

    private final ConcurrentHashMap<String, AtomicLong> counters = new ConcurrentHashMap<String, AtomicLong>();
    private final AtomicLong totalFinished = new AtomicLong(0L);

    private final ArrayDeque<HelpTrace> recentTraces = new ArrayDeque<HelpTrace>(RING_CAPACITY);
    private final long[] latencyBuckets = new long[LATENCY_BUCKET_LABELS.size()];
    private final Object bufferLock = new Object();

    // ------------------------------------------------------------------ trace lifecycle

    /**
     * Opens a trace and stamps its start time.
     *
     * @param traceId  request/chat trace id; a generated id is used when blank
     * @param threadId conversation thread id (may be null)
     * @return a new open trace, never null
     */
    public HelpTrace start(String traceId, String threadId) {
        String id = blankToNull(traceId);
        if (id == null) {
            id = "help-" + UUID.randomUUID().toString();
        }
        return new HelpTrace(id, blankToNull(threadId));
    }

    /**
     * Records the routing decision.
     *
     * @param domain      help domain, typically {@code HelpIntent.Domain#name()}
     * @param routeAction routed action, typically {@code HelpIntent.RouteAction#name()}
     */
    public void markRoute(HelpTrace trace, String domain, String routeAction) {
        if (trace == null) {
            return;
        }
        String safeDomain = orUnknown(domain);
        String safeAction = orUnknown(routeAction);
        trace.setDomain(safeDomain);
        trace.setRouteAction(safeAction);
        increment(ROUTE_COUNTER_PREFIX + safeDomain + "." + safeAction);
    }

    /** Records the answer provider (playbook, knowledge-rag, help-diagnosis, llm, ...). */
    public void markProvider(HelpTrace trace, String provider) {
        if (trace == null) {
            return;
        }
        String safeProvider = orUnknown(provider);
        trace.setProvider(safeProvider);
        increment(PROVIDER_COUNTER_PREFIX + safeProvider);
    }

    /** Marks a cache hit; idempotent per trace so the counter never double counts. */
    public void markCacheHit(HelpTrace trace) {
        if (trace == null || trace.isCacheHit()) {
            return;
        }
        trace.setCacheHit(true);
        increment(COUNTER_CACHE_HIT);
    }

    /** Marks an escalation to a human; idempotent per trace. A miss is derived at finish time. */
    public void markEscalated(HelpTrace trace) {
        if (trace == null || trace.isEscalated()) {
            return;
        }
        trace.setEscalated(true);
        increment(COUNTER_ESCALATED);
    }

    /**
     * Records how many retrieval rounds the answer needed (knowledge retrieval / help graph).
     * Negative values are ignored.
     */
    public void markRetrievalRounds(HelpTrace trace, int rounds) {
        if (trace == null || rounds < 0) {
            return;
        }
        trace.setRetrievalRounds(rounds);
    }

    /** Appends a timeline step ({@code name}, {@code at}, {@code detail}) to the trace. */
    public void addStep(HelpTrace trace, String name, Object detail) {
        if (trace == null) {
            return;
        }
        trace.addStep(name, detail);
    }

    /**
     * Seals the trace: stamps {@code latencyMs} and {@code finishedAt}, updates the latency
     * histogram and {@code cacheMiss}/{@code total} counters, then stores the trace in the
     * ring buffer.
     *
     * <p>Finishing the same trace twice is a no-op for all counters and the ring buffer —
     * the timestamps are simply refreshed. Always call this in a {@code finally} block.</p>
     *
     * @return the same trace for chaining, or null when {@code trace} is null
     */
    public HelpTrace finish(HelpTrace trace) {
        if (trace == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        long latencyMs;
        boolean firstFinish;
        synchronized (trace) {
            firstFinish = !trace.isFinished();
            long startedAt = trace.getStartedAt();
            latencyMs = startedAt <= 0L ? 0L : Math.max(0L, now - startedAt);
            trace.setLatencyMs(latencyMs);
            trace.setFinishedAt(now);
            if (!firstFinish) {
                // Already finished once: keep the buffer/counters stable, only refresh timestamps.
                return trace;
            }
        }
        synchronized (bufferLock) {
            latencyBuckets[bucketIndex(latencyMs)]++;
            if (recentTraces.size() >= RING_CAPACITY) {
                recentTraces.removeFirst();
            }
            recentTraces.addLast(trace);
        }
        totalFinished.incrementAndGet();
        if (!trace.isCacheHit()) {
            increment(COUNTER_CACHE_MISS);
        }
        return trace;
    }

    // ------------------------------------------------------------------ inspection

    /**
     * Point-in-time view for metrics endpoints.
     *
     * <p>Keys: {@code counters} (sorted, includes {@code total}), {@code latencyBuckets}
     * (histogram order), {@code recentTraces} (oldest first, at most 20 maps), {@code total}
     * (finished traces), {@code bufferSize} and {@code bufferCapacity}.</p>
     */
    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("counters", countersSnapshot());
        out.put("latencyBuckets", latencyBucketsSnapshot());
        out.put("recentTraces", recentTraces(RECENT_TRACE_LIMIT));
        out.put("total", totalFinished.get());
        out.put("bufferSize", size());
        out.put("bufferCapacity", RING_CAPACITY);
        return out;
    }

    /** Counter snapshot (sorted), {@code total} included. */
    public Map<String, Long> countersSnapshot() {
        Map<String, Long> out = new TreeMap<String, Long>();
        for (Map.Entry<String, AtomicLong> entry : counters.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                out.put(entry.getKey(), entry.getValue().get());
            }
        }
        out.put(COUNTER_TOTAL, totalFinished.get());
        return out;
    }

    /** Latency histogram in bucket order; values are finished-trace counts. */
    public Map<String, Long> latencyBucketsSnapshot() {
        Map<String, Long> out = new LinkedHashMap<String, Long>();
        synchronized (bufferLock) {
            for (int i = 0; i < LATENCY_BUCKET_LABELS.size(); i++) {
                out.put(LATENCY_BUCKET_LABELS.get(i), latencyBuckets[i]);
            }
        }
        return out;
    }

    /**
     * Tail of the finished-trace ring buffer, oldest first.
     *
     * @param limit max entries; {@code <= 0} means all buffered traces (capped at 200)
     */
    public List<Map<String, Object>> recentTraces(int limit) {
        int effective = limit <= 0 ? RING_CAPACITY : Math.min(limit, RING_CAPACITY);
        List<HelpTrace> selected = new ArrayList<HelpTrace>();
        synchronized (bufferLock) {
            int skip = Math.max(0, recentTraces.size() - effective);
            int index = 0;
            for (HelpTrace trace : recentTraces) {
                if (index++ < skip) {
                    continue;
                }
                selected.add(trace);
            }
        }
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>(selected.size());
        for (HelpTrace trace : selected) {
            out.add(trace == null ? new LinkedHashMap<String, Object>() : trace.toMap());
        }
        return out;
    }

    /** Number of traces currently buffered (0..200). */
    public int size() {
        synchronized (bufferLock) {
            return recentTraces.size();
        }
    }

    /** Test/admin helper: drops counters, histogram and buffered traces. */
    public void reset() {
        counters.clear();
        totalFinished.set(0L);
        synchronized (bufferLock) {
            recentTraces.clear();
            for (int i = 0; i < latencyBuckets.length; i++) {
                latencyBuckets[i] = 0L;
            }
        }
    }

    // ------------------------------------------------------------------ internals

    private void increment(String key) {
        if (key == null) {
            return;
        }
        counter(key).incrementAndGet();
    }

    private AtomicLong counter(String key) {
        AtomicLong existing = counters.get(key);
        if (existing != null) {
            return existing;
        }
        AtomicLong created = new AtomicLong(0L);
        AtomicLong previous = counters.putIfAbsent(key, created);
        return previous == null ? created : previous;
    }

    /** First bucket whose inclusive upper bound covers {@code latencyMs}; last bucket overflows. */
    static int bucketIndex(long latencyMs) {
        long value = Math.max(0L, latencyMs);
        for (int i = 0; i < BUCKET_UPPER_BOUNDS.length; i++) {
            if (value <= BUCKET_UPPER_BOUNDS[i]) {
                return i;
            }
        }
        return BUCKET_UPPER_BOUNDS.length;
    }

    private static String orUnknown(String value) {
        String trimmed = blankToNull(value);
        return trimmed == null ? UNKNOWN : trimmed;
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
