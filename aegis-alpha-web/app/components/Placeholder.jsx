"use client";

import React from "react";

export function Placeholder({
  title,
  desc,
  description,
  status = "接入中",
  eta,
  targetCapability,
}) {
  const body =
    description ||
    desc ||
    "该能力正在接入真实业务数据，当前入口仅展示上线状态。";
  return (
    <div className="card" data-testid="placeholder-page">
      <div className="mb-2 flex flex-wrap items-center gap-2">
        <h2 className="text-lg font-semibold text-gray-800">{title}</h2>
        <span
          data-testid="placeholder-status"
          className="inline-flex rounded-full border border-amber-300 bg-amber-50 px-2 py-0.5 text-[11px] font-semibold text-amber-800"
        >
          {status}
        </span>
      </div>
      <p className="text-sm text-gray-500">{body}</p>
      {(eta || targetCapability) && (
        <div className="mt-3 space-y-1 text-xs text-gray-500">
          {eta ? <p>预计：{eta}</p> : null}
          {targetCapability ? <p>目标能力：{targetCapability}</p> : null}
        </div>
      )}
      <div className="mt-6 rounded-lg border border-dashed border-gray-300 bg-gray-50 py-10 text-center text-sm text-gray-500">
        功能接入中，后端契约稳定后开放操作。
      </div>
    </div>
  );
}
