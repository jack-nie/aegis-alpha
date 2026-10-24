package com.aegis.alpha.service;

import com.aegis.alpha.mapper.ChatMapper;
import com.aegis.alpha.security.AuthenticatedPrincipal;
import com.aegis.alpha.service.help.HelpRoutingService;
import com.aegis.alpha.service.help.HelpService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** P4-11: reading a thread's messages (queued-message answers) is owner-only and chronological. */
class ChatThreadMessagesTest {

    private ChatMapper mapper;
    private ChatService service;
    private final AuthenticatedPrincipal alice = new AuthenticatedPrincipal(
            "u-1", "alice", "tenant-1", Collections.<String>emptyList());
    private final AuthenticatedPrincipal bob = new AuthenticatedPrincipal(
            "u-2", "bob", "tenant-1", Collections.<String>emptyList());

    @BeforeEach
    void setUp() {
        mapper = mock(ChatMapper.class);
        service = new ChatService(mapper, mock(LangChainGateway.class), mock(MarketDataService.class),
                mock(WorkflowService.class), mock(HelpRoutingService.class), mock(HelpService.class));
        Map<String, Object> owner = new LinkedHashMap<String, Object>();
        owner.put("user_id", "u-1");
        owner.put("tenant_id", "tenant-1");
        when(mapper.findThreadOwner("t-1")).thenReturn(owner);
    }

    private static Map<String, Object> row(String id, String role, String content) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("message_id", id);
        m.put("role", role);
        m.put("content", content);
        m.put("created_at", "2026-10-24 10:00:00");
        return m;
    }

    @Test
    void ownerGetsMessagesInChronologicalOrderWithBoundedLimit() {
        when(mapper.findRecentMessages("t-1", ChatService.THREAD_MESSAGES_MAX)).thenReturn(Arrays.asList(
                row("m3", "assistant", "【排队消息结果】..."), row("m2", "assistant", "已排队"), row("m1", "user", "再看看")));

        List<Map<String, Object>> out = service.threadMessages("t-1", alice, 10000);

        assertThat(out).extracting(m -> m.get("messageId")).containsExactly("m1", "m2", "m3");
        assertThat(out.get(0)).containsEntry("role", "user").containsEntry("content", "再看看");
    }

    @Test
    void otherUserIsRejectedAndUnknownThreadIsNull() {
        assertThatThrownBy(() -> service.threadMessages("t-1", bob, 10)).isInstanceOf(SecurityException.class);
        when(mapper.findThreadOwner("missing")).thenReturn(null);
        assertThat(service.threadMessages("missing", alice, 10)).isNull();
        verify(mapper, never()).findRecentMessages(eq("t-1"), anyInt());
    }

    @Test
    void messageIdsAreTimeOrderedAndFitTheColumn() {
        Set<String> seen = new HashSet<String>();
        String prev = "";
        for (int i = 0; i < 500; i++) {
            String id = ChatMessageIds.next();
            assertThat(id.length()).isLessThanOrEqualTo(64);
            assertThat(id.compareTo(prev)).isGreaterThan(0);
            assertThat(seen.add(id)).isTrue();
            prev = id;
        }
    }
}
