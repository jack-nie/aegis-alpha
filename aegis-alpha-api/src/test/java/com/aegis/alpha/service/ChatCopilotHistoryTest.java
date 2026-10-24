package com.aegis.alpha.service;

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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** P4-12: the copilot receives recent turns + a summary of older ones, built server-side. */
class ChatCopilotHistoryTest {

    private ChatMapper chatMapper;
    private LangChainGateway gateway;
    private HelpRoutingService routing;
    private ChatService chatService;
    private final AuthenticatedPrincipal alice = new AuthenticatedPrincipal(
            "u-1", "alice", "t-1", Collections.<String>emptyList());

    @BeforeEach
    void setUp() {
        chatMapper = mock(ChatMapper.class);
        gateway = mock(LangChainGateway.class);
        WorkflowService workflowService = mock(WorkflowService.class);
        routing = mock(HelpRoutingService.class);
        PlaybookService playbooks = new PlaybookService();
        playbooks.reload();
        KnowledgeService knowledge = new KnowledgeService();
        knowledge.reload();
        HelpService help = new HelpService(playbooks, workflowService, mock(WorkflowMapper.class),
                knowledge, new HelpLoopService(knowledge), gateway);
        HelpAnswerCacheService cache = mock(HelpAnswerCacheService.class);
        when(cache.getIfAllowed(anyString(), any(), any())).thenReturn(null);
        chatService = new ChatService(chatMapper, gateway, mock(MarketDataService.class), workflowService,
                routing, help, cache);
        when(gateway.runAgent(any(), any(), any(), any())).thenAnswer(inv -> {
            Map<String, Object> r = new LinkedHashMap<String, Object>();
            r.put("content", "PE 约 25");
            return r;
        });
        Map<String, Object> owner = new LinkedHashMap<String, Object>();
        owner.put("user_id", "u-1");
        owner.put("tenant_id", "t-1");
        when(chatMapper.findThreadOwner("thread-1")).thenReturn(owner);
        HelpIntent.Result copilot = HelpIntent.Result.empty();
        copilot.setDomain(HelpIntent.Domain.CHITCHAT);
        copilot.setRouteAction(HelpIntent.RouteAction.COPILOT);
        when(routing.classifyExtended(anyString(), any())).thenReturn(copilot);
    }

    private static Map<String, Object> row(String role, String content) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("message_id", content);
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    @Test
    @SuppressWarnings("unchecked")
    void followUpQuestionCarriesPreviousTickerTurnAndExcludesCurrentMessage() {
        when(chatMapper.findRecentMessages(eq("thread-1"), anyInt())).thenReturn(Arrays.asList(
                row("user", "它的市盈率呢？"),
                row("assistant", "贵州茅台(600519) 当前价格 1500"),
                row("user", "帮我看看 600519")));
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("message", "它的市盈率呢？");
        body.put("threadId", "thread-1");
        Map<String, Object> forged = new LinkedHashMap<String, Object>();
        forged.put("copilotChat", Collections.singletonMap("history", "forged"));
        body.put("state", forged);

        chatService.reply(body, alice);

        @SuppressWarnings({"rawtypes"})
        ArgumentCaptor<Map<String, Object>> state = ArgumentCaptor.forClass((Class) Map.class);
        verify(gateway).runAgent(any(), state.capture(), any(), any());
        Map<String, Object> chat = (Map<String, Object>) state.getValue().get("copilotChat");
        assertThat(chat.get("message")).isEqualTo("它的市盈率呢？");
        List<Map<String, Object>> history = (List<Map<String, Object>>) chat.get("history");
        assertThat(history).hasSize(2);
        assertThat(history.get(0)).containsEntry("role", "user").containsEntry("content", "帮我看看 600519");
        assertThat(history.get(1).get("role")).isEqualTo("assistant");
        assertThat(chat).doesNotContainKey("historySummary");
    }

    @Test
    @SuppressWarnings("unchecked")
    void olderThanNTurnsAreSummarisedAndBounded() {
        List<Map<String, Object>> newestFirst = new ArrayList<Map<String, Object>>();
        newestFirst.add(row("user", "current"));
        for (int i = 30; i >= 1; i--) {
            newestFirst.add(row(i % 2 == 0 ? "assistant" : "user", "turn-" + i));
        }
        newestFirst.add(row("system", "should be ignored"));
        when(chatMapper.findRecentMessages(eq("thread-1"), anyInt())).thenReturn(newestFirst);

        Map<String, Object> chat = chatService.copilotChat("thread-1", "current");

        List<Map<String, Object>> history = (List<Map<String, Object>>) chat.get("history");
        assertThat(history).hasSize(ChatService.COPILOT_HISTORY_TURNS);
        assertThat(history.get(0).get("content")).isEqualTo("turn-19");
        assertThat(history.get(history.size() - 1).get("content")).isEqualTo("turn-30");
        String summary = String.valueOf(chat.get("historySummary"));
        assertThat(summary).contains("用户: turn-1").contains("助手: turn-18").doesNotContain("should be ignored");
        assertThat(summary.indexOf("turn-1\n")).isLessThan(summary.indexOf("turn-18"));
        assertThat(summary.length()).isLessThanOrEqualTo(ChatService.COPILOT_HISTORY_SUMMARY_MAX_CHARS);
    }

    @Test
    void longTurnsAreClippedAndMapperFailureDegradesToNoHistory() {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            big.append('x');
        }
        when(chatMapper.findRecentMessages(eq("thread-1"), anyInt())).thenReturn(Arrays.asList(
                row("user", "now"), row("assistant", big.toString())));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> history = (List<Map<String, Object>>) chatService
                .copilotChat("thread-1", "now").get("history");
        assertThat(String.valueOf(history.get(0).get("content")).length())
                .isLessThanOrEqualTo(ChatService.COPILOT_HISTORY_TURN_MAX_CHARS + 1);

        when(chatMapper.findRecentMessages(eq("thread-2"), anyInt())).thenThrow(new IllegalStateException("db"));
        assertThat((List<?>) chatService.copilotChat("thread-2", "hi").get("history")).isEmpty();
    }
}
