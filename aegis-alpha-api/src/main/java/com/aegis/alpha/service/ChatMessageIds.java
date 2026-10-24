package com.aegis.alpha.service;

import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * P4-11/P4-12: time-ordered chat message ids. chat_message.created_at has second precision and no
 * sequence column, so a user turn and its reply often tie; ordering by (created_at, message_id) is
 * stable for ids from here ({@code <13-digit millis><4-digit seq>-<uuid>}, 50 chars, fits VARCHAR(64)).
 * Legacy random UUID ids keep working. No migration needed.
 *
 * <p>D10c: deterministic tie-break for rows that share a created_at second. Chronological key:
 * {@code created_at}, then legacy (random UUID) rows before time-ordered rows, then — for legacy rows
 * only — role rank (user, assistant, other: a reply never precedes its question), then message_id
 * (unique, so the order is total). The same rule is used in SQL ({@code ChatMapper.findRecentMessages})
 * and re-applied in Java by {@link #NEWEST_FIRST}, so results do not depend on the database's tie order.
 */
public final class ChatMessageIds {

    private static final AtomicLong LAST = new AtomicLong();

    private ChatMessageIds() {
    }

    public static String next() {
        long now = System.currentTimeMillis() * 10000L;
        long value = LAST.updateAndGet(prev -> Math.max(prev + 1, now));
        return String.format("%017d-%s", value, UUID.randomUUID().toString().replace("-", "").substring(0, 32));
    }

    /** True for ids produced by {@link #next()} ({@code <17 digits>-<32 hex>}). */
    public static boolean isTimeOrdered(String messageId) {
        if (messageId == null || messageId.length() < 18 || messageId.charAt(17) != '-') {
            return false;
        }
        for (int i = 0; i < 17; i++) {
            if (!Character.isDigit(messageId.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    static int roleRank(String role) {
        if ("user".equals(role)) {
            return 0;
        }
        if ("assistant".equals(role)) {
            return 1;
        }
        return 2;
    }

    private static String text(Map<String, Object> row, String snake, String camel) {
        Object value = row == null ? null : (row.get(snake) != null ? row.get(snake) : row.get(camel));
        return value == null ? "" : String.valueOf(value);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compareCreated(Map<String, Object> a, Map<String, Object> b) {
        Object x = a.get("created_at") != null ? a.get("created_at") : a.get("createdAt");
        Object y = b.get("created_at") != null ? b.get("created_at") : b.get("createdAt");
        if (x == null || y == null) {
            return x == null ? (y == null ? 0 : -1) : 1;
        }
        if (x instanceof Comparable && x.getClass().isInstance(y)) {
            return ((Comparable) x).compareTo(y);
        }
        return String.valueOf(x).compareTo(String.valueOf(y));
    }

    /** Chronological order of chat_message rows (maps with message_id/role/created_at). */
    public static final Comparator<Map<String, Object>> CHRONOLOGICAL = (a, b) -> {
        int c = compareCreated(a, b);
        if (c != 0) {
            return c;
        }
        String ida = text(a, "message_id", "messageId");
        String idb = text(b, "message_id", "messageId");
        boolean ta = isTimeOrdered(ida);
        boolean tb = isTimeOrdered(idb);
        if (ta != tb) {
            return ta ? 1 : -1;
        }
        if (!ta) {
            c = Integer.compare(roleRank(text(a, "role", "role")), roleRank(text(b, "role", "role")));
            if (c != 0) {
                return c;
            }
        }
        return ida.compareTo(idb);
    };

    /** Newest first (the order of {@code findRecentMessages}). */
    public static final Comparator<Map<String, Object>> NEWEST_FIRST = CHRONOLOGICAL.reversed();

    /**
     * Sort newest-first in place when every row carries created_at (real chat_message rows);
     * otherwise keep the given (database) order unchanged.
     */
    public static void sortNewestFirst(java.util.List<Map<String, Object>> rows) {
        if (rows == null) {
            return;
        }
        for (Map<String, Object> row : rows) {
            if (row == null || (row.get("created_at") == null && row.get("createdAt") == null)) {
                return;
            }
        }
        rows.sort(NEWEST_FIRST);
    }
}
