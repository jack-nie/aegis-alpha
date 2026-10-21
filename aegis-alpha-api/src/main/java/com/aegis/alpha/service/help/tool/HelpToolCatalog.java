package com.aegis.alpha.service.help.tool;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Read-only Help tool catalog (Help role allowlist).
 *
 * <p>Exactly six tools are published. Approve/reject recommendation, portfolio write,
 * arbitrary SQL and admin user tools are intentionally absent. The {@link HelpToolGateway}
 * only invokes actions for definitions marked {@code readOnly = true}.</p>
 */
@Component
public class HelpToolCatalog {
    public static final String SEARCH_PRODUCT_DOCS = "search_product_docs";
    public static final String GET_WORKFLOW_RUN = "get_workflow_run";
    public static final String GET_WORKFLOW_RUN_NODES = "get_workflow_run_nodes";
    public static final String GET_WORKFLOW_RUN_EVENTS = "get_workflow_run_events";
    public static final String GET_PORTFOLIO_POSITIONS = "get_portfolio_positions";
    public static final String GET_STOCK_QUOTE = "get_stock_quote";

    public static final String CATEGORY_KNOWLEDGE = "knowledge";
    public static final String CATEGORY_WORKFLOW = "workflow";
    public static final String CATEGORY_PORTFOLIO = "portfolio";
    public static final String CATEGORY_MARKET = "market";

    private final Map<String, HelpToolDefinition> definitions;

    /** Production catalog: the six read-only Help tools. */
    public HelpToolCatalog() {
        this(defaultDefinitions());
    }

    /**
     * Custom catalog. Used by tests to prove that the gateway refuses non-read-only
     * definitions; production wiring must only pass read-only definitions.
     */
    public HelpToolCatalog(Collection<HelpToolDefinition> definitions) {
        Map<String, HelpToolDefinition> byName = new LinkedHashMap<String, HelpToolDefinition>();
        if (definitions != null) {
            for (HelpToolDefinition definition : definitions) {
                if (definition != null) {
                    byName.put(definition.getName(), definition);
                }
            }
        }
        this.definitions = Collections.unmodifiableMap(byName);
    }

    public static List<HelpToolDefinition> defaultDefinitions() {
        List<HelpToolDefinition> tools = new ArrayList<HelpToolDefinition>();
        tools.add(new HelpToolDefinition(SEARCH_PRODUCT_DOCS,
                "Search public product documentation and playbooks.",
                true, 3000L, 1, CATEGORY_KNOWLEDGE));
        tools.add(new HelpToolDefinition(GET_WORKFLOW_RUN,
                "Read a workflow run by runId (owner scoped).",
                true, 2000L, 1, CATEGORY_WORKFLOW));
        tools.add(new HelpToolDefinition(GET_WORKFLOW_RUN_NODES,
                "List node executions of a workflow run.",
                true, 3000L, 1, CATEGORY_WORKFLOW));
        tools.add(new HelpToolDefinition(GET_WORKFLOW_RUN_EVENTS,
                "List timeline events of a workflow run.",
                true, 3000L, 1, CATEGORY_WORKFLOW));
        tools.add(new HelpToolDefinition(GET_PORTFOLIO_POSITIONS,
                "Read portfolio positions for the authenticated principal.",
                true, 2000L, 1, CATEGORY_PORTFOLIO));
        tools.add(new HelpToolDefinition(GET_STOCK_QUOTE,
                "Read the latest quote snapshot for a ticker.",
                true, 2500L, 2, CATEGORY_MARKET));
        return tools;
    }

    /** @return definition for {@code name} or {@code null} when the tool is not allowlisted. */
    public HelpToolDefinition find(String name) {
        return name == null ? null : definitions.get(name.trim());
    }

    public boolean contains(String name) {
        return find(name) != null;
    }

    public List<HelpToolDefinition> all() {
        return Collections.unmodifiableList(new ArrayList<HelpToolDefinition>(definitions.values()));
    }

    public Set<String> names() {
        return Collections.unmodifiableSet(definitions.keySet());
    }

    public int size() {
        return definitions.size();
    }
}
