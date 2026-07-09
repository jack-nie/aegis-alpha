package com.aegis.alpha.service;

import com.aegis.alpha.domain.WorkflowDefinition;
import com.aegis.alpha.mapper.WorkflowMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.AbstractMap.SimpleEntry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class IntentRouterServiceTest {
    private WorkflowMapper workflowMapper;
    private LangChainGateway langChainGateway;
    private IntentRouterService service;

    @BeforeEach
    void setUp() {
        workflowMapper = mock(WorkflowMapper.class);
        langChainGateway = mock(LangChainGateway.class);
        service = new IntentRouterService(workflowMapper, langChainGateway);
    }

    private WorkflowDefinition def(String key, String name, String keywords) {
        WorkflowDefinition d = new WorkflowDefinition();
        d.setWorkflowKey(key);
        d.setName(name);
        d.setTriggerKeywords(keywords);
        d.setRoutingDescription("");
        return d;
    }

    @Test
    void classifyReturnsEmptyForNullMessage() {
        when(workflowMapper.findDefinitions()).thenReturn(Arrays.asList(def("daily", "Daily", "daily,日报")));

        IntentRouterService.IntentResult result = service.classify(null);

        assertThat(result.getWorkflowKey()).isNull();
        assertThat(result.getSource()).isEqualTo("none");
    }

    @Test
    void classifyReturnsEmptyForEmptyMessage() {
        when(workflowMapper.findDefinitions()).thenReturn(Arrays.asList(def("daily", "Daily", "daily")));

        IntentRouterService.IntentResult result = service.classify("  ");

        assertThat(result.getWorkflowKey()).isNull();
    }

    @Test
    void classifyReturnsEmptyIfNoDefinitions() {
        when(workflowMapper.findDefinitions()).thenReturn(Collections.emptyList());

        IntentRouterService.IntentResult result = service.classify("daily briefing");

        assertThat(result.getWorkflowKey()).isNull();
    }

    @Test
    void classifyByKeywordMatching() {
        when(workflowMapper.findDefinitions()).thenReturn(Arrays.asList(
                def("daily", "Daily Briefing", "daily,日报,行情"),
                def("deep_dive", "Deep Dive", "deep dive,深度分析")
        ));
        when(langChainGateway.classifyIntent(any(), any())).thenThrow(new RuntimeException("LLM unavailable"));

        IntentRouterService.IntentResult result = service.classify("daily morning briefing");

        assertThat(result.getWorkflowKey()).isEqualTo("daily");
        assertThat(result.getSource()).isEqualTo("keyword_db");
        assertThat(result.getConfidence()).isEqualTo(0.6);
    }

    @Test
    void classifyByKeywordPicksLongestMatch() {
        when(workflowMapper.findDefinitions()).thenReturn(Arrays.asList(
                def("daily", "Daily Briefing", "daily"),
                def("daily_extended", "Daily Extended", "daily morning")
        ));
        when(langChainGateway.classifyIntent(any(), any())).thenThrow(new RuntimeException("LLM unavailable"));

        IntentRouterService.IntentResult result = service.classify("daily morning report");

        assertThat(result.getWorkflowKey()).isEqualTo("daily_extended");
    }

    @Test
    void classifyByLlmWhenAvailable() {
        when(workflowMapper.findDefinitions()).thenReturn(Arrays.asList(
                def("deep_dive", "Deep Dive", "deep dive")
        ));

        Map<String, Object> llmResult = new HashMap<>();
        llmResult.put("workflowKey", "deep_dive");
        llmResult.put("ticker", "AAPL");
        llmResult.put("confidence", 0.95);
        when(langChainGateway.classifyIntent(any(), any())).thenReturn(llmResult);

        IntentRouterService.IntentResult result = service.classify("analyze AAPL stock");

        assertThat(result.getWorkflowKey()).isEqualTo("deep_dive");
        assertThat(result.getTicker()).isEqualTo("AAPL");
        assertThat(result.getSource()).isEqualTo("llm");
        assertThat(result.getConfidence()).isEqualTo(0.95);
    }

    @Test
    void classifyByLlmAcceptsSnakeCaseWorkflowKey() {
        when(workflowMapper.findDefinitions()).thenReturn(Arrays.asList(
                def("stock_analysis", "Stock Analysis", "stock")
        ));

        Map<String, Object> llmResult = new HashMap<>();
        llmResult.put("workflow_key", "stock_analysis");
        llmResult.put("ticker", "AAPL");
        llmResult.put("confidence", 0.9);
        when(langChainGateway.classifyIntent(any(), any())).thenReturn(llmResult);

        IntentRouterService.IntentResult result = service.classify("analyze AAPL");

        assertThat(result.getWorkflowKey()).isEqualTo("stock_analysis");
        assertThat(result.getTicker()).isEqualTo("AAPL");
        assertThat(result.getSource()).isEqualTo("llm");
    }

    @Test
    void classifyLlmReturnsNullKeyFallsBackToKeywords() {
        when(workflowMapper.findDefinitions()).thenReturn(Arrays.asList(
                def("daily", "Daily", "daily")
        ));

        Map<String, Object> llmResult = new HashMap<>();
        llmResult.put("workflowKey", "");
        when(langChainGateway.classifyIntent(any(), any())).thenReturn(llmResult);

        IntentRouterService.IntentResult result = service.classify("daily briefing today");

        assertThat(result.getWorkflowKey()).isEqualTo("daily");
        assertThat(result.getSource()).isEqualTo("keyword_db");
    }

    @Test
    void classifyLlmThrowsFallsBackToKeywords() {
        when(workflowMapper.findDefinitions()).thenReturn(Arrays.asList(
                def("daily", "Daily", "daily")
        ));
        when(langChainGateway.classifyIntent(any(), any())).thenThrow(new RuntimeException("timeout"));

        IntentRouterService.IntentResult result = service.classify("daily summary");

        assertThat(result.getWorkflowKey()).isEqualTo("daily");
        assertThat(result.getSource()).isEqualTo("keyword_db");
    }

    private List<WorkflowDefinition> researchDefinitions() {
        return Arrays.asList(
                def("earnings_reaction", "Earnings Reaction",
                        "earnings,财报,业绩,季报,earnings reaction,earnings call,业绩会,财报解读"),
                def("watchlist_digest", "Watchlist Morning Digest",
                        "digest,早报,自选,watchlist,morning digest,自选股,晨间摘要,watchlist digest"),
                def("stock_analysis", "Stock Analysis",
                        "股票分析,综合分析,帮我分析,分析一下,stock analysis,comprehensive analysis"),
                def("sector-analyst-workflow", "Sector Analyst",
                        "板块,行业,行业分析,sector,industry analysis,sector analyst"),
                def("daily", "Daily Briefing", "daily,日报,行情")
        );
    }

    @Test
    void classifyEarningsReactionChineseByRegex() {
        when(workflowMapper.findDefinitions()).thenReturn(researchDefinitions());
        when(langChainGateway.classifyIntent(any(), any())).thenThrow(new RuntimeException("unavailable"));

        IntentRouterService.IntentResult result = service.classify("帮我看看 AAPL 财报反应");

        assertThat(result.getWorkflowKey()).isEqualTo("earnings_reaction");
        assertThat(result.getSource()).isEqualTo("keyword_regex");
        assertThat(result.getTicker()).isEqualTo("AAPL");
        assertThat(result.getConfidence()).isEqualTo(0.7);
    }

    @Test
    void classifyEarningsReactionEnglishByRegex() {
        when(workflowMapper.findDefinitions()).thenReturn(researchDefinitions());
        when(langChainGateway.classifyIntent(any(), any())).thenThrow(new RuntimeException("unavailable"));

        IntentRouterService.IntentResult result = service.classify("earnings report for MSFT");

        assertThat(result.getWorkflowKey()).isEqualTo("earnings_reaction");
        assertThat(result.getSource()).isEqualTo("keyword_regex");
        assertThat(result.getTicker()).isEqualTo("MSFT");
    }

    @Test
    void classifyEarningsReactionPrefersRegexOverStockAnalysis() {
        when(workflowMapper.findDefinitions()).thenReturn(researchDefinitions());
        when(langChainGateway.classifyIntent(any(), any())).thenThrow(new RuntimeException("unavailable"));

        // contains both 分析一下 (stock_analysis) and 业绩 (earnings) — earnings regex runs first
        IntentRouterService.IntentResult result = service.classify("分析一下 NVDA 业绩");

        assertThat(result.getWorkflowKey()).isEqualTo("earnings_reaction");
        assertThat(result.getSource()).isEqualTo("keyword_regex");
    }

    @Test
    void classifyWatchlistDigestChineseByRegex() {
        when(workflowMapper.findDefinitions()).thenReturn(researchDefinitions());
        when(langChainGateway.classifyIntent(any(), any())).thenThrow(new RuntimeException("unavailable"));

        IntentRouterService.IntentResult result = service.classify("生成今日自选早报");

        assertThat(result.getWorkflowKey()).isEqualTo("watchlist_digest");
        assertThat(result.getSource()).isEqualTo("keyword_regex");
        assertThat(result.getConfidence()).isEqualTo(0.7);
    }

    @Test
    void classifyWatchlistDigestEnglishByRegex() {
        when(workflowMapper.findDefinitions()).thenReturn(researchDefinitions());
        when(langChainGateway.classifyIntent(any(), any())).thenThrow(new RuntimeException("unavailable"));

        IntentRouterService.IntentResult result = service.classify("morning digest please");

        assertThat(result.getWorkflowKey()).isEqualTo("watchlist_digest");
        assertThat(result.getSource()).isEqualTo("keyword_regex");
    }

    @Test
    void classifyStockAnalysisStillWorksWithResearchDefinitions() {
        when(workflowMapper.findDefinitions()).thenReturn(researchDefinitions());
        when(langChainGateway.classifyIntent(any(), any())).thenThrow(new RuntimeException("unavailable"));

        IntentRouterService.IntentResult result = service.classify("分析一下 AAPL 这只股票");

        assertThat(result.getWorkflowKey()).isEqualTo("stock_analysis");
        assertThat(result.getSource()).isEqualTo("keyword_regex");
        assertThat(result.getTicker()).isEqualTo("AAPL");
    }

    @Test
    void classifyDbKeywordMatchingStillWorksForSeededTriggers() {
        when(workflowMapper.findDefinitions()).thenReturn(researchDefinitions());
        when(langChainGateway.classifyIntent(any(), any())).thenThrow(new RuntimeException("unavailable"));

        // "earnings call" is in seeded triggerKeywords but not in the high-precision regex
        IntentRouterService.IntentResult result = service.classify("earnings call summary");

        assertThat(result.getWorkflowKey()).isEqualTo("earnings_reaction");
        assertThat(result.getSource()).isEqualTo("keyword_db");
        assertThat(result.getConfidence()).isEqualTo(0.6);
    }

    @Test
    void extractTickerFromMessage() {
        when(workflowMapper.findDefinitions()).thenReturn(Arrays.asList(
                def("deep_dive", "Deep Dive", "深度分析")
        ));
        when(langChainGateway.classifyIntent(any(), any())).thenThrow(new RuntimeException("unavailable"));

        IntentRouterService.IntentResult result = service.classify("深度分析 NVDA 这只股票");

        assertThat(result.getTicker()).isEqualTo("NVDA");
    }

    @Test
    void extractTickerSkipsStopwords() {
        when(workflowMapper.findDefinitions()).thenReturn(Arrays.asList(
                def("daily", "Daily", "daily")
        ));
        when(langChainGateway.classifyIntent(any(), any())).thenThrow(new RuntimeException("unavailable"));

        IntentRouterService.IntentResult result = service.classify("daily AI sector report");

        assertThat(result.getTicker()).isNull();
    }

    @Test
    void classifyCachesDefinitions() {
        when(workflowMapper.findDefinitions()).thenReturn(Arrays.asList(def("daily", "Daily", "daily")));

        service.classify("daily");
        service.classify("daily again");

        verify(workflowMapper, times(1)).findDefinitions();
    }
}