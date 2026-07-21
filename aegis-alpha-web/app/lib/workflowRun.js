/** Max wait for chat-triggered workflow completion (multi-node LLM can exceed 90s). */
export const WORKFLOW_POLL_TIMEOUT_MS = 300000;
export const WORKFLOW_POLL_INTERVAL_MS = 2000;

export function parseRunResultJson(raw) {
  if (raw === null || raw === undefined || raw === "") return null;
  if (typeof raw === "object") return raw;
  if (typeof raw !== "string") return null;
  try {
    return JSON.parse(raw);
  } catch {
    return null;
  }
}

/** Build user-visible summary after polling a chat-routed workflow run. */
export function summarizeWorkflowRunResult(run, chatResult, runResult) {
  const status = String(run?.status || "UNKNOWN").toUpperCase();
  const runId = chatResult?.runId || run?.runId || "";
  const workflowKey = chatResult?.workflowKey || run?.workflowKey || "";
  const sep = String.fromCharCode(10);

  if (status === "RUNNING" || status === "QUEUED") {
    return (
      `工作流 [${workflowKey}] 仍在执行中（已超过等待上限）。` +
      sep +
      `运行 ID: ${runId}` +
      sep +
      `请打开「运行中心」查看进度与节点输出：/runs/${runId}`
    );
  }
  if (status === "FAILED" || status === "CANCELLED") {
    return (
      `工作流 [${workflowKey}] ${status === "FAILED" ? "执行失败" : "已取消"}。` +
      sep +
      `原因: ${run?.errorMessage || run?.error_message || "未知"}` +
      sep +
      `运行 ID: ${runId}`
    );
  }

  if (runResult && typeof runResult === "object") {
    const rec = runResult.recommendation || runResult["finance.stock_recommendation_aggregate"] || runResult.aggregate;
    if (rec && typeof rec === "object") {
      let text =
        rec.summary ||
        rec.content ||
        (rec.data && typeof rec.data === "object" && (rec.data.summary || rec.data.content)) ||
        "";
      if (!text && rec.data && typeof rec.data === "object") {
        const parts = Object.values(rec.data)
          .filter((value) => typeof value === "string" && value.trim())
          .join(sep);
        if (parts) text = parts;
      }
      if (text && String(text).trim()) {
        return String(text).trim();
      }
    }

    const nodeEntries = Object.entries(runResult)
      .filter(([, value]) => value && typeof value === "object" && (value.nodeId || value.handler || value.summary || value.content))
      .map(([key, value]) => {
        const label = value.nodeName || value.node_name || value.handler || key;
        const summary =
          value.summary ||
          value.content ||
          (value.data && value.data.summary) ||
          (value.data && value.data.content) ||
          "";
        return {
          label,
          summary: String(summary || "").trim(),
          status: value.status || (value.ok === false ? "error" : "ok"),
        };
      })
      .filter((node) => node.summary);

    if (nodeEntries.length > 0) {
      return nodeEntries.map((node) => `**${node.label}** (${node.status}): ${node.summary}`).join(sep + sep);
    }

    const nodeStatuses = Object.entries(runResult)
      .filter(([, value]) => value && typeof value === "object" && (value.nodeId || value.handler))
      .map(([, value]) => `- ${value.nodeName || value.nodeId || value.handler}: ${value.status || "done"}`)
      .join(sep);
    if (nodeStatuses) {
      return `工作流 [${workflowKey}] 已完成。` + sep + nodeStatuses + sep + `运行 ID: ${runId}`;
    }
  }

  if (status === "COMPLETED" || status === "DEGRADED" || status === "COMPLETED_DEGRADED") {
    return (
      `工作流 [${workflowKey}] 已完成，但未解析到可读摘要。` +
      sep +
      `运行 ID: ${runId}` +
      sep +
      `请打开运行中心查看节点详情：/runs/${runId}`
    );
  }

  return (
    `工作流 [${workflowKey}] 状态: ${status || "未知"}。` +
    sep +
    `运行 ID: ${runId}` +
    sep +
    `请打开运行中心：/runs/${runId}`
  );
}

export async function waitForWorkflowRun(api, runId, options = {}) {
  const timeoutMs = options.timeoutMs ?? WORKFLOW_POLL_TIMEOUT_MS;
  const intervalMs = options.intervalMs ?? WORKFLOW_POLL_INTERVAL_MS;
  const onEvents = options.onEvents;
  const pollStart = Date.now();
  let run = await api(`/workflow/runs/${runId}`);
  while (run?.status === "RUNNING" || run?.status === "QUEUED") {
    if (Date.now() - pollStart > timeoutMs) break;
    if (onEvents) {
      try {
        const events = await api(`/workflow/runs/${runId}/events`);
        onEvents(Array.isArray(events) ? events : []);
      } catch {
        /* ignore event poll errors */
      }
    }
    await new Promise((resolve) => setTimeout(resolve, intervalMs));
    run = await api(`/workflow/runs/${runId}`);
  }
  if (onEvents) {
    try {
      const finalEvents = await api(`/workflow/runs/${runId}/events`);
      onEvents(Array.isArray(finalEvents) ? finalEvents : []);
    } catch {
      /* ignore */
    }
  }
  return run;
}
