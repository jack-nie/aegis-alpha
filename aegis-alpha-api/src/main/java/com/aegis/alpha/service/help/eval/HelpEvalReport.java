package com.aegis.alpha.service.help.eval;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Immutable per-case result produced by {@link HelpAnswerEvaluator}.
 *
 * <p>{@code reasons} explains every failed gate in human-readable form and is empty only when the
 * case passed, so a CI failure can be diagnosed without re-running the evaluation.</p>
 */
public final class HelpEvalReport {

    private final String caseId;
    private final double faithfulness;
    private final double relevance;
    private final double taskCompletion;
    private final boolean escalationCorrect;
    private final boolean pass;
    private final List<String> reasons;

    public HelpEvalReport(String caseId, double faithfulness, double relevance, double taskCompletion,
                          boolean escalationCorrect, boolean pass, List<String> reasons) {
        this.caseId = caseId;
        this.faithfulness = faithfulness;
        this.relevance = relevance;
        this.taskCompletion = taskCompletion;
        this.escalationCorrect = escalationCorrect;
        this.pass = pass;
        this.reasons = reasons == null
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(reasons));
    }

    public String getCaseId() {
        return caseId;
    }

    /** Fraction of distinct answer tokens grounded in the retrieved chunks. */
    public double getFaithfulness() {
        return faithfulness;
    }

    /** Fraction of distinct query tokens present in the retrieved chunks. */
    public double getRelevance() {
        return relevance;
    }

    /** 1.0 grounded + sourced, 0.5 actionable-but-unsourced, 0.0 otherwise. */
    public double getTaskCompletion() {
        return taskCompletion;
    }

    public boolean isEscalationCorrect() {
        return escalationCorrect;
    }

    /** True only when every threshold is met and escalation matched expectation. */
    public boolean isPass() {
        return pass;
    }

    /** Empty when the case passed; otherwise one entry per failed gate. */
    public List<String> getReasons() {
        return reasons;
    }

    @Override
    public String toString() {
        return "HelpEvalReport{caseId=" + caseId
                + ", faithfulness=" + format(faithfulness)
                + ", relevance=" + format(relevance)
                + ", taskCompletion=" + format(taskCompletion)
                + ", escalationCorrect=" + escalationCorrect
                + ", pass=" + pass
                + ", reasons=" + reasons + "}";
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }
}
