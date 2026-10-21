package com.aegis.alpha.service.help.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Answer-level evaluation gate for the Help Agent (P2): beyond route accuracy.
 *
 * <p>Runs the JSON fixture at {@code classpath:help/eval/cases.json} through
 * {@link HelpAnswerEvaluator} and asserts, per case, whether the answer should be accepted. The
 * mandated scenarios are the grounded how-to, an invented number, irrelevant retrieval, expected
 * escalation, missing escalation (false confidence), over-escalation, an empty answer and a partial
 * answer with actionable steps but weak grounding. Additional grounded cases keep the fixture
 * representative of the product help corpus, so {@code passRate} is a meaningful gate rather than
 * the score of a pure negative set.</p>
 *
 * <p>The fixture itself is strict JSON (unknown properties are rejected), so a mistyped field fails
 * loudly instead of silently falling back to a default.</p>
 */
class HelpAnswerEvaluatorTest {

    private static final String FIXTURE_PATH = "/help/eval/cases.json";

    /** Scenarios explicitly required for this evaluation layer. */
    private static final Set<String> REQUIRED_SCENARIOS = new LinkedHashSet<String>();

    /** Expected verdict per fixture case: the whole point of the fixture is to know these. */
    private static final Map<String, Boolean> EXPECTED_OUTCOMES = new LinkedHashMap<String, Boolean>();

    static {
        REQUIRED_SCENARIOS.add("eval-01-grounded-howto");
        REQUIRED_SCENARIOS.add("eval-02-invented-number");
        REQUIRED_SCENARIOS.add("eval-03-irrelevant-retrieval");
        REQUIRED_SCENARIOS.add("eval-04-escalation-expected");
        REQUIRED_SCENARIOS.add("eval-05-false-confidence");
        REQUIRED_SCENARIOS.add("eval-06-over-escalation");
        REQUIRED_SCENARIOS.add("eval-07-empty-answer");
        REQUIRED_SCENARIOS.add("eval-08-partial-actionable-weak-grounding");

        // 1 grounded how-to with sources
        EXPECTED_OUTCOMES.put("eval-01-grounded-howto", true);
        // 2 answer invents a number that is not in the chunks
        EXPECTED_OUTCOMES.put("eval-02-invented-number", false);
        // 3 retrieval returned irrelevant chunks
        EXPECTED_OUTCOMES.put("eval-03-irrelevant-retrieval", false);
        // 4 escalation expected and performed
        EXPECTED_OUTCOMES.put("eval-04-escalation-expected", true);
        // 5 escalation expected but skipped: false confidence
        EXPECTED_OUTCOMES.put("eval-05-false-confidence", false);
        // 6 success expected but escalated: over-escalation
        EXPECTED_OUTCOMES.put("eval-06-over-escalation", false);
        // 7 empty answer
        EXPECTED_OUTCOMES.put("eval-07-empty-answer", false);
        // 8 actionable steps, no sources, no grounding
        EXPECTED_OUTCOMES.put("eval-08-partial-actionable-weak-grounding", false);

        // Grounded product-help answers that should be accepted.
        String[] accepted = {
                "eval-09-run-center-node-output",
                "eval-10-approval-inbox",
                "eval-11-published-workflow",
                "eval-12-degraded-meaning",
                "eval-13-rerun-workflow",
                "eval-14-backtest-params",
                "eval-15-watchlist-add",
                "eval-16-daily-report",
                "eval-17-portfolio-detail",
                "eval-18-token-expired",
                "eval-19-publish-workflow",
                "eval-20-approval-timeout",
                "eval-21-knowledge-update",
                "eval-22-help-entry",
                "eval-23-export-logs",
                "eval-24-portfolio-pnl",
                "eval-25-agent-node",
                "eval-26-reject-reason"
        };
        for (String caseId : accepted) {
            EXPECTED_OUTCOMES.put(caseId, true);
        }
    }

    private static List<HelpEvalCase> cases;
    private static Map<String, HelpEvalReport> reports;
    private static HelpAnswerEvaluator.Aggregate aggregate;

    @BeforeAll
    static void loadFixtureAndEvaluate() throws Exception {
        cases = readFixture();
        HelpAnswerEvaluator evaluator = new HelpAnswerEvaluator();
        List<HelpEvalReport> evaluated = evaluator.evaluateAll(cases);
        reports = new LinkedHashMap<String, HelpEvalReport>();
        for (HelpEvalReport report : evaluated) {
            reports.put(report.getCaseId(), report);
        }
        aggregate = evaluator.aggregate(cases, evaluated);
    }

    @Test
    void fixtureCoversRequiredScenarios() {
        assertThat(cases).hasSize(EXPECTED_OUTCOMES.size());
        assertThat(cases.size()).isGreaterThanOrEqualTo(8);

        Set<String> ids = new LinkedHashSet<String>();
        for (HelpEvalCase evalCase : cases) {
            assertThat(evalCase.getId()).as("case id must not be blank").isNotBlank();
            assertThat(ids.add(evalCase.getId())).as("duplicate case id %s", evalCase.getId()).isTrue();
        }
        assertThat(ids).containsAll(REQUIRED_SCENARIOS);
        assertThat(EXPECTED_OUTCOMES.keySet()).containsExactlyElementsOf(ids);
        assertThat(reports.keySet()).containsExactlyElementsOf(ids);
    }

    @Test
    void everyCaseVerdictMatchesFixtureExpectation() {
        int failedCases = 0;
        for (Map.Entry<String, Boolean> expectation : EXPECTED_OUTCOMES.entrySet()) {
            HelpEvalReport report = report(expectation.getKey());
            assertThat(report.isPass())
                    .as("case %s expected pass=%s but was pass=%s (%s)",
                            expectation.getKey(), expectation.getValue(), report.isPass(), report.getReasons())
                    .isEqualTo(expectation.getValue());
            if (!report.isPass()) {
                failedCases++;
            }
        }
        // Six scenarios are deliberate failure modes; everything else must be accepted.
        assertThat(failedCases).isEqualTo(6);
    }

    @Test
    void groundedHowToAnswerWithSourcesPasses() {
        HelpEvalReport report = report("eval-01-grounded-howto");

        assertThat(report.isPass()).isTrue();
        assertThat(report.getReasons()).isEmpty();
        assertThat(report.getFaithfulness()).isCloseTo(1.0, within(0.0001));
        assertThat(report.getRelevance()).isCloseTo(1.0, within(0.0001));
        assertThat(report.getTaskCompletion()).isEqualTo(1.0);
        assertThat(report.isEscalationCorrect()).isTrue();
    }

    @Test
    void inventedNumberFailsFaithfulness() {
        HelpEvalReport report = report("eval-02-invented-number");

        assertThat(report.isPass()).isFalse();
        // "30 分钟" / "5 次" / "邮箱" appear nowhere in the retrieved chunk.
        assertThat(report.getFaithfulness()).isLessThan(0.5);
        assertThat(report.getReasons()).anySatisfy(reason -> assertThat(reason).contains("faithfulness"));
    }

    @Test
    void irrelevantRetrievalChunksFailRelevance() {
        HelpEvalReport report = report("eval-03-irrelevant-retrieval");

        assertThat(report.isPass()).isFalse();
        // The answer copies the (wrong) chunks faithfully; only retrieval relevance is broken.
        assertThat(report.getFaithfulness()).isCloseTo(1.0, within(0.0001));
        assertThat(report.getRelevance()).isLessThan(0.5);
        assertThat(report.getReasons()).anySatisfy(reason -> assertThat(reason).contains("relevance"));
    }

    @Test
    void expectedEscalationPasses() {
        HelpEvalReport report = report("eval-04-escalation-expected");

        assertThat(report.isEscalationCorrect()).isTrue();
        assertThat(report.getTaskCompletion()).isEqualTo(1.0);
        assertThat(report.isPass()).isTrue();
    }

    @Test
    void missingEscalationFailsOnFalseConfidence() {
        HelpEvalReport report = report("eval-05-false-confidence");

        assertThat(report.isEscalationCorrect()).isFalse();
        assertThat(report.isPass()).isFalse();
        assertThat(report.getReasons()).anySatisfy(reason -> assertThat(reason).contains("escalation incorrect"));
    }

    @Test
    void overEscalationFailsTaskCompletion() {
        HelpEvalReport report = report("eval-06-over-escalation");

        assertThat(report.isEscalationCorrect()).isFalse();
        assertThat(report.getTaskCompletion()).isEqualTo(0.0);
        assertThat(report.isPass()).isFalse();
    }

    @Test
    void emptyAnswerFails() {
        HelpEvalReport report = report("eval-07-empty-answer");

        assertThat(report.isPass()).isFalse();
        assertThat(report.getFaithfulness()).isEqualTo(0.0);
        assertThat(report.getTaskCompletion()).isEqualTo(0.0);
        assertThat(report.getReasons()).contains("answer is empty");
    }

    @Test
    void actionableStepsWithoutSourcesEarnPartialCreditButStillFail() {
        HelpEvalReport report = report("eval-08-partial-actionable-weak-grounding");

        // Partial credit, not a pass: the steps are actionable but cite no source and ground nothing.
        assertThat(report.getTaskCompletion()).isEqualTo(0.5);
        assertThat(report.getFaithfulness()).isEqualTo(0.0);
        assertThat(report.getRelevance()).isEqualTo(0.0);
        assertThat(report.isPass()).isFalse();
        assertThat(report.getReasons()).anySatisfy(reason -> assertThat(reason).contains("taskCompletion"));
    }

    @Test
    void aggregateMetricsMeetHelpAgentGates() {
        // Help-agent design doc: faithfulness >= 0.75, relevancy >= 0.80.
        assertThat(aggregate.getPassRate()).isGreaterThanOrEqualTo(0.75);
        assertThat(aggregate.getAvgFaithfulness()).isGreaterThanOrEqualTo(0.75);
        assertThat(aggregate.getAvgRelevance()).isGreaterThanOrEqualTo(0.80);
        assertThat(aggregate.getAvgTaskCompletion()).isGreaterThanOrEqualTo(0.85);
        assertThat(aggregate.getEscalationAccuracy()).isGreaterThanOrEqualTo(0.90);
    }

    @Test
    void aggregateSnapshotMatchesFixture() {
        // Snapshot of the current fixture: intentional failures are 6 of 26 cases.
        assertThat(aggregate.getPassRate()).isCloseTo(0.7692, within(0.0001));
        assertThat(aggregate.getAvgFaithfulness()).isCloseTo(0.7970, within(0.0001));
        assertThat(aggregate.getAvgRelevance()).isCloseTo(0.8638, within(0.0001));
        assertThat(aggregate.getAvgTaskCompletion()).isCloseTo(0.9038, within(0.0001));
        assertThat(aggregate.getEscalationAccuracy()).isCloseTo(0.9231, within(0.0001));
        System.out.println("[help-eval] cases=" + cases.size() + " " + aggregate);
    }

    @Test
    void evaluationIsDeterministic() {
        HelpAnswerEvaluator evaluator = new HelpAnswerEvaluator();
        HelpEvalReport first = evaluator.evaluate(findCase("eval-02-invented-number"));
        HelpEvalReport second = evaluator.evaluate(findCase("eval-02-invented-number"));

        assertThat(first.getFaithfulness()).isEqualTo(second.getFaithfulness());
        assertThat(first.getRelevance()).isEqualTo(second.getRelevance());
        assertThat(first.getTaskCompletion()).isEqualTo(second.getTaskCompletion());
        assertThat(first.getReasons()).isEqualTo(second.getReasons());
    }

    @Test
    void tokenizerKeepsAsciiWordsAndCjkBigrams() {
        Set<String> tokens = HelpAnswerEvaluator.tokenizeUnique("AI Copilot 如何打开？");

        assertThat(tokens).containsExactly("ai", "copilot", "如何", "何打", "打开");
        // Single characters and standalone punctuation never become tokens.
        assertThat(HelpAnswerEvaluator.tokenizeUnique("A 1 我")).isEmpty();
        assertThat(HelpAnswerEvaluator.tokenizeUnique(null)).isEmpty();
    }

    @Test
    void sourceReferenceDetectionAcceptsChunksMarkersAndCitations() {
        List<Map<String, Object>> empty = java.util.Collections.emptyList();
        List<Map<String, Object>> chunks = java.util.Collections.singletonList(
                java.util.Collections.<String, Object>singletonMap("title", "运行中心"));

        assertThat(HelpAnswerEvaluator.hasSourceReference("任意回答", chunks)).isTrue();
        assertThat(HelpAnswerEvaluator.hasSourceReference("参考帮助文档，点击导出日志按钮。", empty)).isTrue();
        assertThat(HelpAnswerEvaluator.hasSourceReference("见 [2] 的说明。", empty)).isTrue();
        assertThat(HelpAnswerEvaluator.hasSourceReference("点击导出日志按钮。", empty)).isFalse();
        assertThat(HelpAnswerEvaluator.containsActionableMarker("点击导出日志按钮。")).isTrue();
        assertThat(HelpAnswerEvaluator.containsActionableMarker("很快就好")).isFalse();
    }

    private static List<HelpEvalCase> readFixture() throws Exception {
        InputStream stream = HelpAnswerEvaluatorTest.class.getResourceAsStream(FIXTURE_PATH);
        assertThat(stream).as("fixture %s must be on the test classpath", FIXTURE_PATH).isNotNull();
        try {
            return new ObjectMapper().readValue(stream, new TypeReference<List<HelpEvalCase>>() {
            });
        } finally {
            stream.close();
        }
    }

    private static HelpEvalReport report(String caseId) {
        HelpEvalReport report = reports.get(caseId);
        assertThat(report).as("missing report for case %s", caseId).isNotNull();
        return report;
    }

    private static HelpEvalCase findCase(String caseId) {
        for (HelpEvalCase evalCase : cases) {
            if (caseId.equals(evalCase.getId())) {
                return evalCase;
            }
        }
        throw new AssertionError("fixture is missing case " + caseId);
    }
}
