"use client";

import React, { useCallback, useEffect, useMemo, useState } from "react";
import { ModalShell } from "../../components/ui";

export function AgentPage({ api }) {
  const [agents, setAgents] = useState([]);
  const [modalMode, setModalMode] = useState(null);
  const [selectedAgent, setSelectedAgent] = useState(null);
  const [form, setForm] = useState({ name: "", desc: "", type: "用户", schedule: "手动触发", prompt: "" });
  const [busy, setBusy] = useState(false);
  const [logResult, setLogResult] = useState(null);
  const [configNotice, setConfigNotice] = useState("");
  const [runForm, setRunForm] = useState({ subject: "manual agent test", ticker: "AAPL", model: "", apiKey: "" });

  const load = useCallback(() => api("/agents").then(setAgents), [api]);
  useEffect(() => {
    load().catch(() => {});
  }, [load]);
  const runningCount = useMemo(() => agents.filter((agent) => (agent.status || "IDLE") === "RUNNING").length, [agents]);

  const toCard = (agent) => ({
    id: agent.agentId,
    name: agent.name,
    desc: agent.description || agent.category,
    status: (agent.status || "IDLE") === "RUNNING" ? "运行中" : "已停止",
    type: agent.systemPreset ? "系统" : "用户",
    schedule: agent.scheduleCron || "手动触发",
    lastRun: agent.lastRunAt || "尚未运行",
    raw: agent,
  });

  const openCreateModal = () => {
    setForm({
      name: "",
      desc: "",
      type: "用户",
      schedule: "手动触发",
      prompt: "你是 Aegis Alpha 投研 Agent，请基于流程 state 输出结构化、可执行结论。",
    });
    setSelectedAgent(null);
    setConfigNotice("");
    setModalMode("create");
  };
  const openConfigModal = async (agent) => {
    setLogResult(null);
    let editableAgent = agent;
    let notice = "";

    if (agent.readonlyFlag) {
      const existingCopy = agents.find(
        (item) => !item.readonlyFlag && item.name === agent.name && item.agentId !== agent.agentId,
      );
      if (existingCopy) {
        editableAgent = existingCopy;
        notice = "系统预设 Agent 是只读模板，已打开同名用户副本进行配置。";
      } else {
        setBusy(true);
        try {
          editableAgent = await api(`/agents/${agent.agentId}/copy`, { method: "POST" });
          notice = "系统预设 Agent 是只读模板，已自动复制为用户副本，下面保存的是副本配置。";
          await load();
        } catch (error) {
          setSelectedAgent(agent);
          setLogResult({
            ok: false,
            provider: "request-error",
            content: error.message || "复制 Agent 失败",
            reason: error.message || "Request failed",
          });
          setModalMode("log");
          return;
        } finally {
          setBusy(false);
        }
      }
    }

    const card = toCard(editableAgent);
    setSelectedAgent(editableAgent);
    setConfigNotice(notice);
    setForm({
      name: card.name,
      desc: card.desc,
      type: card.type,
      schedule: card.schedule,
      prompt: editableAgent.prompt || "",
    });
    setModalMode("config");
  };
  const closeModal = () => {
    setModalMode(null);
    setSelectedAgent(null);
    setLogResult(null);
    setConfigNotice("");
  };
  const saveAgent = async (event) => {
    event.preventDefault();
    if (!form.name.trim() || !form.desc.trim()) return;
    setBusy(true);
    const body = {
      name: form.name.trim(),
      description: form.desc.trim(),
      category: form.type === "系统" ? "system" : "analyst",
      tags: "analyst,custom",
      prompt: form.prompt,
      scheduleCron: form.schedule,
      modelName: selectedAgent?.modelName || "deepseek-v4-flash",
      toolsJson: selectedAgent?.toolsJson || '["market-data","portfolio","news"]',
    };
    try {
      if (modalMode === "config" && selectedAgent) {
        await api(`/agents/${selectedAgent.agentId}`, { method: "PUT", body: JSON.stringify(body) });
      } else {
        await api("/agents", { method: "POST", body: JSON.stringify(body) });
      }
      await load();
      closeModal();
    } finally {
      setBusy(false);
    }
  };
  const runAgent = async (agent) => {
    setSelectedAgent(agent);
    setModalMode("run");
  };
  const executeAgentRun = async () => {
    if (!selectedAgent) return;
    setLogResult({ loading: true });
    try {
      const body = {
        subject: runForm.subject,
        model: runForm.model || undefined,
        state: { ticker: runForm.ticker, subject: runForm.subject },
      };
      if (runForm.apiKey.trim()) body.apiKey = runForm.apiKey.trim();
      const result = await api(`/agents/${selectedAgent.agentId}/run`, {
        method: "POST",
        body: JSON.stringify(body),
      });
      setLogResult(result);
      await load();
    } catch (error) {
      setLogResult({
        ok: false,
        provider: "request-error",
        content: error.message || "Agent 运行失败",
        reason: error.message || "Request failed",
      });
    }
  };
  const copyAgent = async (agent) => {
    await api(`/agents/${agent.agentId}/copy`, { method: "POST" });
    await load();
  };
  const logIsError = logResult && logResult.ok === false;
  const logMeta = logResult?.loading
    ? "RUNNING · LangGraph"
    : `${logIsError ? "ERROR" : "INFO"} · ${logResult?.provider || "LangGraph"}`;

  return (
    <div className="space-y-6">
      <div className="flex flex-col gap-4 lg:flex-row lg:items-center lg:justify-between">
        <div>
          <h2 className="text-lg font-semibold text-gray-800">AI+ Agent</h2>
          <p className="mt-1 text-sm text-gray-500">
            共 {agents.length} 个 Agent，{runningCount} 个正在运行
          </p>
        </div>
        <button onClick={openCreateModal} className="btn-primary text-sm">
          新建 Agent
        </button>
      </div>
      <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
        {agents.map((agent) => {
          const card = toCard(agent);
          return (
            <div key={agent.agentId} className="card transition-shadow hover:shadow-md">
              <div className="flex items-start justify-between gap-4">
                <div>
                  <h3 className="font-medium text-gray-800">{card.name}</h3>
                  <p className="mt-1 text-sm text-gray-500">{card.desc}</p>
                </div>
                <span className={`badge ${card.status === "运行中" ? "badge-green" : "badge-gray"}`}>
                  {card.status}
                </span>
              </div>
              <div className="mt-4 grid grid-cols-2 gap-3 border-t border-gray-100 pt-3 text-xs text-gray-500">
                <span>类型：{card.type}</span>
                <span>频率：{card.schedule}</span>
                <span className="col-span-2">上次运行：{card.lastRun}</span>
              </div>
              <div className="mt-3 flex flex-wrap gap-2">
                <button
                  onClick={() => openConfigModal(agent)}
                  className="btn-secondary min-h-8 px-3 py-1.5 text-xs"
                  disabled={busy}
                >
                  {agent.readonlyFlag ? "配置副本" : "配置"}
                </button>
                <button onClick={() => runAgent(agent)} className="btn-secondary min-h-8 px-3 py-1.5 text-xs">
                  启动
                </button>
                <button onClick={() => copyAgent(agent)} className="btn-secondary min-h-8 px-3 py-1.5 text-xs">
                  复制
                </button>
                <button
                  onClick={() => {
                    setSelectedAgent(agent);
                    setModalMode("log");
                    setLogResult(null);
                  }}
                  className="btn-secondary min-h-8 px-3 py-1.5 text-xs"
                >
                  日志
                </button>
              </div>
            </div>
          );
        })}
      </div>
      {(modalMode === "create" || modalMode === "config") && (
        <ModalShell title={modalMode === "config" ? "配置 Agent" : "新建 Agent"} onClose={closeModal}>
          <form onSubmit={saveAgent} className="p-6">
            {configNotice && (
              <div className="mb-4 rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-xs text-amber-700">
                {configNotice}
              </div>
            )}
            <div className="space-y-4">
              <label className="block">
                <span className="mb-1 block text-sm font-medium text-gray-700">Agent 名称</span>
                <input
                  value={form.name}
                  onChange={(event) => setForm((current) => ({ ...current, name: event.target.value }))}
                  className="input-field"
                  placeholder="例如：港股公告监控 Agent"
                />
              </label>
              <label className="block">
                <span className="mb-1 block text-sm font-medium text-gray-700">任务描述</span>
                <textarea
                  value={form.desc}
                  onChange={(event) => setForm((current) => ({ ...current, desc: event.target.value }))}
                  className="input-field min-h-[96px] resize-none"
                  placeholder="说明这个 Agent 需要监控、分析或自动执行的任务"
                />
              </label>
              <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
                <label className="block">
                  <span className="mb-1 block text-sm font-medium text-gray-700">类型</span>
                  <select
                    value={form.type}
                    onChange={(event) => setForm((current) => ({ ...current, type: event.target.value }))}
                    className="input-field"
                  >
                    <option>用户</option>
                    <option>系统</option>
                  </select>
                </label>
                <label className="block">
                  <span className="mb-1 block text-sm font-medium text-gray-700">运行频率</span>
                  <select
                    value={form.schedule}
                    onChange={(event) => setForm((current) => ({ ...current, schedule: event.target.value }))}
                    className="input-field"
                  >
                    <option>手动触发</option>
                    <option>每 15 分钟</option>
                    <option>每小时</option>
                    <option>每日 09:00</option>
                    <option>每周一 08:30</option>
                  </select>
                </label>
              </div>
              <label className="block">
                <span className="mb-1 block text-sm font-medium text-gray-700">Prompt</span>
                <textarea
                  value={form.prompt}
                  onChange={(event) => setForm((current) => ({ ...current, prompt: event.target.value }))}
                  className="input-field min-h-[120px] resize-y"
                />
              </label>
            </div>
            <div className="mt-6 flex justify-end gap-3">
              <button type="button" onClick={closeModal} className="btn-secondary">
                取消
              </button>
              <button type="submit" className="btn-primary" disabled={busy}>
                {modalMode === "config" ? "保存配置" : "创建 Agent"}
              </button>
            </div>
          </form>
        </ModalShell>
      )}
      {modalMode === "log" && selectedAgent && (
        <ModalShell title={`${selectedAgent.name} 日志`} onClose={closeModal} widthClass="max-w-xl">
          <div className="p-6">
            <div className="space-y-3 text-sm">
              <div className="rounded-lg border border-gray-200 bg-gray-50 p-3">
                <p className="font-medium text-gray-700">最近运行</p>
                <p className="mt-1 text-gray-500">{selectedAgent.lastRunAt || "尚未运行"}</p>
              </div>
              <div className={`rounded-lg border p-3 ${logIsError ? "border-red-200 bg-red-50" : "border-gray-200"}`}>
                <p className={logIsError ? "text-red-700" : "text-gray-700"}>
                  {logResult?.loading
                    ? "Agent 正在运行..."
                    : logResult?.content || "任务初始化完成，已加载市场数据源和策略参数。"}
                </p>
                {logResult?.reason && <p className="mt-2 text-xs text-red-600">{logResult.reason}</p>}
                <p className={`mt-1 text-xs ${logIsError ? "text-red-500" : "text-gray-400"}`}>{logMeta}</p>
              </div>
            </div>
            <div className="mt-6 flex justify-end">
              <button onClick={closeModal} className="btn-primary">
                关闭
              </button>
            </div>
          </div>
        </ModalShell>
      )}
      {modalMode === "run" && selectedAgent && (
        <ModalShell title={`运行 ${selectedAgent.name}`} onClose={closeModal} widthClass="max-w-xl">
          <div className="p-6">
            <p className="mb-4 text-sm text-gray-500">填写运行参数，直接调用底层大模型验证连通性。</p>
            <div className="space-y-4">
              <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
                <label className="block">
                  <span className="mb-1 block text-sm font-medium text-gray-700">Subject</span>
                  <input
                    className="input-field"
                    value={runForm.subject}
                    onChange={(e) => setRunForm((c) => ({ ...c, subject: e.target.value }))}
                  />
                </label>
                <label className="block">
                  <span className="mb-1 block text-sm font-medium text-gray-700">Ticker</span>
                  <input
                    className="input-field"
                    value={runForm.ticker}
                    onChange={(e) => setRunForm((c) => ({ ...c, ticker: e.target.value }))}
                  />
                </label>
                <label className="block">
                  <span className="mb-1 block text-sm font-medium text-gray-700">模型</span>
                  <input
                    className="input-field"
                    value={runForm.model}
                    onChange={(e) => setRunForm((c) => ({ ...c, model: e.target.value }))}
                    placeholder="默认使用后端配置"
                  />
                </label>
                <label className="block">
                  <span className="mb-1 block text-sm font-medium text-gray-700">API Key（可选）</span>
                  <input
                    className="input-field"
                    value={runForm.apiKey}
                    onChange={(e) => setRunForm((c) => ({ ...c, apiKey: e.target.value }))}
                    placeholder="不填则使用后端/编排引擎配置"
                  />
                </label>
              </div>
              <div className="flex justify-end gap-3">
                <button type="button" onClick={closeModal} className="btn-secondary">
                  取消
                </button>
                <button type="button" onClick={executeAgentRun} disabled={busy} className="btn-primary">
                  {busy ? "调用中..." : "测试调用大模型"}
                </button>
              </div>
              {logResult && !logResult.loading && (
                <div className="rounded-xl border border-gray-200 bg-gray-50 p-4 text-sm">
                  <div className="mb-2 text-xs text-gray-500">provider: {logResult.provider || "langgraph"}</div>
                  <div className="whitespace-pre-wrap text-gray-800">
                    {logResult.content || logResult.message || logResult.summary || JSON.stringify(logResult, null, 2)}
                  </div>
                </div>
              )}
              {logResult?.loading && (
                <div className="flex items-center gap-3 rounded-lg border border-gray-200 bg-gray-50 px-4 py-3">
                  <div className="relative h-5 w-5">
                    <div className="absolute inset-0 rounded-full border-2 border-gray-200" />
                    <div className="absolute inset-0 animate-spin rounded-full border-2 border-transparent border-t-blue-500" />
                  </div>
                  <span className="text-sm text-gray-500">Agent 正在调用中...</span>
                </div>
              )}
            </div>
          </div>
        </ModalShell>
      )}
    </div>
  );
}
