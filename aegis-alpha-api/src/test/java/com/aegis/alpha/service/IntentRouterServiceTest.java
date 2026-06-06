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