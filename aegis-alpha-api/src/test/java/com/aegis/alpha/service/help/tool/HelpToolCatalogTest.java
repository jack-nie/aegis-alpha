package com.aegis.alpha.service.help.tool;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HelpToolCatalogTest {

    private final HelpToolCatalog catalog = new HelpToolCatalog();

    @Test
    void exposesExactlyTheSixReadOnlyHelpTools() {
        assertThat(catalog.size()).isEqualTo(6);
        assertThat(catalog.names()).containsExactlyInAnyOrder(
                "search_product_docs",
                "get_workflow_run",
                "get_workflow_run_nodes",
                "get_workflow_run_events",
                "get_portfolio_positions",
                "get_stock_quote");
    }

    @Test
    void everyDefinitionIsReadOnlyAndBounded() {
        List<HelpToolDefinition> definitions = catalog.all();
        assertThat(definitions).hasSize(6);
        for (HelpToolDefinition definition : definitions) {
            assertThat(definition.isReadOnly()).as("readOnly of %s", definition.getName()).isTrue();
            assertThat(definition.getTimeoutMs()).as("timeoutMs of %s", definition.getName()).isGreaterThan(0L);
            assertThat(definition.getMaxRetries()).as("maxRetries of %s", definition.getName())
                    .isGreaterThanOrEqualTo(0);
            assertThat(definition.getDescription()).as("description of %s", definition.getName()).isNotEmpty();
            assertThat(definition.getCategory()).as("category of %s", definition.getName()).isNotEmpty();
        }
    }

    @Test
    void exposesNoWriteOrAdminTools() {
        assertThat(catalog.names()).doesNotContain(
                "approve_recommendation",
                "reject_recommendation",
                "create_portfolio_trade",
                "execute_sql",
                "execute_query",
                "admin_users",
                "delete_portfolio");
        for (String name : catalog.names()) {
            String lower = name.toLowerCase(Locale.ROOT);
            assertThat(lower).doesNotContain("approve");
            assertThat(lower).doesNotContain("reject");
            assertThat(lower).doesNotContain("delete");
            assertThat(lower).doesNotContain("sql");
            assertThat(lower).doesNotContain("admin");
        }
    }

    @Test
    void findResolvesAllowlistedToolsOnly() {
        assertThat(catalog.contains("get_stock_quote")).isTrue();
        HelpToolDefinition quote = catalog.find("get_stock_quote");
        assertThat(quote).isNotNull();
        assertThat(quote.getCategory()).isEqualTo(HelpToolCatalog.CATEGORY_MARKET);
        assertThat(quote.isReadOnly()).isTrue();
        assertThat(quote.getMaxRetries()).isEqualTo(2);

        assertThat(catalog.contains("search_product_docs")).isTrue();
        assertThat(catalog.find("approve_recommendation")).isNull();
        assertThat(catalog.find("unknown_tool")).isNull();
        assertThat(catalog.find(null)).isNull();
    }

    @Test
    void definitionsSerializeForTheToolsEndpoint() {
        Map<String, Object> asMap = catalog.find("get_workflow_run").toMap();
        assertThat(asMap).containsKeys("name", "description", "readOnly", "timeoutMs", "maxRetries", "category");
        assertThat(asMap.get("name")).isEqualTo("get_workflow_run");
        assertThat(asMap.get("readOnly")).isEqualTo(true);
        assertThat(asMap.get("category")).isEqualTo(HelpToolCatalog.CATEGORY_WORKFLOW);
    }

    @Test
    void rejectsDefinitionsWithoutNameOrPositiveTimeout() {
        assertThatThrownBy(() -> new HelpToolDefinition(" ", "desc", true, 1000L, 0, "test"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HelpToolDefinition("tool", "desc", true, 0L, 0, "test"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HelpToolDefinition("tool", "desc", true, 1000L, -1, "test"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void customCatalogCanExposeWriteToolForGuardTests() {
        HelpToolDefinition writeTool = new HelpToolDefinition("approve_recommendation",
                "Approve a recommendation", false, 1000L, 0, "governance");
        HelpToolCatalog custom = new HelpToolCatalog(Arrays.asList(writeTool));

        assertThat(custom.size()).isEqualTo(1);
        assertThat(custom.contains("approve_recommendation")).isTrue();
        assertThat(custom.find("approve_recommendation").isReadOnly()).isFalse();
        assertThat(custom.find("approve_recommendation").toMap()).containsEntry("readOnly", false);
    }
}
