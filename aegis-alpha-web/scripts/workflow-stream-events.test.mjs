import assert from "node:assert/strict";
import { applyStreamEvent, emptyLiveStream, isStreamV2Event } from "../app/features/workflow/streamEvents.js";

let s = emptyLiveStream();
s = applyStreamEvent(s, "node_start", { nodeId: "agent", turn: 1 });
s = applyStreamEvent(s, "message_delta", { nodeId: "agent", turn: 1, attempt: 1, delta: "Hel", final: false });
s = applyStreamEvent(s, "message_delta", { nodeId: "agent", turn: 1, attempt: 1, delta: "lo", final: false });
assert.equal(s.text.agent, "Hello");
s = applyStreamEvent(s, "message_delta", { nodeId: "agent", turn: 1, attempt: 2, delta: "Retry", final: false });
assert.equal(s.text.agent, "Retry", "a new attempt drops the failed attempt's partial text");

s = applyStreamEvent(s, "tool_execution_start", { toolCallId: "c1", toolName: "get_stock_quote", nodeId: "agent" });
assert.equal(s.tools[0].status, "running");
s = applyStreamEvent(s, "tool_execution_end", { toolCallId: "c1", toolName: "get_stock_quote", nodeId: "agent", isError: true, durationMs: 12 });
assert.deepEqual([s.tools.length, s.tools[0].status, s.tools[0].isError], [1, "error", true]);

s = applyStreamEvent(s, "usage", { nodeId: "agent", scope: "node", usage: { totalTokens: 42 } });
assert.equal(s.usage.agent.totalTokens, 42);

s = applyStreamEvent(s, "message_delta", { nodeId: "aggregate", delta: "[INSUFFICIENT_DATA] r", final: true, gatePassed: false, withheld: true });
assert.deepEqual(s.final, { nodeId: "aggregate", text: "[INSUFFICIENT_DATA] r", withheld: true });
s = applyStreamEvent(s, "token", { nodeId: "aggregate", content: "[INSUFFICIENT_DATA] r" });
assert.equal(s.final.withheld, true, "token alias does not override the v2 final event");

assert.equal(isStreamV2Event("tool_execution_end"), true);
assert.equal(isStreamV2Event("node_update"), false);
console.log("workflow-stream-events: ok");
