"use client";

import React from "react";

/**
 * Amber warning banner for degraded workflow runs / recommendation drafts.
 */
export function DegradedBanner({
  title = "运行已降级",
  reasons = [],
  className = "",
  children,
}) {
  const list = Array.isArray(reasons)
    ? reasons.map((item) => String(item || "").trim()).filter(Boolean)
    : reasons
      ? [String(reasons)]
      : [];

  return (
    <div
      data-testid="degraded-banner"
      className={`rounded-lg border border-amber-300 bg-amber-50 px-3 py-2 text-sm text-amber-900 ${className}`}
      role="status"
    >
      <p className="font-semibold">{title}</p>
      {list.length > 0 && (
        <ul className="mt-1 list-disc space-y-0.5 pl-5 text-xs text-amber-800">
          {list.map((reason, index) => (
            <li key={`${index}-${reason}`}>{reason}</li>
          ))}
        </ul>
      )}
      {children}
    </div>
  );
}
