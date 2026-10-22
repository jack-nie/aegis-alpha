package com.aegis.alpha.service;

import com.aegis.alpha.domain.WorkflowDefinition;
import com.aegis.alpha.mapper.WorkflowMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** P1-2: classification cache, circuit breaker and timeout defaults. */
class IntentRouterCacheBreakerTest {

    private LangChainGateway gateway;
    private IntentRouterService router;
    private final AtomicLong now = new AtomicLong(1_000_000L);

    @BeforeEach
    void setUp() {
        WorkflowMapper mapper = mock(WorkflowMapper.class);
        WorkflowDefinition daily = new WorkflowDefinition();
        daily.setWorkflowKey("daily");
        daily.setName("Daily");
        daily.setTriggerKeywords("daily,日报");
        when(mapper.findDefinitions()).thenReturn(Collections.singletonList(daily));
        gateway = mock(LangChainGateway.class);
        router = new IntentRouterService(mapper, gateway);
        router.setClock(now::get);
    }

    @Test
    void cacheHitDoesNotCallGatewayAgain() {
        when(gateway.classifyIntent(any(), any())).thenReturn(llm("daily", 0.9));

        assertThat(router.classify("看看今天市场").getWorkflowKey()).isEqualTo("daily");
        assertThat(router.classify("  看看今天市场 ").getWorkflowKey()).isEqualTo("daily");

        verify(gateway, times(1)).classifyIntent(any(), any());
    }

    @Test
    void cacheEntryExpiresAfterTtl() {
        when(gateway.classifyIntent(any(), any())).thenReturn(llm("daily", 0.9));
        router.classify("看看今天市场");
        now.addAndGet(10 * 60 * 1000L + 1);

        router.classify("看看今天市场");

        verify(gateway, times(2)).classifyIntent(any(), any());
    }

    @Test
    void breakerOpensAfterConsecutiveFailuresAndRecovers() {
        when(gateway.classifyIntent(any(), any())).thenReturn(null);
        router.classify("问题一");
        router.classify("问题二");
        router.classify("问题三");
        verify(gateway, times(3)).classifyIntent(any(), any());

        /* open: no remote calls, keyword fallback still answers */
        assertThat(router.classify("日报 please").getWorkflowKey()).isEqualTo("daily");
        router.classify("问题四");
        verify(gateway, times(3)).classifyIntent(any(), any());

        /* half-open after the cool-down: the gateway is tried again */
        now.addAndGet(30_001L);
        when(gateway.classifyIntent(any(), any())).thenReturn(llm("daily", 0.9));
        assertThat(router.classify("问题五").getSource()).isEqualTo("llm");
        verify(gateway, times(4)).classifyIntent(any(), any());
    }

    @Test
    void thrownExceptionsCountAsFailures() {
        when(gateway.classifyIntent(any(), any())).thenThrow(new IllegalStateException("down"));
        for (int i = 0; i < 5; i++) {
            router.classify("问题" + i);
        }
        verify(gateway, times(3)).classifyIntent(any(), any());
    }

    @Test
    void classifyReadTimeoutDefaultsAboveOrchestratorRouterTimeout() {
        LangChainGateway real = new LangChainGateway(new ObjectMapper(), false, "http://127.0.0.1:1",
                "openai", "deepseek-v4-flash", "", "");
        /* orchestrator AEGIS_ALPHA_ROUTER_TIMEOUT_MS defaults to 2500 ms */
        assertThat(real.classifyReadTimeoutMs()).isEqualTo(4000).isGreaterThan(2500);
    }

    private static Map<String, Object> llm(String key, double confidence) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("workflowKey", key);
        result.put("confidence", confidence);
        return result;
    }
}
