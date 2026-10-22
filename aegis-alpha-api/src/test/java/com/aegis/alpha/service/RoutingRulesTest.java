package com.aegis.alpha.service;

import com.aegis.alpha.domain.WorkflowDefinition;
import com.aegis.alpha.mapper.WorkflowMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P2-3: one rule table (replaces ChatServiceIntentRoutingTest, which covered the removed dead
 * ChatService.resolveWorkflowKey). Live keyword routing goes through IntentRouterService.
 */
class RoutingRulesTest {

    @Test
    void ruleTableLoadsInOrder() {
        assertThat(RoutingRules.tickerStopwords()).contains("AI", "ETF", "CEO").hasSize(13);
        assertThat(RoutingRules.regexRules()).extracting(RoutingRules.RegexRule::getWorkflowKey)
                .containsExactly("earnings_reaction", "watchlist_digest", "stock_analysis", "sector-analyst-workflow");
        assertThat(RoutingRules.isTickerStopword("etf")).isTrue();
        assertThat(RoutingRules.isTickerStopword("NVDA")).isFalse();
    }

    @Test
    void noDuplicatedStopwordTablesRemainInSources() throws IOException {
        Path root = Paths.get("src/main/java");
        List<String> offenders = new ArrayList<String>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).collect(Collectors.toList())) {
                String source = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                if (source.contains("\"CFO\", \"ESG\"") || source.contains("resolveWorkflowKey(")) {
                    offenders.add(file.toString());
                }
            }
        }
        assertThat(offenders).isEmpty();
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "NVDA 财报解读, earnings_reaction, NVDA",
            "给我自选股早报, watchlist_digest, ''",
            "分析一下 AAPL 这只股票, stock_analysis, AAPL",
            "半导体行业分析, sector-analyst-workflow, ''",
            "AI ETF 深度研究 TSLA, deep_dive, TSLA",
            "每日行情概览 日报, daily, ''"
    })
    void liveRulesRouteThroughIntentRouter(String message, String expectedKey, String expectedTicker) {
        WorkflowMapper mapper = mock(WorkflowMapper.class);
        when(mapper.findDefinitions()).thenReturn(Arrays.asList(
                def("daily", "日报,盘前,晨报,每日"),
                def("deep_dive", "deep dive,深度分析,深度研究"),
                def("stock_analysis", ""),
                def("sector-analyst-workflow", "sector,板块"),
                def("earnings_reaction", "财报,业绩"),
                def("watchlist_digest", "早报,自选股")));
        LangChainGateway gateway = mock(LangChainGateway.class);
        when(gateway.classifyIntent(any(), any())).thenThrow(new IllegalStateException("llm off"));
        IntentRouterService router = new IntentRouterService(mapper, gateway);

        IntentRouterService.IntentResult result = router.classifyByRules(message);

        assertThat(result.getWorkflowKey()).isEqualTo(expectedKey);
        assertThat(result.getTicker() == null ? "" : result.getTicker()).isEqualTo(expectedTicker);
    }

    private static WorkflowDefinition def(String key, String keywords) {
        WorkflowDefinition definition = new WorkflowDefinition();
        definition.setWorkflowKey(key);
        definition.setName(key);
        definition.setTriggerKeywords(keywords);
        return definition;
    }
}
