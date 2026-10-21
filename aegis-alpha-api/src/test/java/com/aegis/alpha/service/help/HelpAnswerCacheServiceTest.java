package com.aegis.alpha.service.help;

import com.aegis.alpha.service.CacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HelpAnswerCacheServiceTest {

    private CacheService cacheService;
    private HelpAnswerCacheService helpCache;

    @BeforeEach
    void setUp() {
        cacheService = mock(CacheService.class);
        helpCache = new HelpAnswerCacheService(cacheService);
        helpCache.clearLocalForTest();
    }

    @Test
    void privateRunIdNotCacheable() {
        HelpIntent.Result intent = HelpIntent.Result.empty();
        intent.setDomain(HelpIntent.Domain.PRODUCT_HELP);
        intent.setCacheable(true);
        HelpIntent.Entities entities = new HelpIntent.Entities();
        entities.setRunId("11111111-2222-3333-4444-555555555555");
        intent.setEntities(entities);

        assertThat(helpCache.isCacheable(intent, "什么是 degraded")).isFalse();
        assertThat(helpCache.getIfAllowed("什么是 degraded", intent)).isNull();

        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("content", "x");
        helpCache.putIfAllowed("什么是 degraded", intent, response);
        verify(cacheService, never()).put(anyString(), any(), any());
    }

    @Test
    void publicProductHelpCanCacheLocally() {
        when(cacheService.get(anyString(), any())).thenReturn(null);

        HelpIntent.Result intent = HelpIntent.Result.empty();
        intent.setDomain(HelpIntent.Domain.PRODUCT_HELP);
        intent.setCacheable(true);

        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("content", "playbook body");
        response.put("provider", "help-playbook");
        helpCache.putIfAllowed("什么是 DEGRADED？", intent, response);

        Map<String, Object> hit = helpCache.getIfAllowed("什么是 DEGRADED？", intent);
        assertThat(hit).isNotNull();
        assertThat(hit.get("content")).isEqualTo("playbook body");
        assertThat(hit.get("cacheHit")).isEqualTo(true);
    }

    @Test
    void diagnosisProviderNeverCached() {
        HelpIntent.Result intent = HelpIntent.Result.empty();
        intent.setDomain(HelpIntent.Domain.PRODUCT_HELP);
        intent.setCacheable(true);

        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("content", "diag");
        response.put("provider", "help-diagnosis");
        helpCache.putIfAllowed("任意公开问题", intent, response);
        verify(cacheService, never()).put(anyString(), any(), any());
        assertThat(helpCache.getIfAllowed("任意公开问题", intent)).isNull();
    }
}
