import assert from "node:assert/strict";
import {
  buildChatPayload,
  pickQueueResults,
  queueBadge,
  QUEUE_RESULT_PREFIX,
} from "../app/features/copilot/queueDelivery.js";

assert.deepEqual(buildChatPayload({ text: "hi", pageContext: {}, threadId: null, delivery: "auto" }), {
  message: "hi",
  pageContext: {},
});
assert.deepEqual(buildChatPayload({ text: "hi", pageContext: {}, threadId: "t-1", delivery: "steer" }), {
  message: "hi",
  pageContext: {},
  threadId: "t-1",
  delivery: "steer",
});

assert.equal(queueBadge({ provider: "workflow-router" }), null);
assert.match(queueBadge({ provider: "agent-queue", queued: true, queueKind: "steer", queuePosition: 1 }), /已排队 · 引导 · 第 1 条/);
assert.equal(queueBadge({ provider: "agent-queue", queued: false, queueError: "QUEUE_FULL" }), "未排队（QUEUE_FULL）");

const msgs = [
  { messageId: "m1", role: "user", content: "再看看" },
  { messageId: "m2", role: "assistant", content: `${QUEUE_RESULT_PREFIX}运行 r 已完成。` },
  { messageId: "m3", role: "assistant", content: `${QUEUE_RESULT_PREFIX}运行 r 已完成。2` },
];
assert.deepEqual(pickQueueResults(msgs, new Set(["m2"])).map((m) => m.messageId), ["m3"]);
assert.deepEqual(pickQueueResults(null, []), []);
console.log("copilot-queue-delivery: ok");
