package com.aegis.alpha.service.help;

import lombok.Getter;
import lombok.Setter;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Extended chat intent for Research / Product Help / Run Diagnosis routing (P0).
 */
public final class HelpIntent {

    private HelpIntent() {
    }

    public enum Domain {
        RESEARCH,
        PRODUCT_HELP,
        RUN_DIAGNOSIS,
        CHITCHAT,
        /** P2-7: unrelated to research / the product (poems, jokes, weather ...): polite decline. */
        OUT_OF_SCOPE
    }

    /** L0=playbook, L1=hybrid lexical retrieval (TF-IDF + keyword + RRF), L2=help graph / diagnosis */
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
        CLARIFY,
        /** P2-7: answered with a fixed decline template; no Copilot LLM call. */
        DECLINE
    }

    @Getter
    public static final class PageContext {
        private String path;
        private String runId;
        private String workflowKey;
        private String ticker;
        private String recommendationId;
        private String portfolioId;

        public static PageContext fromMap(Map<String, Object> map) {
            PageContext ctx = new PageContext();
            if (map == null) {
                return ctx;
            }
            ctx.path = string(map.get("path"));
            ctx.runId = firstNonEmpty(map, "runId", "run_id");
            ctx.workflowKey = firstNonEmpty(map, "workflowKey", "workflow_key");
            ctx.ticker = string(map.get("ticker"));
            ctx.recommendationId = firstNonEmpty(map, "recommendationId", "recommendation_id");
            ctx.portfolioId = firstNonEmpty(map, "portfolioId", "portfolio_id");
            return ctx;
        }

        private static String firstNonEmpty(Map<String, Object> map, String... keys) {
            for (String key : keys) {
                String value = string(map.get(key));
                if (!value.isEmpty()) {
                    return value;
                }
            }
            return "";
        }

        private static String string(Object value) {
            return value == null ? "" : String.valueOf(value).trim();
        }
    }

    @Getter
    @Setter
    public static final class Entities {
        private String runId;
        private String ticker;
        private String path;
        private String portfolioId;
        private String recommendationId;

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("runId", emptyToNull(runId));
            m.put("ticker", emptyToNull(ticker));
            m.put("path", emptyToNull(path));
            m.put("portfolioId", emptyToNull(portfolioId));
            m.put("recommendationId", emptyToNull(recommendationId));
            return m;
        }

        private static String emptyToNull(String value) {
            return value == null || value.trim().isEmpty() ? null : value.trim();
        }
    }

    /** P3-3: Lombok generates the plain accessors; null-normalizing entities/data accessors stay explicit. */
    @Getter
    @Setter
    public static final class Result {
        private String workflowKey;
        private String ticker;
        private double confidence;
        private String source = "none";
        private String reason;
        private Domain domain = Domain.CHITCHAT;
        private Complexity complexity = Complexity.L0;
        private RouteAction routeAction = RouteAction.COPILOT;
        private boolean needsClarification;
        private String clarificationPrompt;
        private Entities entities = new Entities();
        private boolean cacheable;
        private String playbookId;
        private Map<String, Object> data = new LinkedHashMap<String, Object>();

        public static Result empty() {
            return new Result();
        }

        public void setEntities(Entities entities) {
            this.entities = entities == null ? new Entities() : entities;
        }

        public Map<String, Object> getData() {
            return data == null ? Collections.<String, Object>emptyMap() : data;
        }

        public void setData(Map<String, Object> data) {
            this.data = data == null ? new LinkedHashMap<String, Object>() : data;
        }

        public Map<String, Object> toEnvelopeFields() {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("domain", domain == null ? Domain.CHITCHAT.name() : domain.name());
            m.put("complexity", complexity == null ? Complexity.L0.name() : complexity.name());
            m.put("routeAction", routeAction == null ? RouteAction.COPILOT.name() : routeAction.name());
            m.put("needsClarification", needsClarification);
            m.put("clarificationPrompt", clarificationPrompt);
            m.put("cacheable", cacheable);
            m.put("entities", entities == null ? Collections.emptyMap() : entities.toMap());
            if (playbookId != null && !playbookId.isEmpty()) {
                m.put("playbookId", playbookId);
            }
            if (reason != null && !reason.isEmpty()) {
                m.put("routeReason", reason);
            }
            return m;
        }
    }
}
