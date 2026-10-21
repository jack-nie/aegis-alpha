package com.aegis.alpha.service.help.trace;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class HelpTraceServiceTest {

    private HelpTraceService traceService;

    @BeforeEach
    void setUp() {
        traceService = new HelpTraceService();
    }

    @Test
    void routeProviderAndRoundsRecorded() {
        HelpTrace trace = traceService.start("trace-1", "thread-1");
        traceService.markRoute(trace, "PRODUCT_HELP", "PLAYBOOK");
        traceService.markProvider(trace, "help-playbook");
        traceService.markRetrievalRounds(trace, 3);
        traceService.addStep(trace, "route", "playbook hit");
        traceService.finish(trace);

        assertThat(trace.getTraceId()).isEqualTo("trace-1");
        assertThat(trace.getThreadId()).isEqualTo("thread-1");
        assertThat(trace.getDomain()).isEqualTo("PRODUCT_HELP");
        assertThat(trace.getRouteAction()).isEqualTo("PLAYBOOK");
        assertThat(trace.getProvider()).isEqualTo("help-playbook");
        assertThat(trace.getRetrievalRounds()).isEqualTo(3);
        assertThat(trace.isCacheHit()).isFalse();
        assertThat(trace.isEscalated()).isFalse();
        assertThat(trace.getStartedAt()).isPositive();
        assertThat(trace.getFinishedAt()).isGreaterThanOrEqualTo(trace.getStartedAt());
        assertThat(trace.getStepCount()).isEqualTo(1);

        Map<String, Object> step = trace.getSteps().get(0);
        assertThat(step).containsKeys("name", "at", "detail");
        assertThat(step.get("name")).isEqualTo("route");
        assertThat(step.get("detail")).isEqualTo("playbook hit");

        Map<String, Long> counters = traceService.countersSnapshot();
        assertThat(counters).containsEntry("route.PRODUCT_HELP.PLAYBOOK", 1L);
        assertThat(counters).containsEntry("provider.help-playbook", 1L);
        assertThat(counters).containsEntry("total", 1L);
    }

    @Test
    void cacheHitAndEscalationCounters() {
        HelpTrace hit = traceService.start("trace-hit", "thread-1");
        traceService.markCacheHit(hit);
        traceService.markCacheHit(hit); // idempotent, must not double count
        traceService.markEscalated(hit);
        traceService.markEscalated(hit);
        traceService.finish(hit);

        HelpTrace miss = traceService.start("trace-miss", "thread-2");
        traceService.finish(miss);

        assertThat(hit.isCacheHit()).isTrue();
        assertThat(hit.isEscalated()).isTrue();

        Map<String, Long> counters = traceService.countersSnapshot();
        assertThat(counters).containsEntry("cacheHit", 1L);
        assertThat(counters).containsEntry("cacheMiss", 1L);
        assertThat(counters).containsEntry("escalated", 1L);
        assertThat(counters).containsEntry("total", 2L);
    }

    @Test
    void latencyIsNonNegativeAndHistogramBucketIncrements() {
        HelpTrace fast = traceService.start("trace-fast", "thread-1");
        traceService.finish(fast);
        assertThat(fast.getLatencyMs()).isGreaterThanOrEqualTo(0L);

        Map<String, Long> afterFast = traceService.latencyBucketsSnapshot();
        assertThat(afterFast.keySet()).containsExactlyElementsOf(HelpTraceService.LATENCY_BUCKET_LABELS);
        assertThat(sum(afterFast)).isEqualTo(1L);
        assertThat(afterFast.get("<=50")).isEqualTo(1L);

        // 300 ms old trace lands in the <=500 bucket, not in <=50 / <=100 / <=200.
        HelpTrace medium = traceService.start("trace-medium", "thread-2");
        medium.setStartedAt(System.currentTimeMillis() - 300L);
        traceService.finish(medium);
        assertThat(medium.getLatencyMs()).isGreaterThanOrEqualTo(300L);
        Map<String, Long> afterMedium = traceService.latencyBucketsSnapshot();
        assertThat(afterMedium.get("<=50")).isEqualTo(1L);
        assertThat(afterMedium.get("<=200")).isZero();
        assertThat(afterMedium.get("<=500")).isEqualTo(1L);
        assertThat(sum(afterMedium)).isEqualTo(2L);

        // 5000 ms old trace lands in the >3000 overflow bucket.
        HelpTrace slow = traceService.start("trace-slow", "thread-3");
        slow.setStartedAt(System.currentTimeMillis() - 5000L);
        traceService.finish(slow);
        assertThat(slow.getLatencyMs()).isGreaterThanOrEqualTo(5000L);
        Map<String, Long> afterSlow = traceService.latencyBucketsSnapshot();
        assertThat(afterSlow.get(">3000")).isEqualTo(1L);
        assertThat(sum(afterSlow)).isEqualTo(3L);
        assertThat(sum(afterSlow)).isEqualTo(traceService.snapshot().get("total"));
    }

    @Test
    void ringBufferCapsAtTwoHundred() {
        for (int i = 0; i < 250; i++) {
            HelpTrace trace = traceService.start("trace-" + i, "thread-" + i);
            traceService.markRoute(trace, "PRODUCT_HELP", "KNOWLEDGE_RAG");
            traceService.finish(trace);
        }

        assertThat(traceService.size()).isEqualTo(HelpTraceService.RING_CAPACITY);
        assertThat(traceService.recentTraces(0)).hasSize(HelpTraceService.RING_CAPACITY);

        List<Map<String, Object>> recent = traceService.recentTraces(HelpTraceService.RECENT_TRACE_LIMIT);
        assertThat(recent).hasSize(HelpTraceService.RECENT_TRACE_LIMIT);
        // oldest first: the buffer kept traces 50..249, so the last 20 are 230..249
        assertThat(recent.get(0).get("traceId")).isEqualTo("trace-230");
        assertThat(recent.get(recent.size() - 1).get("traceId")).isEqualTo("trace-249");

        assertThat(traceService.countersSnapshot()).containsEntry("total", 250L);
        assertThat(traceService.countersSnapshot()).containsEntry("route.PRODUCT_HELP.KNOWLEDGE_RAG", 250L);
    }

    @Test
    void snapshotContainsCountersBucketsAndRecentTraces() {
        HelpTrace first = traceService.start("trace-a", "thread-a");
        traceService.markRoute(first, "RUN_DIAGNOSIS", "HELP_GRAPH");
        traceService.markProvider(first, "help-diagnosis");
        traceService.markEscalated(first);
        traceService.finish(first);

        HelpTrace second = traceService.start("trace-b", "thread-b");
        traceService.markRoute(second, "PRODUCT_HELP", "PLAYBOOK");
        traceService.markCacheHit(second);
        traceService.finish(second);

        Map<String, Object> snapshot = traceService.snapshot();
        assertThat(snapshot).containsKeys("counters", "latencyBuckets", "recentTraces", "total");
        assertThat(snapshot.get("total")).isEqualTo(2L);
        assertThat(snapshot.get("bufferCapacity")).isEqualTo(HelpTraceService.RING_CAPACITY);
        assertThat(snapshot.get("bufferSize")).isEqualTo(2);

        @SuppressWarnings("unchecked")
        Map<String, Long> counters = (Map<String, Long>) snapshot.get("counters");
        assertThat(counters)
                .containsEntry("route.RUN_DIAGNOSIS.HELP_GRAPH", 1L)
                .containsEntry("route.PRODUCT_HELP.PLAYBOOK", 1L)
                .containsEntry("provider.help-diagnosis", 1L)
                .containsEntry("cacheHit", 1L)
                .containsEntry("cacheMiss", 1L)
                .containsEntry("escalated", 1L)
                .containsEntry("total", 2L);

        @SuppressWarnings("unchecked")
        Map<String, Long> buckets = (Map<String, Long>) snapshot.get("latencyBuckets");
        assertThat(buckets).hasSize(HelpTraceService.LATENCY_BUCKET_LABELS.size());
        assertThat(sum(buckets)).isEqualTo(2L);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> recent = (List<Map<String, Object>>) snapshot.get("recentTraces");
        assertThat(recent).hasSize(2);
        assertThat(recent.get(0)).containsKeys("traceId", "threadId", "domain", "routeAction", "provider",
                "cacheHit", "escalated", "retrievalRounds", "latencyMs", "startedAt", "finishedAt", "steps");
        assertThat(recent.get(0).get("traceId")).isEqualTo("trace-a");
        assertThat(recent.get(1).get("traceId")).isEqualTo("trace-b");
        assertThat(recent.get(1).get("cacheHit")).isEqualTo(true);
        assertThat(recent.get(0).get("escalated")).isEqualTo(true);
    }

    @Test
    void hundredConcurrentTracesHaveNoLostUpdates() throws Exception {
        final int count = 100;
        final int threads = 10;
        final CountDownLatch startGate = new CountDownLatch(1);
        final HelpTraceService service = traceService;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<Future<?>>();
        try {
            for (int i = 0; i < count; i++) {
                final int index = i;
                futures.add(pool.submit(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            startGate.await(10, TimeUnit.SECONDS);
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        HelpTrace trace = service.start("concurrent-" + index, "thread-" + index);
                        service.markRoute(trace, "PRODUCT_HELP", "KNOWLEDGE_RAG");
                        service.markProvider(trace, "knowledge-rag");
                        service.markRetrievalRounds(trace, 2);
                        service.addStep(trace, "retrieve", Integer.valueOf(index));
                        service.finish(trace);
                    }
                }));
            }
            startGate.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }

        Map<String, Long> counters = traceService.countersSnapshot();
        assertThat(counters).containsEntry("total", (long) count);
        assertThat(counters).containsEntry("route.PRODUCT_HELP.KNOWLEDGE_RAG", (long) count);
        assertThat(counters).containsEntry("provider.knowledge-rag", (long) count);
        assertThat(counters).containsEntry("cacheMiss", (long) count);
        assertThat(counters).doesNotContainKey("cacheHit");
        assertThat(traceService.size()).isEqualTo(count);
        assertThat(traceService.recentTraces(0)).hasSize(count);
        assertThat(sum(traceService.latencyBucketsSnapshot())).isEqualTo(count);

        for (Map<String, Object> traced : traceService.recentTraces(0)) {
            assertThat((Long) traced.get("latencyMs")).isGreaterThanOrEqualTo(0L);
            assertThat(traced.get("finishedAt")).isNotNull();
            assertThat(traced.get("steps")).isInstanceOf(List.class);
        }
    }

    @Test
    void nullSafeAndDoubleFinishDoesNotDoubleCount() {
        HelpTrace generated = traceService.start(null, null);
        assertThat(generated).isNotNull();
        assertThat(generated.getTraceId()).isNotBlank();
        assertThat(generated.getThreadId()).isNull();

        traceService.markRoute(null, "PRODUCT_HELP", "PLAYBOOK");
        traceService.markProvider(null, "help-playbook");
        traceService.markCacheHit(null);
        traceService.markEscalated(null);
        traceService.markRetrievalRounds(null, 5);
        traceService.addStep(null, "route", null);
        assertThat(traceService.finish(null)).isNull();

        // blank values fall back to the "unknown" placeholder instead of throwing
        traceService.markRoute(generated, null, " ");
        traceService.markProvider(generated, null);
        traceService.addStep(generated, null, null);
        traceService.markRetrievalRounds(generated, -1);

        assertThat(generated.getDomain()).isEqualTo(HelpTraceService.UNKNOWN);
        assertThat(generated.getRouteAction()).isEqualTo(HelpTraceService.UNKNOWN);
        assertThat(generated.getProvider()).isEqualTo(HelpTraceService.UNKNOWN);
        assertThat(generated.getRetrievalRounds()).isZero();
        assertThat(generated.getSteps().get(0).get("name")).isEqualTo("unnamed");

        traceService.finish(generated);
        traceService.finish(generated); // second finish only refreshes timestamps

        assertThat(traceService.countersSnapshot()).containsEntry("total", 1L);
        assertThat(traceService.countersSnapshot()).containsEntry("route.unknown.unknown", 1L);
        assertThat(traceService.countersSnapshot()).containsEntry("provider.unknown", 1L);
        assertThat(traceService.countersSnapshot()).containsEntry("cacheMiss", 1L);
        assertThat(traceService.size()).isEqualTo(1);
        assertThat(sum(traceService.latencyBucketsSnapshot())).isEqualTo(1L);
    }

    @Test
    void emptyServiceSnapshotIsZeroed() {
        Map<String, Object> snapshot = traceService.snapshot();
        assertThat(snapshot.get("total")).isEqualTo(0L);
        assertThat(snapshot.get("bufferSize")).isEqualTo(0);

        @SuppressWarnings("unchecked")
        Map<String, Long> counters = (Map<String, Long>) snapshot.get("counters");
        assertThat(counters).hasSize(1).containsEntry("total", 0L);

        @SuppressWarnings("unchecked")
        Map<String, Long> buckets = (Map<String, Long>) snapshot.get("latencyBuckets");
        assertThat(buckets).hasSize(7);
        assertThat(sum(buckets)).isZero();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> recent = (List<Map<String, Object>>) snapshot.get("recentTraces");
        assertThat(recent).isEmpty();

        traceService.reset();
        assertThat(traceService.size()).isZero();
        assertThat(traceService.countersSnapshot()).containsOnlyKeys("total");
    }

    private static long sum(Map<String, Long> values) {
        long total = 0L;
        for (Long value : values.values()) {
            total += value == null ? 0L : value.longValue();
        }
        return total;
    }
}
