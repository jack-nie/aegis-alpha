package com.aegis.alpha.service.help;

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

        public String getPath() {
            return path;
        }

        public String getRunId() {
            return runId;
        }

        public String getWorkflowKey() {
            return workflowKey;
        }

        public String getTicker() {
            return ticker;
        }

        public String getRecommendationId() {
            return recommendationId;
        }

        public String getPortfolioId() {
            return portfolioId;
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

    public static final class Entities {
        private String runId;
        private String ticker;
        private String path;
        private String portfolioId;
        private String recommendationId;

        public String getRunId() {
            return runId;
        }

        public void setRunId(String runId) {
            this.runId = runId;
        }

        public String getTicker() {
            return ticker;
        }

        public void setTicker(String ticker) {
            this.ticker = ticker;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public String getPortfolioId() {
            return portfolioId;
        }

        public void setPortfolioId(String portfolioId) {
            this.portfolioId = portfolioId;
        }

        public String getRecommendationId() {
            return recommendationId;
        }

        public void setRecommendationId(String recommendationId) {
            this.recommendationId = recommendationId;
        }

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

        public String getWorkflowKey() {
            return workflowKey;
        }

        public void setWorkflowKey(String workflowKey) {
            this.workflowKey = workflowKey;
        }

        public String getTicker() {
            return ticker;
        }

        public void setTicker(String ticker) {
            this.ticker = ticker;
        }

        public double getConfidence() {
            return confidence;
        }

        public void setConfidence(double confidence) {
            this.confidence = confidence;
        }

        public String getSource() {
            return source;
        }

        public void setSource(String source) {
            this.source = source;
        }

        public String getReason() {
            return reason;
        }

        public void setReason(String reason) {
            this.reason = reason;
        }

        public Domain getDomain() {
            return domain;
        }

        public void setDomain(Domain domain) {
            this.domain = domain;
        }

        public Complexity getComplexity() {
            return complexity;
        }

        public void setComplexity(Complexity complexity) {
            this.complexity = complexity;
        }

        public RouteAction getRouteAction() {
            return routeAction;
        }

        public void setRouteAction(RouteAction routeAction) {
            this.routeAction = routeAction;
        }

        public boolean isNeedsClarification() {
            return needsClarification;
        }

        public void setNeedsClarification(boolean needsClarification) {
            this.needsClarification = needsClarification;
        }

        public String getClarificationPrompt() {
            return clarificationPrompt;
        }

        public void setClarificationPrompt(String clarificationPrompt) {
            this.clarificationPrompt = clarificationPrompt;
        }

        public Entities getEntities() {
            return entities;
        }

        public void setEntities(Entities entities) {
            this.entities = entities == null ? new Entities() : entities;
        }

        public boolean isCacheable() {
            return cacheable;
        }

        public void setCacheable(boolean cacheable) {
            this.cacheable = cacheable;
        }

        public String getPlaybookId() {
            return playbookId;
        }

        public void setPlaybookId(String playbookId) {
            this.playbookId = playbookId;
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
