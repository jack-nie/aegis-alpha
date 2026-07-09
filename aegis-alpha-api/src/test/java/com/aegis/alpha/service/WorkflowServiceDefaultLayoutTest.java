package com.aegis.alpha.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.aegis.alpha.domain.WorkflowDefinition;
import com.aegis.alpha.mapper.AgentMapper;
import com.aegis.alpha.mapper.WorkflowMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Asserts research workflow default layouts match research_graph.py topology.
 */
class WorkflowServiceDefaultLayoutTest {
    private WorkflowMapper mapper;
    private WorkflowService service;

    @BeforeEach
    void setUp() {
        mapper = mock(WorkflowMapper.class);
        service = new WorkflowService(
                mapper,
                mock(AgentMapper.class),
                new ObjectMapper(),
                mock(LangChainGateway.class),
                mock(CacheService.class),
                mock(BacktestService.class),
                mock(AgentTraceService.class),
                new WorkflowValidationService());
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
