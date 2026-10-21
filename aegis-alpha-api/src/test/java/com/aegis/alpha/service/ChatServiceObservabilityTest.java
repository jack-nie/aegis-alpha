package com.aegis.alpha.service;

import com.aegis.alpha.domain.HelpSessionState;
import com.aegis.alpha.domain.WorkflowRun;
import com.aegis.alpha.mapper.ChatMapper;
import com.aegis.alpha.security.AuthenticatedPrincipal;
import com.aegis.alpha.service.help.HelpAnswerCacheService;
import com.aegis.alpha.service.help.HelpIntent;
import com.aegis.alpha.service.help.HelpRoutingService;
import com.aegis.alpha.service.help.HelpService;
import com.aegis.alpha.service.help.HelpSessionStateService;
import com.aegis.alpha.service.help.trace.HelpTraceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P1/P2 integration tests: every chat turn must produce a finished trace and
 * persist enough session state for multi-turn run diagnosis.
 */
class ChatServiceObservabilityTest {

    private ChatMapper chatMapper;
    private LangChainGateway langChainGateway;
    private MarketDataService marketDataService;
    private WorkflowService workflowService;
    private HelpRoutingService helpRoutingService;
    private HelpService helpService;
    private HelpAnswerCacheService helpAnswerCacheService;
    private HelpSessionStateService helpSessionStateService;
    private HelpTraceService helpTraceService;
    private ChatService chatService;

    private final AuthenticatedPrincipal principal = new AuthenticatedPrincipal(
            "u-1", "alice", "tenant-1", Collections.<String>emptyList());

    @BeforeEach
    void setUp() {
        chatMapper = mock(ChatMapper.class);
        langChainGateway = mock(LangChainGateway.class);
        marketDataService = mock(MarketDataService.class);
        workflowService = mock(WorkflowService.class);
        helpRoutingService = mock(HelpRoutingService.class);
        helpService = mock(HelpService.class);
        helpAnswerCacheService = mock(HelpAnswerCacheService.class);
        helpSessionStateService = mock(HelpSessionStateService.class);
        helpTraceService = new HelpTraceService();

        when(helpService.safetyRefuse(anyString(), anyString(), any())).thenReturn(null);
        when(helpAnswerCacheService.getIfAllowed(anyString(), any())).thenReturn(null);

        Executor direct = new Executor() {
            @Override
            public void execute(Runnable command) {
                command.run();
            }
        };
        chatService = new ChatService(
                chatMapper, langChainGateway, marketDataService, workflowService,
                helpRoutingService, helpService, helpAnswerCacheService,
                helpSessionStateService, helpTraceService, direct);
    }

    @Test
    void replyFinishesTraceWithRouteProviderAndCacheMiss() {
        when(helpRoutingService.classifyExtended(anyString(), any()))
                .thenReturn(intent(HelpIntent.Domain.PRODUCT_HELP, HelpIntent.RouteAction.KNOWLEDGE_RAG));
        Map<String, Object> answer = response("help-knowledge", "根据文档……");
        when(helpService.answerFromKnowledge(anyString(), anyString(), any(), any()))
                .thenReturn(answer);

        chatService.reply(body("如何运行工作流？"), principal);

        Map<String, Object> snapshot = helpTraceService.snapshot();
        assertThat(snapshot.get("total")).isEqualTo(1L);
        @SuppressWarnings("unchecked")
        Map<String, Long> counters = (Map<String, Long>) snapshot.get("counters");
        assertThat(counters.get("route.PRODUCT_HELP.KNOWLEDGE_RAG")).isEqualTo(1L);
        assertThat(counters.get("provider.help-knowledge")).isEqualTo(1L);
        assertThat(counters.get("cacheMiss")).isEqualTo(1L);
    }

    @Test
    void cacheHitIsMarkedOnTheTrace() {
        when(helpRoutingService.classifyExtended(anyString(), any()))
                .thenReturn(intent(HelpIntent.Domain.PRODUCT_HELP, HelpIntent.RouteAction.PLAYBOOK));
        Map<String, Object> cached = response("help-playbook", "缓存答案");
        cached.put("cacheHit", true);
        when(helpAnswerCacheService.getIfAllowed(anyString(), any())).thenReturn(cached);

        chatService.reply(body("什么是 DEGRADED？"), principal);

        @SuppressWarnings("unchecked")
        Map<String, Long> counters = (Map<String, Long>) helpTraceService.snapshot().get("counters");
        assertThat(counters.get("cacheHit")).isEqualTo(1L);
        assertThat(counters.get("cacheMiss")).isNull();
    }

    @Test
    void diagnosisPersistsRunIdAndRouteInSession() {
        String runId = "11111111-2222-3333-4444-555555555555";
        HelpIntent.Result intent = intent(HelpIntent.Domain.RUN_DIAGNOSIS, HelpIntent.RouteAction.HELP_GRAPH);
        HelpIntent.Entities entities = new HelpIntent.Entities();
        entities.setRunId(runId);
        intent.setEntities(entities);
        when(helpRoutingService.classifyExtended(anyString(), any())).thenReturn(intent);
        Map<String, Object> diagnosis = response("help-diagnosis", "## 运行诊断");
        diagnosis.put("runId", runId);
        diagnosis.put("escalateHuman", true);
        when(helpService.diagnoseRun(anyString(), anyString(), any(), eq(principal))).thenReturn(diagnosis);

        chatService.reply(body("为什么这个 run 失败了？"), principal);

        ArgumentCaptor<HelpSessionState> stateCaptor = ArgumentCaptor.forClass(HelpSessionState.class);
        verify(helpSessionStateService).mergeAfterRoute(stateCaptor.capture(),
                eq("RUN_DIAGNOSIS"), eq("HELP_GRAPH"), eq(runId), eq(false));
        verify(helpSessionStateService).mergeCheckpoint(any(HelpSessionState.class), eq(""));
        verify(helpSessionStateService).save(any(HelpSessionState.class), eq(principal));
        assertThat(stateCaptor.getValue().getGoal()).isEqualTo("为什么这个 run 失败了？");
        @SuppressWarnings("unchecked")
        Map<String, Long> counters = (Map<String, Long>) helpTraceService.snapshot().get("counters");
        assertThat(counters.get("escalated")).isEqualTo(1L);
    }

    @Test
    void clarifyPersistsPendingRunIdCheckpoint() {
        when(helpRoutingService.classifyExtended(anyString(), any()))
                .thenReturn(intent(HelpIntent.Domain.RUN_DIAGNOSIS, HelpIntent.RouteAction.CLARIFY));
        when(helpService.clarify(anyString(), any()))
                .thenReturn(response("help-clarify", "请提供运行 ID（runId）。"));

        chatService.reply(body("这个 run 为什么失败了？"), principal);

        verify(helpSessionStateService).mergeAfterRoute(any(HelpSessionState.class),
                eq("RUN_DIAGNOSIS"), eq("CLARIFY"), eq(null), eq(true));
        verify(helpSessionStateService).mergeCheckpoint(any(HelpSessionState.class),
                eq("{\"pending\":\"run_id\"}"));
        verify(helpSessionStateService).save(any(HelpSessionState.class), eq(principal));
    }

    @Test
    void followUpWithoutRunIdInheritsLastDiagnosedRun() {
        String lastRunId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
        HelpSessionState state = HelpSessionState.forThread("thread-1");
        state.setUserId("u-1");
        state.setTenantId("tenant-1");
        state.setLastRunId(lastRunId);
        state.setLastDomain("RUN_DIAGNOSIS");
        when(helpSessionStateService.load(eq("thread-1"), eq(principal))).thenReturn(state);

        HelpIntent.Result clarify = intent(HelpIntent.Domain.RUN_DIAGNOSIS, HelpIntent.RouteAction.CLARIFY);
        when(helpRoutingService.classifyExtended(anyString(), any())).thenReturn(clarify);
        Map<String, Object> diagnosis = response("help-diagnosis", "## 运行诊断");
        diagnosis.put("runId", lastRunId);
        when(helpService.diagnoseRun(anyString(), anyString(), any(), eq(principal))).thenReturn(diagnosis);

        Map<String, Object> body = body("它为什么失败？");
        body.put("threadId", "thread-1");
        chatService.reply(body, principal);

        ArgumentCaptor<HelpIntent.Result> intentCaptor = ArgumentCaptor.forClass(HelpIntent.Result.class);
        verify(helpService).diagnoseRun(anyString(), anyString(), intentCaptor.capture(), eq(principal));
        assertThat(intentCaptor.getValue().getRouteAction()).isEqualTo(HelpIntent.RouteAction.HELP_GRAPH);
        assertThat(intentCaptor.getValue().getEntities().getRunId()).isEqualTo(lastRunId);
    }

    @Test
    void explicitWorkflowDispatchUsesTheInjectedExecutor() {
        HelpIntent.Result intent = intent(HelpIntent.Domain.RESEARCH, HelpIntent.RouteAction.DISPATCH_WORKFLOW);
        intent.setWorkflowKey("daily");
        when(helpRoutingService.classifyExtended(anyString(), any())).thenReturn(intent);
        WorkflowRun run = new WorkflowRun();
        run.setRunId("run-1");
        run.setWorkflowKey("daily");
        run.setStatus("RUNNING");
        when(workflowService.createRun(eq("daily"), anyString(), any(), eq(principal))).thenReturn(run);

        chatService.reply(body("给我一份盘前日报"), principal);

        verify(workflowService).executeAsync(eq(run), any(), eq("daily"));
        @SuppressWarnings("unchecked")
        Map<String, Long> counters = (Map<String, Long>) helpTraceService.snapshot().get("counters");
        assertThat(counters.get("route.RESEARCH.DISPATCH_WORKFLOW")).isEqualTo(1L);
        assertThat(counters.get("provider.workflow-router")).isEqualTo(1L);
    }

    @Test
    void sessionStateIsSkippedForAnonymousCallers() {
        when(helpRoutingService.classifyExtended(anyString(), any()))
                .thenReturn(intent(HelpIntent.Domain.CHITCHAT, HelpIntent.RouteAction.COPILOT));
        Map<String, Object> copilot = response("copilot", "你好");
        when(langChainGateway.runAgent(any(), any(), any(), any())).thenReturn(copilot);

        chatService.reply(body("你好"));

        verify(helpSessionStateService, never()).save(any(HelpSessionState.class), any());
        assertThat(helpTraceService.snapshot().get("total")).isEqualTo(1L);
    }

    private static HelpIntent.Result intent(HelpIntent.Domain domain, HelpIntent.RouteAction action) {
        HelpIntent.Result result = HelpIntent.Result.empty();
        result.setDomain(domain);
        result.setRouteAction(action);
        return result;
    }

    private static Map<String, Object> body(String message) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("message", message);
        return body;
    }

    private static Map<String, Object> response(String provider, String content) {
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("provider", provider);
        response.put("content", content);
        response.put("message", content);
        response.put("ok", true);
        return response;
    }
}
