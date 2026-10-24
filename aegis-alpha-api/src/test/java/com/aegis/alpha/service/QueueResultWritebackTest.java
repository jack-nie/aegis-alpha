package com.aegis.alpha.service;

import com.aegis.alpha.domain.WorkflowRun;
import com.aegis.alpha.domain.WorkflowRunEvent;
import com.aegis.alpha.mapper.ChatMapper;
import com.aegis.alpha.mapper.WorkflowMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** P4-11: answers to queued chat messages are written back to the chat thread when the run ends. */
class QueueResultWritebackTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private WorkflowMapper mapper;
    private ChatMapper chatMapper;
    private QueueResultWriteback writeback;
    private final List<WorkflowRunEvent> events = new ArrayList<WorkflowRunEvent>();

    @BeforeEach
    void setUp() {
        mapper = mock(WorkflowMapper.class);
        chatMapper = mock(ChatMapper.class);
        when(mapper.findRunEvents("run-1")).thenAnswer(inv -> new ArrayList<WorkflowRunEvent>(events));
        doAnswer(inv -> {
            events.add(inv.getArgument(0));
            return null;
        }).when(mapper).insertRunEvent(any(WorkflowRunEvent.class));
        writeback = new QueueResultWriteback(mapper, chatMapper, objectMapper);
    }

    private WorkflowRun run() {
        WorkflowRun run = new WorkflowRun();
        run.setRunId("run-1");
        return run;
    }

    private void event(String type, Object payload) throws Exception {
        WorkflowRunEvent e = new WorkflowRunEvent();
        e.setRunId("run-1");
        e.setEventType(type);
        e.setPayloadJson(objectMapper.writeValueAsString(payload));
        events.add(e);
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private Map<String, Object> completedPayload() {
        return map("final_state", map("nodes", map("agent", map("summary", "MSFT 市盈率约 35 倍"))));
    }

    @Test
    void completedRunWritesTheFollowUpAnswerToTheThreadOnce() throws Exception {
        writeback.recordQueued("run-1", "thread-1", "follow_up", "q-1");
        event("MESSAGE_DELIVERED", map("kind", "follow_up", "nodeId", "agent",
                "messages", Collections.singletonList(map("id", "q-1"))));

        writeback.onRunEvent(run(), "RUN_COMPLETED", completedPayload());
        writeback.onRunEvent(run(), "RUN_COMPLETED", completedPayload());

        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(chatMapper, times(1)).insertMessage(anyString(), eq("thread-1"), eq("assistant"), content.capture());
        assertThat(content.getValue()).startsWith(QueueResultWriteback.MESSAGE_PREFIX)
                .contains("run-1").contains("agent").contains("MSFT 市盈率约 35 倍");
        WorkflowRunEvent marker = events.get(events.size() - 1);
        assertThat(marker.getEventType()).isEqualTo(QueueResultWriteback.WRITTEN_EVENT);
        assertThat(marker.getPayloadJson()).contains("agent-queue-result").contains("q-1");
    }

    @Test
    void syncRunUsesMessageQueueDeliveriesFromResultJson() throws Exception {
        writeback.recordQueued("run-1", "thread-1", "steer", "q-2");
        WorkflowRun run = run();
        run.setResultJson(objectMapper.writeValueAsString(map(
                "nodes", map("agent", map("summary", "只看风险：波动率偏高")),
                "messageQueue", map("deliveries", Collections.singletonList(
                        map("id", "q-2", "kind", "steer", "nodeId", "agent"))))));

        writeback.onRunEvent(run, "RUN_COMPLETED", null);

        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(chatMapper).insertMessage(anyString(), eq("thread-1"), eq("assistant"), content.capture());
        assertThat(content.getValue()).contains("引导指令").contains("只看风险：波动率偏高");
    }

    @Test
    void pausedRunWaitsForUndeliveredMessagesAndCompletionReportsThem() throws Exception {
        writeback.recordQueued("run-1", "thread-1", "follow_up", "q-3");

        writeback.onRunEvent(run(), "RUN_PAUSED", null);
        verify(chatMapper, never()).insertMessage(anyString(), anyString(), anyString(), anyString());

        writeback.onRunEvent(run(), "RUN_APPROVED", completedPayload());
        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(chatMapper).insertMessage(anyString(), eq("thread-1"), eq("assistant"), content.capture());
        assertThat(content.getValue()).contains("未送达");
    }

    @Test
    void runsWithoutQueuedChatMessagesAndNonTriggersAreIgnored() throws Exception {
        writeback.onRunEvent(run(), "RUN_COMPLETED", completedPayload());
        writeback.recordQueued("run-1", "thread-1", "follow_up", "q-4");
        writeback.onRunEvent(run(), "NODE_COMPLETED", completedPayload());
        verify(chatMapper, never()).insertMessage(anyString(), anyString(), anyString(), anyString());
        assertThat(QueueResultWriteback.isTrigger("RUN_DEGRADED")).isFalse();
        assertThat(Arrays.asList("RUN_COMPLETED", "RUN_PAUSED", "RUN_CANCELLED"))
                .allMatch(QueueResultWriteback::isTrigger);
    }

    @Test
    void writebackFailureNeverPropagates() {
        when(mapper.findRunEvents("run-1")).thenThrow(new IllegalStateException("db down"));
        writeback.onRunEvent(run(), "RUN_COMPLETED", completedPayload());
        verify(chatMapper, never()).insertMessage(anyString(), anyString(), anyString(), anyString());
    }
}
