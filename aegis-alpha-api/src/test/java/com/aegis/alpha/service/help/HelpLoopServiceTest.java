package com.aegis.alpha.service.help;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HelpLoopServiceTest {

    private KnowledgeService knowledgeService;
    private HelpLoopService helpLoopService;

    @BeforeEach
    void setUp() {
        knowledgeService = new KnowledgeService();
        knowledgeService.reload();
        helpLoopService = new HelpLoopService(knowledgeService);
    }

    @Test
    void successfulLoopUsesAclPrefilteredKnowledge() {
        HelpLoopService.LoopResult result = helpLoopService.run(
                "如何运行 published 工作流 操作步骤 说明",
                Collections.emptyList()
        );
        assertThat(result.ok).isTrue();
        assertThat(result.escalate).isFalse();
        assertThat(result.retrievalRounds).isBetween(1, HelpLoopService.MAX_RETRIEVAL_ROUNDS);
        assertThat(result.content).contains("知识库");
        assertThat(result.toDiagnostics().get("preFiltered")).isEqualTo(true);
        assertThat(result.toDiagnostics().get("appliedFilter")).isInstanceOf(Map.class);
    }

    @Test
    void capsAtThreeRoundsThenEscalatesOnEmptyCorpus() {
        KnowledgeService empty = new KnowledgeService();
        empty.replaceDocumentsForTest(Collections.emptyList());
        HelpLoopService loop = new HelpLoopService(empty);

        HelpLoopService.LoopResult result = loop.run("完全无关的 xyzzy-no-hit-query-999", Collections.emptyList());

        assertThat(result.ok).isFalse();
        assertThat(result.escalate).isTrue();
        assertThat(result.retrievalRounds).isEqualTo(HelpLoopService.MAX_RETRIEVAL_ROUNDS);
        assertThat(result.disclaimer).isNotNull();
        assertThat(result.content).contains("无法");
    }

    @Test
    void gradeFailsWithoutChunks() {
        HelpLoopService.Grade grade = helpLoopService.grade("如何审批", Collections.emptyList());
        assertThat(grade.ok).isFalse();
        assertThat(grade.reason).isEqualTo("no_chunks");
    }

    @Test
    void gradePassesWithOverlappingChunks() {
        List<Map<String, Object>> chunks = new ArrayList<Map<String, Object>>();
        Map<String, Object> c = new java.util.LinkedHashMap<String, Object>();
        c.put("title", "推荐审批");
        c.put("excerpt", "如何审批 证据 PENDING_REVIEW 人工操作");
        chunks.add(c);
        HelpLoopService.Grade grade = helpLoopService.grade("如何审批 证据", chunks);
        assertThat(grade.ok).isTrue();
    }

    @Test
    void diagnosticsReportHybridRetrievalMode() {
        HelpLoopService.LoopResult result = helpLoopService.run(
                "如何运行 published 工作流 操作步骤 说明",
                Collections.emptyList()
        );

        assertThat(result.retrievalMode).isEqualTo(KnowledgeService.RETRIEVAL_MODE_HYBRID);
        assertThat(result.retrievalMode).isEqualTo("hybrid_tfidf_keyword_rrf");

        Map<String, Object> diagnostics = result.toDiagnostics();
        // Existing response shape is preserved…
        assertThat(diagnostics).containsKeys("retrievalRounds", "maxRounds", "grade", "chunkCount",
                "appliedFilter", "preFiltered", "escalate");
        // …and the retrieval pipeline is now reported explicitly.
        assertThat(diagnostics.get("retrievalMode")).isEqualTo("hybrid_tfidf_keyword_rrf");

        // Chunks carry the per-hit hybrid diagnostics used to build that ranking.
        assertThat(result.chunks).isNotEmpty();
        assertThat(result.chunks.get(0)).containsKeys("id", "title", "score", "excerpt",
                "fusedScore", "vectorScore", "keywordScore", "vectorRank", "keywordRank");
    }

    @Test
    void escalateDiagnosticsAlsoCarryRetrievalMode() {
        KnowledgeService empty = new KnowledgeService();
        empty.replaceDocumentsForTest(Collections.emptyList());
        HelpLoopService loop = new HelpLoopService(empty);

        HelpLoopService.LoopResult result = loop.run("完全无关的 xyzzy-no-hit-query-999", Collections.emptyList());

        assertThat(result.escalate).isTrue();
        assertThat(result.toDiagnostics().get("retrievalMode"))
                .isEqualTo(KnowledgeService.RETRIEVAL_MODE_HYBRID);
    }
}
