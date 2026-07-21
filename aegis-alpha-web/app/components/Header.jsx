"use client";

import React from "react";
import { resolvePageTitle } from "../lib/routing";
import { Icon } from "./ui";

export function Header({ path, copilotOpen, setCopilotOpen, me, onLogout, onMenuOpen }) {
  const title = resolvePageTitle(path);
  return (
    <header className="min-h-16 flex-shrink-0 border-b border-gray-200 bg-white px-3 py-3 sm:px-4 lg:px-6">
      <div className="flex min-w-0 items-center justify-between gap-3">
        <div className="flex min-w-0 items-center gap-2">
          <button
            type="button"
            onClick={onMenuOpen}
            className="grid h-10 w-10 place-items-center rounded-lg border border-gray-200 bg-white text-gray-800 hover:bg-gray-50 lg:hidden"
            aria-label="打开导航"
          >
            <span className="text-xl leading-none">☰</span>
          </button>
          <h1 className="truncate text-lg font-semibold text-black sm:text-xl">{title}</h1>
        </div>
        <div className="flex shrink-0 items-center gap-2 sm:gap-3">
          <button
            type="button"
            onClick={() => setCopilotOpen(!copilotOpen)}
            className={`grid h-10 w-10 place-items-center rounded-lg border border-gray-200 transition-colors ${copilotOpen ? "bg-gray-100 text-black" : "bg-white text-gray-700 hover:bg-gray-50"}`}
            aria-label={copilotOpen ? "关闭 AI Copilot" : "打开 AI Copilot"}
          >
            <Icon name="send" className="h-4 w-4" />
          </button>
          <span className="hidden max-w-[180px] truncate text-sm text-gray-600 underline underline-offset-2 sm:inline">
            当前用户: {me?.username || "guanghui.nie"}
          </span>
          <button
            type="button"
            onClick={onLogout}
            className="h-10 rounded-lg px-2 text-sm font-semibold text-black hover:bg-gray-50 hover:text-gray-600"
          >
            退出
          </button>
        </div>
      </div>
    </header>
  );
}
