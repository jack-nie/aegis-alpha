package com.aegis.alpha.service;

import com.aegis.alpha.domain.WorkflowNodeRun;
import com.aegis.alpha.domain.WorkflowRun;
import com.aegis.alpha.domain.WorkflowRunEvent;
import com.aegis.alpha.mapper.AgentMapper;
import com.aegis.alpha.mapper.ChatMapper;
import com.aegis.alpha.mapper.WorkflowMapper;
import com.aegis.alpha.security.AuthenticatedPrincipal;
import com.aegis.alpha.service.help.HelpAnswerCacheService;
import com.aegis.alpha.service.help.HelpIntent;
import com.aegis.alpha.service.help.HelpLoopService;
import com.aegis.alpha.service.help.HelpRoutingService;
import com.aegis.alpha.service.help.HelpService;
import com.aegis.alpha.service.help.KnowledgeService;
import com.aegis.alpha.service.help.PlaybookService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P0 security tests for the chat surface:
 * <ul>
 *   <li>roles only ever come from the authenticated principal, never from the request body;</li>
 *   <li>a thread can only be continued by its owner;</li>
 *   <li>run diagnosis is owner scoped (admins may read any run).</li>
 * </ul>
 */
class ChatServiceSecurityTest {

    private ChatMapper chatMapper;
    private LangChainGateway langChainGateway;
    private MarketDataService marketDataService;
    private WorkflowMapper workflowMapper;
    private WorkflowService workflowService;
    private HelpRoutingService helpRoutingService;
    private HelpService helpService;
    private HelpAnswerCacheService helpAnswerCacheService;
    private ChatService chatService;

    @BeforeEach
    void setUp() {
        chatMapper = mock(ChatMapper.class);
        langChainGateway = mock(LangChainGateway.class);
        marketDataService = mock(MarketDataService.class);
        workflowMapper = mock(WorkflowMapper.class);
        workflowService = new WorkflowService(
                workflowMapper,
                mock(AgentMapper.class),
                new ObjectMapper(),
                langChainGateway,
                mock(CacheService.class),
                mock(BacktestService.class),
                mock(AgentTraceService.class),
                new WorkflowValidationService(),
                mock(TokenService.class),
                false);
        helpRoutingService = mock(HelpRoutingService.class);
        helpAnswerCacheService = mock(HelpAnswerCacheService.class);

        PlaybookService playbookService = new PlaybookService();
        playbookService.reload();
        KnowledgeService knowledgeService = new KnowledgeService();
        knowledgeService.reload();
        HelpLoopService helpLoopService = new HelpLoopService(knowledgeService);
        helpService = new HelpService(
                playbookService, workflowService, workflowMapper, knowledgeService, helpLoopService, langChainGateway);

        chatService = new ChatService(
                chatMapper, langChainGateway, marketDataService, workflowService,
                helpRoutingService, helpService, helpAnswerCacheService);

        when(workflowMapper.findRunEvents(anyString())).thenReturn(Collections.<WorkflowRunEvent>emptyList());
        when(workflowMapper.findNodeRuns(anyString())).thenReturn(Collections.<WorkflowNodeRun>emptyList());
        /* Mockito returns an empty Map for Map-returning mocks; a cache "hit" would short-circuit reply(). */
        when(helpAnswerCacheService.getIfAllowed(anyString(), any(), any())).thenReturn(null);
        when(langChainGateway.runAgent(any(), any(), any(), any())).thenAnswer(invocation -> {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("content", "copilot reply");
            return result;
        });
    }

    /* ============================ roles ============================ */

    @Test
    void clientSuppliedAdminRolesAreIgnored() {
        HelpService stubHelp = mock(HelpService.class);
        HelpRoutingService stubRouting = mock(HelpRoutingService.class);
        /* Mockito returns an empty Map for Map-returning mocks; null means "no safety refusal". */
        when(stubHelp.safetyRefuse(anyString(), anyString(), any())).thenReturn(null);
        when(stubRouting.classifyExtended(anyString(), any())).thenReturn(intent(HelpIntent.RouteAction.KNOWLEDGE_RAG));
        when(stubHelp.answerFromKnowledge(anyString(), anyString(), any(), anyList())).thenReturn(null);
        Map<String, Object> helpResponse = new LinkedHashMap<String, Object>();
        helpResponse.put("content", "help answer");
        when(stubHelp.answerFromHelpLoop(anyString(), anyString(), any(), anyList())).thenReturn(helpResponse);
        ChatService service = new ChatService(
                chatMapper, langChainGateway, marketDataService, workflowService,
                stubRouting, stubHelp, helpAnswerCacheService);

        Map<String, Object> body = body("如何发布工作流？");
        body.put("roles", Collections.singletonList("admin"));
        body.put("role", "admin");

        Map<String, Object> response = service.reply(body, principal("u-1", "tenant-1"));

        assertThat(response.get("content")).isEqualTo("help answer");
        ArgumentCaptor<List> rolesCaptor = ArgumentCaptor.forClass(List.class);
        verify(stubHelp).answerFromHelpLoop(anyString(), anyString(), any(), rolesCaptor.capture());
        assertThat(rolesCaptor.getValue()).isEmpty();
    }

    @Test
    void rolesAreTakenFromTheAuthenticatedPrincipal() {
        HelpService stubHelp = mock(HelpService.class);
        HelpRoutingService stubRouting = mock(HelpRoutingService.class);
        when(stubHelp.safetyRefuse(anyString(), anyString(), any())).thenReturn(null);
        when(stubRouting.classifyExtended(anyString(), any())).thenReturn(intent(HelpIntent.RouteAction.KNOWLEDGE_RAG));
        when(stubHelp.answerFromKnowledge(anyString(), anyString(), any(), anyList())).thenReturn(null);
        Map<String, Object> helpResponse = new LinkedHashMap<String, Object>();
        helpResponse.put("content", "help answer");
        when(stubHelp.answerFromHelpLoop(anyString(), anyString(), any(), anyList())).thenReturn(helpResponse);
        ChatService service = new ChatService(
                chatMapper, langChainGateway, marketDataService, workflowService,
                stubRouting, stubHelp, helpAnswerCacheService);

        AuthenticatedPrincipal admin = new AuthenticatedPrincipal(
                "u-admin", "root", "tenant-1", Collections.singletonList("admin"));
        service.reply(body("如何发布工作流？"), admin);

        ArgumentCaptor<List> rolesCaptor = ArgumentCaptor.forClass(List.class);
        verify(stubHelp).answerFromHelpLoop(anyString(), anyString(), any(), rolesCaptor.capture());
        assertThat(rolesCaptor.getValue()).containsExactly("admin");
    }

    /* ============================ threads ============================ */

    @Test
    void threadOwnedByAnotherUserIsRejected() {
        Map<String, Object> owner = new LinkedHashMap<String, Object>();
        owner.put("user_id", "u-other");
        owner.put("tenant_id", "tenant-2");
        when(chatMapper.findThreadOwner("thread-foreign")).thenReturn(owner);

        Map<String, Object> body = body("继续这个会话");
        body.put("threadId", "thread-foreign");

        assertThatThrownBy(() -> chatService.reply(body, principal("u-1", "tenant-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("thread belongs to another user");
        verify(chatMapper, never()).insertMessage(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void threadFromAnotherTenantIsRejected() {
        Map<String, Object> owner = new LinkedHashMap<String, Object>();
        owner.put("user_id", "u-1");
        owner.put("tenant_id", "tenant-2");
        when(chatMapper.findThreadOwner("thread-foreign-tenant")).thenReturn(owner);

        Map<String, Object> body = body("继续这个会话");
        body.put("threadId", "thread-foreign-tenant");

        assertThatThrownBy(() -> chatService.reply(body, principal("u-1", "tenant-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("thread belongs to another user");
    }

    @Test
    void legacyThreadWithoutOwnerIsClaimedByTheCaller() {
        when(chatMapper.findThreadOwner("thread-legacy")).thenReturn(new LinkedHashMap<String, Object>());
        when(chatMapper.updateThreadOwner("thread-legacy", "u-1", "tenant-1")).thenReturn(1);
        when(helpRoutingService.classifyExtended(anyString(), any())).thenReturn(intent(HelpIntent.RouteAction.COPILOT));

        Map<String, Object> body = body("继续这个会话");
        body.put("threadId", "thread-legacy");

        Map<String, Object> response = chatService.reply(body, principal("u-1", "tenant-1"));

        assertThat(response.get("threadId")).isEqualTo("thread-legacy");
        verify(chatMapper).updateThreadOwner("thread-legacy", "u-1", "tenant-1");
    }

    @Test
    void legacyClaimLostToAnotherUserIsRejected() {
        Map<String, Object> winner = new LinkedHashMap<String, Object>();
        winner.put("user_id", "u-2");
        winner.put("tenant_id", "tenant-1");
        when(chatMapper.findThreadOwner("thread-race"))
                .thenReturn(new LinkedHashMap<String, Object>())
                .thenReturn(winner);
        when(chatMapper.updateThreadOwner("thread-race", "u-1", "tenant-1")).thenReturn(0);

        Map<String, Object> body = body("继续这个会话");
        body.put("threadId", "thread-race");

        assertThatThrownBy(() -> chatService.reply(body, principal("u-1", "tenant-1")))
                .isInstanceOf(IllegalArgumentException.class);
        verify(chatMapper, never()).insertMessage(anyString(), eq("thread-race"), anyString(), anyString());
    }

    @Test
    void reusingOwnThreadDoesNotInsertAgain() {
        Map<String, Object> mine = new LinkedHashMap<String, Object>();
        mine.put("user_id", "u-1");
        mine.put("tenant_id", "tenant-1");
        when(chatMapper.findThreadOwner("thread-mine")).thenReturn(mine);
        when(helpRoutingService.classifyExtended(anyString(), any())).thenReturn(intent(HelpIntent.RouteAction.COPILOT));

        for (int i = 0; i < 2; i++) {
            Map<String, Object> body = body("继续这个会话");
            body.put("threadId", "thread-mine");
            chatService.reply(body, principal("u-1", "tenant-1"));
        }

        verify(chatMapper, never()).insertThread(anyString(), anyString());
        verify(chatMapper, never()).insertThreadOwned(anyString(), anyString(), anyString(), anyString());
        verify(chatMapper, never()).updateThreadOwner(anyString(), anyString(), anyString());
    }

    @Test
    void newThreadIsCreatedWithTheCallerAsOwner() {
        when(helpRoutingService.classifyExtended(anyString(), any())).thenReturn(intent(HelpIntent.RouteAction.COPILOT));

        chatService.reply(body("你好"), principal("u-1", "tenant-1"));

        ArgumentCaptor<String> threadId = ArgumentCaptor.forClass(String.class);
        verify(chatMapper).insertThreadOwned(threadId.capture(), anyString(), eq("u-1"), eq("tenant-1"));
        assertThat(threadId.getValue()).isNotBlank();
        verify(chatMapper, never()).insertThread(anyString(), anyString());
    }

    /* ========================= run diagnosis ========================= */

    @Test
    void diagnosisOfAnotherUsersRunIsDeniedWithoutLeakingNodes() {
        String runId = "11111111-2222-3333-4444-555555555555";
        when(workflowMapper.findRun(runId)).thenReturn(run(runId, "u-other", "tenant-2"));
        when(workflowMapper.findNodeRuns(runId)).thenReturn(Collections.singletonList(node("valuation_analysis")));
        when(workflowMapper.findRunEvents(runId)).thenReturn(Collections.singletonList(event("secret node output")));
        when(helpRoutingService.classifyExtended(anyString(), any()))
                .thenReturn(diagnosisIntent(runId));

        Map<String, Object> response = chatService.reply(
                body("为什么这个 run 失败了？"), principal("u-1", "tenant-1"));

        assertThat(response.get("provider")).isEqualTo("help-diagnosis");
        assertThat(response.get("ok")).isEqualTo(false);
        assertThat(response.get("escalateHuman")).isEqualTo(true);
        assertThat(response.get("runId")).isEqualTo(runId);
        String content = String.valueOf(response.get("content"));
        assertThat(content).isEqualTo("未找到运行或无权访问该运行。");
        assertThat(content).doesNotContain("valuation_analysis");
        assertThat(content).doesNotContain("secret node output");
        @SuppressWarnings("unchecked")
        Map<String, Object> diagnosis = (Map<String, Object>) response.get("diagnosis");
        assertThat(diagnosis.get("focus")).isEqualTo("ACCESS_DENIED");
        verify(workflowMapper, never()).findNodeRuns(runId);
        verify(workflowMapper, never()).findRunEvents(runId);
    }

    @Test
    void diagnosisOfOwnRunIsAllowed() {
        String runId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
        when(workflowMapper.findRun(runId)).thenReturn(run(runId, "u-1", "tenant-1"));
        when(workflowMapper.findNodeRuns(runId)).thenReturn(Collections.singletonList(node("valuation_analysis")));
        when(workflowMapper.findRunEvents(runId)).thenReturn(Collections.singletonList(event("node crashed")));
        when(helpRoutingService.classifyExtended(anyString(), any())).thenReturn(diagnosisIntent(runId));

        Map<String, Object> response = chatService.reply(
                body("为什么这个 run 失败了？"), principal("u-1", "tenant-1"));

        assertThat(response.get("provider")).isEqualTo("help-diagnosis");
        assertThat(response.get("runId")).isEqualTo(runId);
        assertThat(response.get("runStatus")).isEqualTo("FAILED");
        String content = String.valueOf(response.get("content"));
        assertThat(content).contains("valuation_analysis");
        assertThat(content).contains("node crashed");
        @SuppressWarnings("unchecked")
        Map<String, Object> diagnosis = (Map<String, Object>) response.get("diagnosis");
        assertThat(diagnosis.get("focus")).isEqualTo("FAILURE");
    }

    @Test
    void adminCanDiagnoseAnotherUsersRun() {
        String runId = "cccccccc-dddd-eeee-ffff-000000000001";
        when(workflowMapper.findRun(runId)).thenReturn(run(runId, "u-other", "tenant-2"));
        when(workflowMapper.findNodeRuns(runId)).thenReturn(Collections.singletonList(node("aggregate")));
        when(helpRoutingService.classifyExtended(anyString(), any())).thenReturn(diagnosisIntent(runId));

        AuthenticatedPrincipal admin = new AuthenticatedPrincipal(
                "u-admin", "root", "tenant-1", Collections.singletonList("admin"));
        Map<String, Object> response = chatService.reply(body("这个 run 发生了什么？"), admin);

        assertThat(response.get("provider")).isEqualTo("help-diagnosis");
        assertThat(response.get("runId")).isEqualTo(runId);
        assertThat(String.valueOf(response.get("content"))).contains("aggregate");
    }

    @Test
    void diagnosisOfLegacyUnownedRunIsDeniedForNonAdmin() {
        String runId = "dddddddd-eeee-ffff-0000-111111111111";
        when(workflowMapper.findRun(runId)).thenReturn(run(runId, null, null));
        when(helpRoutingService.classifyExtended(anyString(), any())).thenReturn(diagnosisIntent(runId));

        Map<String, Object> response = chatService.reply(
                body("这个 run 发生了什么？"), principal("u-1", "tenant-1"));

        assertThat(response.get("ok")).isEqualTo(false);
        assertThat(String.valueOf(response.get("content"))).isEqualTo("未找到运行或无权访问该运行。");
        @SuppressWarnings("unchecked")
        Map<String, Object> diagnosis = (Map<String, Object>) response.get("diagnosis");
        assertThat(diagnosis.get("focus")).isEqualTo("ACCESS_DENIED");
    }

    @Test
    void principalThreadListIsScopedToOwner() {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("thread_id", "thread-1");
        row.put("title", "mine");
        when(chatMapper.threadsForPrincipal("u-1", "tenant-1")).thenReturn(Arrays.asList(row));

        Object threads = chatService.threads(principal("u-1", "tenant-1"));

        assertThat(threads).isEqualTo(Arrays.asList(row));
        verify(chatMapper).threadsForPrincipal("u-1", "tenant-1");
        verify(chatMapper, never()).threads();
    }

    /* ============================ helpers ============================ */

    private static AuthenticatedPrincipal principal(String userId, String tenantId) {
        return new AuthenticatedPrincipal(userId, userId, tenantId, Collections.<String>emptyList());
    }

    private static Map<String, Object> body(String message) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("message", message);
        return body;
    }

    private static HelpIntent.Result intent(HelpIntent.RouteAction action) {
        HelpIntent.Result result = HelpIntent.Result.empty();
        result.setDomain(HelpIntent.Domain.PRODUCT_HELP);
        result.setRouteAction(action);
        return result;
    }

    private static HelpIntent.Result diagnosisIntent(String runId) {
        HelpIntent.Result result = HelpIntent.Result.empty();
        result.setDomain(HelpIntent.Domain.RUN_DIAGNOSIS);
        result.setRouteAction(HelpIntent.RouteAction.HELP_GRAPH);
        HelpIntent.Entities entities = new HelpIntent.Entities();
        entities.setRunId(runId);
        result.setEntities(entities);
        return result;
    }

    private static WorkflowRun run(String runId, String userId, String tenantId) {
        WorkflowRun run = new WorkflowRun();
        run.setRunId(runId);
        run.setWorkflowKey("deep_dive");
        run.setStatus("FAILED");
        run.setSubject("AAPL");
        run.setUserId(userId);
        run.setTenantId(tenantId);
        return run;
    }

    private static WorkflowNodeRun node(String nodeName) {
        WorkflowNodeRun node = new WorkflowNodeRun();
        node.setNodeId(nodeName);
        node.setNodeName(nodeName);
        node.setStatus("FAILED");
        node.setErrorMessage("node crashed");
        node.setAttempt(1);
        node.setMaxAttempts(1);
        return node;
    }

    private static WorkflowRunEvent event(String message) {
        WorkflowRunEvent event = new WorkflowRunEvent();
        event.setEventType("NODE_FAILED");
        event.setMessage(message);
        event.setCreatedAt("2026-07-21T12:00:00Z");
        return event;
    }
}
