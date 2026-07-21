"use client";

import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  displayValue,
  formatDateTime,
  normalizeListResponse,
  pickFirst,
} from "../../lib/format";
import { readSearchParam } from "../../lib/routing";
import { DegradedBanner } from "../../components/DegradedBanner";
import { MetricTile, StatusPill } from "../../components/ui";
import { BacktestTracePanel, RunMeta } from "./trace";

function isRunDegraded(run) {
  if (!run) return false;
  if (run.degraded === true) return true;
  const status = String(pickFirst(run, ["status", "state", "runStatus", "run_status"]) || "").toUpperCase();
  return status.includes("DEGRADED") || status === "COMPLETED_DEGRADED";
}

function resolveRunDegradedReasons(run) {
  if (!run) return [];
  if (Array.isArray(run.degradedReasons) && run.degradedReasons.length) return run.degradedReasons;
  if (Array.isArray(run.reasons) && run.reasons.length) return run.reasons;
  const message = pickFirst(run, ["errorMessage", "error_message", "lastEvent", "last_event", "controlStatus"]);
  return message ? [String(message)] : ["部分节点失败或证据不足，结果可能不可直接批准"];
}

export function WorkflowRunCenterPage({ api, navigate, deepLinkRunId = "" }) {
  const [runs, setRuns] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [actionBusy, setActionBusy] = useState("");
  const [detail, setDetail] = useState({ open: false, row: null, nodes: [], loading: false, error: "" });
  const [statusFilter, setStatusFilter] = useState(() => readSearchParam("status", "ALL").toUpperCase());
  const [traceSearch, setTraceSearch] = useState(() => readSearchParam("traceId", ""));
  const openDetailRequestRef = useRef(0);
  const pendingRunHintRef = useRef(null);

  const loadRuns = useCallback(async () => {
    setLoading(true);
    setError("");
    try {
      const params = new URLSearchParams();
      if (statusFilter && statusFilter !== "ALL") params.set("status", statusFilter);
      if (traceSearch.trim()) params.set("traceId", traceSearch.trim());
      const response = await api(`/workflow/runs${params.toString() ? `?${params.toString()}` : ""}`);
      setRuns(normalizeListResponse(response));
    } catch (ex) {
      setError(ex.message || "加载运行中心失败");
    } finally {
      setLoading(false);
    }
  }, [api, statusFilter, traceSearch]);

  useEffect(() => {
    loadRuns();
  }, [loadRuns]);

  const openTraceById = useCallback(
    async (runId, runHint = null) => {
      const requestId = ++openDetailRequestRef.current;
      if (!runId) {
        setDetail({
          open: true,
          row: runHint,
          nodes: [],
          loading: false,
          error: "该运行记录缺少 runId，无法查询节点详情。",
        });
        return;
      }
      let run = runHint;
      if (!run) {
        run = runs.find((item) => String(pickFirst(item, ["runId", "run_id", "id"])) === String(runId)) || null;
      }
      if (!run) {
        try {
          run = await api(`/workflow/runs/${encodeURIComponent(String(runId))}`);
        } catch {
          run = { runId };
        }
      }
      if (requestId !== openDetailRequestRef.current) return;
      setDetail({
        open: true,
        row: run,
        nodes: [],
        loading: true,
        error: "",
      });
      try {
        const response = await api(`/workflow/runs/${encodeURIComponent(String(runId))}/nodes`);
        if (requestId !== openDetailRequestRef.current) return;
        setDetail({ open: true, row: run, nodes: normalizeListResponse(response), loading: false, error: "" });
      } catch (ex) {
        if (requestId !== openDetailRequestRef.current) return;
        setDetail({ open: true, row: run, nodes: [], loading: false, error: ex.message || "加载节点详情失败" });
      }
    },
    [api, runs],
  );

  const openTrace = (run) => {
    const runId = pickFirst(run, ["runId", "run_id", "id"]);
    if (runId && navigate) {
      pendingRunHintRef.current = run;
      navigate(`/runs/${encodeURIComponent(String(runId))}`);
      return;
    }
    openTraceById(runId, run);
  };

  const closeTrace = () => {
    openDetailRequestRef.current += 1;
    pendingRunHintRef.current = null;
    setDetail({ open: false, row: null, nodes: [], loading: false, error: "" });
    if (navigate) navigate("/workflow/runs");
  };

  useEffect(() => {
    if (!deepLinkRunId) {
      setDetail((prev) =>
        prev.open ? { open: false, row: null, nodes: [], loading: false, error: "" } : prev,
      );
      return;
    }
    const currentId = pickFirst(detail.row, ["runId", "run_id", "id"]);
    if (detail.open && String(currentId) === String(deepLinkRunId) && !detail.loading) return;
    const runHint = pendingRunHintRef.current;
    pendingRunHintRef.current = null;
    void openTraceById(deepLinkRunId, runHint);
    // Only react to deep-link id changes; openTraceById is stable for a given runs snapshot.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [deepLinkRunId]);

  const runAction = async (run, action) => {
    const runId = pickFirst(run, ["runId", "run_id", "id"]);
    if (!runId) return;
    setActionBusy(`${action}:${runId}`);
    setError("");
    try {
      await api(`/workflow/runs/${runId}/${action}`, { method: "POST" });
      await loadRuns();
    } catch (ex) {
      setError(ex.message || `Workflow run ${action} failed`);
    } finally {
      setActionBusy("");
    }
  };

  const statusCounts = useMemo(
    () =>
      runs.reduce((acc, run) => {
        const status = String(
          pickFirst(run, ["status", "state", "runStatus", "run_status"]) || "UNKNOWN",
        ).toUpperCase();
        acc[status] = (acc[status] || 0) + 1;
        return acc;
      }, {}),
    [runs],
  );
  const filteredRuns = useMemo(
    () =>
      runs.filter((run) => {
        const status = String(
          pickFirst(run, ["status", "state", "runStatus", "run_status"]) || "UNKNOWN",
        ).toUpperCase();
        const traceId = displayValue(pickFirst(run, ["traceId", "trace_id", "runId", "run_id", "id"])).toLowerCase();
        const matchesStatus = statusFilter === "ALL" || status === statusFilter;
        const matchesTrace = !traceSearch.trim() || traceId.includes(traceSearch.trim().toLowerCase());
        return matchesStatus && matchesTrace;
      }),
    [runs, statusFilter, traceSearch],
  );
  const updateStatusFilter = (nextStatus) => {
    setStatusFilter(nextStatus);
    if (typeof window === "undefined") return;
    const params = new URLSearchParams(window.location.search);
    if (nextStatus === "ALL") params.delete("status");
    else params.set("status", nextStatus);
    const nextUrl = `${window.location.pathname}${params.toString() ? `?${params.toString()}` : ""}`;
    window.history.replaceState({}, "", nextUrl);
  };
  const renderRunActions = (run) => {
    const runId = displayValue(pickFirst(run, ["runId", "run_id", "id"]));
    const status = displayValue(pickFirst(run, ["status", "state", "runStatus", "run_status"])).toUpperCase();
    const availableActions =
      Array.isArray(run.availableActions) && run.availableActions.length
        ? run.availableActions
        : status === "QUEUED"
          ? ["dispatch", "cancel"]
          : status === "RUNNING"
            ? ["pause", "cancel"]
            : status === "PAUSED"
              ? ["resume", "cancel"]
              : [];
    const actionReasons = run.actionReasons || {
      dispatch: status === "QUEUED" ? "" : "仅队列中的运行可以派发",
      pause: ["QUEUED", "RUNNING"].includes(status) ? "" : "仅队列中或运行中的任务可以暂停",
      resume: status === "PAUSED" ? "" : "仅暂停中的任务可以恢复",
      cancel: ["QUEUED", "PAUSED", "RUNNING"].includes(status) ? "" : "已完成或已失败的任务不能取消",
    };
    const labels = { dispatch: "派发", pause: "暂停", resume: "恢复", cancel: "取消" };
    const busyPrefix = actionBusy.endsWith(`:${runId}`) ? actionBusy.split(":")[0] : "";
    if (!availableActions.length) {
      return (
        <span className="text-xs text-gray-500" title={actionReasons.cancel || "当前状态没有可执行操作"}>
          无可执行操作
        </span>
      );
    }
    return (
      <div className="flex flex-wrap items-center gap-1.5">
        {availableActions.slice(0, 2).map((action) => (
          <button
            key={action}
            type="button"
            data-testid={`workflow-run-${action}`}
            onClick={() => runAction(run, action)}
            disabled={Boolean(busyPrefix)}
            title={actionReasons[action] || labels[action] || action}
            className={`min-h-8 rounded-lg border px-2.5 py-1.5 text-xs font-semibold hover:bg-gray-50 disabled:cursor-not-allowed disabled:opacity-40 ${action === "cancel" ? "border-red-200 text-red-700 hover:bg-red-50" : "border-gray-200 text-gray-800"}`}
          >
            {busyPrefix === action ? "..." : labels[action] || action}
          </button>
        ))}
      </div>
    );
  };

  const selectedRunDegraded = detail.open && isRunDegraded(detail.row);

  return (
    <div className="space-y-4">
      <div className="flex flex-col gap-3 lg:flex-row lg:items-start lg:justify-between">
        <div>
          <h2 className="text-xl font-semibold text-gray-900">运行中心</h2>
          <p className="mt-1 text-sm text-gray-500">集中查看工作流执行状态、traceId、节点数量与运行详情。</p>
        </div>
        <button
          type="button"
          onClick={loadRuns}
          className="h-9 rounded-lg border border-gray-200 bg-white px-3 text-sm font-semibold text-gray-800 hover:bg-gray-50"
        >
          刷新
        </button>
      </div>

      {selectedRunDegraded && (
        <DegradedBanner title="当前选中运行已降级" reasons={resolveRunDegradedReasons(detail.row)} />
      )}

      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        <MetricTile label="总运行数" value={runs.length} />
        <MetricTile label="队列中" value={statusCounts.QUEUED || 0} />
        <MetricTile label="运行中" value={statusCounts.RUNNING || 0} />
        <MetricTile
          label="需处理"
          value={(statusCounts.PAUSED || 0) + (statusCounts.ERROR || 0) + (statusCounts.FAILED || 0)}
        />
      </div>

      <div className="flex flex-col gap-3 rounded-xl border border-gray-200 bg-white p-3 sm:flex-row sm:items-center">
        <label className="flex flex-1 items-center gap-2 text-sm text-gray-600">
          <span className="shrink-0 font-medium">状态</span>
          <select
            className="input-field h-10"
            value={statusFilter}
            onChange={(event) => updateStatusFilter(event.target.value)}
          >
            {["ALL", "QUEUED", "RUNNING", "PAUSED", "COMPLETED", "FAILED", "CANCELLED"].map((status) => (
              <option key={status} value={status}>
                {status === "ALL" ? "全部" : status}
              </option>
            ))}
          </select>
        </label>
        <label className="flex flex-[2] items-center gap-2 text-sm text-gray-600">
          <span className="shrink-0 font-medium">traceId</span>
          <input
            className="input-field h-10"
            value={traceSearch}
            onChange={(event) => setTraceSearch(event.target.value)}
            placeholder="搜索 traceId / runId"
          />
        </label>
      </div>

      <div data-testid="workflow-run-card-list" className="space-y-3 lg:hidden">
        {loading && (
          <div className="rounded-xl border border-gray-200 bg-white p-5 text-center text-sm text-gray-400">
            正在加载运行记录...
          </div>
        )}
        {!loading && error && (
          <div className="rounded-xl border border-red-200 bg-red-50 p-5 text-center text-sm text-red-600">{error}</div>
        )}
        {!loading && !error && !filteredRuns.length && (
          <div className="rounded-xl border border-gray-200 bg-white p-5 text-center text-sm text-gray-400">
            暂无匹配运行记录
          </div>
        )}
        {!loading &&
          !error &&
          filteredRuns.map((run, index) => {
            const runId = displayValue(pickFirst(run, ["runId", "run_id", "id"]));
            const traceId = displayValue(pickFirst(run, ["traceId", "trace_id"]));
            const subject = displayValue(pickFirst(run, ["subject", "topic", "name"]));
            return (
              <div key={runId || index} className="rounded-xl border border-gray-200 bg-white p-4">
                <div className="flex items-start justify-between gap-3">
                  <div className="min-w-0">
                    <p className="truncate font-semibold text-gray-900">
                      {displayValue(pickFirst(run, ["workflowName", "workflow_name", "workflowKey", "workflow_key"])) ||
                        "-"}
                    </p>
                    <p className="mt-1 truncate text-sm text-gray-500">{subject || "-"}</p>
                  </div>
                  <StatusPill status={pickFirst(run, ["status", "state", "runStatus", "run_status"])} />
                </div>
                <div className="mt-3 grid grid-cols-2 gap-2 text-xs text-gray-500">
                  <RunMeta
                    label="开始"
                    value={formatDateTime(pickFirst(run, ["startedAt", "started_at", "createdAt", "created_at"]))}
                  />
                  <RunMeta
                    label="节点"
                    value={
                      displayValue(pickFirst(run, ["nodeCount", "node_count", "nodes", "totalNodes", "total_nodes"])) ||
                      "-"
                    }
                  />
                  <RunMeta label="traceId" value={traceId || "-"} mono />
                  <RunMeta label="runId" value={runId || "-"} mono />
                </div>
                <div className="mt-4 flex flex-wrap items-center justify-between gap-2">
                  <button
                    type="button"
                    onClick={() => openTrace(run)}
                    className="min-h-8 rounded-lg border border-gray-200 px-3 py-1.5 text-xs font-semibold text-gray-800 hover:bg-gray-50"
                  >
                    详情
                  </button>
                  {renderRunActions(run)}
                </div>
              </div>
            );
          })}
      </div>

      <div className="overflow-hidden rounded-xl border border-gray-200 bg-white">
        <div className="hidden lg:block">
          <table data-testid="workflow-run-center-table" className="w-full min-w-[920px] divide-y divide-gray-200">
            <thead className="bg-gray-50">
              <tr>
                {["开始时间", "工作流", "主题", "状态", "节点数", "最近事件", "操作"].map((head) => (
                  <th key={head} className="table-header">
                    {head}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100 bg-white">
              {loading && (
                <tr>
                  <td colSpan={7} className="px-4 py-10 text-center text-sm text-gray-400">
                    正在加载运行记录...
                  </td>
                </tr>
              )}
              {!loading && error && (
                <tr>
                  <td colSpan={7} className="px-4 py-10 text-center text-sm text-red-600">
                    {error}
                  </td>
                </tr>
              )}
              {!loading && !error && !filteredRuns.length && (
                <tr>
                  <td colSpan={7} className="px-4 py-10 text-center text-sm text-gray-400">
                    暂无匹配运行记录
                  </td>
                </tr>
              )}
              {!loading &&
                !error &&
                filteredRuns.map((run, index) => {
                  const runId = displayValue(pickFirst(run, ["runId", "run_id", "id"]));
                  const traceId = displayValue(pickFirst(run, ["traceId", "trace_id"]));
                  const status = displayValue(pickFirst(run, ["status", "state", "runStatus", "run_status"]));
                  const lastEvent =
                    displayValue(
                      pickFirst(run, [
                        "lastEvent",
                        "last_event",
                        "errorMessage",
                        "error_message",
                        "controlStatus",
                        "control_status",
                      ]),
                    ) ||
                    traceId ||
                    runId ||
                    "-";
                  return (
                    <tr key={runId || index} className="hover:bg-gray-50">
                      <td className="table-cell whitespace-nowrap">
                        {formatDateTime(pickFirst(run, ["startedAt", "started_at", "createdAt", "created_at"]))}
                      </td>
                      <td className="table-cell font-medium text-gray-900">
                        {displayValue(
                          pickFirst(run, ["workflowName", "workflow_name", "workflowKey", "workflow_key"]),
                        ) || "-"}
                      </td>
                      <td
                        className="table-cell max-w-[220px] truncate"
                        title={displayValue(pickFirst(run, ["subject", "topic", "name"]))}
                      >
                        {displayValue(pickFirst(run, ["subject", "topic", "name"])) || "-"}
                      </td>
                      <td className="table-cell">
                        <StatusPill status={status} />
                      </td>
                      <td className="table-cell">
                        {displayValue(
                          pickFirst(run, ["nodeCount", "node_count", "nodes", "totalNodes", "total_nodes"]),
                        ) || "-"}
                      </td>
                      <td className="table-cell max-w-[260px] truncate text-xs text-gray-500" title={lastEvent}>
                        {lastEvent}
                      </td>
                      <td className="table-cell">
                        <div className="flex items-center gap-2">
                          <button
                            type="button"
                            onClick={() => openTrace(run)}
                            className="min-h-8 rounded-lg border border-gray-200 px-2.5 py-1.5 text-xs font-semibold text-gray-800 hover:bg-gray-50"
                          >
                            详情
                          </button>
                          {renderRunActions(run)}
                        </div>
                      </td>
                    </tr>
                  );
                })}
            </tbody>
          </table>
        </div>
      </div>

      {detail.open && <BacktestTracePanel detail={detail} onClose={closeTrace} />}
    </div>
  );
}
