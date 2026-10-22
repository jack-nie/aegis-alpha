package com.aegis.alpha.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * P2-3: the one deterministic routing rule table (ticker stopwords + ordered regex rules),
 * loaded from {@code classpath:routing/routing-rules.json}. Used by IntentRouterService,
 * HelpRoutingService and ChatService; the orchestrator fallback is kept in sync by a test.
 */
public final class RoutingRules {
    public static final String RESOURCE = "routing/routing-rules.json";

    public static final class RegexRule {
        private final String workflowKey;
        private final Pattern pattern;
        private final boolean extractTicker;

        RegexRule(String workflowKey, Pattern pattern, boolean extractTicker) {
            this.workflowKey = workflowKey;
            this.pattern = pattern;
            this.extractTicker = extractTicker;
        }

        public String getWorkflowKey() { return workflowKey; }
        public Pattern getPattern() { return pattern; }
        public boolean isExtractTicker() { return extractTicker; }
    }

    private static final Set<String> TICKER_STOPWORDS;
    private static final List<RegexRule> REGEX_RULES;

    static {
        Set<String> stopwords = new LinkedHashSet<String>();
        List<RegexRule> rules = new ArrayList<RegexRule>();
        try (InputStream in = RoutingRules.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing " + RESOURCE);
            }
            JsonNode root = new ObjectMapper().readTree(in);
            for (JsonNode word : root.path("tickerStopwords")) {
                stopwords.add(word.asText().toUpperCase(Locale.ROOT));
            }
            for (JsonNode rule : root.path("regexRules")) {
                rules.add(new RegexRule(rule.path("workflowKey").asText(),
                        Pattern.compile(rule.path("pattern").asText()),
                        rule.path("extractTicker").asBoolean(true)));
            }
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Invalid " + RESOURCE, ex);
        }
        TICKER_STOPWORDS = Collections.unmodifiableSet(stopwords);
        REGEX_RULES = Collections.unmodifiableList(rules);
    }

    private RoutingRules() {
    }

    public static Set<String> tickerStopwords() {
        return TICKER_STOPWORDS;
    }

    public static boolean isTickerStopword(String symbol) {
        return symbol == null || TICKER_STOPWORDS.contains(symbol.toUpperCase(Locale.ROOT));
    }

    /** Ordered: earlier rules win (research keys before stock_analysis). */
    public static List<RegexRule> regexRules() {
        return REGEX_RULES;
    }
}
