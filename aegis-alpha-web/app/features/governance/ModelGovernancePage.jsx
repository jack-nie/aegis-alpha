"use client";

import React, { useCallback, useEffect, useState } from "react";
import { MetricTile, StatusPill } from "../../components/ui";
import {
  displayValue,
  formatDateTime,
  formatMoney,
  formatNumber,
  normalizeListResponse,
  pickFirst,
} from "../../lib/format";

export function ModelGovernancePage({ api }) {
  const [models, setModels] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  const loadModels = useCallback(async () => {
    setLoading(true);
    setError("");
    try {
      const response = await api("/governance/models");
      setModels(normalizeListResponse(response));
    } catch (ex) {
      setError(ex.message || "加载模型治理配置失败");
    } finally {
      setLoading(false);
    }
  }, [api]);

  useEffect(() => {
    loadModels();
  }, [loadModels]);

  const activeModels = models.filter((model) => String(pickFirst(model, ["status"])).toUpperCase() === "ACTIVE").length;
  const totalContext = models.reduce(
    (sum, model) => sum + Number(pickFirst(model, ["contextWindow", "context_window"]) || 0),
    0,
  );

  return (
    <div className="space-y-4">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
        <div>
          <h2 className="text-xl font-semibold text-gray-900">模型治理</h2>
          <p className="mt-1 text-sm text-gray-500">集中管理可用模型、上下文窗口、成本参数和降级模型。</p>
        </div>
        <button
          type="button"
          onClick={loadModels}
          className="h-9 rounded-lg border border-gray-200 bg-white px-3 text-sm font-semibold text-gray-800 hover:bg-gray-50"
        >
          刷新
        </button>
      </div>

      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        <MetricTile label="模型总数" value={models.length} />
        <MetricTile label="启用模型" value={activeModels} />
        <MetricTile label="上下文合计" value={formatNumber(totalContext)} />
        <MetricTile label="治理状态" value={error ? "ERROR" : "READY"} />
      </div>

      <div className="overflow-hidden rounded-xl border border-gray-200 bg-white">
        <div className="overflow-auto">
          <table data-testid="model-governance-table" className="w-full min-w-[1040px] divide-y divide-gray-200">
            <thead className="bg-gray-50">
              <tr>
                {["Provider", "Model", "状态", "上下文", "Prompt 成本", "Completion 成本", "降级模型", "创建时间"].map(
                  (head) => (
                    <th key={head} className="table-header">
                      {head}
                    </th>
                  ),
                )}
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100 bg-white">
              {loading && (
                <tr>
                  <td colSpan={8} className="px-4 py-10 text-center text-sm text-gray-400">
                    正在加载模型配置...
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
              {!loading && !error && !models.length && (
                <tr>
                  <td colSpan={8} className="px-4 py-10 text-center text-sm text-gray-400">
                    暂无模型配置
                  </td>
                </tr>
              )}
              {!loading &&
                !error &&
                models.map((model, index) => {
                  const modelName = displayValue(pickFirst(model, ["modelName", "model_name"]));
                  return (
                    <tr
                      key={pickFirst(model, ["modelConfigId", "model_config_id"]) || `${modelName}-${index}`}
                      className="hover:bg-gray-50"
                    >
                      <td className="table-cell font-medium text-gray-900">
                        {displayValue(pickFirst(model, ["provider"])) || "-"}
                      </td>
                      <td className="table-cell font-mono text-xs text-gray-800">{modelName || "-"}</td>
                      <td className="table-cell">
                        <StatusPill status={pickFirst(model, ["status"])} />
                      </td>
                      <td className="table-cell">
                        {formatNumber(pickFirst(model, ["contextWindow", "context_window"]))}
                      </td>
                      <td className="table-cell">
                        {formatMoney(pickFirst(model, ["promptTokenCostUsd", "prompt_token_cost_usd"]), "USD")}
                      </td>
                      <td className="table-cell">
                        {formatMoney(pickFirst(model, ["completionTokenCostUsd", "completion_token_cost_usd"]), "USD")}
                      </td>
                      <td className="table-cell font-mono text-xs">
                        {displayValue(pickFirst(model, ["fallbackModel", "fallback_model"])) || "-"}
                      </td>
                      <td className="table-cell whitespace-nowrap">
                        {formatDateTime(pickFirst(model, ["createdAt", "created_at"]))}
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
