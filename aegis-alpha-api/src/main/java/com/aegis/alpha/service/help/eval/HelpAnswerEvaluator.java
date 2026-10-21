package com.aegis.alpha.service.help.eval;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Deterministic, dependency-free answer-level evaluator for the Help Agent (P2).
 *
 * <p>Scope: answer quality on top of route accuracy. Every metric is a token-overlap ratio
 * computed against the {@code chunks} retrieved for the case, so the same case always produces the
 * same numbers. This is a <b>grounding proxy, not an LLM judge</b> — it catches regressions such as
 * invented facts, irrelevant retrieval and wrong escalation decisions in CI; it does not replace
 * human review of answer quality.</p>
 *
 * <p>Metric definitions:</p>
 * <ul>
 *   <li><b>faithfulness</b>: fraction of distinct answer tokens that occur in the union of chunk
 *       {@code title}/{@code text} tokens. Low values mean the answer drifted from its sources
 *       (for example an invented number).</li>
 *   <li><b>relevance</b>: fraction of distinct query tokens that occur in the union of chunk
 *       {@code title}/{@code text} tokens. Low values mean retrieval hit the wrong documents,
 *       regardless of how well the answer matches them.</li>
 *   <li><b>taskCompletion</b>: 1.0 when the answer is non-empty, carries a source/chunk reference
 *       (or the case supplied chunks) and did not escalate when escalation was not expected;
 *       0.5 when the answer offers actionable steps but no sources; 0.0 otherwise.</li>
 *   <li><b>escalationCorrect</b>: {@code escalated == expectedEscalate}.</li>
 * </ul>
 *
 * <p>Tokenization: lowercased ASCII words plus CJK bigrams; tokens shorter than two characters are
 * ignored, so punctuation and single characters never contribute. No LLM, no external service, no
 * randomness — the evaluator is a pure function of the case.</p>
 */
public class HelpAnswerEvaluator {

    /** Default grounding gate from the help-agent design doc (Faithfulness &gt;= 0.75). */
    public static final double DEFAULT_MIN_FAITHFULNESS = 0.75;
    /** Default retrieval gate from the help-agent design doc (Relevancy &gt;= 0.80). */
    public static final double DEFAULT_MIN_RELEVANCE = 0.80;
    /** Task completion is binary unless a case relaxes it. */
    public static final double DEFAULT_MIN_TASK_COMPLETION = 1.0;

    /** Partial credit for an answer that lists actionable steps but cites no source. */
    private static final double PARTIAL_ACTIONABLE_SCORE = 0.5;
    private static final int MIN_TOKEN_LENGTH = 2;
    private static final double EPSILON = 1e-9;

    /** Markers that make an answer actionable even when it is not grounded. */
    private static final String[] ACTIONABLE_MARKERS = {
            "步骤", "打开", "路径", "点击", "运行", "查看", "联系"
    };

    /** Markers that count as an explicit source / chunk reference inside the answer text. */
    private static final String[] SOURCE_MARKERS = {
            "来源", "参考", "引用", "文档", "source", "chunk", "citation"
    };

    /** Numeric citation such as "[1]". */
    private static final Pattern CITATION_PATTERN = Pattern.compile("\\[\\d+\\]");

    /**
     * Evaluates a single case.
     *
     * @param evalCase case to evaluate, must not be null
     * @return immutable per-case report, never null
     */
    public HelpEvalReport evaluate(HelpEvalCase evalCase) {
        if (evalCase == null) {
            throw new IllegalArgumentException("evalCase must not be null");
        }
        String answer = evalCase.getAnswer() == null ? "" : evalCase.getAnswer();
        String query = evalCase.getQuery() == null ? "" : evalCase.getQuery();
        List<Map<String, Object>> chunks = evalCase.getChunks() == null
                ? Collections.<Map<String, Object>>emptyList()
                : evalCase.getChunks();

        Set<String> corpusTokens = corpusTokens(chunks);
        double faithfulness = round4(ratio(tokenizeUnique(answer), corpusTokens));
        double relevance = round4(ratio(tokenizeUnique(query), corpusTokens));
        double taskCompletion = round4(taskCompletion(answer, chunks,
                evalCase.isEscalated(), evalCase.isExpectedEscalate()));
        boolean escalationCorrect = evalCase.isEscalated() == evalCase.isExpectedEscalate();
        boolean answerEmpty = answer.trim().isEmpty();

        List<String> reasons = new ArrayList<String>();
        if (answerEmpty) {
            reasons.add("answer is empty");
        }
        if (faithfulness + EPSILON < evalCase.getMinFaithfulness()) {
            reasons.add("faithfulness " + format4(faithfulness)
                    + " below " + format4(evalCase.getMinFaithfulness()));
        }
        if (relevance + EPSILON < evalCase.getMinRelevance()) {
            reasons.add("relevance " + format4(relevance)
                    + " below " + format4(evalCase.getMinRelevance()));
        }
        if (taskCompletion + EPSILON < evalCase.getMinTaskCompletion()) {
            reasons.add("taskCompletion " + format4(taskCompletion)
                    + " below " + format4(evalCase.getMinTaskCompletion()));
        }
        if (!escalationCorrect) {
            reasons.add("escalation incorrect: expected=" + evalCase.isExpectedEscalate()
                    + " actual=" + evalCase.isEscalated());
        }

        boolean pass = !answerEmpty
                && faithfulness + EPSILON >= evalCase.getMinFaithfulness()
                && relevance + EPSILON >= evalCase.getMinRelevance()
                && taskCompletion + EPSILON >= evalCase.getMinTaskCompletion()
                && escalationCorrect;

        return new HelpEvalReport(evalCase.getId(), faithfulness, relevance,
                taskCompletion, escalationCorrect, pass, reasons);
    }

    /**
     * Evaluates every case, preserving fixture order.
     *
     * @param cases cases to evaluate, null is treated as an empty list
     * @return reports in the same order as {@code cases}
     */
    public List<HelpEvalReport> evaluateAll(List<HelpEvalCase> cases) {
        List<HelpEvalReport> reports = new ArrayList<HelpEvalReport>();
        if (cases == null) {
            return reports;
        }
        for (HelpEvalCase evalCase : cases) {
            reports.add(evaluate(evalCase));
        }
        return reports;
    }

    /**
     * Aggregates a fixture run.
     *
     * <p>Averages are taken over the supplied reports; {@code passRate} and
     * {@code escalationAccuracy} divide by {@code max(cases, reports)} so a case without a report
     * counts as not passed instead of silently shrinking the denominator.</p>
     *
     * @param cases   cases that were evaluated (may be null)
     * @param reports reports produced for those cases (may be null)
     * @return aggregate metrics, never null
     */
    public Aggregate aggregate(List<HelpEvalCase> cases, List<HelpEvalReport> reports) {
        List<HelpEvalReport> safeReports = reports == null
                ? Collections.<HelpEvalReport>emptyList()
                : reports;
        int caseCount = cases == null ? 0 : cases.size();
        int total = Math.max(caseCount, safeReports.size());
        if (total == 0) {
            return new Aggregate(0.0, 0.0, 0.0, 0.0, 0.0);
        }

        int passed = 0;
        int escalationCorrect = 0;
        int counted = 0;
        double sumFaithfulness = 0.0;
        double sumRelevance = 0.0;
        double sumTaskCompletion = 0.0;
        for (HelpEvalReport report : safeReports) {
            if (report == null) {
                continue;
            }
            counted++;
            if (report.isPass()) {
                passed++;
            }
            if (report.isEscalationCorrect()) {
                escalationCorrect++;
            }
            sumFaithfulness += report.getFaithfulness();
            sumRelevance += report.getRelevance();
            sumTaskCompletion += report.getTaskCompletion();
        }

        return new Aggregate(
                round4(passed / (double) total),
                counted == 0 ? 0.0 : round4(sumFaithfulness / counted),
                counted == 0 ? 0.0 : round4(sumRelevance / counted),
                counted == 0 ? 0.0 : round4(sumTaskCompletion / counted),
                round4(escalationCorrect / (double) total));
    }

    /**
     * Tokenizes text into distinct lowercased ASCII words and CJK bigrams.
     * Tokens shorter than two characters are dropped.
     *
     * @param text raw text, null/empty yields an empty set
     * @return distinct tokens in first-seen order
     */
    static Set<String> tokenizeUnique(String text) {
        Set<String> tokens = new LinkedHashSet<String>();
        if (text == null || text.isEmpty()) {
            return tokens;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        StringBuilder asciiBuffer = new StringBuilder();
        for (int i = 0; i < lower.length(); i++) {
            char current = lower.charAt(i);
            if (isAsciiWordChar(current)) {
                asciiBuffer.append(current);
                continue;
            }
            flushAsciiToken(tokens, asciiBuffer);
            if (isHan(current) && i + 1 < lower.length() && isHan(lower.charAt(i + 1))) {
                tokens.add(lower.substring(i, i + 2));
            }
        }
        flushAsciiToken(tokens, asciiBuffer);
        return tokens;
    }

    /**
     * Package-visible for tests: an answer counts as sourced when the case carried chunks or when
     * the answer itself cites a source ("来源"/"参考"/"引用"/"文档"/chunk/… or "[1]").
     */
    static boolean hasSourceReference(String answer, List<Map<String, Object>> chunks) {
        if (chunks != null && !chunks.isEmpty()) {
            return true;
        }
        if (answer == null) {
            return false;
        }
        String lower = answer.toLowerCase(Locale.ROOT);
        if (lower.trim().isEmpty()) {
            return false;
        }
        for (String marker : SOURCE_MARKERS) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return CITATION_PATTERN.matcher(lower).find();
    }

    /**
     * Package-visible for tests: true when the answer contains at least one actionable marker.
     */
    static boolean containsActionableMarker(String answer) {
        if (answer == null || answer.isEmpty()) {
            return false;
        }
        String lower = answer.toLowerCase(Locale.ROOT);
        for (String marker : ACTIONABLE_MARKERS) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private static double taskCompletion(String answer, List<Map<String, Object>> chunks,
                                         boolean escalated, boolean expectedEscalate) {
        if (answer.trim().isEmpty()) {
            return 0.0;
        }
        boolean hasSources = hasSourceReference(answer, chunks);
        boolean overEscalated = !expectedEscalate && escalated;
        if (hasSources && !overEscalated) {
            return 1.0;
        }
        if (!hasSources && containsActionableMarker(answer)) {
            return PARTIAL_ACTIONABLE_SCORE;
        }
        return 0.0;
    }

    private static Set<String> corpusTokens(List<Map<String, Object>> chunks) {
        Set<String> tokens = new LinkedHashSet<String>();
        for (Map<String, Object> chunk : chunks) {
            if (chunk == null) {
                continue;
            }
            tokens.addAll(tokenizeUnique(stringValue(chunk.get("title"))));
            tokens.addAll(tokenizeUnique(stringValue(chunk.get("text"))));
        }
        return tokens;
    }

    private static double ratio(Collection<String> tokens, Set<String> corpusTokens) {
        if (tokens.isEmpty()) {
            return 0.0;
        }
        int hits = 0;
        for (String token : tokens) {
            if (corpusTokens.contains(token)) {
                hits++;
            }
        }
        return hits / (double) tokens.size();
    }

    private static void flushAsciiToken(Set<String> tokens, StringBuilder buffer) {
        if (buffer.length() >= MIN_TOKEN_LENGTH) {
            tokens.add(buffer.toString());
        }
        buffer.setLength(0);
    }

    private static boolean isAsciiWordChar(char value) {
        return value < 128 && Character.isLetterOrDigit(value);
    }

    private static boolean isHan(char value) {
        return Character.UnicodeScript.of(value) == Character.UnicodeScript.HAN;
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static double round4(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    private static String format4(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    /**
     * Fixture-level metrics for one evaluation run.
     */
    public static final class Aggregate {
        private final double passRate;
        private final double avgFaithfulness;
        private final double avgRelevance;
        private final double avgTaskCompletion;
        private final double escalationAccuracy;

        public Aggregate(double passRate, double avgFaithfulness, double avgRelevance,
                         double avgTaskCompletion, double escalationAccuracy) {
            this.passRate = passRate;
            this.avgFaithfulness = avgFaithfulness;
            this.avgRelevance = avgRelevance;
            this.avgTaskCompletion = avgTaskCompletion;
            this.escalationAccuracy = escalationAccuracy;
        }

        public double getPassRate() {
            return passRate;
        }

        public double getAvgFaithfulness() {
            return avgFaithfulness;
        }

        public double getAvgRelevance() {
            return avgRelevance;
        }

        public double getAvgTaskCompletion() {
            return avgTaskCompletion;
        }

        public double getEscalationAccuracy() {
            return escalationAccuracy;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            map.put("passRate", passRate);
            map.put("avgFaithfulness", avgFaithfulness);
            map.put("avgRelevance", avgRelevance);
            map.put("avgTaskCompletion", avgTaskCompletion);
            map.put("escalationAccuracy", escalationAccuracy);
            return map;
        }

        @Override
        public String toString() {
            return "Aggregate{passRate=" + format4(passRate)
                    + ", avgFaithfulness=" + format4(avgFaithfulness)
                    + ", avgRelevance=" + format4(avgRelevance)
                    + ", avgTaskCompletion=" + format4(avgTaskCompletion)
                    + ", escalationAccuracy=" + format4(escalationAccuracy) + "}";
        }
    }
}
