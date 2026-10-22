package com.aegis.alpha.service.help;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Ranking behaviour of the hybrid <b>lexical</b> retrieval pipeline:
 * TF-IDF vector cosine + legacy keyword score, fused with RRF (0.6 vector / 0.4 keyword),
 * then deterministically reranked.
 *
 * <p>These tests use synthetic corpora so each ranking property can be asserted in isolation.
 * They deliberately do not assume anything semantic: every match below is a shared surface token
 * (ASCII word or CJK bigram) or a curated keyword field hit.</p>
 */
class KnowledgeServiceHybridTest {

    private static final List<String> NO_ROLES = Collections.emptyList();

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private static KnowledgeService serviceWith(KnowledgeService.KnowledgeDoc... docs) {
        KnowledgeService service = new KnowledgeService();
        service.replaceDocumentsForTest(new ArrayList<KnowledgeService.KnowledgeDoc>(Arrays.asList(docs)));
        return service;
    }

    private static KnowledgeService.KnowledgeDoc doc(String id, String title, String visibility,
                                                     List<String> keywords, List<String> roles, String body) {
        KnowledgeService.KnowledgeDoc doc = new KnowledgeService.KnowledgeDoc();
        doc.setId(id);
        doc.setTitle(title);
        doc.setVisibility(visibility);
        doc.setKeywords(new ArrayList<String>(keywords));
        doc.setAllowedRoles(new ArrayList<String>(roles));
        doc.setBodyMarkdown(body);
        return doc;
    }

    private static KnowledgeService.KnowledgeDoc publicDoc(String id, String title, String body,
                                                           String... keywords) {
        return doc(id, title, "public", Arrays.asList(keywords), Collections.<String>emptyList(), body);
    }

    private static List<String> ids(KnowledgeService.SearchResult result) {
        List<String> ids = new ArrayList<String>();
        for (KnowledgeService.KnowledgeHit hit : result.getHits()) {
            ids.add(hit.getDoc().getId());
        }
        return ids;
    }

    private static List<Double> fusedScores(KnowledgeService.SearchResult result) {
        List<Double> scores = new ArrayList<Double>();
        for (KnowledgeService.KnowledgeHit hit : result.getHits()) {
            scores.add(Double.valueOf(hit.getFusedScore()));
        }
        return scores;
    }

    private static KnowledgeService.KnowledgeHit hitFor(KnowledgeService.SearchResult result, String id) {
        for (KnowledgeService.KnowledgeHit hit : result.getHits()) {
            if (id.equals(hit.getDoc().getId())) {
                return hit;
            }
        }
        return null;
    }

    /** Docs whose curated keyword fields match the query, plus one doc with no lexical overlap. */
    private static KnowledgeService serviceForRanking() {
        return serviceWith(
                publicDoc("approval-strong", "审批证据说明",
                        "审批人核对 evidence 与 degraded 标记后由人工操作。",
                        "审批", "evidence", "证据"),
                publicDoc("approval-generic", "推荐流程概览",
                        "工作流产出 RecommendationDraft 后需要人工复核，附 evidence 与 证据 摘要。"),
                publicDoc("unrelated", "运行中心",
                        "路径 /runs 查看节点状态。", "run")
        );
    }

    // ------------------------------------------------------------------
    // ranking
    // ------------------------------------------------------------------

    @Test
    void highKeywordMatchRanksFirst() {
        KnowledgeService.SearchResult result =
                serviceForRanking().search("审批 evidence 证据", NO_ROLES, 5);

        // The curated keyword + title match wins; the doc with only body mentions comes second.
        assertThat(ids(result)).containsExactly("approval-strong", "approval-generic");

        KnowledgeService.KnowledgeHit top = result.getHits().get(0);
        assertThat(top.getKeywordRank()).isEqualTo(1);
        assertThat(top.getKeywordScore()).isEqualTo(1d); // normalized best keyword score
        assertThat(top.getScore()).isGreaterThan(result.getHits().get(1).getScore());
        assertThat(top.getExcerpt()).isNotBlank();

        // Docs with no shared surface token are dropped entirely, not ranked last.
        assertThat(hitFor(result, "unrelated")).isNull();
    }

    @Test
    void vectorMatchCanOutrankKeywordMatch() {
        // "svc-a" has a strong TF-IDF vector match but almost no keyword score;
        // "retry-backoff" has a strong keyword-field match but shares no token with the doc text.
        KnowledgeService service = serviceWith(
                publicDoc("svc-a", "Service Reliability",
                        "retry backoff retry backoff retry backoff retry backoff policy"),
                publicDoc("retry-backoff", "运维手册", "见内部手册说明。", "retry backoff"),
                publicDoc("noise-doc", "无关文档", "nothing relevant here at all")
        );

        KnowledgeService.SearchResult result = service.search("retry backoff", NO_ROLES, 5);

        assertThat(ids(result)).containsExactly("svc-a", "retry-backoff");

        KnowledgeService.KnowledgeHit vectorHit = result.getHits().get(0);
        KnowledgeService.KnowledgeHit keywordHit = result.getHits().get(1);

        // Vector side: rank 1 with a near-perfect cosine.
        assertThat(vectorHit.getVectorRank()).isEqualTo(1);
        assertThat(vectorHit.getVectorScore()).isGreaterThan(0.9d);
        assertThat(vectorHit.getKeywordRank()).isEqualTo(2);

        // Keyword side: rank 1 despite sharing no token with the document text.
        assertThat(keywordHit.getKeywordRank()).isEqualTo(1);
        assertThat(keywordHit.getScore()).isGreaterThan(vectorHit.getScore());
        assertThat(keywordHit.getVectorRank()).isZero();
        assertThat(keywordHit.getVectorScore()).isEqualTo(0d);

        // RRF: absent from the vector ranking, so it only receives the keyword contribution.
        assertThat(keywordHit.getFusedScore()).isCloseTo(0.4d / 61d, within(1e-12));

        assertThat(result.getVectorCandidates()).isEqualTo(1);
        assertThat(result.getKeywordCandidates()).isEqualTo(2);
        assertThat(result.getFusedCandidates()).isEqualTo(2);
        assertThat(result.isReranked()).isTrue();
    }

    @Test
    void cjkBigramsHitBothKeywordAndVectorRetrieval() {
        // P2-1: the keyword tokenizer now emits CJK bigrams too (it used to glue "如何发布工作流"
        // into one Han run and score 0), so both retrievers rank the document first.
        KnowledgeService service = serviceWith(
                publicDoc("cn-doc", "工作流发布操作说明",
                        "画布编辑中的草稿需要先发布才能被生产对话触发。")
        );

        KnowledgeService.SearchResult result = service.search("如何发布工作流", NO_ROLES, 5);

        assertThat(ids(result)).containsExactly("cn-doc");
        KnowledgeService.KnowledgeHit hit = result.getHits().get(0);
        assertThat(hit.getKeywordScore()).isGreaterThan(0d);
        assertThat(hit.getKeywordRank()).isEqualTo(1);
        assertThat(hit.getVectorRank()).isEqualTo(1);
        assertThat(hit.getVectorScore()).isGreaterThan(0d);
        assertThat(hit.getFusedScore()).isCloseTo(0.6d / 61d + 0.4d / 61d, within(1e-12));

        assertThat(result.getKeywordCandidates()).isEqualTo(1);
        assertThat(result.getVectorCandidates()).isEqualTo(1);
        assertThat(result.getFusedCandidates()).isEqualTo(1);
        assertThat(result.isReranked()).isTrue();
    }

    @Test
    void hitsAreOrderedByFusedScoreAndExcerptsAreKept() {
        KnowledgeService service = serviceWith(
                publicDoc("workflow-ops", "工作流运行说明",
                        "published 工作流 工作流 工作流 触发。", "工作流"),
                publicDoc("approval", "审批治理", "PENDING_REVIEW 需要人工审批。", "审批"),
                publicDoc("run-center", "运行中心", "查看 节点输出与状态。", "运行中心")
        );

        KnowledgeService.SearchResult result = service.search("工作流 审批", NO_ROLES, 5);

        // run-center shares no token with the query, so only two docs carry evidence.
        assertThat(ids(result)).containsExactly("workflow-ops", "approval");
        List<Double> scores = fusedScores(result);
        for (int i = 1; i < scores.size(); i++) {
            assertThat(scores.get(i).doubleValue())
                    .isLessThanOrEqualTo(scores.get(i - 1).doubleValue());
        }
        for (KnowledgeService.KnowledgeHit hit : result.getHits()) {
            assertThat(hit.getExcerpt()).isNotBlank();
            assertThat(hit.getFusedScore()).isGreaterThan(0d);
            assertThat(hit.getDoc().getBodyMarkdown()).startsWith(hit.getExcerpt().replace("…", ""));
        }
    }

    @Test
    void limitIsAppliedAfterRerank() {
        KnowledgeService service = serviceWith(
                publicDoc("workflow-ops", "工作流运行说明",
                        "published 工作流 工作流 工作流 触发。", "工作流"),
                publicDoc("approval", "审批治理", "PENDING_REVIEW 需要人工审批。", "审批"),
                publicDoc("run-center", "运行中心", "查看 节点输出与状态。", "运行中心")
        );

        KnowledgeService.SearchResult limited = service.search("工作流 审批", NO_ROLES, 1);
        KnowledgeService.SearchResult full = service.search("工作流 审批", NO_ROLES, 5);

        assertThat(limited.getHits()).hasSize(1);
        assertThat(limited.getHits().get(0).getDoc().getId())
                .isEqualTo(full.getHits().get(0).getDoc().getId());
        // Cutting the result list must not change what was fused and reranked.
        assertThat(limited.getFusedCandidates()).isEqualTo(full.getFusedCandidates());
        assertThat(limited.getVectorCandidates()).isEqualTo(full.getVectorCandidates());
        assertThat(limited.getKeywordCandidates()).isEqualTo(full.getKeywordCandidates());
        assertThat(full.getHits()).hasSize(2);
    }

    // ------------------------------------------------------------------
    // ACL pre-filter
    // ------------------------------------------------------------------

    @Test
    void aclPreFilterExcludesInternalDocsForAnonymousAndIncludesThemForDesk() {
        KnowledgeService service = serviceWith(
                publicDoc("public-ops", "工作流操作说明", "published 工作流 经 Copilot 触发后在运行中心查看节点。", "工作流"),
                doc("internal-ops", "内部运维笔记", "internal",
                        Arrays.asList("orchestrator 重启"), Arrays.asList("desk"),
                        "Orchestrator 重启前先确认 token 与 运维 窗口。")
        );

        KnowledgeService.SearchResult anon = service.search("orchestrator 运维", NO_ROLES, 10);
        assertThat(anon.getCandidateCount()).isEqualTo(1);
        assertThat(anon.getCorpusCount()).isEqualTo(2);
        // The internal doc is the only lexical match, so an anonymous caller gets no hits at all:
        // the ACL filter runs before scoring, never after.
        assertThat(anon.getHits()).isEmpty();
        assertThat(ids(anon)).doesNotContain("internal-ops");
        assertThat(anon.getVectorCandidates()).isZero();
        assertThat(anon.getKeywordCandidates()).isZero();
        assertThat(anon.isReranked()).isFalse();

        KnowledgeService.SearchResult desk = service.search("orchestrator 运维", Arrays.asList("desk"), 10);
        assertThat(desk.getCandidateCount()).isEqualTo(2);
        assertThat(ids(desk)).contains("internal-ops");
        assertThat(desk.getHits().get(0).getDoc().getId()).isEqualTo("internal-ops");
        assertThat(desk.getHits().get(0).getDoc().getVisibility()).isEqualTo("internal");
    }

    @Test
    void anonymousCallerNeverSeesInternalDocEvenWithRoleKeywords() {
        KnowledgeService service = serviceWith(
                doc("internal-ops", "内部运维笔记", "internal",
                        Arrays.asList("运维", "内部"), Arrays.asList("desk", "admin"),
                        "本条仅 desk/admin 角色在 ACL 预过滤后可见。")
        );

        assertThat(service.search("内部 运维", NO_ROLES, 10).getHits()).isEmpty();
        assertThat(service.search("内部 运维", Arrays.asList("desk"), 10).getHits()).hasSize(1);
        assertThat(service.search("内部 运维", Arrays.asList("viewer"), 10).getHits()).isEmpty();
    }

    // ------------------------------------------------------------------
    // degenerate input
    // ------------------------------------------------------------------

    @Test
    void emptyQueryReturnsNoHitsWithoutError() {
        KnowledgeService service = serviceForRanking();

        KnowledgeService.SearchResult blank = service.search("", NO_ROLES, 5);
        assertThat(blank.getHits()).isEmpty();
        assertThat(blank.getRetrievalMode()).isEqualTo(KnowledgeService.RETRIEVAL_MODE_HYBRID);
        assertThat(blank.getVectorCandidates()).isZero();
        assertThat(blank.getKeywordCandidates()).isZero();
        assertThat(blank.getFusedCandidates()).isZero();
        assertThat(blank.isReranked()).isFalse();
        assertThat(blank.getCandidateCount()).isEqualTo(3);
        assertThat(blank.getCorpusCount()).isEqualTo(3);

        assertThat(service.search(null, null, 5).getHits()).isEmpty();
        assertThat(service.search("   ", NO_ROLES, 5).getHits()).isEmpty();
        // Query with no usable token at all: no vector, no keyword, no error.
        assertThat(service.search("???", NO_ROLES, 5).getHits()).isEmpty();
    }

    @Test
    void emptyCorpusReturnsNoHitsWithoutError() {
        KnowledgeService empty = serviceWith();

        KnowledgeService.SearchResult result = empty.search("审批 evidence", NO_ROLES, 5);

        assertThat(result.getHits()).isEmpty();
        assertThat(result.getCandidateCount()).isZero();
        assertThat(result.getCorpusCount()).isZero();
        assertThat(result.getVectorCandidates()).isZero();
        assertThat(result.getKeywordCandidates()).isZero();
        assertThat(result.getFusedCandidates()).isZero();
        assertThat(result.isReranked()).isFalse();
        assertThat(result.getRetrievalMode()).isEqualTo("hybrid_tfidf_keyword_rrf");
    }

    @Test
    void docsWithEmptyTextDoNotBreakTheVectorIndex() {
        KnowledgeService service = serviceWith(
                doc("no-text", "无正文", "public", Collections.<String>emptyList(),
                        Collections.<String>emptyList(), ""),
                publicDoc("has-text", "工作流说明", "published 工作流 操作步骤。", "工作流")
        );

        KnowledgeService.SearchResult result = service.search("工作流 操作步骤", NO_ROLES, 5);

        assertThat(ids(result)).containsExactly("has-text");
        assertThat(result.getCandidateCount()).isEqualTo(2);
        assertThat(result.getHits().get(0).getExcerpt()).isNotBlank();
    }

    // ------------------------------------------------------------------
    // metadata + determinism
    // ------------------------------------------------------------------

    @Test
    void metadataReportsHybridModeAndCandidateCounts() {
        KnowledgeService service = serviceWith(
                publicDoc("workflow-ops", "工作流运行说明", "published 工作流 经 Copilot 触发。", "工作流"),
                publicDoc("approval", "审批治理", "PENDING_REVIEW 需要人工审批。", "审批"),
                publicDoc("run-center", "运行中心", "查看 工作流 节点输出与 审批 状态。", "运行中心"),
                publicDoc("unrelated", "无关文档", "nothing relevant here at all")
        );

        KnowledgeService.SearchResult result = service.search("工作流 审批", NO_ROLES, 5);

        assertThat(result.getRetrievalMode()).isEqualTo("hybrid_tfidf_keyword_rrf");
        assertThat(result.isReranked()).isTrue();
        assertThat(result.getCandidateCount()).isEqualTo(4); // ACL-filtered pool
        assertThat(result.getCorpusCount()).isEqualTo(4);
        assertThat(result.getVectorCandidates()).isEqualTo(3);
        assertThat(result.getKeywordCandidates()).isEqualTo(3);
        assertThat(result.getFusedCandidates()).isEqualTo(3);
        // Union of the two rankings can never exceed the sum, nor be smaller than the larger one.
        assertThat(result.getFusedCandidates()).isGreaterThanOrEqualTo(
                Math.max(result.getVectorCandidates(), result.getKeywordCandidates()));
        assertThat(result.getHits()).hasSize(3);

        Map<String, Object> map = result.toMap();
        assertThat(map.get("retrievalMode")).isEqualTo("hybrid_tfidf_keyword_rrf");
        assertThat(map.get("vectorCandidates")).isEqualTo(3);
        assertThat(map.get("keywordCandidates")).isEqualTo(3);
        assertThat(map.get("fusedCandidates")).isEqualTo(3);
        assertThat(map.get("reranked")).isEqualTo(true);
        assertThat(map.get("candidateCount")).isEqualTo(4);
        assertThat(map.get("corpusCount")).isEqualTo(4);
        assertThat(map.get("preFiltered")).isEqualTo(true);
    }

    @Test
    void rankingIsDeterministicAcrossRepeatedCalls() {
        KnowledgeService service = serviceForRanking();

        KnowledgeService.SearchResult first = service.search("审批 evidence 证据", NO_ROLES, 5);
        List<String> expectedIds = ids(first);
        List<Double> expectedScores = fusedScores(first);

        for (int i = 0; i < 5; i++) {
            KnowledgeService.SearchResult repeated = service.search("审批 evidence 证据", NO_ROLES, 5);
            assertThat(ids(repeated)).containsExactlyElementsOf(expectedIds);
            assertThat(fusedScores(repeated)).containsExactlyElementsOf(expectedScores);
        }

        // A fresh instance over an equivalent corpus produces the same order and the same scores.
        KnowledgeService rebuilt = serviceForRanking();
        KnowledgeService.SearchResult fromRebuilt = rebuilt.search("审批 evidence 证据", NO_ROLES, 5);
        assertThat(ids(fromRebuilt)).containsExactlyElementsOf(expectedIds);
        assertThat(fusedScores(fromRebuilt)).containsExactlyElementsOf(expectedScores);
    }

    @Test
    void equalScoresAreBrokenByDocumentIdAscending() {
        // Two documents with identical text produce identical keyword scores and cosines,
        // so only the doc-id tie-break inside each ranking can decide the order.
        KnowledgeService service = serviceWith(
                publicDoc("beta-doc", "工作流说明", "published 工作流 操作步骤。", "工作流"),
                publicDoc("alpha-doc", "工作流说明", "published 工作流 操作步骤。", "工作流")
        );

        KnowledgeService.SearchResult result = service.search("工作流 操作步骤", NO_ROLES, 5);

        assertThat(ids(result)).containsExactly("alpha-doc", "beta-doc");
        KnowledgeService.KnowledgeHit first = result.getHits().get(0);
        KnowledgeService.KnowledgeHit second = result.getHits().get(1);
        assertThat(first.getVectorScore()).isEqualTo(second.getVectorScore());
        assertThat(first.getScore()).isEqualTo(second.getScore());
        assertThat(first.getVectorRank()).isEqualTo(1);
        assertThat(second.getVectorRank()).isEqualTo(2);
    }
}
