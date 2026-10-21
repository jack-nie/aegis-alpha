package com.aegis.alpha.service.help;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Corpus-level behaviour of the hybrid (TF-IDF + keyword + RRF) knowledge retrieval:
 * loading, ACL pre-filter, and the shape of the response map consumed by HelpService.
 * Ranking-specific behaviour lives in {@link KnowledgeServiceHybridTest}.
 */
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

    @Test
    void searchResultReportsHybridMetadataForPublicCorpus() {
        KnowledgeService.SearchResult result = knowledgeService.search(
                "如何运行 published 工作流 操作步骤", Collections.emptyList(), 5);

        assertThat(result.getRetrievalMode()).isEqualTo(KnowledgeService.RETRIEVAL_MODE_HYBRID);
        assertThat(KnowledgeService.RETRIEVAL_MODE_HYBRID).isEqualTo("hybrid_tfidf_keyword_rrf");
        assertThat(result.isReranked()).isTrue();
        assertThat(result.getVectorCandidates()).isGreaterThan(0);
        assertThat(result.getKeywordCandidates()).isGreaterThan(0);
        assertThat(result.getFusedCandidates()).isGreaterThan(0);
        assertThat(result.getFusedCandidates())
                .isLessThanOrEqualTo(result.getVectorCandidates() + result.getKeywordCandidates());

        Map<String, Object> map = result.toMap();
        assertThat(map.get("retrievalMode")).isEqualTo("hybrid_tfidf_keyword_rrf");
        assertThat(map.get("reranked")).isEqualTo(true);
        assertThat((Integer) map.get("vectorCandidates")).isGreaterThan(0);
        assertThat((Integer) map.get("keywordCandidates")).isGreaterThan(0);
        assertThat((Integer) map.get("fusedCandidates")).isGreaterThan(0);

        // Per-hit diagnostics are present and the ranking is reflected by them.
        KnowledgeService.KnowledgeHit top = result.getHits().get(0);
        assertThat(top.getExcerpt()).isNotBlank();
        assertThat(top.getFusedScore()).isGreaterThan(0d);
        assertThat(top.getVectorRank()).isEqualTo(1);
        assertThat(top.getKeywordRank()).isEqualTo(1);
        assertThat(top.getKeywordScore()).isEqualTo(1d);
        assertThat(top.toMap()).containsKeys("fusedScore", "vectorScore", "keywordScore",
                "vectorRank", "keywordRank");
    }

    @Test
    void legacyHitAndSearchResultConstructorsStillWork() {
        KnowledgeService.KnowledgeDoc doc = knowledgeService.listAll().get(0);
        KnowledgeService.KnowledgeHit hit = new KnowledgeService.KnowledgeHit(doc, 7, "excerpt");
        assertThat(hit.getScore()).isEqualTo(7);
        assertThat(hit.getExcerpt()).isEqualTo("excerpt");
        assertThat(hit.getFusedScore()).isEqualTo(0d);

        KnowledgeService.SearchResult legacy = new KnowledgeService.SearchResult(
                Collections.singletonList(hit),
                Collections.<String, Object>singletonMap("principalRoles", Collections.emptyList()),
                1,
                1);
        assertThat(legacy.getHits()).hasSize(1);
        assertThat(legacy.getRetrievalMode()).isEqualTo(KnowledgeService.RETRIEVAL_MODE_HYBRID);
        assertThat(legacy.isReranked()).isFalse();
        assertThat(legacy.toMap()).containsKeys("hits", "appliedFilter", "candidateCount",
                "corpusCount", "preFiltered", "retrievalMode", "reranked");
    }
}
