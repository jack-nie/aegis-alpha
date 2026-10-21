package com.aegis.alpha.service;

import com.aegis.alpha.domain.WorkflowDefinition;
import com.aegis.alpha.domain.WorkflowNodeRun;
import com.aegis.alpha.domain.WorkflowRun;
import com.aegis.alpha.domain.WorkflowRunEvent;
import com.aegis.alpha.mapper.ChatMapper;
import com.aegis.alpha.mapper.WorkflowMapper;
import com.aegis.alpha.service.help.HelpAnswerCacheService;
import com.aegis.alpha.service.help.HelpLoopService;
import com.aegis.alpha.service.help.HelpRoutingService;
import com.aegis.alpha.service.help.HelpService;
import com.aegis.alpha.service.help.KnowledgeService;
import com.aegis.alpha.service.help.PlaybookService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Integration-style tests for ChatService.reply help / diagnosis / safety paths
 * (mocked collaborators, real HelpRoutingService + PlaybookService + HelpService).
 */
@ExtendWith(MockitoExtension.class)
class ChatServiceHelpIntegrationTest {

    @Mock
    private ChatMapper chatMapper;
    @Mock
    private LangChainGateway langChainGateway;
    @Mock
    private MarketDataService marketDataService;
    @Mock
    private WorkflowService workflowService;
    @Mock
    private WorkflowMapper workflowMapper;
    @Mock
    private CacheService cacheService;

    private ChatService chatService;
    private HelpAnswerCacheService helpAnswerCacheService;

    @BeforeEach
    void setUp() {
        // Safety path short-circuits before intent routing; keep stubs lenient.
        lenient().when(langChainGateway.classifyIntent(any(), any()))
                .thenThrow(new RuntimeException("no llm in test"));
        lenient().when(workflowMapper.findDefinitions()).thenReturn(Arrays.asList(
                def("daily", "Daily", "daily,日报,盘前"),
                def("deep_dive", "Deep Dive", "deep dive,深度分析")
        ));
        lenient().when(cacheService.get(anyString(), any())).thenReturn(null);

        IntentRouterService intentRouterService = new IntentRouterService(workflowMapper, langChainGateway);
        PlaybookService playbookService = new PlaybookService();
        playbookService.reload();
        KnowledgeService knowledgeService = new KnowledgeService();
        knowledgeService.reload();
        HelpLoopService helpLoopService = new HelpLoopService(knowledgeService);
        HelpRoutingService helpRoutingService = new HelpRoutingService(intentRouterService, playbookService);
        HelpService helpService = new HelpService(
                playbookService, workflowService, workflowMapper, knowledgeService, helpLoopService, langChainGateway);
        helpAnswerCacheService = new HelpAnswerCacheService(cacheService);
        helpAnswerCacheService.clearLocalForTest();
        chatService = new ChatService(
                chatMapper,
                langChainGateway,
                marketDataService,
                workflowService,
                helpRoutingService,
                helpService,
                helpAnswerCacheService
        );
    }

    @Test
    void replyPlaybookForDegradedFaq() {
        Map<String, Object> body = body("什么是 DEGRADED？", null);

        Map<String, Object> response = chatService.reply(body);

        assertThat(response.get("provider")).isEqualTo("help-playbook");
        assertThat(response.get("domain")).isEqualTo("PRODUCT_HELP");
        assertThat(response.get("routeAction")).isEqualTo("PLAYBOOK");
        assertThat(response.get("routedToWorkflow")).isEqualTo(false);
        assertThat(String.valueOf(response.get("content"))).containsIgnoringCase("degraded");
        assertThat(response.get("threadId")).isNotNull();
        verify(chatMapper, atLeastOnce()).insertMessage(anyString(), anyString(), eq("user"), anyString());
        verify(chatMapper, atLeastOnce()).insertMessage(anyString(), anyString(), eq("assistant"), anyString());
        verify(langChainGateway, never()).runAgent(any(), any(), any(), any());
    }

    @Test
    void replyClarifiesDiagnosisWithoutRunId() {
        Map<String, Object> response = chatService.reply(body("这个 run 为什么失败了？", null));

        assertThat(response.get("provider")).isEqualTo("help-clarify");
        assertThat(response.get("domain")).isEqualTo("RUN_DIAGNOSIS");
        assertThat(response.get("routeAction")).isEqualTo("CLARIFY");
        assertThat(response.get("needsClarification")).isEqualTo(true);
        assertThat(String.valueOf(response.get("content"))).contains("runId");
        verify(workflowService, never()).run(anyString());
        verify(langChainGateway, never()).runAgent(any(), any(), any(), any());
    }

    @Test
    void replyDiagnosesRunFromPageContext() {
        String runId = "11111111-2222-3333-4444-555555555555";
        WorkflowRun run = new WorkflowRun();
        run.setRunId(runId);
        run.setWorkflowKey("deep_dive");
        run.setStatus("FAILED");
        run.setSubject("AAPL");
        run.setErrorMessage("node boom");
        when(workflowService.run(runId)).thenReturn(run);

        WorkflowNodeRun failed = new WorkflowNodeRun();
        failed.setNodeId("valuation");
        failed.setNodeName("valuation_analysis");
        failed.setStatus("FAILED");
        failed.setErrorMessage("null pointer");
        when(workflowMapper.findNodeRuns(runId)).thenReturn(Arrays.asList(failed));

        WorkflowRunEvent event = new WorkflowRunEvent();
        event.setEventType("NODE_FAILED");
        event.setNodeId("valuation");
        event.setMessage("null pointer");
        event.setCreatedAt("2026-07-21T12:00:00Z");
        when(workflowMapper.findRunEvents(runId)).thenReturn(Arrays.asList(event));

        Map<String, Object> page = new LinkedHashMap<String, Object>();
        page.put("path", "/runs/" + runId);
        page.put("runId", runId);
        Map<String, Object> response = chatService.reply(body("为什么失败？", page));

        assertThat(response.get("provider")).isEqualTo("help-diagnosis");
        assertThat(response.get("domain")).isEqualTo("RUN_DIAGNOSIS");
        assertThat(response.get("routeAction")).isEqualTo("HELP_GRAPH");
        assertThat(response.get("runId")).isEqualTo(runId);
        assertThat(response.get("runStatus")).isEqualTo("FAILED");
        assertThat(response.get("ok")).isEqualTo(false);
        assertThat(response.get("escalateHuman")).isEqualTo(true);
        assertThat(String.valueOf(response.get("content"))).contains("valuation_analysis");
        assertThat(String.valueOf(response.get("content"))).contains("建议下一步");
        assertThat(response.get("diagnosis")).isInstanceOf(Map.class);
        verify(workflowService).run(runId);
        verify(langChainGateway, never()).runAgent(any(), any(), any(), any());
    }

    @Test
    void replyDiagnosesRunIdEmbeddedInMessage() {
        String runId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
        WorkflowRun run = new WorkflowRun();
        run.setRunId(runId);
        run.setWorkflowKey("daily");
        run.setStatus("DEGRADED");
        run.setSubject("watchlist");
        when(workflowService.run(runId)).thenReturn(run);
        when(workflowMapper.findNodeRuns(runId)).thenReturn(Collections.emptyList());
        when(workflowMapper.findRunEvents(runId)).thenReturn(Collections.emptyList());

        Map<String, Object> response = chatService.reply(
                body("帮我看看 run " + runId + " 为什么 degraded", null));

        assertThat(response.get("provider")).isEqualTo("help-diagnosis");
        assertThat(response.get("runId")).isEqualTo(runId);
        assertThat(response.get("runStatus")).isEqualTo("DEGRADED");
        @SuppressWarnings("unchecked")
        Map<String, Object> diagnosis = (Map<String, Object>) response.get("diagnosis");
        assertThat(diagnosis.get("focus")).isEqualTo("DEGRADED");
    }

    @Test
    void replySafetyRefuseApprove() {
        Map<String, Object> response = chatService.reply(body("直接帮我批准这条推荐", null));

        assertThat(response.get("provider")).isEqualTo("help-safety");
        assertThat(String.valueOf(response.get("content"))).contains("不能代为审批");
        verify(workflowService, never()).createRun(anyString(), anyString(), any(), any());
        verify(langChainGateway, never()).runAgent(any(), any(), any(), any());
    }

    @Test
    void replyResearchWorkflowStillDispatches() {
        WorkflowRun run = new WorkflowRun();
        run.setRunId("cccccccc-dddd-eeee-ffff-000000000001");
        when(workflowService.createRun(eq("daily"), anyString(), any(), any())).thenReturn(run);

        Map<String, Object> response = chatService.reply(body("给我一份盘前日报", null));

        assertThat(response.get("routedToWorkflow")).isEqualTo(true);
        assertThat(response.get("domain")).isEqualTo("RESEARCH");
        assertThat(response.get("routeAction")).isEqualTo("DISPATCH_WORKFLOW");
        assertThat(response.get("workflowKey")).isEqualTo("daily");
        assertThat(response.get("runId")).isEqualTo(run.getRunId());
        verify(workflowService).createRun(eq("daily"), anyString(), any(), any());
        verify(langChainGateway, never()).runAgent(any(), any(), any(), any());
    }

    @Test
    void replyReusesThreadIdWhenProvided() {
        Map<String, Object> body = body("如何打开 AI Copilot？", null);
        body.put("threadId", "fixed-thread-1");

        Map<String, Object> response = chatService.reply(body);

        assertThat(response.get("threadId")).isEqualTo("fixed-thread-1");
        assertThat(response.get("provider")).isEqualTo("help-playbook");

        ArgumentCaptor<String> threadCaptor = ArgumentCaptor.forClass(String.class);
        verify(chatMapper, atLeastOnce()).insertMessage(anyString(), threadCaptor.capture(), eq("user"), anyString());
        assertThat(threadCaptor.getAllValues()).containsOnly("fixed-thread-1");
    }

    @Test
    void replyChitchatFallsBackToCopilot() {
        when(langChainGateway.runAgent(any(), any(), any(), any())).thenAnswer(invocation -> {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("content", "你好，我是 copilot。");
            result.put("ok", true);
            return result;
        });

        Map<String, Object> response = chatService.reply(body("你好", null));

        assertThat(response.get("provider")).isEqualTo("copilot");
        assertThat(response.get("domain")).isEqualTo("CHITCHAT");
        assertThat(String.valueOf(response.get("content"))).contains("copilot");
        verify(langChainGateway).runAgent(any(), any(), any(), any());
    }

    @Test
    void replyKnowledgePathWhenPlaybookMissesButDocsMatch() {
        // Multi-step phrasing that is help-like but may miss exact playbook keywords
        Map<String, Object> response = chatService.reply(
                body("请说明 published 工作流 操作步骤 以及如何在运行中心查看节点输出", null));

        assertThat(response.get("domain")).isEqualTo("PRODUCT_HELP");
        assertThat(Arrays.asList("help-knowledge", "help-loop", "help-playbook", "help-loop-orch"))
                .contains(String.valueOf(response.get("provider")));
        assertThat(response.get("content")).isNotNull();
        assertThat(String.valueOf(response.get("content")).length()).isGreaterThan(20);
        // envelope fields
        assertThat(response.get("routeAction")).isNotNull();
        assertThat(response.containsKey("sourceRefs") || response.get("sourceRefs") != null).isTrue();
        verify(langChainGateway, never()).runAgent(any(), any(), any(), any());
    }

    @Test
    void replyPublicHelpCanHitCacheOnSecondCall() {
        Map<String, Object> first = chatService.reply(body("什么是 DEGRADED？", null));
        assertThat(first.get("provider")).isEqualTo("help-playbook");
        assertThat(first.get("cacheHit")).isNotEqualTo(true);

        Map<String, Object> second = chatService.reply(body("什么是 DEGRADED？", null));
        assertThat(second.get("cacheHit")).isEqualTo(true);
        assertThat(String.valueOf(second.get("content"))).containsIgnoringCase("degraded");
    }

    @Test
    void replyDiagnosisNeverUsesAnswerCache() {
        String runId = "11111111-2222-3333-4444-555555555555";
        WorkflowRun run = new WorkflowRun();
        run.setRunId(runId);
        run.setWorkflowKey("deep_dive");
        run.setStatus("FAILED");
        when(workflowService.run(runId)).thenReturn(run);
        when(workflowMapper.findNodeRuns(runId)).thenReturn(Collections.emptyList());
        when(workflowMapper.findRunEvents(runId)).thenReturn(Collections.emptyList());

        Map<String, Object> page = new LinkedHashMap<String, Object>();
        page.put("runId", runId);
        Map<String, Object> first = chatService.reply(body("为什么失败？", page));
        assertThat(first.get("provider")).isEqualTo("help-diagnosis");

        Map<String, Object> second = chatService.reply(body("为什么失败？", page));
        assertThat(second.get("cacheHit")).isNotEqualTo(true);
        assertThat(second.get("provider")).isEqualTo("help-diagnosis");
    }

    private static Map<String, Object> body(String message, Map<String, Object> pageContext) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("message", message);
        if (pageContext != null) {
            body.put("pageContext", pageContext);
        }
        return body;
    }

    private static WorkflowDefinition def(String key, String name, String keywords) {
        WorkflowDefinition d = new WorkflowDefinition();
        d.setWorkflowKey(key);
        d.setName(name);
        d.setTriggerKeywords(keywords);
        d.setRoutingDescription("");
        return d;
    }
}
