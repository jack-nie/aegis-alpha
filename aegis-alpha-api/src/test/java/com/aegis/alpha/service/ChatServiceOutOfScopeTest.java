package com.aegis.alpha.service;

import com.aegis.alpha.domain.WorkflowDefinition;
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

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** P2-7: unrelated requests get a polite decline and never reach the Copilot LLM. */
class ChatServiceOutOfScopeTest {
    private LangChainGateway gateway;
    private HelpRoutingService routing;
    private ChatService chatService;
    private final AuthenticatedPrincipal user =
            new AuthenticatedPrincipal("u-1", "alice", "tenant-1", Collections.singletonList("research_user"));

    @BeforeEach
    void setUp() {
        WorkflowMapper workflowMapper = mock(WorkflowMapper.class);
        WorkflowDefinition deepDive = new WorkflowDefinition();
        deepDive.setWorkflowKey("deep_dive");
        deepDive.setName("Deep Dive");
        deepDive.setTriggerKeywords("深度分析");
        when(workflowMapper.findDefinitions()).thenReturn(Arrays.asList(deepDive));
        gateway = mock(LangChainGateway.class);
        when(gateway.runAgent(any(), any(), any(), any())).thenReturn(new LinkedHashMap<String, Object>());
        PlaybookService playbooks = new PlaybookService();
        playbooks.reload();
        KnowledgeService knowledge = new KnowledgeService();
        knowledge.reload();
        WorkflowService workflowService = new WorkflowService(workflowMapper, mock(AgentMapper.class), new ObjectMapper(),
                gateway, mock(CacheService.class), mock(BacktestService.class), mock(AgentTraceService.class),
                new WorkflowValidationService(), mock(TokenService.class), false);
        HelpService help = new HelpService(playbooks, workflowService, workflowMapper, knowledge,
                new HelpLoopService(knowledge), gateway);
        routing = new HelpRoutingService(new IntentRouterService(workflowMapper, gateway), playbooks);
        HelpAnswerCacheService cache = mock(HelpAnswerCacheService.class);
        when(cache.getIfAllowed(anyString(), any(), any())).thenReturn(null);
        chatService = new ChatService(mock(ChatMapper.class), gateway, mock(MarketDataService.class), workflowService,
                routing, help, cache);
    }

    @Test
    void poemRequestIsDeclinedWithoutCopilotLlm() {
        when(gateway.classifyIntent(any(), any())).thenThrow(new IllegalStateException("llm off"));
        Map<String, Object> body = new HashMap<String, Object>();
        body.put("message", "帮我写首诗");

        Map<String, Object> response = chatService.reply(body, user);

        assertThat(response.get("domain")).isEqualTo("OUT_OF_SCOPE");
        assertThat(response.get("provider")).isEqualTo("help-out-of-scope");
        assertThat(String.valueOf(response.get("content"))).contains("超出了我的服务范围");
        verify(gateway, never()).runAgent(any(), any(), any(), any());
        verify(gateway, never()).classifyIntent(any(), any());
    }

    @Test
    void classifierCanReturnOutOfScope() {
        Map<String, Object> llm = new HashMap<String, Object>();
        llm.put("domain", "OUT_OF_SCOPE");
        llm.put("confidence", 0.9);
        when(gateway.classifyIntent(any(), any())).thenReturn(llm);

        HelpIntent.Result result = routing.classifyExtended("帮我把这段家书润色得更感人一些", null);

        assertThat(result.getDomain()).isEqualTo(HelpIntent.Domain.OUT_OF_SCOPE);
        assertThat(result.getRouteAction()).isEqualTo(HelpIntent.RouteAction.DECLINE);
        assertThat(result.isCacheable()).isFalse();
    }

    @Test
    void researchPhrasingIsNotOutOfScope() {
        when(gateway.classifyIntent(any(), any())).thenThrow(new IllegalStateException("llm off"));
        HelpIntent.Result result = routing.classifyExtended("深度分析 NVDA 并写一首诗总结", null);
        assertThat(result.getDomain()).isNotEqualTo(HelpIntent.Domain.OUT_OF_SCOPE);
    }
}
