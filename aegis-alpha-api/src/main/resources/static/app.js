const API_BASE = "/_backend";
const TOKEN_KEY = "marketmind_access_token";

const appState = {
  route: "home",
  me: null,
  cache: {},
};

const routes = {
  home: { crumb: "主页", loader: null, render: renderHome },
  data: { crumb: "数据中心 / 仪表盘", loader: () => api("/dashboard"), render: renderData },
  portfolio: { crumb: "投资组合 / 总览", loader: () => api("/portfolio/portfolios"), render: renderPortfolio },
  workflow: { crumb: "AI+ / 工作流", loader: () => api("/workflows"), render: renderWorkflow },
  backtest: { crumb: "回测 / 回测管理", loader: () => api("/backtest/history"), render: renderBacktest },
  profile: { crumb: "个人中心 / 个人信息", loader: () => api("/profile"), render: renderProfile },
};

function getToken() {
  return localStorage.getItem(TOKEN_KEY);
}

function setToken(token) {
  if (token) {
    localStorage.setItem(TOKEN_KEY, token);
  } else {
    localStorage.removeItem(TOKEN_KEY);
  }
}

async function api(path, options = {}) {
  const headers = {
    Accept: "application/json",
    ...(options.body ? { "Content-Type": "application/json" } : {}),
    ...(getToken() ? { Authorization: `Bearer ${getToken()}` } : {}),
    ...options.headers,
  };
  const response = await fetch(`${API_BASE}${path}`, {
    ...options,
    headers,
    cache: "no-store",
  });
  const text = await response.text();
  const payload = text ? JSON.parse(text) : null;
  if (!response.ok) {
    if (response.status === 401) {
      logout();
    }
    throw new Error(payload?.message || payload?.detail || `Request failed: ${response.status}`);
  }
  return payload;
}

async function boot() {
  document.getElementById("loginView").hidden = Boolean(getToken());
  document.getElementById("appView").hidden = !getToken();
  if (!getToken()) {
    return;
  }
  try {
    appState.me = await api("/auth/me");
    updateUserChrome();
    const initial = location.hash.replace("#", "") || "home";
    await navigate(routes[initial] ? initial : "home");
  } catch (error) {
    console.error(error);
    logout();
  }
}

function updateUserChrome() {
  document.querySelectorAll("[data-current-user]").forEach((node) => {
    node.textContent = `当前用户: ${appState.me?.username || "guanghui.nie"}`;
  });
}

async function navigate(route, { force = false } = {}) {
  appState.route = route;
  location.hash = route;
  document.getElementById("pageCrumb").textContent = routes[route].crumb;
  document.getElementById("content").innerHTML = `<p class="muted loading">加载中...</p>`;
  document.querySelectorAll(".nav-drawer button").forEach((button) => {
    button.classList.toggle("active", button.dataset.route === route);
  });
  closeDrawer();

  try {
    if (routes[route].loader && (force || !appState.cache[route])) {
      appState.cache[route] = await routes[route].loader();
    }
    document.getElementById("content").innerHTML = routes[route].render(appState.cache[route]);
  } catch (error) {
    document.getElementById("content").innerHTML = `
      <section class="card api-error">
        <h2>加载失败</h2>
        <p>${escapeHtml(error.message)}</p>
        <button class="primary" data-action="retry-route">重试</button>
      </section>
    `;
  }
}

function renderHome() {
  return `
    <section class="hero">
      <span class="badge">✣ Aegis Alpha</span>
      <h2>今天想分析什么？</h2>
      <p>像 GPT/Gemini 一样，从一个问题开始。你可以直接提问市场、组合、风险或数据问题。</p>
      <div class="prompt-box">
        <textarea id="homePrompt" placeholder="给 Aegis Alpha 一个任务，例如：帮我评估本周组合风险并给出对冲建议"></textarea>
        <div class="send-row"><button class="send" data-action="send-home-prompt">➤</button></div>
      </div>
      <div class="suggestions">
        <button class="suggestion" data-prompt="帮我展示一下GOOG最近的股价">帮我展示一下GOOG最近的股价</button>
        <button class="suggestion" data-prompt="帮我分析一下ARCC">帮我分析一下ARCC</button>
        <button class="suggestion" data-prompt="帮我分析一下AI行业">帮我分析一下AI行业</button>
      </div>
    </section>
  `;
}

function renderPortfolio(portfolios = []) {
  const rows = portfolios.length
    ? portfolios.map((portfolio) => `
        <tr>
          <td>${escapeHtml(portfolio.name)}</td>
          <td>${formatMoney(portfolio.nav)}</td>
          <td class="${portfolio.returnPct >= 0 ? "positive" : "negative"}">${formatPct(portfolio.returnPct)}</td>
          <td>${portfolio.assets}</td>
          <td>${escapeHtml(portfolio.updatedAt)}</td>
        </tr>
      `).join("")
    : `<tr><td colspan="5" class="muted">还没有组合，请先创建并开始记录资产与交易。</td></tr>`;

  return `
    <section>
      <div class="page-title">
        <div><h2>组合列表</h2></div>
        <div class="toolbar">
          <button data-action="export-portfolios">导出组合</button>
          <button class="primary" data-action="create-portfolio">新建组合</button>
        </div>
      </div>
      <div class="card">
        <table>
          <thead><tr><th>组合</th><th>净值</th><th>收益</th><th>资产数</th><th>更新时间</th></tr></thead>
          <tbody>${rows}</tbody>
        </table>
      </div>
    </section>
  `;
}

function renderWorkflow(workflows = []) {
  return `
    <section>
      <div class="page-title">
        <div>
          <h2>工作流预览</h2>
          <p>系统预设工作流（只读、不可删除）。</p>
        </div>
      </div>
      <div class="grid">
        ${workflows.map((workflow) => {
          const key = workflow.workflowKey || workflow.key;
          return `
          <article class="card workflow-card">
            <span class="node-icon">⌘</span>
            <h3>${escapeHtml(workflow.name)}</h3>
            <p>${escapeHtml(key)} · v${workflow.version}</p>
            <p>节点 ${workflow.nodes}　连线 ${workflow.edges}　系统预设</p>
            <button class="workflow-action" data-action="run-workflow" data-workflow="${escapeHtml(key)}">
              <span>打开流程图</span><span>→</span>
            </button>
          </article>
        `}).join("")}
      </div>
    </section>
  `;
}

function renderBacktest(runs = []) {
  const rows = runs.length
    ? runs.map((run) => `
        <tr>
          <td>${escapeHtml(run.runName)}</td>
          <td>${escapeHtml(run.strategy)}</td>
          <td>${escapeHtml(run.status)}</td>
          <td class="${run.totalReturnPct >= 0 ? "positive" : "negative"}">${formatPct(run.totalReturnPct)}</td>
          <td>${run.sharpe.toFixed(2)}</td>
          <td>${escapeHtml(run.startedAt)}</td>
        </tr>
      `).join("")
    : `<tr><td colspan="6" class="muted">暂无回测历史。</td></tr>`;
  return `
    <section>
      <div class="page-title">
        <div>
          <h2>回测历史</h2>
          <p>按条件筛选历史回测，并查看关键指标与走势。</p>
        </div>
        <button class="primary" data-action="start-backtest">新建回测</button>
      </div>
      <div class="card">
        <table>
          <thead><tr><th>任务</th><th>策略</th><th>状态</th><th>总收益</th><th>Sharpe</th><th>开始时间</th></tr></thead>
          <tbody>${rows}</tbody>
        </table>
      </div>
    </section>
  `;
}

function renderProfile(profile) {
  const user = profile || appState.me || {};
  return `
    <section>
      <div class="page-title">
        <div><h2>个人信息</h2></div>
        <div class="profile-tabs">
          <button>策略</button>
          <button>审计</button>
          <button>设置</button>
        </div>
      </div>
      <div class="card">
        <p><strong>用户名:</strong> ${escapeHtml(user.username || "")}</p>
        <p><strong>用户 ID:</strong> ${escapeHtml(user.userId || user.user_id || "")}</p>
        <p><strong>租户 ID:</strong> ${escapeHtml(user.tenantId || user.tenant_id || "")}</p>
        <p><strong>角色:</strong> ${escapeHtml((user.roles || ["portfolio_manager"]).join(", "))}</p>
      </div>
    </section>
  `;
}

function renderData(data) {
  if (!data) {
    return "";
  }
  return `
    <section>
      <div class="page-title">
        <div>
          <h2>仪表盘</h2>
          <p>核心投资数据、日更分析与个股研究入口总览。</p>
        </div>
      </div>

      <section class="section">
        <h2>宏观可视化</h2>
        <p class="muted">四大经济体经济四象限与信用脉冲的 UI 原型展示。</p>
        <article class="card">
          <h3>经济四象限</h3>
          <div class="chart-box quadrant"></div>
          ${legend()}
          ${table(["日期", "中国", "美国", "欧洲", "日本"], data.quadrantRows)}
        </article>
        <article class="card">
          <h3>信用脉冲</h3>
          <div class="chart-box credit"></div>
          ${legend()}
          ${table(["日期", "中国", "美国", "欧洲", "日本"], data.creditRows, true)}
        </article>
      </section>

      <article class="card section">
        <div class="page-title">
          <div>
            <h3>日更分析</h3>
            <p>状态: ${escapeHtml(data.daily.status)}</p>
            <p><a>打开每日报告</a>　|　<a>打开审计追踪</a></p>
          </div>
          <button class="primary" data-action="run-daily">运行日更分析</button>
        </div>
      </article>

      <section class="grid metrics section">
        ${Object.entries(data.counts).map(([label, value]) => `<div class="metric"><small>${escapeHtml(label)}</small><strong>${value}</strong></div>`).join("")}
      </section>

      <article class="card section">
        <h3>宏观指标</h3>
        <div class="grid two">
          ${data.indicators.map((item) => metricCard(item.name, item.value, item.subtitle)).join("")}
        </div>
      </article>

      <article class="card section">
        <div class="page-title">
          <h3>全球市场快照</h3>
          <p>上涨 ${data.marketBreadth.up} / 下跌 ${data.marketBreadth.down}（上涨占比 ${data.marketBreadth.upRatio}%）</p>
        </div>
        <div class="list">
          ${data.markets.map((market) => `
            <div class="market-row">
              <span>${escapeHtml(market.name)} <span class="muted">(${escapeHtml(market.symbol)})</span></span>
              <span class="${market.changePct < 0 ? "negative" : "positive"}">${formatPct(market.changePct)}</span>
            </div>
          `).join("")}
        </div>
      </article>

      <article class="card section">
        <div class="page-title">
          <h3>个股研究</h3>
          <button>单票研究</button>
        </div>
      </article>

      <article class="card section">
        <h3>行业分析工作流</h3>
        <div class="page-title">
          <input id="sectorInput" placeholder="输入行业（例如 semiconductor）" />
          <button class="primary" data-action="run-sector">运行行业工作流</button>
        </div>
        <p class="muted">用于直接触发 sector-analyst-workflow。</p>
      </article>

      <article class="card section">
        <h3>打开每日报告</h3>
        <p class="muted">${data.daily.reports.length ? `${data.daily.reports.length} 份日报。` : "暂无日报。"}</p>
      </article>
    </section>
  `;
}

function metricCard(label, value, subtitle) {
  return `<div class="metric"><small>${escapeHtml(label)}</small><strong>${escapeHtml(String(value))}</strong><small>${escapeHtml(subtitle)}</small></div>`;
}

function legend() {
  return `
    <div class="legend">
      <span class="pill cn">● 中国</span>
      <span class="pill us">● 美国</span>
      <span class="pill eu">● 欧洲</span>
      <span class="pill jp">● 日本</span>
    </div>
  `;
}

function table(headers, rows, signed = false) {
  return `
    <table>
      <thead><tr>${headers.map((h) => `<th>${escapeHtml(h)}</th>`).join("")}</tr></thead>
      <tbody>
        ${rows.map((row) => `
          <tr>
            ${row.map((cell, index) => {
              const value = String(cell);
              const klass = signed && index > 0
                ? value.startsWith("-") ? "negative" : value.startsWith("+") ? "positive" : "muted"
                : "";
              return `<td class="${klass}">${escapeHtml(value)}</td>`;
            }).join("")}
          </tr>
        `).join("")}
      </tbody>
    </table>
  `;
}

function formatMoney(value) {
  return Number(value || 0).toLocaleString("zh-CN", {
    style: "currency",
    currency: "USD",
    maximumFractionDigits: 0,
  });
}

function formatPct(value) {
  const number = Number(value || 0);
  return `${number >= 0 ? "+" : ""}${number.toFixed(2)}%`;
}

function escapeHtml(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}

function openDrawer() {
  document.getElementById("navDrawer").classList.add("open");
  document.getElementById("overlay").hidden = false;
}

function closeDrawer() {
  document.getElementById("navDrawer").classList.remove("open");
  document.getElementById("overlay").hidden = true;
}

function logout() {
  setToken(null);
  document.getElementById("loginView").hidden = false;
  document.getElementById("appView").hidden = true;
  appState.cache = {};
}

async function handleAction(action, target) {
  if (action === "retry-route") {
    await navigate(appState.route, { force: true });
  }
  if (action === "create-portfolio") {
    await api("/portfolio/portfolios", {
      method: "POST",
      body: JSON.stringify({ name: "Core Income Portfolio" }),
    });
    await navigate("portfolio", { force: true });
  }
  if (action === "start-backtest") {
    await api("/backtest/history", {
      method: "POST",
      body: JSON.stringify({ runName: "ARCC income backtest", strategy: "Dividend Carry" }),
    });
    await navigate("backtest", { force: true });
  }
  if (action === "run-daily") {
    await api("/workflow/runs", {
      method: "POST",
      body: JSON.stringify({ workflowKey: "daily", subject: "global market daily" }),
    });
    appState.cache.data = null;
    await navigate("data", { force: true });
  }
  if (action === "run-sector") {
    const subject = document.getElementById("sectorInput")?.value.trim() || "semiconductor";
    await api("/workflow/runs", {
      method: "POST",
      body: JSON.stringify({ workflowKey: "sector-analyst-workflow", subject }),
    });
    openChat(`已启动行业工作流：${subject}`);
  }
  if (action === "run-workflow") {
    const workflowKey = target.dataset.workflow;
    await api("/workflow/runs", {
      method: "POST",
      body: JSON.stringify({ workflowKey, subject: workflowKey }),
    });
    openChat(`已启动工作流：${workflowKey}`);
  }
  if (action === "send-home-prompt") {
    const text = document.getElementById("homePrompt")?.value.trim();
    if (text) {
      const reply = await api("/chat/messages", {
        method: "POST",
        body: JSON.stringify({ message: text }),
      });
      openChat(reply.message);
    }
  }
  if (action === "export-portfolios") {
    const portfolios = appState.cache.portfolio || [];
    const blob = new Blob([JSON.stringify(portfolios, null, 2)], { type: "application/json" });
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = "portfolios.json";
    link.click();
    URL.revokeObjectURL(url);
  }
}

function openChat(message) {
  const panel = document.getElementById("chatPanel");
  panel.hidden = false;
  if (message) {
    document.getElementById("chatReply").textContent = message;
  }
}

document.getElementById("loginForm").addEventListener("submit", async (event) => {
  event.preventDefault();
  document.getElementById("loginError").hidden = true;
  try {
    const payload = await api("/auth/login", {
      method: "POST",
      body: JSON.stringify({
        username: document.getElementById("username").value.trim(),
        password: document.getElementById("password").value,
      }),
    });
    setToken(payload.access_token);
    await boot();
  } catch (error) {
    document.getElementById("loginError").textContent = error.message || "账号或密码错误。";
    document.getElementById("loginError").hidden = false;
  }
});

document.getElementById("logoutBtn").addEventListener("click", logout);
document.getElementById("menuBtn").addEventListener("click", openDrawer);
document.getElementById("overlay").addEventListener("click", closeDrawer);

document.querySelectorAll("[data-route]").forEach((button) => {
  button.addEventListener("click", () => navigate(button.dataset.route));
});

document.getElementById("content").addEventListener("click", async (event) => {
  const promptButton = event.target.closest("[data-prompt]");
  if (promptButton) {
    document.getElementById("homePrompt").value = promptButton.dataset.prompt;
    return;
  }
  const actionTarget = event.target.closest("[data-action]");
  if (!actionTarget) {
    return;
  }
  try {
    await handleAction(actionTarget.dataset.action, actionTarget);
  } catch (error) {
    openChat(error.message);
  }
});

document.getElementById("chatButton").addEventListener("click", () => openChat());
document.getElementById("closeChat").addEventListener("click", () => {
  document.getElementById("chatPanel").hidden = true;
});

window.addEventListener("hashchange", () => {
  const route = location.hash.replace("#", "") || "home";
  if (getToken() && routes[route] && appState.route !== route) {
    navigate(route);
  }
});

boot();
