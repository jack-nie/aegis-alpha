package com.aegis.alpha.service.help.tool;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable descriptor of a Help agent tool exposed through {@link HelpToolGateway}.
 *
 * <p>Only read-only tools may be published by the production {@link HelpToolCatalog};
 * the gateway rejects any definition whose {@code readOnly} flag is {@code false}
 * before an action is invoked.</p>
 */
public final class HelpToolDefinition {
    private final String name;
    private final String description;
    private final boolean readOnly;
    private final long timeoutMs;
    private final int maxRetries;
    private final String category;

    public HelpToolDefinition(String name, String description, boolean readOnly, long timeoutMs,
                              int maxRetries, String category) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("help tool name is required");
        }
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("help tool timeoutMs must be positive");
        }
        if (maxRetries < 0) {
            throw new IllegalArgumentException("help tool maxRetries must not be negative");
        }
        this.name = name.trim();
        this.description = description == null ? "" : description;
        this.readOnly = readOnly;
        this.timeoutMs = timeoutMs;
        this.maxRetries = maxRetries;
        this.category = category == null || category.trim().isEmpty() ? "general" : category.trim();
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public String getCategory() {
        return category;
    }

    /** Flat representation used by {@code GET /_backend/help/tools}. */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("name", name);
        map.put("description", description);
        map.put("readOnly", readOnly);
        map.put("timeoutMs", timeoutMs);
        map.put("maxRetries", maxRetries);
        map.put("category", category);
        return map;
    }

    @Override
    public String toString() {
        return "HelpToolDefinition{name='" + name + "', readOnly=" + readOnly
                + ", timeoutMs=" + timeoutMs + ", maxRetries=" + maxRetries
                + ", category='" + category + "'}";
    }
}
