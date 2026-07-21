"use client";

import React, { useCallback, useEffect, useState } from "react";
import { StatusPill } from "../../components/ui";
import {
  displayValue,
  formatDateTime,
  normalizeListResponse,
  pickFirst,
} from "../../lib/format";

export function AuditLogPage({ api }) {
  const [events, setEvents] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [pendingBackend, setPendingBackend] = useState(false);

  const loadEvents = useCallback(async () => {
    setLoading(true);
    setError("");
    setPendingBackend(false);
    try {
      const response = await api("/admin/audit-events");
      setEvents(normalizeListResponse(response));
    } catch (ex) {
      const message = ex.message || "加载审计日志失败";
      if (message.includes("404")) {
        setPendingBackend(true);
        setEvents([]);
      } else {
        setError(message);
      }
    } finally {
      setLoading(false);
    }
  }, [api]);

  useEffect(() => {
    loadEvents();
  }, [loadEvents]);

  return (
    <div className="space-y-4">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <h2 className="text-xl font-semibold text-gray-900">审计日志</h2>
          <p className="mt-1 text-sm text-gray-500">
            查看用户、工作流、Agent 与系统治理事件，支持后端审计接口灰度接入。
          </p>
        </div>
        <button
          type="button"
          onClick={loadEvents}
          className="h-9 rounded-lg border border-gray-200 bg-white px-3 text-sm font-semibold text-gray-800 hover:bg-gray-50"
        >
          刷新
        </button>
      </div>

      {pendingBackend && (
        <div className="rounded-xl border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-800">
          后端审计接口暂未启用（404）。页面已就绪，接口上线后会自动展示最新事件。
        </div>
      )}

      <div className="overflow-hidden rounded-xl border border-gray-200 bg-white">
        <div className="overflow-auto">
          <table data-testid="audit-event-table" className="w-full min-w-[1120px] divide-y divide-gray-200">
            <thead className="bg-gray-50">
              <tr>
                {["时间", "动作", "操作者", "资源类型", "资源 ID", "traceId", "requestId", "结果"].map((head) => (
                  <th key={head} className="table-header">
                    {head}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100 bg-white">
              {loading && (
                <tr>
                  <td colSpan={8} className="px-4 py-10 text-center text-sm text-gray-400">
                    正在加载审计日志...
                  </td>
                </tr>
              )}
              {!loading && error && (
                <tr>
                  <td colSpan={8} className="px-4 py-10 text-center text-sm text-red-600">
                    {error}
                  </td>
                </tr>
              )}
              {!loading && !error && !events.length && (
                <tr>
                  <td colSpan={8} className="px-4 py-10 text-center text-sm text-gray-400">
                    {pendingBackend ? "等待后端审计事件接口上线" : "暂无审计事件"}
                  </td>
                </tr>
              )}
              {!loading &&
                !error &&
                events.map((event, index) => {
                  const action = displayValue(pickFirst(event, ["action", "eventAction", "event_action", "type"]));
                  const actor = displayValue(
                    pickFirst(event, ["username", "actor", "actorUsername", "actor_username", "userId", "user_id"]),
                  );
                  const traceId = displayValue(pickFirst(event, ["traceId", "trace_id"]));
                  return (
                    <tr key={pickFirst(event, ["eventId", "event_id", "id"]) || index} className="hover:bg-gray-50">
                      <td className="table-cell whitespace-nowrap">
                        {formatDateTime(pickFirst(event, ["createdAt", "created_at", "timestamp"]))}
                      </td>
                      <td className="table-cell font-medium text-gray-900">{action || "-"}</td>
                      <td className="table-cell">{actor || "-"}</td>
                      <td className="table-cell">
                        {displayValue(pickFirst(event, ["resourceType", "resource_type"])) || "-"}
                      </td>
                      <td className="table-cell max-w-[220px] truncate font-mono text-xs">
                        {displayValue(pickFirst(event, ["resourceId", "resource_id"])) || "-"}
                      </td>
                      <td className="table-cell max-w-[180px] truncate font-mono text-xs" title={traceId}>
                        {traceId || "-"}
                      </td>
                      <td className="table-cell max-w-[180px] truncate font-mono text-xs">
                        {displayValue(pickFirst(event, ["requestId", "request_id"])) || "-"}
                      </td>
                      <td className="table-cell">
                        <StatusPill status={pickFirst(event, ["status", "result", "outcome"]) || "RECORDED"} />
                      </td>
                    </tr>
                  );
                })}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
}
