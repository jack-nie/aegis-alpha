package com.aegis.alpha.service;

import com.aegis.alpha.domain.WorkflowRun;
import com.aegis.alpha.domain.WorkflowRunEvent;
import com.aegis.alpha.mapper.ChatMapper;
import com.aegis.alpha.mapper.WorkflowMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * P4-11 (R6): when a run that chat messages were queued into reaches a terminal / paused state, the
 * answer to those queued messages is written back to the chat thread as an assistant message.
 *
 * <p>Bookkeeping lives in run events (DB), not in memory, so it survives restarts and works on any
 * API replica: {@code CHAT_MESSAGE_QUEUED {threadId, queueId, kind}} is recorded by the chat path,
 * {@code MESSAGE_DELIVERED} (already recorded from the stream) says which agent node took a message,
 * and {@code CHAT_QUEUE_RESULT_WRITTEN {threadId, queueIds}} makes the write-back idempotent.
 *
 * <p>chat_message has no provider column (no migration in this phase): the message is marked with a
 * text prefix and the run event carries {@code provider=agent-queue-result}.
 */
@Component
public class QueueResultWriteback {

    private static final Logger log = LoggerFactory.getLogger(QueueResultWriteback.class);

    public static final String CHAT_QUEUED_EVENT = "CHAT_MESSAGE_QUEUED";
    public static final String WRITTEN_EVENT = "CHAT_QUEUE_RESULT_WRITTEN";
    public static final String PROVIDER = "agent-queue-result";
    static final String MESSAGE_PREFIX = "\u3010\u6392\u961f\u6d88\u606f\u7ed3\u679c\u3011";
    private static final int SUMMARY_MAX_CHARS = 800;

    /** Run events after which queued chat messages get their answer written back. */
    static final Set<String> TRIGGERS = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
            "RUN_COMPLETED", "RUN_PAUSED", "RUN_APPROVED", "RUN_REJECTED", "RUN_CANCELLED", "RUN_FAILED")));

    private final WorkflowMapper mapper;
    private final ChatMapper chatMapper;
    private final ObjectMapper objectMapper;

    public QueueResultWriteback(WorkflowMapper mapper, ChatMapper chatMapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.chatMapper = chatMapper;
        this.objectMapper = objectMapper;
    }

    public static boolean isTrigger(String eventType) {
        return eventType != null && TRIGGERS.contains(eventType);
    }

    /** Never throws: a write-back problem must not break run completion. */
    public void onRunEvent(WorkflowRun run, String eventType, Object payload) {
        if (run == null || run.getRunId() == null || !isTrigger(eventType)) {
            return;
        }
        try {
            writeBack(run, eventType, payload);
        } catch (Exception ex) {
            log.warn("[QUEUE] chat write-back failed for run {}: {}", run.getRunId(), ex.getClass().getSimpleName());
        }
    }

    private void writeBack(WorkflowRun run, String eventType, Object payload) {
        List<WorkflowRunEvent> events = mapper.findRunEvents(run.getRunId());
        if (events == null || events.isEmpty()) {
            return;
        }
        Map<String, List<Map<String, Object>>> pendingByThread = new LinkedHashMap<String, List<Map<String, Object>>>();
        Set<String> written = new HashSet<String>();
        Map<String, String> deliveredTo = new LinkedHashMap<String, String>();
        List<Map<String, Object>> queued = new ArrayList<Map<String, Object>>();
        for (WorkflowRunEvent event : events) {
            Map<String, Object> data = parse(event.getPayloadJson());
            if (CHAT_QUEUED_EVENT.equals(event.getEventType())) {
                queued.add(data);
            } else if (WRITTEN_EVENT.equals(event.getEventType())) {
                Object ids = data.get("queueIds");
                if (ids instanceof List) {
                    for (Object id : (List<?>) ids) {
                        written.add(String.valueOf(id));
                    }
                }
            } else if ("MESSAGE_DELIVERED".equals(event.getEventType())) {
                String nodeId = str(data.get("nodeId"));
                Object messages = data.get("messages");
                if (messages instanceof List) {
                    for (Object m : (List<?>) messages) {
                        if (m instanceof Map) {
                            deliveredTo.put(str(((Map<?, ?>) m).get("id")), nodeId);
                        }
                    }
                }
            }
        }
        /* Sync runs do not stream queue events: use final_state.messageQueue.deliveries instead. */
        for (Map<String, Object> delivery : deliveries(payload, run.getResultJson())) {
            String id = str(delivery.get("id"));
            if (!id.isEmpty() && !deliveredTo.containsKey(id)) {
                deliveredTo.put(id, str(delivery.get("nodeId")));
            }
        }
        boolean paused = "RUN_PAUSED".equals(eventType);
        for (Map<String, Object> q : queued) {
            String queueId = str(q.get("queueId"));
            String threadId = str(q.get("threadId"));
            if (queueId.isEmpty() || threadId.isEmpty() || written.contains(queueId)) {
                continue;
            }
            if (paused && !deliveredTo.containsKey(queueId)) {
                continue; // the run may still deliver it after approval
            }
            List<Map<String, Object>> list = pendingByThread.get(threadId);
            if (list == null) {
                list = new ArrayList<Map<String, Object>>();
                pendingByThread.put(threadId, list);
            }
            list.add(q);
        }
        if (pendingByThread.isEmpty()) {
            return;
        }
        Map<String, Object> nodes = nodeOutputs(payload, run.getResultJson());
        int seq = 0;
        for (Map.Entry<String, List<Map<String, Object>>> entry : pendingByThread.entrySet()) {
            List<String> ids = new ArrayList<String>();
            StringBuilder text = new StringBuilder(MESSAGE_PREFIX)
                    .append("\u8fd0\u884c ").append(run.getRunId()).append(" ")
                    .append(statusLabel(eventType)).append("\u3002");
            for (Map<String, Object> q : entry.getValue()) {
                String queueId = str(q.get("queueId"));
                ids.add(queueId);
                String kind = "steer".equals(str(q.get("kind"))) ? "\u5f15\u5bfc\u6307\u4ee4" : "\u540e\u7eed\u6d88\u606f";
                String nodeId = deliveredTo.get(queueId);
                text.append("\n- \u4f60\u6392\u961f\u7684").append(kind);
                if (nodeId == null) {
                    text.append("\u672a\u9001\u8fbe\u667a\u80fd\u4f53\uff08\u8fd0\u884c\u5df2\u7ed3\u675f\uff09\uff0c\u8bf7\u91cd\u65b0\u53d1\u9001\u3002");
                    continue;
                }
                String summary = nodeSummary(nodes, nodeId);
                text.append(" \u7531 ").append(nodeId).append(" \u5904\u7406\uff1a")
                        .append(summary.isEmpty() ? "\uff08\u65e0\u6458\u8981\uff0c\u8bf7\u67e5\u770b\u8fd0\u884c\u8be6\u60c5\uff09" : summary);
            }
            chatMapper.insertMessage(ChatMessageIds.next(), entry.getKey(), "assistant", text.toString());
            Map<String, Object> marker = new LinkedHashMap<String, Object>();
            marker.put("threadId", entry.getKey());
            marker.put("queueIds", ids);
            marker.put("provider", PROVIDER);
            marker.put("trigger", eventType);
            insertEvent(run.getRunId(), WRITTEN_EVENT, "Queued chat message result written back.", marker, 1000100 + seq++);
        }
    }

    /** Records that a chat thread queued a message into the run (called by the chat path). */
    public void recordQueued(String runId, String threadId, String kind, Object queueId) {
        if (runId == null || threadId == null || queueId == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("threadId", threadId);
        payload.put("queueId", String.valueOf(queueId));
        payload.put("kind", kind);
        try {
            insertEvent(runId, CHAT_QUEUED_EVENT, "Chat message queued into the running workflow.", payload, 500000);
        } catch (Exception ex) {
            log.warn("[QUEUE] could not record chat queue bookkeeping for run {}: {}", runId, ex.getClass().getSimpleName());
        }
    }

    private void insertEvent(String runId, String type, String message, Object payload, int sortOrder) {
        WorkflowRunEvent event = new WorkflowRunEvent();
        event.setEventId(UUID.randomUUID().toString());
        event.setRunId(runId);
        event.setEventType(type);
        event.setStatus("INFO");
        event.setMessage(message);
        try {
            event.setPayloadJson(objectMapper.writeValueAsString(payload));
        } catch (Exception ex) {
            event.setPayloadJson("{}");
        }
        event.setCreatedAt(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        event.setSortOrder(sortOrder);
        mapper.insertRunEvent(event);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> deliveries(Object payload, String resultJson) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Object source : new Object[] {payload, parse(resultJson)}) {
            Map<String, Object> root = source instanceof Map ? (Map<String, Object>) source : Collections.<String, Object>emptyMap();
            List<Object> candidates = new ArrayList<Object>();
            candidates.add(root.get("messageQueue"));
            for (String key : new String[] {"final_state", "finalState"}) {
                if (root.get(key) instanceof Map) {
                    candidates.add(((Map<?, ?>) root.get(key)).get("messageQueue"));
                }
            }
            for (Object mq : candidates) {
                if (mq instanceof Map && ((Map<?, ?>) mq).get("deliveries") instanceof List) {
                    for (Object d : (List<?>) ((Map<?, ?>) mq).get("deliveries")) {
                        if (d instanceof Map) {
                            out.add((Map<String, Object>) d);
                        }
                    }
                }
            }
        }
        return out;
    }

    private static String statusLabel(String eventType) {
        if ("RUN_PAUSED".equals(eventType)) {
            return "\u5df2\u6682\u505c\uff08\u7b49\u5f85\u5ba1\u6279\uff09";
        }
        if ("RUN_CANCELLED".equals(eventType)) {
            return "\u5df2\u53d6\u6d88";
        }
        if ("RUN_FAILED".equals(eventType)) {
            return "\u5df2\u5931\u8d25";
        }
        if ("RUN_REJECTED".equals(eventType)) {
            return "\u5ba1\u6279\u5df2\u62d2\u7edd";
        }
        return "\u5df2\u5b8c\u6210";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> nodeOutputs(Object payload, String resultJson) {
        for (Object source : new Object[] {payload, parse(resultJson)}) {
            Map<String, Object> root = source instanceof Map ? (Map<String, Object>) source : parse(source instanceof String ? (String) source : null);
            for (String key : new String[] {"final_state", "finalState"}) {
                Object inner = root.get(key);
                if (inner instanceof Map && ((Map<?, ?>) inner).get("nodes") instanceof Map) {
                    return (Map<String, Object>) ((Map<?, ?>) inner).get("nodes");
                }
            }
            for (String key : new String[] {"nodes", "node_outputs", "nodeOutputs"}) {
                if (root.get(key) instanceof Map) {
                    return (Map<String, Object>) root.get(key);
                }
            }
        }
        return Collections.emptyMap();
    }

    private static String nodeSummary(Map<String, Object> nodes, String nodeId) {
        Object node = nodes.get(nodeId);
        if (!(node instanceof Map)) {
            return "";
        }
        Object summary = ((Map<?, ?>) node).get("summary");
        if (summary == null && ((Map<?, ?>) node).get("output") instanceof Map) {
            summary = ((Map<?, ?>) ((Map<?, ?>) node).get("output")).get("summary");
        }
        String text = summary == null ? "" : String.valueOf(summary).trim();
        return text.length() > SUMMARY_MAX_CHARS ? text.substring(0, SUMMARY_MAX_CHARS) + "\u2026" : text;
    }

    private Map<String, Object> parse(String json) {
        if (json == null || json.trim().isEmpty()) {
            return Collections.emptyMap();
        }
        try {
            Map<String, Object> map = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() { });
            return map == null ? Collections.<String, Object>emptyMap() : map;
        } catch (Exception ex) {
            return Collections.emptyMap();
        }
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
