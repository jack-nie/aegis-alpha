package com.aegis.alpha.service.help.eval;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * One answer-level evaluation case for the Help Agent, loaded from
 * {@code classpath:help/eval/cases.json}.
 *
 * <p>A case pairs what the agent actually produced ({@code answer}, {@code chunks} retrieved,
 * {@code escalated}) with what it should have produced ({@code expectedEscalate} plus the
 * per-case metric floors). Thresholds default to the design-doc gates and can be relaxed per case,
 * for example escalation cases where grounding is not the behaviour under test.</p>
 */
public class HelpEvalCase {

    private String id;
    private String query;
    private String answer;
    private List<Map<String, Object>> chunks = new ArrayList<Map<String, Object>>();
    private boolean escalated;
    private boolean expectedEscalate;
    private double minFaithfulness = HelpAnswerEvaluator.DEFAULT_MIN_FAITHFULNESS;
    private double minRelevance = HelpAnswerEvaluator.DEFAULT_MIN_RELEVANCE;
    private double minTaskCompletion = HelpAnswerEvaluator.DEFAULT_MIN_TASK_COMPLETION;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    public String getAnswer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }

    /** Retrieved chunks, each carrying at least {@code title} and {@code text}. */
    public List<Map<String, Object>> getChunks() {
        return chunks == null ? Collections.<Map<String, Object>>emptyList() : chunks;
    }

    public void setChunks(List<Map<String, Object>> chunks) {
        this.chunks = chunks == null ? new ArrayList<Map<String, Object>>() : chunks;
    }

    /** Whether the agent escalated to a human. */
    public boolean isEscalated() {
        return escalated;
    }

    public void setEscalated(boolean escalated) {
        this.escalated = escalated;
    }

    /** Whether escalation was the correct behaviour for this query. */
    public boolean isExpectedEscalate() {
        return expectedEscalate;
    }

    public void setExpectedEscalate(boolean expectedEscalate) {
        this.expectedEscalate = expectedEscalate;
    }

    public double getMinFaithfulness() {
        return minFaithfulness;
    }

    public void setMinFaithfulness(double minFaithfulness) {
        this.minFaithfulness = minFaithfulness;
    }

    public double getMinRelevance() {
        return minRelevance;
    }

    public void setMinRelevance(double minRelevance) {
        this.minRelevance = minRelevance;
    }

    public double getMinTaskCompletion() {
        return minTaskCompletion;
    }

    public void setMinTaskCompletion(double minTaskCompletion) {
        this.minTaskCompletion = minTaskCompletion;
    }

    @Override
    public String toString() {
        return "HelpEvalCase{id=" + id + ", escalated=" + escalated
                + ", expectedEscalate=" + expectedEscalate + "}";
    }
}
