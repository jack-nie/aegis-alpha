package com.aegis.alpha.service.help.tool;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Unified read-only gateway for Help agent tools.
 *
 * <p>Guarantees, in order:</p>
 * <ol>
 *   <li>allowlist check — unknown tools and any non-read-only definition are rejected
 *       before the action runs;</li>
 *   <li>per-tool timeout taken from {@link HelpToolDefinition#getTimeoutMs()};</li>
 *   <li>bounded retry ({@code maxRetries}) only for retryable I/O failures
 *       ({@link IOException}, {@link TimeoutException},
 *       {@link ResourceAccessException}) — argument/security errors fail fast;</li>
 *   <li>per-tool circuit breaker: open after 5 consecutive failures for 30 seconds;</li>
 *   <li>in-memory audit ring buffer (last 200 calls) and counters.</li>
 * </ol>
 *
 * <p>All state is held in memory; the gateway is thread-safe and stateless per call.</p>
 */
@Service
public class HelpToolGateway implements DisposableBean {
    public static final int DEFAULT_CIRCUIT_FAILURE_THRESHOLD = 5;
    public static final long DEFAULT_CIRCUIT_OPEN_MILLIS = 30000L;
    public static final int AUDIT_CAPACITY = 200;
    public static final int AUDIT_DEFAULT_LIMIT = 20;
    /** Bounded timeout workers: a stuck read can never spawn unbounded threads. */
    public static final int TOOL_WORKER_THREADS = 16;
    public static final int TOOL_QUEUE_CAPACITY = 200;

    public static final String ERROR_UNKNOWN_TOOL = "unknown_tool";
    public static final String ERROR_TOOL_NOT_READ_ONLY = "tool_not_read_only";
    public static final String ERROR_CIRCUIT_OPEN = "circuit_open";
    public static final String ERROR_MISSING_ACTION = "missing_action";
    public static final String ERROR_INTERRUPTED = "interrupted";
    public static final String ANONYMOUS_PRINCIPAL = "anonymous";

    private final HelpToolCatalog catalog;
    private final int circuitFailureThreshold;
    private final long circuitOpenMillis;
    private final ExecutorService executor;

    private final ConcurrentHashMap<String, Circuit> circuits = new ConcurrentHashMap<String, Circuit>();
    private final ArrayDeque<AuditEntry> auditEntries = new ArrayDeque<AuditEntry>();
    private final Object auditLock = new Object();

    private final AtomicLong callCount = new AtomicLong(0L);
    private final AtomicLong failureCount = new AtomicLong(0L);
    private final AtomicLong retryCount = new AtomicLong(0L);
    private final AtomicLong circuitOpenCount = new AtomicLong(0L);

    @Autowired
    public HelpToolGateway(HelpToolCatalog catalog) {
        this(catalog, DEFAULT_CIRCUIT_FAILURE_THRESHOLD, DEFAULT_CIRCUIT_OPEN_MILLIS);
    }

    /** Visible for tests: shortened breaker window so recovery can be asserted quickly. */
    public HelpToolGateway(HelpToolCatalog catalog, int circuitFailureThreshold, long circuitOpenMillis) {
        if (catalog == null) {
            throw new IllegalArgumentException("help tool catalog is required");
        }
        if (circuitFailureThreshold <= 0) {
            throw new IllegalArgumentException("circuit failure threshold must be positive");
        }
        if (circuitOpenMillis <= 0) {
            throw new IllegalArgumentException("circuit open window must be positive");
        }
        this.catalog = catalog;
        this.circuitFailureThreshold = circuitFailureThreshold;
        this.circuitOpenMillis = circuitOpenMillis;
        ThreadFactory threadFactory = new ThreadFactory() {
            private final AtomicInteger sequence = new AtomicInteger(0);

            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "help-tool-" + sequence.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        };
        this.executor = new ThreadPoolExecutor(
                TOOL_WORKER_THREADS,
                TOOL_WORKER_THREADS,
                60L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<Runnable>(TOOL_QUEUE_CAPACITY),
                threadFactory,
                new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * Executes {@code action} under the policy of the allowlisted {@code toolName}.
     *
     * @param toolName        catalog tool name
     * @param principalUserId caller identity, recorded in the audit buffer
     * @param action          read-only work to perform; never invoked for unknown,
     *                        non-read-only or circuit-open tools
     * @return result carrying data, error, attempts, latency and breaker state
     */
    public HelpToolResult execute(String toolName, String principalUserId, Callable<Map<String, Object>> action) {
        long startedNanos = System.nanoTime();
        callCount.incrementAndGet();
        String principal = normalizePrincipal(principalUserId);

        if (toolName == null || toolName.trim().isEmpty()) {
            return reject("unknown", principal, ERROR_UNKNOWN_TOOL, startedNanos);
        }
        String requestedName = toolName.trim();
        HelpToolDefinition definition = catalog.find(requestedName);
        if (definition == null) {
            return reject(requestedName, principal, ERROR_UNKNOWN_TOOL, startedNanos);
        }
        if (!definition.isReadOnly()) {
            return reject(definition.getName(), principal, ERROR_TOOL_NOT_READ_ONLY, startedNanos);
        }
        if (action == null) {
            return reject(definition.getName(), principal, ERROR_MISSING_ACTION, startedNanos);
        }
        if (isCircuitOpen(definition.getName())) {
            HelpToolResult open = HelpToolResult.circuitOpen(definition.getName(), elapsedMillis(startedNanos));
            complete(definition.getName(), principal, open);
            return open;
        }

        int maxAttempts = definition.getMaxRetries() + 1;
        int attempts = 0;
        String lastError = null;
        while (attempts < maxAttempts) {
            attempts++;
            try {
                Map<String, Object> data = invoke(action, definition.getTimeoutMs());
                resetCircuit(definition.getName());
                HelpToolResult success = HelpToolResult.success(definition.getName(), data, attempts,
                        elapsedMillis(startedNanos));
                complete(definition.getName(), principal, success);
                return success;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                lastError = ERROR_INTERRUPTED;
                break;
            } catch (Exception ex) {
                lastError = describeError(ex);
                if (!isRetryable(ex) || attempts >= maxAttempts) {
                    break;
                }
                retryCount.incrementAndGet();
            }
        }

        recordCircuitFailure(definition.getName());
        HelpToolResult failure = HelpToolResult.failure(definition.getName(), lastError, attempts,
                elapsedMillis(startedNanos));
        complete(definition.getName(), principal, failure);
        return failure;
    }

    /** Tail of the audit ring buffer, oldest first, at most {@code limit} entries. */
    public List<Map<String, Object>> audit(int limit) {
        int effective = limit <= 0 ? AUDIT_CAPACITY : Math.min(limit, AUDIT_CAPACITY);
        List<Map<String, Object>> snapshot = new ArrayList<Map<String, Object>>();
        synchronized (auditLock) {
            int skip = Math.max(0, auditEntries.size() - effective);
            int index = 0;
            for (AuditEntry entry : auditEntries) {
                if (index++ < skip) {
                    continue;
                }
                snapshot.add(entry.toMap());
            }
        }
        return snapshot;
    }

    public int auditSize() {
        synchronized (auditLock) {
            return auditEntries.size();
        }
    }

    /**
     * Counter snapshot.
     *
     * <p>{@code calls} counts every {@link #execute} invocation, {@code failures} every
     * result with {@code ok=false} (including rejected and circuit-open results),
     * {@code retries} every extra attempt, {@code circuitOpens} every breaker opening.</p>
     */
    public Map<String, Object> metricsSnapshot() {
        Map<String, Object> metrics = new LinkedHashMap<String, Object>();
        metrics.put("calls", callCount.get());
        metrics.put("failures", failureCount.get());
        metrics.put("retries", retryCount.get());
        metrics.put("circuitOpens", circuitOpenCount.get());
        metrics.put("auditSize", auditSize());
        metrics.put("openCircuits", openCircuitNames());
        metrics.put("circuitFailureThreshold", circuitFailureThreshold);
        metrics.put("circuitOpenMillis", circuitOpenMillis);
        return metrics;
    }

    public int getCircuitFailureThreshold() {
        return circuitFailureThreshold;
    }

    public long getCircuitOpenMillis() {
        return circuitOpenMillis;
    }

    public boolean isCircuitOpenFor(String toolName) {
        return toolName != null && isCircuitOpen(toolName);
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
    }

    // ---------------------------------------------------------------- internals

    private Map<String, Object> invoke(Callable<Map<String, Object>> action, long timeoutMs)
            throws InterruptedException, ExecutionException, TimeoutException {
        Future<Map<String, Object>> future = executor.submit(action);
        try {
            Map<String, Object> data = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            return data == null ? Collections.<String, Object>emptyMap()
                    : new LinkedHashMap<String, Object>(data);
        } catch (InterruptedException ex) {
            future.cancel(true);
            throw ex;
        } catch (TimeoutException ex) {
            future.cancel(true);
            throw ex;
        }
    }

    private HelpToolResult reject(String tool, String principal, String error, long startedNanos) {
        HelpToolResult rejected = HelpToolResult.rejected(tool, error, elapsedMillis(startedNanos));
        complete(tool, principal, rejected);
        return rejected;
    }

    private void complete(String tool, String principal, HelpToolResult result) {
        AuditEntry entry = new AuditEntry(tool, principal, result.isOk(), result.getAttempts(),
                result.getLatencyMs(), result.getError(), System.currentTimeMillis());
        synchronized (auditLock) {
            if (auditEntries.size() >= AUDIT_CAPACITY) {
                auditEntries.removeFirst();
            }
            auditEntries.addLast(entry);
        }
        if (!result.isOk()) {
            failureCount.incrementAndGet();
        }
    }

    private Circuit circuitFor(String tool) {
        Circuit circuit = circuits.get(tool);
        if (circuit != null) {
            return circuit;
        }
        Circuit created = new Circuit();
        Circuit existing = circuits.putIfAbsent(tool, created);
        return existing == null ? created : existing;
    }

    private boolean isCircuitOpen(String tool) {
        Circuit circuit = circuits.get(tool);
        return circuit != null && circuit.openUntilMillis > System.currentTimeMillis();
    }

    private void recordCircuitFailure(String tool) {
        Circuit circuit = circuitFor(tool);
        int consecutiveFailures = circuit.consecutiveFailures.incrementAndGet();
        if (consecutiveFailures < circuitFailureThreshold) {
            return;
        }
        long now = System.currentTimeMillis();
        synchronized (circuit) {
            if (circuit.openUntilMillis <= now) {
                circuit.openUntilMillis = now + circuitOpenMillis;
                circuitOpenCount.incrementAndGet();
            }
        }
    }

    private void resetCircuit(String tool) {
        Circuit circuit = circuits.get(tool);
        if (circuit == null) {
            return;
        }
        circuit.consecutiveFailures.set(0);
        synchronized (circuit) {
            circuit.openUntilMillis = 0L;
        }
    }

    private List<String> openCircuitNames() {
        long now = System.currentTimeMillis();
        List<String> open = new ArrayList<String>();
        for (Map.Entry<String, Circuit> entry : circuits.entrySet()) {
            if (entry.getValue().openUntilMillis > now) {
                open.add(entry.getKey());
            }
        }
        Collections.sort(open);
        return open;
    }

    /**
     * Retryable: transient I/O and transport failures only. Argument validation and
     * security failures must never be retried.
     */
    static boolean isRetryable(Throwable throwable) {
        Throwable cause = unwrap(throwable);
        return cause instanceof IOException
                || cause instanceof TimeoutException
                || cause instanceof ResourceAccessException;
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof ExecutionException && current.getCause() != null
                && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private static String describeError(Throwable throwable) {
        Throwable cause = unwrap(throwable);
        String message = cause.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return cause.getClass().getSimpleName();
        }
        return cause.getClass().getSimpleName() + ": " + message.trim();
    }

    private static String normalizePrincipal(String principalUserId) {
        return principalUserId == null || principalUserId.trim().isEmpty()
                ? ANONYMOUS_PRINCIPAL
                : principalUserId.trim();
    }

    private static long elapsedMillis(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    private static final class Circuit {
        private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
        private volatile long openUntilMillis = 0L;
    }

    /** One audit entry; {@code timestamp} is an ISO-8601 UTC string. */
    private static final class AuditEntry {
        private final String tool;
        private final String principal;
        private final boolean ok;
        private final int attempts;
        private final long latencyMs;
        private final String error;
        private final long epochMillis;

        private AuditEntry(String tool, String principal, boolean ok, int attempts, long latencyMs,
                           String error, long epochMillis) {
            this.tool = tool;
            this.principal = principal;
            this.ok = ok;
            this.attempts = attempts;
            this.latencyMs = latencyMs;
            this.error = error;
            this.epochMillis = epochMillis;
        }

        private Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            map.put("tool", tool);
            map.put("principal", principal);
            map.put("ok", ok);
            map.put("attempts", attempts);
            map.put("latencyMs", latencyMs);
            map.put("error", error);
            map.put("timestamp", Instant.ofEpochMilli(epochMillis).toString());
            return map;
        }
    }
}
