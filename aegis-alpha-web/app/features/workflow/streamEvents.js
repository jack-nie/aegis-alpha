// P4-10: client-side reducer for orchestrator event protocol v2 (forwarded unchanged by the API).
// Pure function so it can be unit-tested with plain node (scripts/workflow-stream-events.test.mjs).

export const STREAM_V2_EVENTS = [
  "node_start",
  "message_delta",
  "token",
  "tool_execution_start",
  "tool_execution_end",
  "usage",
];

export const emptyLiveStream = () => ({ text: {}, attempt: {}, final: null, tools: [], usage: {} });

export function isStreamV2Event(name) {
  return STREAM_V2_EVENTS.includes(name);
}

export function applyStreamEvent(state, name, data) {
  const prev = state || emptyLiveStream();
  const payload = data || {};
  const nodeId = payload.nodeId || "";
  switch (name) {
    case "node_start":
      return { ...prev, text: { ...prev.text, [nodeId]: "" } };
    case "message_delta": {
      if (payload.final) {
        // The gated aggregate answer: replaces any partial text; withheld means the gate failed.
        return {
          ...prev,
          text: { ...prev.text, [nodeId]: String(payload.delta || "") },
          final: { nodeId, text: String(payload.delta || ""), withheld: Boolean(payload.withheld) },
        };
      }
      const key = `${nodeId}:${payload.turn || 1}`;
      const attempt = payload.attempt || 1;
      // A retried attempt restarts the turn's text (the failed attempt's partial text is dropped).
      const restart = prev.attempt[key] !== undefined && prev.attempt[key] !== attempt;
      const base = restart ? "" : prev.text[nodeId] || "";
      return {
        ...prev,
        attempt: { ...prev.attempt, [key]: attempt },
        text: { ...prev.text, [nodeId]: base + String(payload.delta || "") },
      };
    }
    case "token":
      // Deprecated alias of the final message_delta; ignored when the v2 event was already seen.
      return prev.final ? prev : { ...prev, final: { nodeId, text: String(payload.content || ""), withheld: false } };
    case "tool_execution_start":
      return {
        ...prev,
        tools: [
          ...prev.tools,
          { toolCallId: payload.toolCallId, toolName: payload.toolName, nodeId, status: "running" },
        ],
      };
    case "tool_execution_end": {
      let found = false;
      const tools = prev.tools.map((tool) => {
        if (tool.toolCallId !== payload.toolCallId) return tool;
        found = true;
        return {
          ...tool,
          status: payload.isError ? "error" : "done",
          isError: Boolean(payload.isError),
          durationMs: payload.durationMs,
          truncated: Boolean(payload.truncated),
        };
      });
      if (!found) {
        tools.push({
          toolCallId: payload.toolCallId,
          toolName: payload.toolName,
          nodeId,
          status: payload.isError ? "error" : "done",
          isError: Boolean(payload.isError),
          durationMs: payload.durationMs,
        });
      }
      return { ...prev, tools };
    }
    case "usage":
      return { ...prev, usage: { ...prev.usage, [nodeId]: payload.usage || {} } };
    default:
      return prev;
  }
}
