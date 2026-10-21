"use client";

import React, { useCallback, useEffect, useState } from "react";
import { StatusPill } from "../../components/ui";
import { BacktestTracePanel } from "../runs/trace";
import {
  displayValue,
  formatConfidence,
  formatDateTime,
  normalizeListResponse,
  pickFirst,
} from "../../lib/format";

export function BacktestHistoryPage({ api }) {
  const [items, setItems] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [detail, setDetail] = useState({ open: false, row: null, nodes: [], loading: false, error: "" });

  const loadHistory = useCallback(async () => {
    setLoading(true);
    setError("");
    try {
      const response = await api("/backtest/history");
      setItems(normalizeListResponse(response));
    } catch (ex) {
      setError(ex.message || "加载回测历史失败");
    } finally {
      setLoading(false);
    }
  }, [api]);

  useEffect(() => {
    loadHistory();
  }, [loadHistory]);

  const openTrace = async (row) => {
    const workflowRunId = pickFirst(row, ["workflowRunId", "workflow_run_id", "runId", "run_id"]);
    setDetail({
      open: true,
      row,
      nodes: [],
      loading: Boolean(workflowRunId),
      error: workflowRunId ? "" : "该历史记录没有 workflowRunId，无法查询调用栈。",
    });
    if (!workflowRunId) return;
    try {
      const response = await api(`/workflow/runs/${workflowRunId}/nodes`);
      setDetail({ open: true, row, nodes: normalizeListResponse(response), loading: false, error: "" });
    } catch (ex) {
      setDetail({ open: true, row, nodes: [], loading: false, error: ex.message || "加载调用栈失败" });
    }
  };

  return (
    <div className="space-y-4">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <h2 className="text-xl font-semibold text-gray-900">回测历史</h2>
          <p className="mt-1 text-sm text-gray-500">展示历史任务、推荐结果和关联工作流调用栈。</p>
        </div>
        <button
          type="button"
          onClick={loadHistory}
          className="h-9 rounded-lg border border-gray-200 bg-white px-3 text-sm font-semibold text-gray-800 hover:bg-gray-50"
        >
          刷新
        </button>
      </div>

      <div className="overflow-hidden rounded-xl border border-gray-200 bg-white">
        <div className="overflow-auto">
          <table className="w-full min-w-[1280px] divide-y divide-gray-200">
            <thead className="bg-gray-50">
              <tr>
                {[
                  "运行时间",
                  "任务名称",
                  "工作流/策略",
                  "股票/主题",
                  "状态",
                  "节点数",
                  "最终建议",
                  "置信度",
                  "traceId",
                  "操作",
                ].map((head) => (
                  <th key={head} className="table-header">
                    {head}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100 bg-white">
              {loading && (
                <tr>
                  <td colSpan={10} className="px-4 py-10 text-center text-sm text-gray-400">
                    正在加载回测历史...
                  </td>
                </tr>
              )}
              {!loading && error && (
                <tr>
                  <td colSpan={10} className="px-4 py-10 text-center text-sm text-red-600">
                    {error}
                  </td>
                </tr>
              )}
              {!loading && !error && !items.length && (
                <tr>
                  <td colSpan={10} className="px-4 py-10 text-center text-sm text-gray-400">
                    暂无回测历史
                  </td>
                </tr>
              )}
              {!loading &&
                !error &&
                items.map((row, index) => {
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
                  const ticker = displayValue(pickFirst(row, ["ticker", "symbol", "stock", "stockCode", "stock_code"]));
                  const subject = displayValue(pickFirst(row, ["subject", "topic", "theme", "industry"]));
                  const status = displayValue(pickFirst(row, ["status", "state", "runStatus", "run_status"]));
                  const confidence = pickFirst(row, [
                    "confidence",
                    "score",
                    "recommendationConfidence",
                    "recommendation_confidence",
                  ]);
                  const traceId = displayValue(
                    pickFirst(row, ["traceId", "trace_id", "workflowRunId", "workflow_run_id", "runId", "run_id"]),
                  );
                  const nodeCount = Array.isArray(row.nodes)
                    ? row.nodes.length
                    : displayValue(pickFirst(row, ["nodeCount", "node_count", "nodes", "totalNodes", "total_nodes"]));
                  const recommendation = displayValue(
                    pickFirst(row, [
                      "finalRecommendation",
                      "final_recommendation",
                      "recommendation",
                      "advice",
                      "finalAdvice",
                      "final_advice",
                    ]),
                  );
                  return (
                    <tr
                      key={
                        pickFirst(row, ["id", "historyId", "history_id", "workflowRunId", "workflow_run_id"]) || index
                      }
                    >
                      <td className="table-cell whitespace-nowrap">
                        {formatDateTime(
                          pickFirst(row, [
                            "runTime",
                            "run_time",
                            "createdAt",
                            "created_at",
                            "startedAt",
                            "started_at",
                            "completedAt",
                            "completed_at",
                          ]),
                        )}
                      </td>
                      <td className="table-cell font-medium text-gray-900">
                        {displayValue(pickFirst(row, ["taskName", "task_name", "name", "title"])) || "-"}
                      </td>
                      <td className="table-cell">{workflowName || "-"}</td>
                      <td className="table-cell">{[ticker, subject].filter(Boolean).join(" / ") || "-"}</td>
                      <td className="table-cell">
                        <StatusPill status={status} />
                      </td>
                      <td className="table-cell">{nodeCount || "-"}</td>
                      <td className="table-cell max-w-[260px] truncate" title={recommendation}>
                        {recommendation || "-"}
                      </td>
                      <td className="table-cell">{formatConfidence(confidence)}</td>
                      <td className="table-cell font-mono text-xs">{traceId || "-"}</td>
                      <td className="table-cell">
                        <button
                          type="button"
                          onClick={() => openTrace(row)}
                          className="rounded-lg border border-gray-200 px-3 py-1.5 text-xs font-semibold text-gray-800 hover:bg-gray-50"
                        >
                          查看调用栈
                        </button>
                      </td>
                    </tr>
                  );
                })}
            </tbody>
          </table>
        </div>
      </div>

      {detail.open && (
        <BacktestTracePanel
          detail={detail}
          onClose={() => setDetail({ open: false, row: null, nodes: [], loading: false, error: "" })}
        />
      )}
    </div>
  );
}
