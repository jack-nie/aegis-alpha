package com.aegis.alpha.service;

import com.aegis.alpha.domain.WorkflowRun;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P0-2: identity (roles / userId / tenantId) comes only from the authenticated principal.
 * Client-supplied {@code roles} and {@code state.userId} must never widen access.
 */
class ChatServicePrincipalIdentityTest {

    private ChatMapper chatMapper;
    private LangChainGateway langChainGateway;
    private WorkflowService workflowService;
    private HelpRoutingService helpRoutingService;
    private ChatService chatService;

    @BeforeEach
    void setUp() {
        chatMapper = mock(ChatMapper.class);
        langChainGateway = mock(LangChainGateway.class);
        workflowService = mock(WorkflowService.class);
        helpRoutingService = mock(HelpRoutingService.class);
        PlaybookService playbookService = new PlaybookService();
        playbookService.reload();
        KnowledgeService knowledgeService = new KnowledgeService();
        knowledgeService.reload();
        HelpService helpService = new HelpService(playbookService, workflowService, mock(WorkflowMapper.class),
                knowledgeService, new HelpLoopService(knowledgeService), langChainGateway);
        HelpAnswerCacheService cache = mock(HelpAnswerCacheService.class);
        /* Mockito returns an empty Map by default, which would look like a cache hit. */
        when(cache.getIfAllowed(anyString(), any(), any())).thenReturn(null);
        chatService = new ChatService(chatMapper, langChainGateway, mock(MarketDataService.class), workflowService,
                helpRoutingService, helpService, cache);
        when(langChainGateway.runAgent(any(), any(), any(), any())).thenAnswer(invocation -> {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("content", "copilot reply");
            return result;
        });
    }

    @Test
    void bodyRolesDoNotRevealInternalKnowledge() {
        when(helpRoutingService.classifyExtended(anyString(), any())).thenReturn(knowledgeIntent());
        Map<String, Object> body = body("内部运维 orchestrator 重启 节点 token");
        body.put("roles", Collections.singletonList("desk"));
        body.put("role", "desk");

        Map<String, Object> anonymous = chatService.reply(body, principal("u-1", "t-1"));
        Map<String, Object> desk = chatService.reply(body("内部运维 orchestrator 重启 节点 token"),
                new AuthenticatedPrincipal("u-2", "desk-user", "t-1", Collections.singletonList("desk")));

        assertThat(sourceIds(anonymous)).doesNotContain("internal-ops");
        assertThat(String.valueOf(anonymous.get("content"))).doesNotContain("内部运维笔记");
        /* positive control: a real desk principal does see the internal document */
        assertThat(sourceIds(desk)).contains("internal-ops");
    }

    @Test
    void stateUserIdIsReplacedByAuthenticatedUserOnDispatch() {
        WorkflowRun run = new WorkflowRun();
        run.setRunId("run-1");
        when(workflowService.createRun(eq("daily"), anyString(), any(), any())).thenReturn(run);
        Map<String, Object> body = body("盘前日报");
        body.put("workflowKey", "daily");
        Map<String, Object> state = new LinkedHashMap<String, Object>();
        state.put("userId", "victim");
        state.put("user_id", "victim");
        state.put("tenantId", "other-tenant");
        state.put("roles", Arrays.asList("admin"));
        state.put("horizon", "1d");
        body.put("state", state);

        chatService.reply(body, principal("u-1", "t-1"));

        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<Map<String, Object>> inputs = ArgumentCaptor.forClass((Class) Map.class);
        verify(workflowService).createRun(eq("daily"), anyString(), inputs.capture(), any());
        assertThat(inputs.getValue().get("userId")).isEqualTo("u-1");
        assertThat(inputs.getValue().get("tenantId")).isEqualTo("t-1");
        assertThat(inputs.getValue()).doesNotContainKeys("user_id", "roles");
        assertThat(inputs.getValue().get("horizon")).isEqualTo("1d");
    }

    @Test
    void stateUserIdIsReplacedInCopilotAgentState() {
        HelpIntent.Result copilot = HelpIntent.Result.empty();
        copilot.setDomain(HelpIntent.Domain.CHITCHAT);
        copilot.setRouteAction(HelpIntent.RouteAction.COPILOT);
        when(helpRoutingService.classifyExtended(anyString(), any())).thenReturn(copilot);
        Map<String, Object> body = body("你好");
        Map<String, Object> state = new LinkedHashMap<String, Object>();
        state.put("userId", "victim");
        body.put("state", state);

        chatService.reply(body, principal("u-1", "t-1"));

        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<Map<String, Object>> agentState = ArgumentCaptor.forClass((Class) Map.class);
        verify(langChainGateway).runAgent(any(), agentState.capture(), any(), any());
        assertThat(agentState.getValue().get("userId")).isEqualTo("u-1");
    }

    private static HelpIntent.Result knowledgeIntent() {
        HelpIntent.Result result = HelpIntent.Result.empty();
        result.setDomain(HelpIntent.Domain.PRODUCT_HELP);
        result.setRouteAction(HelpIntent.RouteAction.KNOWLEDGE_RAG);
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<String> sourceIds(Map<String, Object> response) {
        List<String> ids = new java.util.ArrayList<String>();
        Object refs = response.get("sourceRefs");
        if (refs instanceof List) {
            for (Object ref : (List<Object>) refs) {
                if (ref instanceof Map) {
                    ids.add(String.valueOf(((Map<String, Object>) ref).get("id")));
                }
            }
        }
        return ids;
    }

    private static AuthenticatedPrincipal principal(String userId, String tenantId) {
        return new AuthenticatedPrincipal(userId, "user-" + userId, tenantId, Collections.<String>emptyList());
    }

    private static Map<String, Object> body(String message) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("message", message);
        return body;
    }
}
