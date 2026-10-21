package com.aegis.alpha.service.help.tool;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class HelpToolGatewayTest {
    private static final String TOOL = "get_workflow_run";
    private static final long GENEROUS_TIMEOUT_MS = 2000L;

    private final List<HelpToolGateway> createdGateways = new ArrayList<HelpToolGateway>();

    @AfterEach
    void tearDown() {
        for (HelpToolGateway gateway : createdGateways) {
            gateway.destroy();
        }
        createdGateways.clear();
    }

    @Test
    void executesAllowedReadOnlyTool() throws Exception {
        HelpToolGateway gateway = gateway(new HelpToolCatalog());

        HelpToolResult result = gateway.execute("get_stock_quote", "u-1", new Callable<Map<String, Object>>() {
            @Override
            public Map<String, Object> call() {
                return data("symbol", "AAPL");
            }
        });

        assertThat(result.isOk()).isTrue();
        assertThat(result.getTool()).isEqualTo("get_stock_quote");
        assertThat(result.getData()).containsEntry("symbol", "AAPL");
        assertThat(result.getError()).isNull();
        assertThat(result.getAttempts()).isEqualTo(1);
        assertThat(result.getLatencyMs()).isGreaterThanOrEqualTo(0L);
        assertThat(result.isCircuitOpen()).isFalse();
    }

    @Test
    void rejectsUnknownToolBeforeInvoking() throws Exception {
        HelpToolGateway gateway = gateway(new HelpToolCatalog());
        AtomicInteger invocations = new AtomicInteger(0);

        HelpToolResult result = gateway.execute("execute_sql", "u-1", counting(invocations));

        assertThat(result.isOk()).isFalse();
        assertThat(result.getTool()).isEqualTo("execute_sql");
        assertThat(result.getError()).isEqualTo(HelpToolGateway.ERROR_UNKNOWN_TOOL);
        assertThat(result.getAttempts()).isZero();
        assertThat(result.getData()).isEmpty();
        assertThat(invocations.get()).isZero();
    }

    @Test
    void rejectsNonReadOnlyToolBeforeInvoking() throws Exception {
        HelpToolDefinition writeTool = new HelpToolDefinition("approve_recommendation",
                "Approve a recommendation", false, GENEROUS_TIMEOUT_MS, 0, "governance");
        HelpToolGateway gateway = gateway(catalogWith(writeTool));
        AtomicInteger invocations = new AtomicInteger(0);

        HelpToolResult result = gateway.execute("approve_recommendation", "u-1", counting(invocations));

        assertThat(result.isOk()).isFalse();
        assertThat(result.getError()).isEqualTo(HelpToolGateway.ERROR_TOOL_NOT_READ_ONLY);
        assertThat(result.getAttempts()).isZero();
        assertThat(invocations.get()).isZero();
        assertThat(gateway.metricsSnapshot()).containsEntry("failures", 1L);
    }

    @Test
    void timesOutPerToolWithoutRetryWhenMaxRetriesIsZero() throws Exception {
        HelpToolGateway gateway = gateway(catalogWith(definition(TOOL, true, 60L, 0)));

        HelpToolResult result = gateway.execute(TOOL, "u-1", new Callable<Map<String, Object>>() {
            @Override
            public Map<String, Object> call() throws Exception {
                Thread.sleep(500L);
                return data("slow", true);
            }
        });

        assertThat(result.isOk()).isFalse();
        assertThat(result.getError()).containsIgnoringCase("timeout");
        assertThat(result.getAttempts()).isEqualTo(1);
        assertThat(result.getLatencyMs()).isGreaterThanOrEqualTo(50L);
        assertThat(gateway.metricsSnapshot()).containsEntry("retries", 0L);
    }

    @Test
    void retriesRetryableIOExceptionThenSucceeds() throws Exception {
        HelpToolGateway gateway = gateway(catalogWith(definition(TOOL, true, GENEROUS_TIMEOUT_MS, 2)));
        final AtomicInteger attempts = new AtomicInteger(0);

        HelpToolResult result = gateway.execute(TOOL, "u-1", new Callable<Map<String, Object>>() {
            @Override
            public Map<String, Object> call() throws Exception {
                if (attempts.incrementAndGet() == 1) {
                    throw new IOException("connection reset");
                }
                return data("runId", "r-1");
            }
        });

        assertThat(result.isOk()).isTrue();
        assertThat(result.getAttempts()).isEqualTo(2);
        assertThat(result.getData()).containsEntry("runId", "r-1");
        assertThat(attempts.get()).isEqualTo(2);
        assertThat(gateway.metricsSnapshot()).containsEntry("retries", 1L);
    }

    @Test
    void retriesTimeoutThenSucceeds() throws Exception {
        HelpToolGateway gateway = gateway(catalogWith(definition(TOOL, true, 100L, 1)));
        final AtomicInteger attempts = new AtomicInteger(0);

        HelpToolResult result = gateway.execute(TOOL, "u-1", new Callable<Map<String, Object>>() {
            @Override
            public Map<String, Object> call() throws Exception {
                if (attempts.incrementAndGet() == 1) {
                    Thread.sleep(400L);
                }
                return data("runId", "r-1");
            }
        });

        assertThat(result.isOk()).isTrue();
        assertThat(result.getAttempts()).isEqualTo(2);
        assertThat(gateway.metricsSnapshot()).containsEntry("retries", 1L);
    }

    @Test
    void retriesResourceAccessException() throws Exception {
        HelpToolGateway gateway = gateway(catalogWith(definition("get_stock_quote", true, GENEROUS_TIMEOUT_MS, 1)));
        final AtomicInteger attempts = new AtomicInteger(0);

        HelpToolResult result = gateway.execute("get_stock_quote", "u-1", new Callable<Map<String, Object>>() {
            @Override
            public Map<String, Object> call() throws Exception {
                if (attempts.incrementAndGet() == 1) {
                    throw new ResourceAccessException("read timed out");
                }
                return data("symbol", "AAPL");
            }
        });

        assertThat(result.isOk()).isTrue();
        assertThat(result.getAttempts()).isEqualTo(2);
    }

    @Test
    void doesNotRetryIllegalArgumentException() throws Exception {
        HelpToolGateway gateway = gateway(catalogWith(definition(TOOL, true, GENEROUS_TIMEOUT_MS, 3)));
        final AtomicInteger attempts = new AtomicInteger(0);

        HelpToolResult result = gateway.execute(TOOL, "u-1", new Callable<Map<String, Object>>() {
            @Override
            public Map<String, Object> call() {
                attempts.incrementAndGet();
                throw new IllegalArgumentException("runId is required");
            }
        });

        assertThat(result.isOk()).isFalse();
        assertThat(result.getAttempts()).isEqualTo(1);
        assertThat(attempts.get()).isEqualTo(1);
        assertThat(result.getError()).contains("IllegalArgumentException").contains("runId is required");
        assertThat(gateway.metricsSnapshot()).containsEntry("retries", 0L);
    }

    @Test
    void doesNotRetrySecurityException() throws Exception {
        HelpToolGateway gateway = gateway(catalogWith(definition(TOOL, true, GENEROUS_TIMEOUT_MS, 3)));
        final AtomicInteger attempts = new AtomicInteger(0);

        HelpToolResult result = gateway.execute(TOOL, "u-1", new Callable<Map<String, Object>>() {
            @Override
            public Map<String, Object> call() {
                attempts.incrementAndGet();
                throw new SecurityException("run belongs to another tenant");
            }
        });

        assertThat(result.isOk()).isFalse();
        assertThat(result.getAttempts()).isEqualTo(1);
        assertThat(attempts.get()).isEqualTo(1);
        assertThat(result.getError()).contains("SecurityException");
    }

    @Test
    void circuitOpensAfterThresholdAndRecoversAfterWindow() throws Exception {
        HelpToolGateway gateway = gateway(catalogWith(definition(TOOL, true, GENEROUS_TIMEOUT_MS, 0)), 5, 200L);
        final AtomicInteger invocations = new AtomicInteger(0);
        Callable<Map<String, Object>> failing = new Callable<Map<String, Object>>() {
            @Override
            public Map<String, Object> call() {
                invocations.incrementAndGet();
                throw new IllegalArgumentException("bad run id");
            }
        };

        for (int call = 1; call <= 5; call++) {
            HelpToolResult result = gateway.execute(TOOL, "u-1", failing);
            assertThat(result.isOk()).isFalse();
            assertThat(result.isCircuitOpen()).isFalse();
        }
        assertThat(invocations.get()).isEqualTo(5);
        assertThat(gateway.isCircuitOpenFor(TOOL)).isTrue();
        assertThat(gateway.metricsSnapshot()).containsEntry("circuitOpens", 1L);

        HelpToolResult blocked = gateway.execute(TOOL, "u-1", failing);
        assertThat(blocked.isOk()).isFalse();
        assertThat(blocked.isCircuitOpen()).isTrue();
        assertThat(blocked.getError()).isEqualTo(HelpToolGateway.ERROR_CIRCUIT_OPEN);
        assertThat(blocked.getAttempts()).isZero();
        assertThat(invocations.get()).isEqualTo(5);
        assertThat(openCircuits(gateway)).containsExactly(TOOL);

        Thread.sleep(350L);

        HelpToolResult recovered = gateway.execute(TOOL, "u-1", new Callable<Map<String, Object>>() {
            @Override
            public Map<String, Object> call() {
                return data("runId", "r-1");
            }
        });
        assertThat(recovered.isOk()).isTrue();
        assertThat(recovered.isCircuitOpen()).isFalse();
        assertThat(gateway.isCircuitOpenFor(TOOL)).isFalse();
        assertThat(openCircuits(gateway)).isEmpty();

        HelpToolResult afterRecovery = gateway.execute(TOOL, "u-1", new Callable<Map<String, Object>>() {
            @Override
            public Map<String, Object> call() {
                return data("runId", "r-2");
            }
        });
        assertThat(afterRecovery.isOk()).isTrue();
    }

    @Test
    void auditRecordsEveryCall() throws Exception {
        HelpToolGateway gateway = gateway(new HelpToolCatalog());

        gateway.execute("get_stock_quote", "u-1", new Callable<Map<String, Object>>() {
            @Override
            public Map<String, Object> call() {
                return data("symbol", "AAPL");
            }
        });
        gateway.execute("execute_sql", "u-2", new Callable<Map<String, Object>>() {
            @Override
            public Map<String, Object> call() {
                return data("rows", 2);
            }
        });

        List<Map<String, Object>> audit = gateway.audit(20);
        assertThat(audit).hasSize(2);

        Map<String, Object> successEntry = audit.get(0);
        assertThat(successEntry).containsEntry("tool", "get_stock_quote")
                .containsEntry("principal", "u-1")
                .containsEntry("ok", true)
                .containsEntry("attempts", 1);
        assertThat(successEntry.get("error")).isNull();
        assertThat(successEntry.get("latencyMs")).isInstanceOf(Long.class);
        assertThat(successEntry.get("timestamp")).isInstanceOf(String.class);

        Map<String, Object> rejectedEntry = audit.get(1);
        assertThat(rejectedEntry).containsEntry("tool", "execute_sql")
                .containsEntry("principal", "u-2")
                .containsEntry("ok", false)
                .containsEntry("error", HelpToolGateway.ERROR_UNKNOWN_TOOL);
    }

    @Test
    void auditIsBoundedToRingBufferCapacity() throws Exception {
        HelpToolGateway gateway = gateway(new HelpToolCatalog());
        Callable<Map<String, Object>> action = new Callable<Map<String, Object>>() {
            @Override
            public Map<String, Object> call() {
                return data("ok", true);
            }
        };

        for (int call = 0; call < HelpToolGateway.AUDIT_CAPACITY + 5; call++) {
            gateway.execute("search_product_docs", "u-1", action);
        }

        assertThat(gateway.auditSize()).isEqualTo(HelpToolGateway.AUDIT_CAPACITY);
        assertThat(gateway.audit(1000)).hasSize(HelpToolGateway.AUDIT_CAPACITY);
        assertThat(gateway.audit(20)).hasSize(20);
    }

    @Test
    void countersTrackCallsFailuresRetriesAndCircuitOpens() throws Exception {
        HelpToolGateway gateway = gateway(catalogWith(definition(TOOL, true, GENEROUS_TIMEOUT_MS, 2)));
        final AtomicInteger flakyAttempts = new AtomicInteger(0);

        gateway.execute(TOOL, "u-1", new Callable<Map<String, Object>>() {
            @Override
            public Map<String, Object> call() {
                return data("ok", true);
            }
        });
        gateway.execute(TOOL, "u-1", new Callable<Map<String, Object>>() {
            @Override
            public Map<String, Object> call() throws Exception {
                if (flakyAttempts.incrementAndGet() == 1) {
                    throw new IOException("flaky transport");
                }
                return data("ok", true);
            }
        });
        gateway.execute("unknown_tool", "u-1", new Callable<Map<String, Object>>() {
            @Override
            public Map<String, Object> call() {
                return data("ok", true);
            }
        });

        Map<String, Object> metrics = gateway.metricsSnapshot();
        assertThat(metrics).containsEntry("calls", 3L)
                .containsEntry("failures", 1L)
                .containsEntry("retries", 1L)
                .containsEntry("circuitOpens", 0L);
        assertThat(metrics.get("openCircuits")).isEqualTo(Collections.emptyList());
        assertThat(metrics.get("auditSize")).isEqualTo(3);
    }

    // ---------------------------------------------------------------- helpers

    private HelpToolGateway gateway(HelpToolCatalog catalog) {
        return gateway(catalog, HelpToolGateway.DEFAULT_CIRCUIT_FAILURE_THRESHOLD,
                HelpToolGateway.DEFAULT_CIRCUIT_OPEN_MILLIS);
    }

    private HelpToolGateway gateway(HelpToolCatalog catalog, int failureThreshold, long openMillis) {
        HelpToolGateway gateway = new HelpToolGateway(catalog, failureThreshold, openMillis);
        createdGateways.add(gateway);
        return gateway;
    }

    private static HelpToolCatalog catalogWith(HelpToolDefinition... definitions) {
        List<HelpToolDefinition> tools = new ArrayList<HelpToolDefinition>();
        Collections.addAll(tools, definitions);
        return new HelpToolCatalog(tools);
    }

    private static HelpToolDefinition definition(String name, boolean readOnly, long timeoutMs, int maxRetries) {
        return new HelpToolDefinition(name, "test tool " + name, readOnly, timeoutMs, maxRetries, "test");
    }

    private static List<String> openCircuits(HelpToolGateway gateway) {
        List<String> names = new ArrayList<String>();
        Object value = gateway.metricsSnapshot().get("openCircuits");
        if (value instanceof List) {
            for (Object item : (List<?>) value) {
                names.add(String.valueOf(item));
            }
        }
        return names;
    }

    private static Callable<Map<String, Object>> counting(final AtomicInteger invocations) {
        return new Callable<Map<String, Object>>() {
            @Override
            public Map<String, Object> call() {
                invocations.incrementAndGet();
                return data("ok", true);
            }
        };
    }

    private static Map<String, Object> data(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return map;
    }
}
