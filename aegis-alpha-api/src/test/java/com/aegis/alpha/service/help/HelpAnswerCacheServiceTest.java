package com.aegis.alpha.service.help;

import com.aegis.alpha.security.AuthenticatedPrincipal;
import com.aegis.alpha.service.CacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

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
        assertThat(helpCache.getIfAllowed("什么是 degraded", intent, null)).isNull();

        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("content", "x");
        helpCache.putIfAllowed("什么是 degraded", intent, response, null);
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
        helpCache.putIfAllowed("什么是 DEGRADED？", intent, response, null);

        Map<String, Object> hit = helpCache.getIfAllowed("什么是 DEGRADED？", intent, null);
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
        helpCache.putIfAllowed("任意公开问题", intent, response, null);
        verify(cacheService, never()).put(anyString(), any(), any());
        assertThat(helpCache.getIfAllowed("任意公开问题", intent, null)).isNull();
    }

    /* ---------------- P1-3: role/tenant partitioning, TTL, capacity ---------------- */

    @Test
    void deskAnswerWithInternalSourceIsNotServedToRolelessUser() {
        when(cacheService.get(anyString(), any())).thenReturn(null);
        HelpIntent.Result intent = productHelp();
        Map<String, Object> deskAnswer = new LinkedHashMap<String, Object>();
        deskAnswer.put("content", "internal ops answer");
        deskAnswer.put("provider", "help-knowledge");
        deskAnswer.put("knowledge", knowledgeWithVisibility("internal"));

        helpCache.putIfAllowed("orchestrator 重启", intent, deskAnswer, principal("t-1", "desk"));

        assertThat(helpCache.getIfAllowed("orchestrator 重启", intent, principal("t-1"))).isNull();
        /* internal provenance is not cached even for the desk partition */
        assertThat(helpCache.getIfAllowed("orchestrator 重启", intent, principal("t-1", "desk"))).isNull();
        verify(cacheService, never()).put(anyString(), any(), any());
    }

    @Test
    void partitionsByRolesAndTenant() {
        when(cacheService.get(anyString(), any())).thenReturn(null);
        HelpIntent.Result intent = productHelp();
        helpCache.putIfAllowed("如何发布工作流", intent, publicAnswer("desk view"), principal("t-1", "desk", "admin"));

        assertThat(helpCache.getIfAllowed("如何发布工作流", intent, principal("t-1", "admin", "desk"))).isNotNull();
        assertThat(helpCache.getIfAllowed("如何发布工作流", intent, principal("t-1"))).isNull();
        assertThat(helpCache.getIfAllowed("如何发布工作流", intent, principal("t-2", "desk", "admin"))).isNull();
        assertThat(helpCache.cacheKey("x", principal("t-1", "desk", "admin")))
                .isEqualTo(helpCache.cacheKey("x", principal("t-1", "admin", "desk")))
                .startsWith("help:answer:v2:");
    }

    @Test
    void diagnosticFieldsAreStrippedBeforeStoring() {
        when(cacheService.get(anyString(), any())).thenReturn(null);
        Map<String, Object> answer = publicAnswer("body");
        answer.put("helpLoop", Collections.singletonMap("appliedFilter", "roles=desk"));
        answer.put("knowledge", knowledgeWithVisibility("public"));
        answer.put("appliedFilter", "x");
        helpCache.putIfAllowed("如何打开 copilot", productHelp(), answer, null);

        Map<String, Object> hit = helpCache.getIfAllowed("如何打开 copilot", productHelp(), null);
        assertThat(hit).containsEntry("content", "body").doesNotContainKeys("helpLoop", "knowledge", "appliedFilter");
    }

    @Test
    void localLayerExpiresAfterTtl() {
        when(cacheService.get(anyString(), any())).thenReturn(null);
        AtomicLong now = new AtomicLong(0);
        helpCache.setClock(now::get);
        helpCache.putIfAllowed("如何打开 copilot", productHelp(), publicAnswer("body"), null);
        assertThat(helpCache.getIfAllowed("如何打开 copilot", productHelp(), null)).isNotNull();

        now.addAndGet(30 * 60 * 1000L);

        assertThat(helpCache.getIfAllowed("如何打开 copilot", productHelp(), null)).isNull();
    }

    @Test
    void localLayerEvictsBeyondCapacity() {
        when(cacheService.get(anyString(), any())).thenReturn(null);
        for (int i = 0; i <= HelpAnswerCacheService.LOCAL_MAX_ENTRIES + 5; i++) {
            helpCache.putIfAllowed("如何操作 " + i, productHelp(), publicAnswer("a" + i), null);
        }
        assertThat(helpCache.localSizeForTest()).isEqualTo(HelpAnswerCacheService.LOCAL_MAX_ENTRIES);
        assertThat(helpCache.getIfAllowed("如何操作 0", productHelp(), null)).isNull();
    }

    private static HelpIntent.Result productHelp() {
        HelpIntent.Result intent = HelpIntent.Result.empty();
        intent.setDomain(HelpIntent.Domain.PRODUCT_HELP);
        intent.setCacheable(true);
        return intent;
    }

    private static Map<String, Object> publicAnswer(String content) {
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("content", content);
        response.put("provider", "help-playbook");
        return response;
    }

    private static Map<String, Object> knowledgeWithVisibility(String visibility) {
        Map<String, Object> hit = new LinkedHashMap<String, Object>();
        hit.put("id", "doc");
        hit.put("visibility", visibility);
        List<Map<String, Object>> hits = Collections.singletonList(hit);
        Map<String, Object> knowledge = new LinkedHashMap<String, Object>();
        knowledge.put("hits", hits);
        return knowledge;
    }

    private static AuthenticatedPrincipal principal(String tenantId, String... roles) {
        return new AuthenticatedPrincipal("u", "user", tenantId, Arrays.asList(roles));
    }
}
