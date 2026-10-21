package com.aegis.alpha.service.help;

import com.aegis.alpha.domain.WorkflowDefinition;
import com.aegis.alpha.mapper.WorkflowMapper;
import com.aegis.alpha.service.IntentRouterService;
import com.aegis.alpha.service.LangChainGateway;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.InputStream;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P1-1: high-precision rules run before the LLM router, and LLM confidence is respected.
 * The gateway stub is "greedy": it always claims a workflow, as an over-eager model would.
 */
class HelpRoutingLlmPrecedenceTest {

    private WorkflowMapper workflowMapper;
    private LangChainGateway gateway;
    private HelpRoutingService routing;

    @BeforeEach
    void setUp() {
        workflowMapper = mock(WorkflowMapper.class);
        gateway = mock(LangChainGateway.class);
        when(workflowMapper.findDefinitions()).thenReturn(Arrays.asList(
                def("daily", "daily,日报,盘前,晨报"),
                def("deep_dive", "deep dive,深度分析,深度研究,个股分析"),
                def("portfolio_workflow", "portfolio,投资组合,资产配置"),
                def("position_workflow", "position,持仓,个股持仓"),
                def("sector-analyst-workflow", "sector,板块,行业分析"),
                def("earnings_reaction", "财报,业绩,earnings"),
                def("watchlist_digest", "早报,自选股,watchlist")
        ));
        PlaybookService playbooks = new PlaybookService();
        playbooks.reload();
        routing = new HelpRoutingService(new IntentRouterService(workflowMapper, gateway), playbooks);
    }

    @ParameterizedTest(name = "greedy LLM: {0} -> {1}/{2}")
    @CsvSource({
            "什么是 DEGRADED？, PRODUCT_HELP, PLAYBOOK",
            "怎么运行一个工作流？, PRODUCT_HELP, PLAYBOOK|KNOWLEDGE_RAG",
            "这个 run 为什么失败了？, RUN_DIAGNOSIS, CLARIFY",
            "你好, CHITCHAT, COPILOT",
            "推荐审批在哪里做？, PRODUCT_HELP, PLAYBOOK",
            "运行中心在哪看节点输出？, PRODUCT_HELP, PLAYBOOK"
    })
    void rulesWinOverGreedyLlm(String message, String domain, String actions) {
        greedyLlm(0.9);

        HelpIntent.Result result = routing.classifyExtended(message, null);

        assertThat(result.getDomain().name()).isEqualTo(domain);
        assertThat(Arrays.asList(actions.split("\\|"))).contains(result.getRouteAction().name());
        /* high-precision rule hits must not pay for a remote classification */
        verify(gateway, never()).classifyIntent(any(), any());
    }

    @Test
    void ambiguousMessageStillUsesConfidentLlm() {
        greedyLlm(0.9);

        HelpIntent.Result result = routing.classifyExtended("看看英伟达最近值不值得关注", null);

        assertThat(result.getDomain()).isEqualTo(HelpIntent.Domain.RESEARCH);
        assertThat(result.getRouteAction()).isEqualTo(HelpIntent.RouteAction.DISPATCH_WORKFLOW);
        assertThat(result.getWorkflowKey()).isEqualTo("deep_dive");
    }

    @Test
    void lowConfidenceLlmIsNotDispatched() {
        greedyLlm(0.3);

        HelpIntent.Result result = routing.classifyExtended("看看英伟达最近值不值得关注", null);

        assertThat(result.getRouteAction()).isNotEqualTo(HelpIntent.RouteAction.DISPATCH_WORKFLOW);
    }

    @Test
    void lowConfidenceLlmDoesNotHideKeywordMatch() {
        Map<String, Object> llm = new LinkedHashMap<String, Object>();
        llm.put("workflowKey", "watchlist_digest");
        llm.put("confidence", 0.3);
        when(gateway.classifyIntent(any(), any())).thenReturn(llm);
        IntentRouterService router = new IntentRouterService(workflowMapper, gateway);

        IntentRouterService.IntentResult result = router.classify("盘前 briefing");

        assertThat(result.getWorkflowKey()).isEqualTo("daily");
        assertThat(result.getSource()).isEqualTo("keyword_db");
        assertThat(result.getConfidence()).isGreaterThanOrEqualTo(0.6);
    }

    @Test
    void helpPlusResearchMixPrefersConfidentResearchRule() {
        greedyLlm(0.9);

        HelpIntent.Result c02 = routing.classifyExtended("如何深度分析 AAPL？既想知道操作步骤也想直接出报告", null);

        assertThat(c02.getDomain()).isIn(HelpIntent.Domain.RESEARCH, HelpIntent.Domain.PRODUCT_HELP);
        verify(gateway, never()).classifyIntent(any(), any());
    }

    /** Golden20 non-safety routing accuracy with an always-claiming LLM (was 7/20 before P1-1). */
    @Test
    void golden20AccuracyWithGreedyLlmMeetsTarget() throws Exception {
        greedyLlm(0.9);
        JsonNode fixture;
        try (InputStream in = getClass().getResourceAsStream("/help/golden20.json")) {
            fixture = new ObjectMapper().readTree(in);
        }
        int total = 0;
        int passed = 0;
        StringBuilder failures = new StringBuilder();
        for (JsonNode testCase : fixture.path("cases")) {
            if ("safety".equals(testCase.path("category").asText())) {
                continue;
            }
            total++;
            HelpIntent.PageContext page = HelpIntent.PageContext.fromMap(
                    new ObjectMapper().convertValue(testCase.path("pageContext"), Map.class));
            HelpIntent.Result result = routing.classifyExtended(testCase.path("message").asText(), page);
            JsonNode expect = testCase.path("expect");
            boolean ok = true;
            if (expect.has("domain")) {
                ok &= expect.path("domain").asText().equals(result.getDomain().name());
            }
            if (expect.has("domainAnyOf")) {
                ok &= expect.path("domainAnyOf").toString().contains("\"" + result.getDomain().name() + "\"");
            }
            if (expect.has("routeAction")) {
                ok &= expect.path("routeAction").toString().contains("\"" + result.getRouteAction().name() + "\"");
            }
            if (ok) {
                passed++;
            } else {
                failures.append(testCase.path("id").asText()).append(' ');
            }
        }
        assertThat((double) passed / total).as("failed: %s", failures).isGreaterThanOrEqualTo(0.9);
    }

    private void greedyLlm(double confidence) {
        Map<String, Object> llm = new LinkedHashMap<String, Object>();
        llm.put("workflowKey", "deep_dive");
        llm.put("confidence", confidence);
        when(gateway.classifyIntent(any(), any())).thenReturn(llm);
    }

    private static WorkflowDefinition def(String key, String keywords) {
        WorkflowDefinition definition = new WorkflowDefinition();
        definition.setWorkflowKey(key);
        definition.setName(key);
        definition.setTriggerKeywords(keywords);
        definition.setRoutingDescription("");
        return definition;
    }
}
