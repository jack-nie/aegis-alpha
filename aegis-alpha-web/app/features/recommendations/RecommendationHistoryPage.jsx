"use client";

import React, { useCallback, useEffect, useRef, useState } from "react";
import {
  displayValue,
  formatConfidence,
  formatDateTime,
  formatNumber,
  normalizeListResponse,
  pickFirst,
  summarizeJson,
} from "../../lib/format";
import { DegradedBanner } from "../../components/DegradedBanner";
import { Badge, JsonViewer, MetricTile, StatusPill } from "../../components/ui";
import { RecommendationQualityBadges } from "./RecommendationQualityBadges";
import {
  isRecommendationDegradedDraft,
  resolveApproveDisabledReason,
  resolveRecommendationApprovable,
} from "./recommendationQuality";

export function RecommendationHistoryPage({ api, navigate, deepLinkWorkflowRunId = "" }) {
  const [items, setItems] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [actionBusy, setActionBusy] = useState("");
  const [approvalFilter, setApprovalFilter] = useState("all");
  const [detail, setDetail] = useState({
    open: false,
    recommendation: null,
    evidence: [],
    llmCalls: [],
    approvable: null,
    loading: false,
    error: "",
  });
  const openDetailRequestRef = useRef(0);
  const pendingRecommendationHintRef = useRef(null);

  const loadRecommendations = useCallback(async () => {
    setLoading(true);
    setError("");
    try {
      const response = await api("/recommendations");
      setItems(normalizeListResponse(response));
    } catch (ex) {
      setError(ex.message || "加载推荐历史失败");
    } finally {
      setLoading(false);
    }
  }, [api]);

  useEffect(() => {
    loadRecommendations();
  }, [loadRecommendations]);

  const openDetailById = useCallback(
    async (workflowRunId, rowHint = null) => {
      const requestId = ++openDetailRequestRef.current;
      const row =
        rowHint ||
        items.find((item) => String(pickFirst(item, ["workflowRunId", "workflow_run_id"])) === String(workflowRunId)) ||
        (workflowRunId ? { workflowRunId } : null);
      setDetail({
        open: true,
        recommendation: row,
        evidence: [],
        llmCalls: [],
        approvable: null,
        loading: Boolean(workflowRunId),
        error: workflowRunId ? "" : "缺少 workflowRunId，无法加载推荐证据。",
      });
      if (!workflowRunId) return;
      try {
        const response = await api(`/recommendations/${encodeURIComponent(String(workflowRunId))}`);
        let llmCalls = [];
        try {
          llmCalls = normalizeListResponse(
            await api(`/governance/llm-calls?workflowRunId=${encodeURIComponent(String(workflowRunId))}`),
          );
        } catch {
          llmCalls = [];
        }
        if (requestId !== openDetailRequestRef.current) return;
        const recommendation = response?.recommendation || row;
        const evidence = normalizeListResponse(response?.evidence || []);
        const approvable =
          typeof response?.approvable === "boolean"
            ? response.approvable
            : resolveRecommendationApprovable(recommendation, { evidence });
        setDetail({
          open: true,
          recommendation,
          evidence,
          llmCalls,
          approvable,
          loading: false,
          error: "",
        });
      } catch (ex) {
        if (requestId !== openDetailRequestRef.current) return;
        setDetail({
          open: true,
          recommendation: row,
          evidence: [],
          llmCalls: [],
          approvable: null,
          loading: false,
          error: ex.message || "加载推荐详情失败",
        });
      }
    },
    [api, items],
  );

  const openDetail = (row) => {
    const workflowRunId = pickFirst(row, ["workflowRunId", "workflow_run_id"]);
    if (workflowRunId && navigate) {
      pendingRecommendationHintRef.current = row;
      navigate(`/recommendations/${encodeURIComponent(String(workflowRunId))}`);
      return;
    }
    openDetailById(workflowRunId, row);
  };

  const closeDetail = () => {
    openDetailRequestRef.current += 1;
    pendingRecommendationHintRef.current = null;
    setDetail({
      open: false,
      recommendation: null,
      evidence: [],
      llmCalls: [],
      approvable: null,
      loading: false,
      error: "",
    });
    if (navigate) navigate("/recommendations");
  };

  useEffect(() => {
    if (!deepLinkWorkflowRunId) {
      setDetail((prev) =>
        prev.open
          ? {
              open: false,
              recommendation: null,
              evidence: [],
              llmCalls: [],
              approvable: null,
              loading: false,
              error: "",
            }
          : prev,
      );
      return;
    }
    const currentId = pickFirst(detail.recommendation, ["workflowRunId", "workflow_run_id"]);
    if (detail.open && String(currentId) === String(deepLinkWorkflowRunId) && !detail.loading) return;
    const rowHint = pendingRecommendationHintRef.current;
    pendingRecommendationHintRef.current = null;
    void openDetailById(deepLinkWorkflowRunId, rowHint);
    // Only react to deep-link id changes.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [deepLinkWorkflowRunId]);

  const decide = async (row, action) => {
    const workflowRunId = pickFirst(row, ["workflowRunId", "workflow_run_id"]);
    if (!workflowRunId) return;
    setActionBusy(`${action}:${workflowRunId}`);
    setError("");
    try {
      const updated = await api(recommendationDecisionPath(workflowRunId, action), { method: "POST" });
      setDetail((prev) =>
        prev.open && pickFirst(prev.recommendation, ["workflowRunId", "workflow_run_id"]) === workflowRunId
          ? {
              ...prev,
              recommendation: updated,
              approvable: resolveRecommendationApprovable(updated, { evidence: prev.evidence }),
            }
          : prev,
      );
      await loadRecommendations();
    } catch (ex) {
      setError(ex.message || "更新推荐审批状态失败");
    } finally {
      setActionBusy("");
    }
  };

  const pendingItems = items.filter(
    (item) => String(pickFirst(item, ["approvalStatus", "approval_status"])).toUpperCase() === "PENDING_REVIEW",
  );
  const pendingCount = pendingItems.length;
  const approvedCount = items.filter(
    (item) => String(pickFirst(item, ["approvalStatus", "approval_status"])).toUpperCase() === "APPROVED",
  ).length;
  const approvableCount = pendingItems.filter((item) => resolveRecommendationApprovable(item)).length;
  const notApprovableCount = pendingItems.filter((item) => !resolveRecommendationApprovable(item)).length;

  const filteredItems = items.filter((item) => {
    const approval = String(pickFirst(item, ["approvalStatus", "approval_status"])).toUpperCase();
    const pending = approval === "PENDING_REVIEW";
    if (approvalFilter === "pending") return pending;
    if (approvalFilter === "approvable") return pending && resolveRecommendationApprovable(item);
    if (approvalFilter === "not_approvable") return pending && !resolveRecommendationApprovable(item);
    return true;
  });

  const filterChips = [
    { key: "all", label: "全部", count: items.length },
    { key: "pending", label: "待审", count: pendingCount },
    { key: "approvable", label: "可批准", count: approvableCount },
    { key: "not_approvable", label: "不可批准", count: notApprovableCount },
  ];

  return (
    <div className="space-y-4">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
        <div>
          <h2 className="text-xl font-semibold text-gray-900">推荐历史</h2>
          <p className="mt-1 text-sm text-gray-500">查看工作流生成的股票推荐、证据链、模型调用和审批状态。</p>
        </div>
        <button
          type="button"
          onClick={loadRecommendations}
          className="h-9 rounded-lg border border-gray-200 bg-white px-3 text-sm font-semibold text-gray-800 hover:bg-gray-50"
        >
          刷新
        </button>
      </div>

      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        <MetricTile label="推荐总数" value={items.length} />
        <MetricTile label="待复核" value={pendingCount} />
        <MetricTile label="已批准" value={approvedCount} />
        <MetricTile
          label="覆盖标的"
          value={new Set(items.map((item) => pickFirst(item, ["symbol"])).filter(Boolean)).size}
        />
      </div>

      <div className="flex flex-wrap items-center gap-2" data-testid="recommendation-approval-filters">
        {filterChips.map((chip) => {
          const active = approvalFilter === chip.key;
          return (
            <button
              key={chip.key}
              type="button"
              onClick={() => setApprovalFilter(chip.key)}
              className={`rounded-full border px-3 py-1 text-xs font-semibold transition-colors ${
                active
                  ? "border-blue-300 bg-blue-50 text-blue-700"
                  : "border-gray-200 bg-white text-gray-600 hover:border-gray-300 hover:bg-gray-50"
              }`}
            >
              {chip.label}
              <span className={`ml-1 ${active ? "text-blue-500" : "text-gray-400"}`}>{chip.count}</span>
            </button>
          );
        })}
      </div>

      <div className="overflow-hidden rounded-xl border border-gray-200 bg-white">
        <div className="overflow-auto">
          <table data-testid="recommendation-history-table" className="w-full min-w-[1320px] divide-y divide-gray-200">
            <thead className="bg-gray-50">
              <tr>
                {["创建时间", "标的", "建议", "置信度", "周期", "审批状态", "traceId", "workflowRunId", "操作"].map(
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
                  <td colSpan={9} className="px-4 py-10 text-center text-sm text-gray-400">
                    正在加载推荐历史...
                  </td>
                </tr>
              )}
              {!loading && error && (
                <tr>
                  <td colSpan={9} className="px-4 py-10 text-center text-sm text-red-600">
                    {error}
                  </td>
                </tr>
              )}
              {!loading && !error && !filteredItems.length && (
                <tr>
                  <td colSpan={9} className="px-4 py-10 text-center text-sm text-gray-400">
                    {items.length ? "当前筛选下暂无推荐" : "暂无推荐历史"}
                  </td>
                </tr>
              )}
              {!loading &&
                !error &&
                filteredItems.map((item, index) => {
                  const workflowRunId = displayValue(pickFirst(item, ["workflowRunId", "workflow_run_id"]));
                  const busyPrefix = actionBusy.endsWith(`:${workflowRunId}`) ? actionBusy.split(":")[0] : "";
                  const approval = String(
                    pickFirst(item, ["approvalStatus", "approval_status"]) || "",
                  ).toUpperCase();
                  const canApprove = resolveRecommendationApprovable(item);
                  const approveDisabled = Boolean(busyPrefix) || !canApprove || approval !== "PENDING_REVIEW";
                  const approveDisabledReason = approveDisabled && !busyPrefix ? resolveApproveDisabledReason(item) : "";
                  const degraded = isRecommendationDegradedDraft(item);
                  return (
                    <tr
                      key={pickFirst(item, ["recommendationId", "recommendation_id"]) || workflowRunId || index}
                      className="hover:bg-gray-50"
                    >
                      <td className="table-cell whitespace-nowrap">
                        {formatDateTime(pickFirst(item, ["createdAt", "created_at"]))}
                      </td>
                      <td className="table-cell font-semibold text-gray-900">
                        {displayValue(pickFirst(item, ["symbol"])) || "-"}
                      </td>
                      <td className="table-cell">
                        <div className="flex flex-wrap items-center gap-1.5">
                          <StatusPill status={pickFirst(item, ["recommendation"])} />
                          <RecommendationQualityBadges item={item} />
                        </div>
                      </td>
                      <td className="table-cell">{formatConfidence(pickFirst(item, ["confidence"]))}</td>
                      <td className="table-cell">
                        {displayValue(pickFirst(item, ["timeHorizon", "time_horizon"])) || "-"}
                      </td>
                      <td className="table-cell">
                        <div className="flex flex-wrap items-center gap-1.5">
                          <StatusPill status={pickFirst(item, ["approvalStatus", "approval_status"])} />
                          {approval === "PENDING_REVIEW" && !canApprove && (
                            <span className="inline-flex rounded-full border border-gray-200 bg-gray-50 px-2 py-0.5 text-[11px] font-semibold text-gray-500">
                              不可批准
                            </span>
                          )}
                        </div>
                      </td>
                      <td className="table-cell max-w-[180px] truncate font-mono text-xs">
                        {displayValue(pickFirst(item, ["traceId", "trace_id"])) || "-"}
                      </td>
                      <td className="table-cell max-w-[220px] truncate font-mono text-xs" title={workflowRunId}>
                        {workflowRunId || "-"}
                      </td>
                      <td className="table-cell">
                        <div className="flex flex-col items-start gap-1">
                          <div className="flex flex-wrap items-center gap-1.5">
                            <button
                              type="button"
                              onClick={() => openDetail(item)}
                              className="rounded-lg border border-gray-200 px-2.5 py-1.5 text-xs font-semibold text-gray-800 hover:bg-gray-50"
                            >
                              详情
                            </button>
                            <button
                              type="button"
                              data-testid="recommendation-approve"
                              onClick={() => decide(item, "approve")}
                              disabled={approveDisabled}
                              title={approveDisabledReason || ""}
                              className="rounded-lg border border-emerald-200 px-2.5 py-1.5 text-xs font-semibold text-emerald-700 hover:bg-emerald-50 disabled:cursor-not-allowed disabled:opacity-40"
                            >
                              {busyPrefix === "approve" ? "..." : "批准"}
                            </button>
                            <button
                              type="button"
                              data-testid="recommendation-reject"
                              onClick={() => decide(item, "reject")}
                              disabled={Boolean(busyPrefix) || approval !== "PENDING_REVIEW"}
                              className="rounded-lg border border-red-200 px-2.5 py-1.5 text-xs font-semibold text-red-700 hover:bg-red-50 disabled:cursor-not-allowed disabled:opacity-40"
                            >
                              {busyPrefix === "reject" ? "..." : "驳回"}
                            </button>
                          </div>
                          {approveDisabledReason && (
                            <span data-testid="approve-disabled-reason" className="text-[11px] text-amber-700">
                              {approveDisabledReason}
                            </span>
                          )}
                          {degraded && (
                            <span className="text-[11px] text-amber-700">降级草稿 — 需重跑补全证据</span>
                          )}
                        </div>
                      </td>
                    </tr>
                  );
                })}
            </tbody>
          </table>
        </div>
      </div>

      {detail.open && (
        <RecommendationDetailPanel
          detail={detail}
          actionBusy={actionBusy}
          onClose={closeDetail}
          onDecision={decide}
        />
      )}
    </div>
  );
}

function recommendationDecisionPath(workflowRunId, action) {
  if (action === "approve") return `/recommendations/${workflowRunId}/approve`;
  if (action === "reject") return `/recommendations/${workflowRunId}/reject`;
  return `/recommendations/${workflowRunId}/${action}`;
}

function RecommendationDetailPanel({ detail, actionBusy, onClose, onDecision }) {
  const recommendation = detail.recommendation || {};
  const workflowRunId = displayValue(pickFirst(recommendation, ["workflowRunId", "workflow_run_id"]));
  const evidence = detail.evidence || [];
  const llmCalls = detail.llmCalls || [];
  const totalTokens = llmCalls.reduce(
    (sum, call) => sum + Number(pickFirst(call, ["totalTokens", "total_tokens"]) || 0),
    0,
  );
  const busyPrefix = actionBusy.endsWith(`:${workflowRunId}`) ? actionBusy.split(":")[0] : "";

  return (
    <div className="fixed inset-0 z-50">
      <div className="absolute inset-0 bg-white/60 backdrop-blur-[4px]" onClick={onClose} />
      <aside className="absolute inset-y-0 right-0 flex w-full max-w-4xl flex-col border-l border-gray-100 bg-white shadow-2xl">
        <div className="flex items-start justify-between border-b border-gray-100 px-5 py-4">
          <div>
            <h3 className="text-lg font-semibold text-gray-900">
              {displayValue(pickFirst(recommendation, ["symbol"])) || "-"} 推荐详情
            </h3>
            <p className="mt-1 font-mono text-xs text-gray-500">{workflowRunId || "no-workflow-run"}</p>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="rounded-md px-2 py-1 text-2xl leading-none text-gray-800 hover:bg-gray-100"
            aria-label="关闭推荐详情"
          >
            ×
          </button>
        </div>

        <div className="min-h-0 flex-1 overflow-auto p-5">
          {detail.loading && (
            <div className="rounded-lg border border-dashed border-gray-200 py-12 text-center text-sm text-gray-400">
              正在加载推荐详情...
            </div>
          )}
          {!detail.loading && detail.error && (
            <div className="rounded-lg border border-red-200 bg-red-50 p-4 text-sm text-red-700">{detail.error}</div>
          )}
          {!detail.loading && !detail.error && (
            <div className="space-y-5">
              <section className="rounded-xl border border-gray-200 bg-white p-4">
                {(() => {
                  const approval = String(
                    pickFirst(recommendation, ["approvalStatus", "approval_status"]) || "",
                  ).toUpperCase();
                  const recLabel = String(pickFirst(recommendation, ["recommendation"]) || "").toUpperCase();
                  const disclaimer = String(pickFirst(recommendation, ["disclaimer"]) || "");
                  const isDraft =
                    approval === "PENDING_REVIEW" ||
                    recLabel === "INSUFFICIENT_DATA" ||
                    disclaimer.includes("DEGRADED") ||
                    disclaimer.includes("DRAFT");
                  const canApprove = resolveRecommendationApprovable(recommendation, {
                    approvable: detail.approvable,
                    evidence,
                  });
                  const approveBlocked = !canApprove || approval !== "PENDING_REVIEW";
                  const insufficient = recLabel === "INSUFFICIENT_DATA";
                  const degraded = isRecommendationDegradedDraft(recommendation);
                  const approveDisabledReason =
                    approveBlocked && !busyPrefix
                      ? resolveApproveDisabledReason(recommendation, {
                          approvable: detail.approvable,
                          evidence,
                        })
                      : "";
                  const degradedReasons = [];
                  if (insufficient) degradedReasons.push("数据不足（INSUFFICIENT_DATA）");
                  if (degraded && !insufficient) degradedReasons.push("降级草稿，请重新运行补全证据");
                  if (Array.isArray(evidence) && evidence.length === 0) degradedReasons.push("缺少证据");
                  return (
                    <>
                      {(degraded || insufficient) && (
                        <div className="mb-3">
                          <DegradedBanner
                            title={insufficient ? "数据不足，不可批准" : "降级草稿，不可直接批准"}
                            reasons={degradedReasons}
                          />
                        </div>
                      )}
                      {isDraft && !degraded && !insufficient && (
                        <div className="mb-3 rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-xs font-semibold text-amber-800">
                          草稿 / 待审 — 非正式可交易建议
                          {approveBlocked ? "；当前状态不可批准（数据不足、降级或无证据）" : ""}
                        </div>
                      )}
                      <div className="flex flex-wrap items-center justify-between gap-3">
                        <div className="flex flex-wrap items-center gap-2">
                          <StatusPill status={pickFirst(recommendation, ["recommendation"])} />
                          <StatusPill status={pickFirst(recommendation, ["approvalStatus", "approval_status"])} />
                          <RecommendationQualityBadges item={recommendation} />
                          <span className="text-sm text-gray-500">
                            置信度 {formatConfidence(pickFirst(recommendation, ["confidence"]))}
                          </span>
                          <span className="text-sm text-gray-500">Token {formatNumber(totalTokens)}</span>
                        </div>
                        <div className="flex flex-col items-end gap-1">
                          <div className="flex items-center gap-2">
                            <button
                              type="button"
                              data-testid="recommendation-approve"
                              onClick={() => onDecision(recommendation, "approve")}
                              disabled={Boolean(busyPrefix) || approveBlocked}
                              title={approveDisabledReason || ""}
                              className="rounded-lg border border-emerald-200 px-3 py-1.5 text-xs font-semibold text-emerald-700 hover:bg-emerald-50 disabled:cursor-not-allowed disabled:opacity-40"
                            >
                              批准
                            </button>
                            <button
                              type="button"
                              data-testid="recommendation-reject"
                              onClick={() => onDecision(recommendation, "reject")}
                              disabled={Boolean(busyPrefix) || approval !== "PENDING_REVIEW"}
                              className="rounded-lg border border-red-200 px-3 py-1.5 text-xs font-semibold text-red-700 hover:bg-red-50 disabled:cursor-not-allowed disabled:opacity-40"
                            >
                              驳回
                            </button>
                          </div>
                          {approveDisabledReason && (
                            <span data-testid="approve-disabled-reason" className="text-[11px] text-amber-700">
                              {approveDisabledReason}
                            </span>
                          )}
                        </div>
                      </div>
                      <p className="mt-3 text-sm leading-6 text-gray-600">
                        {displayValue(pickFirst(recommendation, ["disclaimer"])) || "-"}
                      </p>
                      <JsonViewer
                        compact
                        value={summarizeJson(pickFirst(recommendation, ["rationaleJson", "rationale_json"]))}
                      />
                    </>
                  );
                })()}
              </section>

              <section className="rounded-xl border border-gray-200 bg-white p-4">
                <h4 className="text-sm font-semibold text-gray-900">证据链</h4>
                <div className="mt-3 space-y-3">
                  {!evidence.length && (
                    <div className="rounded-lg border border-dashed border-gray-200 py-8 text-center text-sm text-gray-400">
                      暂无证据记录
                    </div>
                  )}
                  {evidence.map((item, index) => (
                    <div
                      key={pickFirst(item, ["evidenceId", "evidence_id"]) || index}
                      className="rounded-lg border border-gray-100 bg-gray-50 px-3 py-2"
                    >
                      <div className="flex flex-wrap items-center justify-between gap-2">
                        <p className="font-medium text-gray-900">{displayValue(pickFirst(item, ["title"])) || "-"}</p>
                        <div className="flex items-center gap-2">
                          <Badge tone="blue">
                            {displayValue(pickFirst(item, ["sourceType", "source_type"])) || "source"}
                          </Badge>
                          <Badge>{displayValue(pickFirst(item, ["trustTier", "trust_tier"])) || "TIER"}</Badge>
                        </div>
                      </div>
                      <p className="mt-1 text-sm text-gray-600">{displayValue(pickFirst(item, ["summary"])) || "-"}</p>
                      {pickFirst(item, ["url"]) && (
                        <a
                          className="mt-1 block truncate text-xs text-blue-700 hover:underline"
                          href={pickFirst(item, ["url"])}
                          target="_blank"
                          rel="noreferrer"
                        >
                          {pickFirst(item, ["url"])}
                        </a>
                      )}
                    </div>
                  ))}
                </div>
              </section>

              <section className="rounded-xl border border-gray-200 bg-white p-4">
                <h4 className="text-sm font-semibold text-gray-900">模型调用</h4>
                <div className="mt-3 overflow-auto">
                  <table className="w-full min-w-[760px] divide-y divide-gray-200">
                    <thead className="bg-gray-50">
                      <tr>
                        {["Provider", "Model", "状态", "Prompt", "Completion", "Total"].map((head) => (
                          <th key={head} className="table-header">
                            {head}
                          </th>
                        ))}
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-gray-100 bg-white">
                      {!llmCalls.length && (
                        <tr>
                          <td colSpan={6} className="px-4 py-8 text-center text-sm text-gray-400">
                            暂无模型调用记录
                          </td>
                        </tr>
                      )}
                      {llmCalls.map((call, index) => (
                        <tr key={pickFirst(call, ["llmCallId", "llm_call_id"]) || index}>
                          <td className="table-cell">{displayValue(pickFirst(call, ["provider"])) || "-"}</td>
                          <td className="table-cell font-mono text-xs">
                            {displayValue(pickFirst(call, ["modelName", "model_name"])) || "-"}
                          </td>
                          <td className="table-cell">
                            <StatusPill status={pickFirst(call, ["status"])} />
                          </td>
                          <td className="table-cell">
                            {formatNumber(pickFirst(call, ["promptTokens", "prompt_tokens"]))}
                          </td>
                          <td className="table-cell">
                            {formatNumber(pickFirst(call, ["completionTokens", "completion_tokens"]))}
                          </td>
                          <td className="table-cell">
                            {formatNumber(pickFirst(call, ["totalTokens", "total_tokens"]))}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </section>
            </div>
          )}
        </div>
      </aside>
    </div>
  );
}
