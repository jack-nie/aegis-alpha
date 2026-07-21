"use client";

import React from "react";
import { pickFirst } from "../../lib/format";
import { isRecommendationDegradedDraft } from "./recommendationQuality";

export function RecommendationQualityBadges({ item }) {
  if (!item) return null;
  const recLabel = String(pickFirst(item, ["recommendation"]) || "").toUpperCase();
  const disclaimer = String(pickFirst(item, ["disclaimer"]) || "");
  const insufficient = recLabel === "INSUFFICIENT_DATA";
  // Explicit degraded markers, or helper true beyond pure INSUFFICIENT_DATA label
  const explicitDegraded =
    disclaimer.includes("DEGRADED") ||
    disclaimer.includes("DRAFT/DEGRADED") ||
    disclaimer.includes("[DRAFT") ||
    (isRecommendationDegradedDraft(item) && !insufficient);
  if (!insufficient && !explicitDegraded) return null;
  return (
    <span className="inline-flex flex-wrap items-center gap-1">
      {insufficient && (
        <span
          data-testid="badge-insufficient-data"
          className="inline-flex rounded-full border border-orange-300 bg-orange-50 px-2 py-0.5 text-[11px] font-semibold text-orange-800"
          title="数据不足，不可批准"
        >
          数据不足
        </span>
      )}
      {explicitDegraded && (
        <span
          data-testid="badge-degraded-draft"
          className="inline-flex rounded-full border border-amber-300 bg-amber-50 px-2 py-0.5 text-[11px] font-semibold text-amber-800"
          title="降级草稿，不可批准"
        >
          降级草稿
        </span>
      )}
    </span>
  );
}
