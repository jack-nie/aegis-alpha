import React, { useCallback, useEffect, useState } from "react";

export function AgentTestPage({ api }) {
  const [agents, setAgents] = useState([]);
  const [agentId, setAgentId] = useState("");
  const [model, setModel] = useState("deepseek-v4-flash");
  const [apiKey, setApiKey] = useState("");
  const [subject, setSubject] = useState("manual agent llm test");
  const [ticker, setTicker] = useState("AAPL");
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState(null);

  useEffect(() => { api("/agents").then((list) => { setAgents(list); if (list.length) setAgentId(list[0].agentId); }).catch(() => {}); }, [api]);

  const run = useCallback(async () => {
    if (!agentId) return;
    setBusy(true);
    setResult(null);
    try {
      const body = {
        subject,
        model,
        state: { ticker, subject },
      };
      if (apiKey.trim()) body.apiKey = apiKey.trim();
      const res = await api(`/agents/${agentId}/run`, { method: "POST", body: JSON.stringify(body) });
      setResult(res);
    } catch (e) {
      setResult({ ok: false, content: e.message || "Agent 测试失败" });
    } finally {
      setBusy(false);
    }
  }, [api, agentId, model, apiKey, subject, ticker]);

  return (
    <div className="space-y-4">
      <h2 className="text-xl font-semibold text-gray-900">Agent 测试</h2>
      <p className="text-sm text-gray-500">选择 Agent，填写输入后直接调用底层大模型验证连通性。</p>

      <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
        <label className="block">
          <span className="mb-1 block text-sm font-medium text-gray-700">Agent</span>
          <select className="input-field" value={agentId} onChange={(e) => setAgentId(e.target.value)}>
            {agents.map((a) => <option key={a.agentId} value={a.agentId}>{a.name}</option>)}
          </select>
        </label>
        <label className="block">
          <span className="mb-1 block text-sm font-medium text-gray-700">模型</span>
          <input className="input-field" value={model} onChange={(e) => setModel(e.target.value)} />
        </label>
        <label className="block">
          <span className="mb-1 block text-sm font-medium text-gray-700">API Key（可选）</span>
          <input className="input-field" value={apiKey} onChange={(e) => setApiKey(e.target.value)} placeholder="不填则使用后端/编排引擎配置" />
        </label>
        <label className="block">
          <span className="mb-1 block text-sm font-medium text-gray-700">Ticker</span>
          <input className="input-field" value={ticker} onChange={(e) => setTicker(e.target.value)} />
        </label>
        <label className="block md:col-span-2">
          <span className="mb-1 block text-sm font-medium text-gray-700">Subject</span>
          <input className="input-field" value={subject} onChange={(e) => setSubject(e.target.value)} />
        </label>
      </div>

      <button onClick={run} disabled={busy || !agentId} className="btn-primary text-sm">{busy ? "调用中..." : "测试调用大模型"}</button>

      {result && (
        <div className="rounded-xl border border-gray-200 bg-gray-50 p-4 text-sm">
          <div className="mb-2 text-xs text-gray-500">provider: {result.provider || "langgraph"}</div>
          <div className="whitespace-pre-wrap text-gray-800">{result.content || result.message || result.summary || JSON.stringify(result, null, 2)}</div>
        </div>
      )}
    </div>
  );
}

export function WorkflowTestPage({ api }) {
  const [workflows, setWorkflows] = useState([]);
  const [workflowKey, setWorkflowKey] = useState("");
  const [model, setModel] = useState("deepseek-v4-flash");
  const [apiKey, setApiKey] = useState("");
  const [ticker, setTicker] = useState("AAPL");
  const [industry, setIndustry] = useState("AI Infrastructure");
  const [subject, setSubject] = useState("stock recommendation research");
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState(null);

  useEffect(() => { api("/workflows").then((list) => { setWorkflows(list); if (list.length) setWorkflowKey(list[0].workflowKey); }).catch(() => {}); }, [api]);

  const run = useCallback(async () => {
    if (!workflowKey) return;
    setBusy(true);
    setResult(null);
    try {
      const inputs = { ticker, industry, subject, model };
      if (apiKey.trim()) inputs.apiKey = apiKey.trim();
      const run = await api(`/workflows/${workflowKey}/run`, { method: "POST", body: JSON.stringify({ subject, inputs }) });
      setResult(run);
    } catch (e) {
      setResult({ ok: false, status: "FAILED", error: e.message || "工作流测试失败" });
    } finally {
      setBusy(false);
    }
  }, [api, workflowKey, model, apiKey, ticker, industry, subject]);

  return (
    <div className="space-y-4">
      <h2 className="text-xl font-semibold text-gray-900">工作流测试</h2>
      <p className="text-sm text-gray-500">选择工作流并输入主题参数，直接触发一次真实运行并查看状态。</p>

      <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
        <label className="block">
          <span className="mb-1 block text-sm font-medium text-gray-700">工作流</span>
          <select className="input-field" value={workflowKey} onChange={(e) => setWorkflowKey(e.target.value)}>
            {workflows.map((w) => <option key={w.workflowKey} value={w.workflowKey}>{w.name}</option>)}
          </select>
        </label>
        <label className="block">
          <span className="mb-1 block text-sm font-medium text-gray-700">模型</span>
          <input className="input-field" value={model} onChange={(e) => setModel(e.target.value)} />
        </label>
        <label className="block">
          <span className="mb-1 block text-sm font-medium text-gray-700">API Key（可选）</span>
          <input className="input-field" value={apiKey} onChange={(e) => setApiKey(e.target.value)} placeholder="不填则使用后端/编排引擎配置" />
        </label>
        <label className="block">
          <span className="mb-1 block text-sm font-medium text-gray-700">Ticker</span>
          <input className="input-field" value={ticker} onChange={(e) => setTicker(e.target.value)} />
        </label>
        <label className="block">
          <span className="mb-1 block text-sm font-medium text-gray-700">Industry</span>
          <input className="input-field" value={industry} onChange={(e) => setIndustry(e.target.value)} />
        </label>
        <label className="block md:col-span-2">
          <span className="mb-1 block text-sm font-medium text-gray-700">Subject</span>
          <input className="input-field" value={subject} onChange={(e) => setSubject(e.target.value)} />
        </label>
      </div>

      <button onClick={run} disabled={busy || !workflowKey} className="btn-primary text-sm">{busy ? "调用中..." : "测试运行工作流"}</button>

      {result && (
        <div className="rounded-xl border border-gray-200 bg-gray-50 p-4 text-sm">
          <div className="mb-2 text-xs text-gray-500">status: {result.status}</div>
          <pre className="max-h-80 overflow-auto whitespace-pre-wrap text-gray-800">{JSON.stringify(result, null, 2)}</pre>
        </div>
      )}
    </div>
  );
}

