export const KNOWN_APP_PATHS = new Set([
  "/",
  "/portfolio",
  "/portfolio/assets",
  "/portfolio/trades",
  "/portfolio/portfolios",
  "/agent",
  "/workflow",
  "/workflow/runs",
  "/data-center/dashboard",
  "/data-center/macro",
  "/data-center/stock",
  "/data-center/option",
  "/data-center/index",
  "/data-center/industry",
  "/backtest",
  "/backtest/history",
  "/governance/audit",
  "/governance/models",
  "/recommendations",
  "/watchlist",
  "/profile",
  "/profile/strategy",
  "/profile/audit",
  "/profile/setting",
  "/report",
]);

export function normalizePathname(value) {
  const raw =
    String(value || "/")
      .split("?")[0]
      .replace(/\/+$/, "") || "/";
  if (KNOWN_APP_PATHS.has(raw)) return raw;
  // Deep links that survive refresh/share (Phase 0.5 / P0 UX)
  if (/^\/runs\/[^/]+$/.test(raw)) return raw;
  if (/^\/recommendations\/[^/]+$/.test(raw)) return raw;
  return "/";
}

export function readInitialPathname() {
  if (typeof window === "undefined") return "/";
  return normalizePathname(window.location.pathname);
}

export function readSearchParam(name, fallback = "") {
  if (typeof window === "undefined") return fallback;
  return new URLSearchParams(window.location.search).get(name) || fallback;
}

export function extractDeepLinkRunId(path) {
  const match = String(path || "").match(/^\/runs\/([^/]+)$/);
  if (!match) return "";
  try {
    return decodeURIComponent(match[1]);
  } catch {
    return match[1];
  }
}

export function extractDeepLinkRecommendationId(path) {
  const match = String(path || "").match(/^\/recommendations\/([^/]+)$/);
  if (!match) return "";
  try {
    return decodeURIComponent(match[1]);
  } catch {
    return match[1];
  }
}

export const pageTitles = {
  "/": "Aegis Alpha",
  "/portfolio": "投资组合 / 组合概览",
  "/portfolio/assets": "投资组合 / 持仓",
  "/portfolio/trades": "投资组合 / 交易流水",
  "/portfolio/portfolios": "组合列表",
  "/agent": "AI+ / Agent",
  "/workflow": "AI+ / 工作流",
  "/workflow/runs": "AI+ / 运行中心",
  "/report": "Report",
  "/backtest": "回测管理",
  "/backtest/history": "回测历史",
  "/data-center/dashboard": "数据中心仪表盘",
  "/data-center/macro": "宏观数据",
  "/data-center/stock": "个股数据",
  "/data-center/option": "期权数据",
  "/data-center/index": "指数数据",
  "/data-center/industry": "行业数据",
  "/profile": "个人信息",
  "/profile/strategy": "我的策略",
  "/profile/audit": "审计",
  "/profile/setting": "设置",
  "/governance/audit": "治理 / 审计日志",
  "/governance/models": "治理 / 模型治理",
  "/recommendations": "研究与推荐 / 推荐历史",
};

export function resolvePageTitle(path) {
  if (pageTitles[path]) return pageTitles[path];
  if (extractDeepLinkRunId(path)) return pageTitles["/workflow/runs"];
  if (extractDeepLinkRecommendationId(path)) return pageTitles["/recommendations"];
  return "Aegis Alpha";
}

export const navItems = [
  { label: "主页", icon: "home", path: "/", exact: true },
  {
    label: "投资组合",
    icon: "portfolio",
    children: [
      { label: "组合概览", path: "/portfolio", exact: true },
      { label: "持仓", path: "/portfolio/assets" },
      { label: "交易流水", path: "/portfolio/trades" },
      { label: "组合列表", path: "/portfolio/portfolios" },
    ],
  },
  {
    label: "AI+",
    icon: "bolt",
    children: [
      { label: "Agent", path: "/agent" },
      { label: "工作流", path: "/workflow" },
      { label: "运行中心", path: "/workflow/runs" },
      { label: "报告（接入中）", path: "/report" },
    ],
  },
  { label: "自选股（接入中）", icon: "star", path: "/watchlist" },
  {
    label: "回测",
    icon: "backtest",
    children: [
      { label: "回测管理", path: "/backtest", exact: true },
      { label: "回测历史", path: "/backtest/history" },
    ],
  },
  {
    label: "研究与推荐",
    icon: "market",
    children: [{ label: "推荐历史", path: "/recommendations" }],
  },
  { label: "市场", icon: "market", path: "/data-center/dashboard" },
  {
    label: "治理",
    icon: "audit",
    children: [
      { label: "模型治理", path: "/governance/models" },
      { label: "审计日志", path: "/governance/audit" },
    ],
  },
];
