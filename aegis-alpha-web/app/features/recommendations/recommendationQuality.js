import { pickFirst } from "../../lib/format";

export function isRecommendationDegradedDraft(item) {
  if (!item) return false;
  const recLabel = String(pickFirst(item, ["recommendation"]) || "").toUpperCase();
  const disclaimer = String(pickFirst(item, ["disclaimer"]) || "");
  const rationale = pickFirst(item, ["rationaleJson", "rationale_json"]);
  let rationaleDegraded = false;
  if (rationale && typeof rationale === "object") {
    rationaleDegraded = Boolean(rationale.degraded);
  } else if (typeof rationale === "string" && rationale.includes("degraded")) {
    try {
      const parsed = JSON.parse(rationale);
      rationaleDegraded = Boolean(parsed?.degraded);
    } catch {
      rationaleDegraded = /"degraded"\s*:\s*true/i.test(rationale);
    }
  }
  return (
    recLabel === "INSUFFICIENT_DATA" ||
    disclaimer.includes("DEGRADED") ||
    disclaimer.includes("DRAFT/DEGRADED") ||
    rationaleDegraded
  );
}

/** Prefer explicit `approvable` from detail API; otherwise heuristic on label / disclaimer / evidence. */
export function resolveRecommendationApprovable(item, { approvable, evidence } = {}) {
  if (!item) return false;
  const approval = String(pickFirst(item, ["approvalStatus", "approval_status"]) || "").toUpperCase();
  if (approval && approval !== "PENDING_REVIEW") return false;
  if (typeof approvable === "boolean") return approvable;
  if (typeof pickFirst(item, ["approvable"]) === "boolean") {
    return Boolean(pickFirst(item, ["approvable"]));
  }
  if (isRecommendationDegradedDraft(item)) return false;
  if (Array.isArray(evidence) && evidence.length === 0) return false;
  return approval === "PENDING_REVIEW" || !approval;
}

/**
 * Explicit human-readable reason when approve is disabled for quality / status reasons.
 * Returns empty string when approve is allowed.
 */
export function resolveApproveDisabledReason(item, { approvable, evidence } = {}) {
  if (!item) return "缺少推荐数据";
  const approval = String(pickFirst(item, ["approvalStatus", "approval_status"]) || "").toUpperCase();
  const recLabel = String(pickFirst(item, ["recommendation"]) || "").toUpperCase();

  if (approval && approval !== "PENDING_REVIEW") {
    if (approval === "APPROVED") return "已批准";
    if (approval === "REJECTED") return "已驳回";
    return "非待审状态，不可批准";
  }
  if (recLabel === "INSUFFICIENT_DATA") return "数据不足，不可批准";
  if (isRecommendationDegradedDraft(item)) return "降级草稿，请重新运行补全证据";
  if (Array.isArray(evidence) && evidence.length === 0) return "缺少证据";

  const canApprove = resolveRecommendationApprovable(item, { approvable, evidence });
  if (!canApprove) {
    if (typeof approvable === "boolean" && !approvable) {
      return "数据不足，不可批准";
    }
    return "缺少证据";
  }
  return "";
}
