package com.aegis.alpha.service;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * P4-11/P4-12: time-ordered chat message ids. chat_message.created_at has second precision and no
 * sequence column, so a user turn and its reply often tie; ordering by (created_at, message_id) is
 * stable for ids from here ({@code <13-digit millis><4-digit seq>-<uuid>}, 50 chars, fits VARCHAR(64)).
 * Legacy random UUID ids keep working (they just sort by created_at only). No migration needed.
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
}
