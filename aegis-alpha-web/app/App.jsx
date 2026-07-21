"use client";

import React, { useCallback, useEffect, useState } from "react";

import { Header } from "./components/Header";
import { Placeholder } from "./components/Placeholder";
import { Sidebar } from "./components/Sidebar";
import { ChatBubbleIcon, useFocusTrap } from "./components/ui";
import { AgentPage } from "./features/agent/AgentPage";
import { LoginPage } from "./features/auth/LoginPage";
import { BacktestHistoryPage } from "./features/backtest/BacktestHistoryPage";
import { AICopilot } from "./features/copilot/AICopilot";
import { Dashboard } from "./features/dashboard/Dashboard";
import { AuditLogPage } from "./features/governance/AuditLogPage";
import { ModelGovernancePage } from "./features/governance/ModelGovernancePage";
import { Home } from "./features/home/Home";
import { Portfolio } from "./features/portfolio/Portfolio";
import { RecommendationHistoryPage } from "./features/recommendations/RecommendationHistoryPage";
import { WorkflowRunCenterPage } from "./features/runs/WorkflowRunCenterPage";
import { WorkflowPage } from "./features/workflow/WorkflowPage";
import { request } from "./lib/request";
import {
  extractDeepLinkRecommendationId,
  extractDeepLinkRunId,
  normalizePathname,
  pageTitles,
  readInitialPathname,
} from "./lib/routing";

export default function App() {
  const [path, setPath] = useState(readInitialPathname);
  const [copilotOpen, setCopilotOpen] = useState(false);
  const [copilotPrompt, setCopilotPrompt] = useState(null);
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false);
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false);
  const [token, setToken] = useState("");
  const [me, setMe] = useState(null);
  const [booting, setBooting] = useState(true);
  const mobileMenuRef = useFocusTrap(mobileMenuOpen, () => setMobileMenuOpen(false));

  useEffect(() => {
    const saved = window.localStorage.getItem("aegis_access_token");
    if (!saved) {
      setBooting(false);
      return;
    }
    setToken(saved);
    request("/auth/me", {}, saved)
      .then(setMe)
      .catch(() => {
        window.localStorage.removeItem("aegis_access_token");
        setToken("");
      })
      .finally(() => setBooting(false));
  }, []);
  const api = useCallback((target, options = {}) => request(target, options, token), [token]);
  useEffect(() => {
    const syncFromHistory = () => setPath(readInitialPathname());
    window.addEventListener("popstate", syncFromHistory);
    return () => window.removeEventListener("popstate", syncFromHistory);
  }, []);
  const navigate = useCallback((target) => {
    const nextUrl = String(target || "/");
    const nextPath = normalizePathname(nextUrl);
    if (typeof window !== "undefined") {
      const currentUrl = `${window.location.pathname}${window.location.search}`;
      if (currentUrl !== nextUrl) window.history.pushState({}, "", nextUrl);
    }
    setPath(nextPath);
    setMobileMenuOpen(false);
  }, []);
  const openCopilotWithPrompt = useCallback((text) => {
    setCopilotPrompt({ id: `${Date.now()}-${Math.random().toString(36).slice(2)}`, text });
    setCopilotOpen(true);
  }, []);
  const clearCopilotPrompt = useCallback(() => setCopilotPrompt(null), []);
  const logout = () => {
    window.localStorage.removeItem("aegis_access_token");
    setToken("");
    setMe(null);
  };
  const login = (accessToken, user) => {
    window.localStorage.setItem("aegis_access_token", accessToken);
    setToken(accessToken);
    setMe(user);
  };

  if (booting)
    return <div className="grid min-h-screen place-items-center text-sm text-gray-500">加载 Aegis Alpha...</div>;
  if (!token) return <LoginPage onLogin={login} />;

  const deepLinkRunId = extractDeepLinkRunId(path);
  const deepLinkWorkflowRunId = extractDeepLinkRecommendationId(path);

  let page = <Home navigate={navigate} openCopilotWithPrompt={openCopilotWithPrompt} />;
  if (path === "/agent") page = <AgentPage api={api} />;
  else if (path === "/workflow/runs" || deepLinkRunId)
    page = <WorkflowRunCenterPage api={api} navigate={navigate} deepLinkRunId={deepLinkRunId} />;
  else if (path === "/workflow") page = <WorkflowPage api={api} token={token} />;
  else if (path.startsWith("/portfolio")) page = <Portfolio api={api} navigate={navigate} path={path} />;
  else if (path === "/data-center/dashboard") page = <Dashboard api={api} />;
  else if (path.startsWith("/data-center")) page = <Dashboard api={api} />;
  else if (path === "/backtest/history") page = <BacktestHistoryPage api={api} />;
  else if (path.startsWith("/backtest"))
    page = (
      <Placeholder
        title={pageTitles[path] || "回测"}
        desc="回测管理入口保持导航可达，策略编排与执行能力接入后开放。"
        eta="后续迭代"
        targetCapability="策略回测编排与结果对比"
      />
    );
  else if (path === "/governance/models") page = <ModelGovernancePage api={api} />;
  else if (path === "/governance/audit") page = <AuditLogPage api={api} />;
  else if (path === "/recommendations" || deepLinkWorkflowRunId)
    page = (
      <RecommendationHistoryPage
        api={api}
        navigate={navigate}
        deepLinkWorkflowRunId={deepLinkWorkflowRunId}
      />
    );
  else if (path === "/watchlist")
    page = (
      <Placeholder
        title="自选股"
        desc="自选股页面保持当前导航样式，后续可接入关注列表与价格提醒。"
        targetCapability="自选股列表与价格提醒"
      />
    );
  else if (path.startsWith("/profile")) page = <Placeholder title={pageTitles[path] || "个人中心"} />;
  else if (path === "/report")
    page = (
      <Placeholder
        title="Report"
        desc="报告生成入口，沿用 Aegis Alpha 平台风格。"
        targetCapability="研究报告生成与导出"
      />
    );

  return (
    <div className="flex h-screen overflow-hidden bg-white">
      <Sidebar path={path} navigate={navigate} collapsed={sidebarCollapsed} setCollapsed={setSidebarCollapsed} />
      <div className="flex min-w-0 flex-1 flex-col">
        <Header
          path={path}
          copilotOpen={copilotOpen}
          setCopilotOpen={setCopilotOpen}
          me={me}
          onLogout={logout}
          onMenuOpen={() => setMobileMenuOpen(true)}
        />
        <main className="flex-1 overflow-auto bg-white">
          <div className="p-4 sm:p-5 lg:p-6">{page}</div>
        </main>
      </div>
      {mobileMenuOpen && (
        <div
          ref={mobileMenuRef}
          role="dialog"
          aria-modal="true"
          aria-label="导航菜单"
          tabIndex={-1}
          className="fixed inset-0 z-50 lg:hidden"
        >
          <div className="absolute inset-0 bg-gray-900/35" onClick={() => setMobileMenuOpen(false)} />
          <div className="absolute inset-y-0 left-0 bg-white shadow-2xl">
            <Sidebar
              path={path}
              navigate={navigate}
              collapsed={false}
              setCollapsed={setSidebarCollapsed}
              mobile
              onNavigate={() => setMobileMenuOpen(false)}
            />
          </div>
        </div>
      )}
      {!copilotOpen && (
        <button
          type="button"
          onClick={() => setCopilotOpen(true)}
          className="fixed bottom-6 right-6 z-40 grid h-10 w-10 place-items-center rounded-full bg-neutral-950 text-white shadow-lg transition-colors hover:bg-neutral-800"
          aria-label="打开 AI Copilot"
        >
          <ChatBubbleIcon className="h-5 w-5" />
        </button>
      )}
      {copilotOpen && (
        <AICopilot
          setCopilotOpen={setCopilotOpen}
          api={api}
          promptRequest={copilotPrompt}
          onPromptHandled={clearCopilotPrompt}
          pageContext={{
            path,
            runId: (() => {
              const m = String(path || "").match(/^\/runs\/([^/?#]+)/);
              return m ? m[1] : undefined;
            })(),
            recommendationId: (() => {
              const m = String(path || "").match(/^\/recommendations\/([^/?#]+)/);
              return m ? m[1] : undefined;
            })(),
          }}
        />
      )}
    </div>
  );
}
