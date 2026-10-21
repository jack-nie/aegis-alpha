package com.aegis.alpha.service.help;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeServiceTest {

    private KnowledgeService knowledgeService;

    @BeforeEach
    void setUp() {
        knowledgeService = new KnowledgeService();
        knowledgeService.reload();
    }

    @Test
    void loadsPublicAndInternalDocs() {
        assertThat(knowledgeService.listAll()).isNotEmpty();
        assertThat(knowledgeService.listAll().stream().anyMatch(d -> "public".equalsIgnoreCase(d.getVisibility())))
                .isTrue();
        assertThat(knowledgeService.listAll().stream().anyMatch(d -> "internal".equalsIgnoreCase(d.getVisibility())))
                .isTrue();
    }

    @Test
    void preFilterExcludesInternalWithoutRole() {
        List<KnowledgeService.KnowledgeDoc> open = knowledgeService.preFilter(Collections.emptyList());
        assertThat(open.stream().noneMatch(d -> "internal".equalsIgnoreCase(d.getVisibility()))).isTrue();
        assertThat(open.stream().noneMatch(d -> "internal-ops".equals(d.getId()))).isTrue();
    }

    @Test
    void preFilterIncludesInternalForDeskRole() {
        List<KnowledgeService.KnowledgeDoc> desk = knowledgeService.preFilter(Arrays.asList("desk"));
        assertThat(desk.stream().anyMatch(d -> "internal-ops".equals(d.getId()))).isTrue();
    }

    @Test
    void searchAppliesPreFilterBeforeHits() {
        KnowledgeService.SearchResult anon = knowledgeService.search("orchestrator 运维 内部", Collections.emptyList(), 10);
        assertThat(anon.getAppliedFilter()).containsKey("visibilityIn");
        assertThat(anon.getAppliedFilter().get("preFiltered") == null
                || Boolean.TRUE.equals(anon.getAppliedFilter().get("preFiltered"))
                || anon.getAppliedFilter().containsKey("principalRoles")).isTrue();
        // Internal doc must not appear for anonymous even if keywords match
        assertThat(anon.getHits().stream().noneMatch(h -> "internal-ops".equals(h.getDoc().getId()))).isTrue();
        assertThat(anon.getCandidateCount()).isLessThanOrEqualTo(anon.getCorpusCount());

        KnowledgeService.SearchResult desk = knowledgeService.search("orchestrator 运维 内部", Arrays.asList("desk"), 10);
        assertThat(desk.getHits().stream().anyMatch(h -> "internal-ops".equals(h.getDoc().getId()))).isTrue();
    }

    @Test
    void searchPublicWorkflowOps() {
        KnowledgeService.SearchResult result = knowledgeService.search("如何运行 published 工作流 操作步骤", Collections.emptyList(), 5);
        assertThat(result.getHits()).isNotEmpty();
        Map<String, Object> map = result.toMap();
        assertThat(map.get("preFiltered")).isEqualTo(true);
        assertThat(map.get("appliedFilter")).isInstanceOf(Map.class);
    }

    @Test
    void isAllowedRejectsPrivateVisibility() {
        KnowledgeService.KnowledgeDoc priv = new KnowledgeService.KnowledgeDoc();
        priv.setVisibility("private");
        assertThat(KnowledgeService.isAllowed(priv, Arrays.asList("admin"))).isFalse();
    }
}
