// P4-11: chat → running-workflow message queue (delivery selector, queue badge, written-back answers).
// Pure helpers so they can be tested with plain node (scripts/copilot-queue-delivery.test.mjs).

export const DELIVERY_OPTIONS = [
  { value: "auto", label: "自动" },
  { value: "steer", label: "引导（steer）" },
  { value: "follow_up", label: "后续（follow-up）" },
  { value: "new", label: "新任务" },
];

/** Same prefix as QueueResultWriteback.MESSAGE_PREFIX on the API side. */
export const QUEUE_RESULT_PREFIX = "【排队消息结果】";

export function buildChatPayload({ text, pageContext, threadId, delivery }) {
  const payload = { message: text, pageContext };
  if (threadId) payload.threadId = threadId;
  if (delivery && delivery !== "auto") payload.delivery = delivery;
  return payload;
}

/** Badge text for a reply that was queued into (or rejected by) the running workflow, else null. */
export function queueBadge(result) {
  if (!result || result.provider !== "agent-queue") return null;
  if (result.queued) {
    const kind = result.queueKind === "steer" ? "引导" : "后续";
    const pos = result.queuePosition != null ? ` · 第 ${result.queuePosition} 条` : "";
    return `已排队 · ${kind}${pos} · 运行结束后结果写回此对话`;
  }
  return `未排队${result.queueError ? `（${result.queueError}）` : ""}`;
}

/** Written-back answers not shown yet (thread messages are chronological). */
export function pickQueueResults(threadMessages, seenIds) {
  const seen = seenIds instanceof Set ? seenIds : new Set(seenIds || []);
  return (Array.isArray(threadMessages) ? threadMessages : []).filter(
    (m) =>
      m &&
      m.role === "assistant" &&
      typeof m.content === "string" &&
      m.content.startsWith(QUEUE_RESULT_PREFIX) &&
      !seen.has(m.messageId),
  );
}
