package com.aegis.alpha.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.aegis.alpha.domain.WorkflowDefinition;
import com.aegis.alpha.domain.WorkflowLayout;
import com.aegis.alpha.mapper.AgentMapper;
import com.aegis.alpha.mapper.WorkflowMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Asserts research workflow default layouts match research_graph.py topology
 * and layoutVersion-based read-path refresh policy.
 */
class WorkflowServiceDefaultLayoutTest {
    private WorkflowMapper mapper;
    private ObjectMapper objectMapper;
    private WorkflowService service;

    @BeforeEach
    void setUp() {
        mapper = mock(WorkflowMapper.class);
        objectMapper = new ObjectMapper();
        service = new WorkflowService(
                mapper,
                mock(AgentMapper.class),
                objectMapper,
                mock(LangChainGateway.class),
                mock(CacheService.class),
                mock(BacktestService.class),
                mock(AgentTraceService.class),
                new WorkflowValidationService(),
                mock(TokenService.class),
                false);
    }

    @Test
    void stockAnalysisDefaultLayoutIsSequentialMultiSpecialist() {
        stubDefinition("stock_analysis", "Stock Analysis");

        Map<String, Object> layout = service.layout("stock_analysis");

        assertThat(nodeIds(layout)).containsExactly(
                "start", "fundamentals", "news", "valuation", "risk", "aggregate", "end");
        assertThat(handlers(layout)).containsExactly(
                "scheduler.manual",
                "finance.fundamental_analysis",
                "finance.industry_news",
                "finance.valuation_analysis",
                "finance.risk_assessment",
                "finance.stock_recommendation_aggregate",
                "workflow.end");
        assertThat(edgeCount(layout)).isEqualTo(6);
        assertResearchMetadata(layout, "fan_in_aggregate");
    }

    @Test
    void earningsReactionDefaultLayoutHasAggregateHandler() {
        stubDefinition("earnings_reaction", "Earnings Reaction");

        Map<String, Object> layout = service.layout("earnings_reaction");

        assertThat(nodeIds(layout)).containsExactly(
                "start", "market_analysis", "financial_interpretation", "industry_news", "aggregate", "end");
        assertThat(handlers(layout)).contains(
                "finance.market_analysis",
                "finance.financial_interpretation",
                "finance.industry_news",
                "finance.stock_recommendation_aggregate");
        assertThat(edgeCount(layout)).isEqualTo(5);
        assertResearchMetadata(layout, "earnings_reaction");
    }

    @Test
    void watchlistDigestDefaultLayoutIsLightweightWithoutAggregate() {
        stubDefinition("watchlist_digest", "Watchlist Morning Digest");

        Map<String, Object> layout = service.layout("watchlist_digest");

        assertThat(nodeIds(layout)).containsExactly(
                "start", "market_analysis", "industry_news", "risk_assessment", "end");
        assertThat(handlers(layout)).contains(
                "finance.market_analysis",
                "finance.industry_news",
                "finance.risk_assessment");
        assertThat(handlers(layout)).doesNotContain("finance.stock_recommendation_aggregate");
        assertThat(edgeCount(layout)).isEqualTo(4);
        assertResearchMetadata(layout, "watchlist_digest");
    }

    @Test
    void researchDefaultsExposeLayoutVersionTwo() {
        for (String key : Arrays.asList("stock_analysis", "earnings_reaction", "watchlist_digest")) {
            stubDefinition(key, key);
            Map<String, Object> layout = service.layout(key);
            assertThat(layoutVersion(layout))
                    .as("layoutVersion for %s", key)
                    .isEqualTo(WorkflowService.CURRENT_RESEARCH_LAYOUT_VERSION);
        }
    }

    @Test
    void staleResearchLayoutWithoutVersionIsIgnored() throws Exception {
        stubDefinition("stock_analysis", "Stock Analysis");
        WorkflowLayout stale = new WorkflowLayout();
        stale.setWorkflowKey("stock_analysis");
        stale.setLayoutJson("{"
                + "\"workflowKey\":\"stock_analysis\","
                + "\"nodes\":[{\"id\":\"start\",\"type\":\"workflowNode\","
                + "\"data\":{\"handler\":\"scheduler.manual\",\"label\":\"Start\",\"nodeType\":\"start\"}}],"
                + "\"edges\":[]"
                + "}");
        stale.setUpdatedAt("2026-01-01 00:00:00");
        when(mapper.findLayout("stock_analysis")).thenReturn(stale);

        Map<String, Object> layout = service.layout("stock_analysis");

        assertThat(nodeIds(layout)).containsExactly(
                "start", "fundamentals", "news", "valuation", "risk", "aggregate", "end");
        assertThat(layoutVersion(layout)).isEqualTo(2);
        assertThat(layout.get("updatedAt")).isEqualTo("2026-01-01 00:00:00");
    }

    @Test
    void researchLayoutWithVersionTwoIsPreserved() throws Exception {
        stubDefinition("stock_analysis", "Stock Analysis");
        Map<String, Object> stored = new LinkedHashMap<String, Object>();
        stored.put("workflowKey", "stock_analysis");
        stored.put("engine", "langgraph");
        List<Map<String, Object>> nodes = new ArrayList<Map<String, Object>>();
        nodes.add(simpleNode("start", "scheduler.manual", "Start", "start"));
        nodes.add(simpleNode("end", "workflow.end", "End", "end"));
        stored.put("nodes", nodes);
        List<Map<String, Object>> edges = new ArrayList<Map<String, Object>>();
        Map<String, Object> edge = new LinkedHashMap<String, Object>();
        edge.put("id", "start-end");
        edge.put("source", "start");
        edge.put("target", "end");
        edges.add(edge);
        stored.put("edges", edges);
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put("layoutVersion", 2);
        metadata.put("pattern", "fan_in_aggregate");
        stored.put("metadata", metadata);

        WorkflowLayout record = new WorkflowLayout();
        record.setWorkflowKey("stock_analysis");
        record.setLayoutJson(objectMapper.writeValueAsString(stored));
        record.setUpdatedAt("2026-07-01 12:00:00");
        when(mapper.findLayout("stock_analysis")).thenReturn(record);

        Map<String, Object> layout = service.layout("stock_analysis");

        assertThat(nodeIds(layout)).containsExactly("start", "end");
        assertThat(layoutVersion(layout)).isEqualTo(2);
    }

    @Test
    void customResearchLayoutIsNeverAutoRefreshed() throws Exception {
        stubDefinition("earnings_reaction", "Earnings Reaction");
        Map<String, Object> stored = new LinkedHashMap<String, Object>();
        stored.put("workflowKey", "earnings_reaction");
        stored.put("engine", "langgraph");
        List<Map<String, Object>> nodes = new ArrayList<Map<String, Object>>();
        nodes.add(simpleNode("start", "scheduler.manual", "Start", "start"));
        nodes.add(simpleNode("custom_only", "general.agent", "Custom", "agent"));
        nodes.add(simpleNode("end", "workflow.end", "End", "end"));
        stored.put("nodes", nodes);
        stored.put("edges", Collections.emptyList());
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put("custom", true);
        stored.put("metadata", metadata);

        WorkflowLayout record = new WorkflowLayout();
        record.setWorkflowKey("earnings_reaction");
        record.setLayoutJson(objectMapper.writeValueAsString(stored));
        when(mapper.findLayout("earnings_reaction")).thenReturn(record);

        Map<String, Object> layout = service.layout("earnings_reaction");

        assertThat(nodeIds(layout)).containsExactly("start", "custom_only", "end");
    }

    private static Map<String, Object> simpleNode(String id, String handler, String label, String nodeType) {
        Map<String, Object> node = new LinkedHashMap<String, Object>();
        node.put("id", id);
        node.put("type", "workflowNode");
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("handler", handler);
        data.put("label", label);
        data.put("nodeType", nodeType);
        node.put("data", data);
        return node;
    }

    private void stubDefinition(String workflowKey, String name) {
        WorkflowDefinition definition = new WorkflowDefinition();
        definition.setWorkflowKey(workflowKey);
        definition.setName(name);
        definition.setEngine("langgraph");
        when(mapper.findDefinition(workflowKey)).thenReturn(definition);
        when(mapper.findLayout(workflowKey)).thenReturn(null);
    }

    @SuppressWarnings("unchecked")
    private static void assertResearchMetadata(Map<String, Object> layout, String expectedPattern) {
        Map<String, Object> metadata = (Map<String, Object>) layout.get("metadata");
        assertThat(metadata).isNotNull();
        assertThat(metadata.get("layoutVersion")).isEqualTo(WorkflowService.CURRENT_RESEARCH_LAYOUT_VERSION);
        assertThat(metadata.get("pattern")).isEqualTo(expectedPattern);
        assertThat(metadata.get("custom")).isEqualTo(false);
    }

    @SuppressWarnings("unchecked")
    private static int layoutVersion(Map<String, Object> layout) {
        Map<String, Object> metadata = (Map<String, Object>) layout.get("metadata");
        Object version = metadata == null ? null : metadata.get("layoutVersion");
        if (version instanceof Number) {
            return ((Number) version).intValue();
        }
        return version == null ? 0 : Integer.parseInt(String.valueOf(version));
    }

    @SuppressWarnings("unchecked")
    private static List<String> nodeIds(Map<String, Object> layout) {
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) layout.get("nodes");
        return nodes.stream().map(n -> String.valueOf(n.get("id"))).collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    private static List<String> handlers(Map<String, Object> layout) {
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) layout.get("nodes");
        return nodes.stream()
                .map(n -> (Map<String, Object>) n.get("data"))
                .map(data -> String.valueOf(data.get("handler")))
                .collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    private static int edgeCount(Map<String, Object> layout) {
        return ((List<?>) layout.get("edges")).size();
    }
}
