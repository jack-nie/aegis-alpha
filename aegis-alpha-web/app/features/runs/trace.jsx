"use client";

import React from "react";
import {
  displayValue,
  formatDateTime,
  formatDuration,
  pickFirst,
  summarizeJson,
} from "../../lib/format";
import { DrawerShell, JsonViewer, StatusPill } from "../../components/ui";

export function BacktestTracePanel({ detail, onClose }) {
  const row = detail.row || {};
  const runId = displayValue(pickFirst(row, ["workflowRunId", "workflow_run_id", "runId", "run_id", "id"]));
  const traceId = displayValue(pickFirst(row, ["traceId", "trace_id"]));
  const status = displayValue(pickFirst(row, ["status", "state", "runStatus", "run_status"]));
  const workflowName = displayValue(
    pickFirst(row, [
      "workflowName",
      "workflow_name",
      "workflowKey",
      "workflow_key",
      "strategyName",
      "strategy_name",
      "strategy",
    ]),
  );
  const startedAt = pickFirst(row, ["startedAt", "started_at", "runTime", "run_time", "createdAt", "created_at"]);
  const completedAt = pickFirst(row, ["completedAt", "completed_at", "endedAt", "ended_at"]);
  const workflowVersionId = displayValue(pickFirst(row, ["workflowVersionId", "workflow_version_id"]));
  const idempotencyKey = displayValue(pickFirst(row, ["idempotencyKey", "idempotency_key"]));
  const controlStatus = displayValue(pickFirst(row, ["controlStatus", "control_status"]));
  const nodeCount =
    detail.nodes.length ||
    displayValue(pickFirst(row, ["nodeCount", "node_count", "nodes", "totalNodes", "total_nodes"])) ||
    "-";
  return (
    <DrawerShell title="工作流运行详情" onClose={onClose}>
      <div className="min-h-0 flex-1 overflow-auto p-5">
        <p className="mb-4 text-sm text-gray-500">
          {pickFirst(row, ["taskName", "task_name", "name", "title", "subject"]) || runId || "-"}
        </p>
        <section className="mb-5 rounded-xl border border-gray-200 bg-white p-4">
          <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
            <div>
              <p className="text-sm font-semibold text-gray-900">{workflowName || "未命名工作流"}</p>
              <p className="mt-1 font-mono text-xs text-gray-500">{runId || "no-run-id"}</p>
            </div>
            <StatusPill status={status} />
          </div>
          <div className="grid grid-cols-1 gap-3 text-sm sm:grid-cols-2">
            <RunMeta label="traceId" value={traceId || "-"} mono />
            <RunMeta label="节点数" value={nodeCount} />
            <RunMeta label="开始时间" value={formatDateTime(startedAt)} />
            <RunMeta label="完成时间" value={formatDateTime(completedAt)} />
            <RunMeta label="workflowVersionId" value={workflowVersionId || "-"} mono />
            <RunMeta label="idempotencyKey" value={idempotencyKey || "-"} mono />
            <RunMeta label="controlStatus" value={controlStatus || status || "-"} />
          </div>
        </section>
        {detail.loading && (
          <div className="rounded-lg border border-dashed border-gray-200 py-12 text-center text-sm text-gray-400">
            正在加载节点调用栈...
          </div>
        )}
        {!detail.loading && detail.error && (
          <div className="rounded-lg border border-red-200 bg-red-50 p-4 text-sm text-red-700">{detail.error}</div>
        )}
        {!detail.loading && !detail.error && !detail.nodes.length && (
          <div className="rounded-lg border border-dashed border-gray-200 py-12 text-center text-sm text-gray-400">
            暂无节点调用记录
          </div>
        )}
        {!detail.loading && !detail.error && detail.nodes.length > 0 && (
          <div>
            <h4 className="mb-3 text-sm font-semibold text-gray-900">节点时间线</h4>
            <div className="space-y-3">
              {detail.nodes.map((node, index) => (
                <TraceNodeCard
                  key={pickFirst(node, ["id", "nodeRunId", "node_run_id", "nodeId", "node_id"]) || index}
                  node={node}
                  index={index}
                />
              ))}
            </div>
          </div>
        )}
      </div>
    </DrawerShell>
  );
}

function TraceNodeCard({ node, index }) {
  const output = pickFirst(node, ["outputJson", "output_json", "output", "outputs", "result", "response", "data"]);
  const input = pickFirst(node, ["inputJson", "input_json", "input", "inputs"]);
  const handler = pickFirst(node, [
    "handler",
    "functionName",
    "function_name",
    "agent",
    "agentName",
    "agent_name",
    "agentId",
    "agent_id",
  ]);
  const nodeType = pickFirst(node, ["nodeType", "node_type", "type"]);
  return (
    <div className="rounded-xl border border-gray-200 bg-white p-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <div className="flex items-center gap-2">
            <span className="grid h-6 w-6 place-items-center rounded-full bg-gray-900 text-xs font-semibold text-white">
              {index + 1}
            </span>
            <h4 className="font-semibold text-gray-900">
              {pickFirst(node, ["nodeName", "node_name", "label", "name", "nodeId", "node_id"]) || "节点"}
            </h4>
            <StatusPill status={pickFirst(node, ["status", "state", "runStatus", "run_status"])} />
          </div>
          <p className="mt-2 text-xs text-gray-500">
            handler/agent: <span className="font-mono text-gray-700">{handler || "-"}</span>
            <span className="ml-3">
              type: <span className="font-mono text-gray-700">{nodeType || "-"}</span>
            </span>
          </p>
        </div>
        <div className="text-right text-xs text-gray-500">
          <p>
            耗时:{" "}
            {formatDuration(
              pickFirst(node, ["durationMs", "duration_ms", "elapsedMs", "elapsed_ms", "latencyMs", "latency_ms"]),
            )}
          </p>
          <p className="mt-1">
            {formatDateTime(pickFirst(node, ["startedAt", "started_at", "createdAt", "created_at"]))}
          </p>
        </div>
      </div>
      {pickFirst(node, ["error", "errorMessage", "error_message", "exception"]) && (
        <div className="mt-3 rounded-lg border border-red-200 bg-red-50 p-3 text-sm text-red-700">
          {String(pickFirst(node, ["error", "errorMessage", "error_message", "exception"]))}
        </div>
      )}
      <div className="mt-3 grid grid-cols-1 gap-3 lg:grid-cols-2">
        {input !== undefined && input !== null && (
          <div>
            <p className="text-xs font-semibold text-gray-500">输入摘要</p>
            <JsonViewer className="max-h-52" value={summarizeJson(input)} compact />
          </div>
        )}
        {output !== undefined && output !== null && (
          <div>
            <p className="text-xs font-semibold text-gray-500">输出摘要</p>
            <JsonViewer className="max-h-52" value={summarizeJson(output)} compact />
          </div>
        )}
      </div>
    </div>
  );
}

export function RunMeta({ label, value, mono = false }) {
  return (
    <div>
      <p className="text-xs font-medium text-gray-500">{label}</p>
      <p className={`mt-1 truncate text-gray-900 ${mono ? "font-mono text-xs" : "text-sm"}`}>{value || "-"}</p>
    </div>
  );
}
