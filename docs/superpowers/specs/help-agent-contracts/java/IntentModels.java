package com.aegis.alpha.design.help;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Design-normative intent models for Help / Support routing.
 * Port into com.aegis.alpha.service (or domain) during P0 — do not compile from docs/.
 */
public final class IntentModels {

    private IntentModels() {
    }

    public enum Domain {
        RESEARCH,
        PRODUCT_HELP,
        RUN_DIAGNOSIS,
        CHITCHAT
    }

    /** L0=playbook, L1=vanilla RAG, L2=help_graph */
    public enum Complexity {
        L0,
        L1,
        L2
    }

    public enum RouteAction {
        DISPATCH_WORKFLOW,
        PLAYBOOK,
        KNOWLEDGE_RAG,
        HELP_GRAPH,
        COPILOT,
        CLARIFY
    }

    public static final class PageContext {
        public String path;
        public String runId;
        public String workflowKey;
        public String ticker;
        public String recommendationId;
        public String portfolioId;
    }

    public static final class Entities {
        public String runId;
        public String ticker;
        public String path;
        public String portfolioId;
        public String recommendationId;
    }

    /**
     * Extended intent result. Existing IntentRouterService.IntentResult remains for
     * workflowKey-only callers; ChatService should prefer this shape (Map or class).
     */
    public static final class ExtendedIntentResult {
        public String workflowKey;
        public String ticker;
        public double confidence;
        public String source;
        public String reason;
        public Domain domain = Domain.CHITCHAT;
        public Complexity complexity = Complexity.L0;
        public RouteAction routeAction = RouteAction.COPILOT;
        public boolean needsClarification;
        public String clarificationPrompt;
        public Entities entities = new Entities();
        public boolean cacheable;
        public Map<String, Object> data = new LinkedHashMap<String, Object>();

        public static ExtendedIntentResult empty() {
            return new ExtendedIntentResult();
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("workflowKey", workflowKey);
            m.put("ticker", ticker);
            m.put("confidence", confidence);
            m.put("source", source);
            m.put("reason", reason);
            m.put("domain", domain == null ? null : domain.name());
            m.put("complexity", complexity == null ? null : complexity.name());
            m.put("routeAction", routeAction == null ? null : routeAction.name());
            m.put("needsClarification", needsClarification);
            m.put("clarificationPrompt", clarificationPrompt);
            m.put("cacheable", cacheable);
            if (entities != null) {
                Map<String, Object> e = new LinkedHashMap<String, Object>();
                e.put("runId", entities.runId);
                e.put("ticker", entities.ticker);
                e.put("path", entities.path);
                e.put("portfolioId", entities.portfolioId);
                e.put("recommendationId", entities.recommendationId);
                m.put("entities", e);
            }
            m.put("data", data == null ? Collections.emptyMap() : data);
            return m;
        }
    }
}
