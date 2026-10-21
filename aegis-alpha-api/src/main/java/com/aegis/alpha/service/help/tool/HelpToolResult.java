package com.aegis.alpha.service.help.tool;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Outcome of a single {@link HelpToolGateway#execute} call.
 */
public final class HelpToolResult {
    private final boolean ok;
    private final String tool;
    private final Map<String, Object> data;
    private final String error;
    private final int attempts;
    private final long latencyMs;
    private final boolean circuitOpen;

    private HelpToolResult(boolean ok, String tool, Map<String, Object> data, String error,
                           int attempts, long latencyMs, boolean circuitOpen) {
        this.ok = ok;
        this.tool = tool;
        this.data = data == null ? Collections.<String, Object>emptyMap() : data;
        this.error = error;
        this.attempts = attempts;
        this.latencyMs = latencyMs;
        this.circuitOpen = circuitOpen;
    }

    public static HelpToolResult success(String tool, Map<String, Object> data, int attempts, long latencyMs) {
        return new HelpToolResult(true, tool, data, null, attempts, latencyMs, false);
    }

    public static HelpToolResult failure(String tool, String error, int attempts, long latencyMs) {
        return new HelpToolResult(false, tool, null, error, attempts, latencyMs, false);
    }

    public static HelpToolResult rejected(String tool, String error, long latencyMs) {
        return new HelpToolResult(false, tool, null, error, 0, latencyMs, false);
    }

    public static HelpToolResult circuitOpen(String tool, long latencyMs) {
        return new HelpToolResult(false, tool, null, HelpToolGateway.ERROR_CIRCUIT_OPEN, 0, latencyMs, true);
    }

    public boolean isOk() {
        return ok;
    }

    public String getTool() {
        return tool;
    }

    public Map<String, Object> getData() {
        return data;
    }

    public String getError() {
        return error;
    }

    public int getAttempts() {
        return attempts;
    }

    public long getLatencyMs() {
        return latencyMs;
    }

    public boolean isCircuitOpen() {
        return circuitOpen;
    }

    /** Flat representation for chat/tool-gather consumers. */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("ok", ok);
        map.put("tool", tool);
        map.put("data", data);
        map.put("error", error);
        map.put("attempts", attempts);
        map.put("latencyMs", latencyMs);
        map.put("circuitOpen", circuitOpen);
        return map;
    }

    @Override
    public String toString() {
        return "HelpToolResult{ok=" + ok + ", tool='" + tool + "', error='" + error
                + "', attempts=" + attempts + ", latencyMs=" + latencyMs
                + ", circuitOpen=" + circuitOpen + "}";
    }
}
