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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Chat messages sent while the thread's dispatched run is RUNNING are queued into that agent loop. */
class ChatServiceActiveRunQueueTest {

    private static final String RUN_ID = "run-active";

    private ChatMapper chatMapper;
    private LangChainGateway langChainGateway;
    private WorkflowService workflowService;
    private HelpRoutingService helpRoutingService;
    private HelpService helpService;
    private HelpSessionStateService helpSessionStateService;
    private ChatService chatService;
    private HelpSessionState session;

    private final AuthenticatedPrincipal principal = new AuthenticatedPrincipal(
            "u-1", "alice", "tenant-1", Collections.<String>emptyList());

    @BeforeEach
    void setUp() {
        chatMapper = mock(ChatMapper.class);
        /* D2: legacy threads are no longer claimable, so every thread is owned by the caller. */
        java.util.Map<String, Object> owned = new java.util.LinkedHashMap<String, Object>();
        owned.put("user_id", "u-1");
        owned.put("tenant_id", "tenant-1");
        when(chatMapper.findThreadOwner(anyString())).thenReturn(owned);
        langChainGateway = mock(LangChainGateway.class);
        when(langChainGateway.isEnabled()).thenReturn(true);
        workflowService = mock(WorkflowService.class);
        helpRoutingService = mock(HelpRoutingService.class);
        helpService = mock(HelpService.class);
        HelpAnswerCacheService cache = mock(HelpAnswerCacheService.class);
        helpSessionStateService = mock(HelpSessionStateService.class);
        when(helpService.safetyRefuse(anyString(), anyString(), any())).thenReturn(null);
        when(cache.getIfAllowed(anyString(), any(), any())).thenReturn(null);

        session = HelpSessionState.forThread("thread-1");
        session.setUserId("u-1");
        session.setTenantId("tenant-1");
        session.setLastRunId(RUN_ID);
        session.setLastDomain("RESEARCH");
        session.setLastRouteAction("DISPATCH_WORKFLOW");
        when(helpSessionStateService.load(eq("thread-1"), eq(principal))).thenReturn(session);

        HelpIntent.Result intent = HelpIntent.Result.empty();
        intent.setDomain(HelpIntent.Domain.RESEARCH);
        intent.setRouteAction(HelpIntent.RouteAction.DISPATCH_WORKFLOW);
        intent.setWorkflowKey("daily");
        when(helpRoutingService.classifyExtended(anyString(), any())).thenReturn(intent);
        WorkflowRun run = new WorkflowRun();
        run.setRunId("run-new");
        run.setWorkflowKey("daily");
        run.setStatus("RUNNING");
        when(workflowService.createRun(eq("daily"), anyString(), any(), any())).thenReturn(run);

        chatService = new ChatService(chatMapper, langChainGateway, mock(MarketDataService.class), workflowService,
                helpRoutingService, helpService, cache, helpSessionStateService, new HelpTraceService(), Runnable::run);
    }

    @Test
    void followUpIsQueuedIntoRunningRunInsteadOfStartingAnother() {
        when(workflowService.enqueueRunMessage(RUN_ID, principal, "follow_up", "再看看 MSFT"))
                .thenReturn(accepted("q-1", 1));

        Map<String, Object> response = chatService.reply(body("再看看 MSFT", null), principal);

        assertThat(response).containsEntry("queued", true).containsEntry("queueKind", "follow_up")
                .containsEntry("queueId", "q-1").containsEntry("queuePosition", 1)
                .containsEntry("runId", RUN_ID).containsEntry("provider", "agent-queue");
        verify(workflowService, never()).createRun(anyString(), anyString(), any(), any());
        verify(chatMapper).insertMessage(anyString(), eq("thread-1"), eq("assistant"), anyString());
        /* P4-11: bookkeeping so the answer is written back to this thread when the run ends. */
        verify(workflowService).recordChatQueuedMessage(RUN_ID, "thread-1", "follow_up", "q-1");
    }

    @Test
    void explicitSteerDeliveryIsForwarded() {
        when(workflowService.enqueueRunMessage(RUN_ID, principal, "steer", "停，换成 MSFT"))
                .thenReturn(accepted("q-2", 1));

        Map<String, Object> response = chatService.reply(body("停，换成 MSFT", "steer"), principal);

        assertThat(response).containsEntry("queued", true).containsEntry("queueKind", "steer");
    }

    @Test
    void finishedRunFallsBackToNormalRouting() {
        when(workflowService.enqueueRunMessage(eq(RUN_ID), eq(principal), anyString(), anyString()))
                .thenReturn(failure("RUN_NOT_RUNNING", 409));

        Map<String, Object> response = chatService.reply(body("给我一份盘前日报", null), principal);

        assertThat(response.get("queued")).isNull();
        verify(workflowService).createRun(eq("daily"), anyString(), any(), eq(principal));
    }

    @Test
    void parallelRunWithNoAgentLeftToReceiveFallsBackToNormalRouting() {
        when(workflowService.enqueueRunMessage(eq(RUN_ID), eq(principal), anyString(), anyString()))
                .thenReturn(failure("NO_RECEIVER", 409));

        Map<String, Object> response = chatService.reply(body("给我一份盘前日报", null), principal);

        assertThat(response.get("queued")).isNull();
        verify(workflowService).createRun(eq("daily"), anyString(), any(), eq(principal));
    }

    @Test
    void runPausedAtApprovalFallsBackInsteadOfApproving() {
        when(workflowService.enqueueRunMessage(eq(RUN_ID), eq(principal), anyString(), anyString()))
                .thenReturn(failure("RUN_PAUSED", 409));

        Map<String, Object> response = chatService.reply(body("approve", null), principal);

        assertThat(response.get("queued")).isNull();
        assertThat(response).containsEntry("provider", "workflow-router");
        verify(workflowService, never()).decideApproval(anyString(), org.mockito.ArgumentMatchers.anyBoolean(), any(), any());
    }

    @Test
    void fullQueueIsReportedWithoutStartingNewRun() {
        when(workflowService.enqueueRunMessage(eq(RUN_ID), eq(principal), anyString(), anyString()))
                .thenReturn(failure("QUEUE_FULL", 429));

        Map<String, Object> response = chatService.reply(body("还有一件事", null), principal);

        assertThat(response).containsEntry("queued", false).containsEntry("queueError", "QUEUE_FULL")
                .containsEntry("ok", false);
        verify(workflowService, never()).createRun(anyString(), anyString(), any(), any());
    }

    @Test
    void deliveryNewAndExplicitWorkflowKeySkipTheQueue() {
        chatService.reply(body("给我一份盘前日报", "new"), principal);
        Map<String, Object> explicit = body("给我一份盘前日报", null);
        explicit.put("workflowKey", "daily");
        chatService.reply(explicit, principal);

        verify(workflowService, never()).enqueueRunMessage(anyString(), any(), anyString(), anyString());
    }

    @Test
    void implicitQueueOnlyWhenThreadDispatchedTheRun() {
        session.setLastDomain("RUN_DIAGNOSIS");
        session.setLastRouteAction("HELP_GRAPH");

        chatService.reply(body("它为什么失败？", null), principal);

        verify(workflowService, never()).enqueueRunMessage(anyString(), any(), anyString(), anyString());
    }

    @Test
    void anonymousOrDisabledOrchestratorSkipsTheQueue() {
        chatService.reply(body("hi", "steer"));
        when(langChainGateway.isEnabled()).thenReturn(false);
        chatService.reply(body("hi", "steer"), principal);

        verify(workflowService, never()).enqueueRunMessage(anyString(), any(), anyString(), anyString());
    }

    private static Map<String, Object> body(String message, String delivery) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", message);
        body.put("threadId", "thread-1");
        if (delivery != null) {
            body.put("delivery", delivery);
        }
        return body;
    }

    private static Map<String, Object> accepted(String id, int position) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("id", id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("message", message);
        out.put("position", position);
        return out;
    }

    private static Map<String, Object> failure(String code, int status) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("code", code);
        out.put("httpStatus", status);
        return out;
    }
}
