package com.aegis.alpha.service.help;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Workflow-first help loop: retrieve → grade → rewrite/re-retrieve (≤3) → draft or escalate.
 * Uses KnowledgeService with ACL pre-filter; does not invent run/portfolio facts.
 *
 * <p>Retrieval is the hybrid <b>lexical</b> pipeline of {@link KnowledgeService}
 * (TF-IDF vector + keyword + RRF rerank, no neural embeddings). The mode actually used is carried
 * into {@link LoopResult#retrievalMode} and surfaced by {@link LoopResult#toDiagnostics()}.</p>
 */
@Service
public class HelpLoopService {
    public static final int MAX_RETRIEVAL_ROUNDS = 3;
    public static final String DEFAULT_DISCLAIMER = HelpService.DEFAULT_DISCLAIMER;

    private final KnowledgeService knowledgeService;

    public HelpLoopService(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    public LoopResult run(String message, List<String> principalRoles) {
        String query = message == null ? "" : message.trim();
        List<Map<String, Object>> allChunks = new ArrayList<Map<String, Object>>();
        Map<String, Object> lastFilter = new LinkedHashMap<String, Object>();
        String currentQuery = query;
        Grade lastGrade = Grade.fail("no_rounds");
        String lastRetrievalMode = KnowledgeService.RETRIEVAL_MODE_HYBRID;

        for (int round = 1; round <= MAX_RETRIEVAL_ROUNDS; round++) {
            KnowledgeService.SearchResult search = knowledgeService.search(currentQuery, principalRoles, 5);
            lastFilter = search.getAppliedFilter();
            lastRetrievalMode = search.getRetrievalMode();
            List<Map<String, Object>> chunks = new ArrayList<Map<String, Object>>();
            for (KnowledgeService.KnowledgeHit hit : search.getHits()) {
                chunks.add(hit.toMap());
            }
            // merge unique by id
            for (Map<String, Object> chunk : chunks) {
                String id = string(chunk.get("id"));
                boolean exists = false;
                for (Map<String, Object> existing : allChunks) {
                    if (id.equals(string(existing.get("id")))) {
                        exists = true;
                        break;
                    }
                }
                if (!exists) {
                    allChunks.add(chunk);
                }
            }

            lastGrade = grade(query, allChunks);
            if (lastGrade.ok) {
                return draftSuccess(query, allChunks, lastGrade, round, lastFilter, lastRetrievalMode);
            }
            if (round < MAX_RETRIEVAL_ROUNDS) {
                currentQuery = rewriteQuery(query, currentQuery, round);
            }
        }
        return escalate(query, allChunks, lastGrade, MAX_RETRIEVAL_ROUNDS, lastFilter, lastRetrievalMode);
    }

    /**
     * Deterministic evidence grader (no LLM). Used by tests for re-retrieve cap.
     */
    public Grade grade(String message, List<Map<String, Object>> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return Grade.fail("no_chunks");
        }
        String text = message == null ? "" : message.toLowerCase(Locale.ROOT);
        List<String> tokens = tokenize(text);
        if (tokens.isEmpty()) {
            return Grade.ok(1.0, 1.0, "empty_query_tokens");
        }
        StringBuilder blob = new StringBuilder();
        for (Map<String, Object> c : chunks) {
            blob.append(' ').append(string(c.get("excerpt"))).append(' ').append(string(c.get("text")))
                    .append(' ').append(string(c.get("title")));
        }
        String lowerBlob = blob.toString().toLowerCase(Locale.ROOT);
        int hits = 0;
        for (String t : tokens) {
            if (lowerBlob.contains(t)) {
                hits++;
            }
        }
        double coverage = (double) hits / (double) tokens.size();
        double density = Math.min(lowerBlob.length() / 200.0, 1.0);
        // High coverage can pass with shorter excerpts; density only required when coverage is marginal.
        boolean ok = coverage >= 0.5 || (coverage >= 0.35 && density >= 0.15);
        if (ok) {
            return Grade.ok(coverage, density, "pass");
        }
        String reason = coverage < 0.35 ? "low_coverage" : "low_density";
        return new Grade(false, coverage, density, reason);
    }

    public String rewriteQuery(String original, String previous, int round) {
        String base = original == null ? "" : original.trim();
        if (round == 1) {
            return base + " 操作 步骤 说明";
        }
        if (round == 2) {
            return base + " workflow 运行 中心 审批";
        }
        return previous == null ? base : previous;
    }

    private LoopResult draftSuccess(
            String query,
            List<Map<String, Object>> chunks,
            Grade grade,
            int rounds,
            Map<String, Object> filter,
            String retrievalMode
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 知识库回答\n\n");
        sb.append("根据公开产品文档整理如下（共检索 ").append(rounds).append(" 轮）：\n\n");
        int i = 1;
        for (Map<String, Object> chunk : chunks) {
            if (i > 3) {
                break;
            }
            sb.append("### ").append(i++).append(". ").append(string(chunk.get("title"))).append("\n\n");
            sb.append(string(chunk.get("excerpt"))).append("\n\n");
        }
        sb.append("_来源均经 ACL 预过滤后的公开/授权文档。私有 run/组合数据不会出现在知识索引中。_\n");
        sb.append("_检索方式：词面向量（TF-IDF）+ 关键词 + RRF 融合（无神经嵌入，不含同义词/语义扩展）。_\n");

        LoopResult result = new LoopResult();
        result.ok = true;
        result.escalate = false;
        result.content = sb.toString();
        result.retrievalRounds = rounds;
        result.grade = grade;
        result.chunks = chunks;
        result.appliedFilter = filter;
        result.retrievalMode = retrievalMode;
        result.disclaimer = null;
        result.provider = "help-loop";
        return result;
    }

    private LoopResult escalate(
            String query,
            List<Map<String, Object>> chunks,
            Grade grade,
            int rounds,
            Map<String, Object> filter,
            String retrievalMode
    ) {
        LoopResult result = new LoopResult();
        result.ok = false;
        result.escalate = true;
        result.content = "根据现有公开资料无法给出可靠回答。\n\n" + DEFAULT_DISCLAIMER;
        result.retrievalRounds = rounds;
        result.grade = grade;
        result.chunks = chunks;
        result.appliedFilter = filter;
        result.retrievalMode = retrievalMode;
        result.disclaimer = DEFAULT_DISCLAIMER;
        result.provider = "help-loop";
        return result;
    }

    private static List<String> tokenize(String lower) {
        List<String> tokens = new ArrayList<String>();
        StringBuilder ascii = new StringBuilder();
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                ascii.append(c);
            } else {
                if (ascii.length() > 1) {
                    tokens.add(ascii.toString());
                }
                ascii.setLength(0);
                if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN
                        && i + 1 < lower.length()
                        && Character.UnicodeScript.of(lower.charAt(i + 1)) == Character.UnicodeScript.HAN) {
                    tokens.add(lower.substring(i, i + 2));
                }
            }
        }
        if (ascii.length() > 1) {
            tokens.add(ascii.toString());
        }
        return tokens;
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    public static final class Grade {
        public final boolean ok;
        public final double coverage;
        public final double density;
        public final String reason;

        public Grade(boolean ok, double coverage, double density, String reason) {
            this.ok = ok;
            this.coverage = coverage;
            this.density = density;
            this.reason = reason;
        }

        public static Grade ok(double coverage, double density, String reason) {
            return new Grade(true, coverage, density, reason);
        }

        public static Grade fail(String reason) {
            return new Grade(false, 0, 0, reason);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("ok", ok);
            m.put("coverage", coverage);
            m.put("density", density);
            m.put("reason", reason);
            return m;
        }
    }

    public static final class LoopResult {
        public boolean ok;
        public boolean escalate;
        public String content;
        public int retrievalRounds;
        public Grade grade;
        public List<Map<String, Object>> chunks;
        public Map<String, Object> appliedFilter;
        /** Retrieval pipeline reported by the last {@link KnowledgeService.SearchResult}. */
        public String retrievalMode;
        public String disclaimer;
        public String provider;

        public Map<String, Object> toDiagnostics() {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("retrievalRounds", retrievalRounds);
            m.put("maxRounds", MAX_RETRIEVAL_ROUNDS);
            m.put("grade", grade == null ? null : grade.toMap());
            m.put("chunkCount", chunks == null ? 0 : chunks.size());
            m.put("appliedFilter", appliedFilter);
            m.put("preFiltered", true);
            m.put("escalate", escalate);
            m.put("retrievalMode", retrievalMode);
            return m;
        }
    }
}
