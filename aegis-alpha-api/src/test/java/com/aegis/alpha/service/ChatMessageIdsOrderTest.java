package com.aegis.alpha.service;

import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** D10c: deterministic same-second ordering, including legacy random-UUID message ids. */
class ChatMessageIdsOrderTest {

    private static Map<String, Object> row(String id, String role, String created) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("message_id", id);
        row.put("role", role);
        row.put("created_at", Timestamp.valueOf(created));
        return row;
    }

    private static List<String> ids(List<Map<String, Object>> rows) {
        List<String> out = new ArrayList<String>();
        for (Map<String, Object> row : rows) {
            out.add(String.valueOf(row.get("message_id")));
        }
        return out;
    }

    @Test
    void recognisesTimeOrderedIds() {
        assertThat(ChatMessageIds.isTimeOrdered(ChatMessageIds.next())).isTrue();
        assertThat(ChatMessageIds.isTimeOrdered("f47ac10b-58cc-4372-a567-0e02b2c3d479")).isFalse();
        assertThat(ChatMessageIds.isTimeOrdered("12345678901234567x")).isFalse();
    }

    @Test
    void legacySameSecondReplyNeverPrecedesItsQuestion() {
        // The assistant id sorts before the user id lexically; the role rank decides.
        Map<String, Object> user = row("ffff0000-0000-0000-0000-000000000000", "user", "2025-01-01 10:00:00");
        Map<String, Object> reply = row("0000ffff-0000-0000-0000-000000000000", "assistant", "2025-01-01 10:00:00");
        Map<String, Object> older = row("aaaa0000-0000-0000-0000-000000000000", "assistant", "2025-01-01 09:59:59");
        for (List<Map<String, Object>> input : Arrays.asList(
                Arrays.asList(user, reply, older), Arrays.asList(older, reply, user), Arrays.asList(reply, older, user))) {
            List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>(input);
            ChatMessageIds.sortNewestFirst(rows);
            assertThat(ids(rows)).containsExactly(
                    "0000ffff-0000-0000-0000-000000000000",
                    "ffff0000-0000-0000-0000-000000000000",
                    "aaaa0000-0000-0000-0000-000000000000");
        }
    }

    @Test
    void timeOrderedIdsKeepInsertionOrderAndFollowLegacyRowsInTheSameSecond() {
        String a = ChatMessageIds.next();
        String b = ChatMessageIds.next();
        String c = ChatMessageIds.next();
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>(Arrays.asList(
                row(a, "user", "2025-01-01 10:00:00"),
                row("ffff0000-0000-0000-0000-000000000000", "assistant", "2025-01-01 10:00:00"),
                row(c, "user", "2025-01-01 10:00:00"),
                row(b, "assistant", "2025-01-01 10:00:00")));
        Collections.shuffle(rows);
        ChatMessageIds.sortNewestFirst(rows);
        assertThat(ids(rows)).containsExactly(c, b, a, "ffff0000-0000-0000-0000-000000000000");
    }

    @Test
    void rowsWithoutCreatedAtKeepDatabaseOrder() {
        Map<String, Object> first = new LinkedHashMap<String, Object>();
        first.put("message_id", "m3");
        first.put("role", "user");
        Map<String, Object> second = new LinkedHashMap<String, Object>();
        second.put("message_id", "m2");
        second.put("role", "assistant");
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>(Arrays.asList(first, second));
        ChatMessageIds.sortNewestFirst(rows);
        assertThat(ids(rows)).containsExactly("m3", "m2");
    }
}
