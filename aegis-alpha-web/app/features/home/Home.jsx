"use client";

import React, { useState } from "react";
import { Icon } from "../../components/ui";

export function Home({ navigate, openCopilotWithPrompt }) {
  const [query, setQuery] = useState("");
  const quickActions = [
    { label: "分析投资组合", icon: "📊", desc: "查看持仓和风险暴露", path: "/portfolio" },
    { label: "回测策略", icon: "📈", desc: "验证交易策略表现", path: "/backtest" },
    { label: "查看数据中心", icon: "🏛️", desc: "宏观、个股、行业数据", path: "/data-center/dashboard" },
    { label: "管理 Agent", icon: "🤖", desc: "配置 AI 自动任务", path: "/agent" },
  ];
  return (
    <div className="mx-auto max-w-4xl">
      <div className="mb-10 text-center">
        <h2 className="mb-2 text-2xl font-bold text-gray-800">Aegis Alpha</h2>
        <p className="text-sm text-gray-500">智能投资研究助手，帮你洞察市场、管理组合、优化策略</p>
      </div>
      <form
        onSubmit={(e) => {
          e.preventDefault();
          const text = query.trim();
          if (text) {
            openCopilotWithPrompt(text);
            setQuery("");
          }
        }}
        className="mb-8"
      >
        <div className="relative">
          <input
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="输入你的问题，例如：分析当前市场宏观环境..."
            className="w-full rounded-xl border border-gray-300 bg-white px-5 py-4 pr-12 text-base shadow-sm focus:border-blue-500 focus:outline-none focus:ring-2 focus:ring-blue-500"
          />
          <button
            type="submit"
            className="absolute right-2 top-1/2 -translate-y-1/2 rounded-lg bg-blue-600 p-2.5 text-white transition-colors hover:bg-blue-700"
            aria-label="发送研究问题"
          >
            <Icon name="send" />
          </button>
        </div>
      </form>
      <div className="mb-8 grid grid-cols-2 gap-4 md:grid-cols-4">
        {quickActions.map((action) => (
          <button
            key={action.label}
            onClick={() => navigate(action.path)}
            className="card group cursor-pointer text-left transition-all hover:border-blue-200 hover:shadow-md"
          >
            <div className="mb-2 text-2xl">{action.icon}</div>
            <h3 className="text-sm font-medium text-gray-800 transition-colors group-hover:text-blue-600">
              {action.label}
            </h3>
            <p className="mt-1 text-xs text-gray-400">{action.desc}</p>
          </button>
        ))}
      </div>
      <div className="card">
        <h3 className="mb-4 font-medium text-gray-800">快速开始</h3>
        <div className="space-y-3">
          {[
            "在搜索框中输入你的研究问题",
            "AI Copilot 将分析数据并给出回答",
            "使用左侧导航浏览数据中心、组合管理和回测功能",
          ].map((text, index) => (
            <div key={text} className="flex items-center gap-3 text-sm text-gray-600">
              <span className="flex h-6 w-6 items-center justify-center rounded-full bg-blue-100 text-xs font-medium text-blue-600">
                {index + 1}
              </span>
              <span>{text}</span>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
