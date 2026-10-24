"use client";

import React, { useCallback, useEffect, useMemo, useState } from "react";
import {
  Background,
  Controls,
  MarkerType,
  MiniMap,
  ReactFlow,
  ReactFlowProvider,
  addEdge,
  applyEdgeChanges,
  applyNodeChanges,
} from "@xyflow/react";

import { DegradedBanner } from "../../components/DegradedBanner";
import { Icon, WorkflowPreviewGlyph } from "../../components/ui";
import { API_BASE } from "../../lib/request";
import { applyStreamEvent, emptyLiveStream, isStreamV2Event } from "./streamEvents";
import {
  defaultWorkflowLayouts,
  workflowCatalogGroups,
  workflowPreviewByKey,
  workflowPreviewDefinitions,
} from "./catalog";
import {
  CatalogNodeDetailDrawer,
  NodeCatalog,
  NodeEditorDrawer,
  WorkflowNodeCard,
  normalizeEdges,
  normalizeNodes,
} from "./WorkflowNodes";

export function WorkflowPage({ api, token }) {
  return (
    <ReactFlowProvider>
      <WorkflowComposer api={api} token={token} />
    </ReactFlowProvider>
  );
}

export function WorkflowComposer({ api, token }) {
  const [workflows, setWorkflows] = useState([]);
  const [agents, setAgents] = useState([]);
  const [workflow, setWorkflow] = useState(null);
  const [, setShowWorkflowList] = useState(false);
  const [activeTab, setActiveTab] = useState("graph");
  const [jsonText, setJsonText] = useState("");
  const [search, setSearch] = useState("");
  const [selectedNodeId, setSelectedNodeId] = useState(null);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [catalogDetailNode, setCatalogDetailNode] = useState(null);
  const [toast, setToast] = useState("");
  const [runStatuses, setRunStatuses] = useState({});
  const [runDegradedReasons, setRunDegradedReasons] = useState([]);
  const [liveStream, setLiveStream] = useState(emptyLiveStream);
  const [runForm, setRunForm] = useState({
    ticker: "AAPL",
    industry: "AI Infrastructure",
    subject: "stock recommendation research",
    model: "",
    apiKey: "",
  });
  const [nodes, setNodes] = useState([]);
  const [edges, setEdges] = useState([]);

  const loadLists = useCallback(
    () =>
      Promise.all([api("/workflows"), api("/agents")]).then(([w, a]) => {
        setWorkflows(w);
        setAgents(a);
      }),
    [api],
  );
  useEffect(() => {
    loadLists().catch(() => {});
  }, [loadLists]);

  const showToast = useCallback((message) => {
    setToast(message);
    window.setTimeout(() => setToast(""), 2400);
  }, []);
  const selectedWorkflowNode = useMemo(
    () => nodes.find((node) => node.id === selectedNodeId) || null,
    [nodes, selectedNodeId],
  );
  const catalogGroups = useMemo(() => {
    const keyword = search.trim().toLowerCase();
    return workflowCatalogGroups.map((group) => ({
      ...group,
      nodes: group.nodes.filter((node) => {
        if (!keyword) return true;
        return [node.label, node.id, node.handler].join(" ").toLowerCase().includes(keyword);
      }),
    }));
  }, [search]);

  const openWorkflow = useCallback(
    async (definition) => {
      let layout = null;
      try {
        layout = await api(`/workflows/${definition.workflowKey}/layout`);
      } catch (ex) {
        layout = defaultWorkflowLayouts[definition.workflowKey];
        if (!layout) throw ex;
        showToast("后端未返回布局，已加载本地默认编排");
      }
      setWorkflow({ ...definition, layout });
      const normalizedNodes = normalizeNodes(layout.nodes || []);
      setNodes(normalizedNodes);
      setEdges(normalizeEdges(layout.edges || []));
      setSelectedNodeId(normalizedNodes[0]?.id || null);
      setDrawerOpen(false);
      setCatalogDetailNode(null);
      setShowWorkflowList(false);
    },
    [api, showToast],
  );

  const previewWorkflows = useMemo(() => {
    const backendByKey = new Map(workflows.map((item) => [item.workflowKey, item]));
    return workflowPreviewDefinitions.map((item) => {
      const backendItem = backendByKey.get(item.workflowKey) || {};
      return { ...backendItem, ...item, description: backendItem.description || item.description || "" };
    });
  }, [workflows]);

  const _createWorkflow = async () => {
    const created = await api("/workflows", {
      method: "POST",
      body: JSON.stringify({
        name: "Custom Agent Workflow",
        description: "可自定义的 Agent 编排流程",
        engine: "langgraph",
      }),
    });
    await loadLists();
    await openWorkflow(created);
  };

  const denormalizeForSave = (nodes, edges) => ({
    nodes: nodes.map(({ id, type, position, data }) => ({
      id,
      type: type === "workflowNode" ? "workflowNode" : type,
      position,
      data: {
        label: data.label || id,
        nodeType: data.nodeType || "logic",
        handler: data.handler || "general.node",
        agentId: data.agentId || "",
        prompt: data.prompt || "",
        inputKeys: data.inputKeys || [],
        outputKeys: data.outputKeys || [],
      },
    })),
    edges: edges.map(({ source, target, animated }) => ({
      id: `${source}-${target}`,
      source,
      target,
      animated: animated !== false,
    })),
  });

  const saveLayout = async () => {
    if (!workflow) return;
    const clean = denormalizeForSave(nodes, edges);
    await api(`/workflows/${workflow.workflowKey}/layout`, {
      method: "PUT",
      body: JSON.stringify({ name: workflow.name, engine: workflow.engine || "langgraph", ...clean }),
    });
    showToast("布局已保存到后端");
  };

  const runWorkflowSync = async () => {
    if (!workflow) return;
    if (!workflow.readonlyFlag) await saveLayout();
    setRunStatuses({});
    const inputs = {
      ticker: runForm.ticker.trim() || "AAPL",
      industry: runForm.industry.trim() || "AI Infrastructure",
      subject: runForm.subject.trim() || "stock recommendation research",
      trade_date: new Date().toISOString().slice(0, 10),
      ...(runForm.model.trim() ? { model: runForm.model.trim() } : {}),
      ...(runForm.apiKey.trim() ? { apiKey: runForm.apiKey.trim() } : {}),
    };
    const run = await api(`/workflows/${workflow.workflowKey}/run`, {
      method: "POST",
      body: JSON.stringify({ subject: inputs.subject, inputs }),
    });
    const nodeRuns = await api(`/workflow/runs/${run.runId}/nodes`);
    const statuses = {};
    (Array.isArray(nodeRuns) ? nodeRuns : []).forEach((item) => {
      statuses[item.nodeId] =
        item.status === "COMPLETED"
          ? "success"
          : item.status === "RUNNING"
            ? "running"
            : item.status === "FAILED"
              ? "error"
              : "idle";
    });
    setRunStatuses(statuses);
    setNodes((current) =>
      current.map((node) => ({ ...node, data: { ...node.data, runStatus: statuses[node.id] || "idle" } })),
    );
    showToast(`运行完成：${run.status}`);
  };

  const runWorkflow = async () => {
    if (!workflow) return;
    if (!workflow.readonlyFlag) await saveLayout();
    setRunStatuses({});
    setRunDegradedReasons([]);
    setLiveStream(emptyLiveStream());
    const inputs = {
      ticker: runForm.ticker.trim() || "AAPL",
      industry: runForm.industry.trim() || "AI Infrastructure",
      subject: runForm.subject.trim() || "stock recommendation research",
      trade_date: new Date().toISOString().slice(0, 10),
      ...(runForm.model.trim() ? { model: runForm.model.trim() } : {}),
      ...(runForm.apiKey.trim() ? { apiKey: runForm.apiKey.trim() } : {}),
    };
    try {
      const resp = await fetch(`${API_BASE}/workflows/${workflow.workflowKey}/run/stream`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          ...(token ? { Authorization: `Bearer ${token}` } : {}),
        },
        body: JSON.stringify({ subject: inputs.subject, inputs }),
      });
      if (!resp.ok) {
        return runWorkflowSync();
      }
      const reader = resp.body.getReader();
      const decoder = new TextDecoder();
      let buffer = "";
      // Persist event type across stream chunks (event: and data: may arrive separately)
      let currentEvent = "";
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        const parts = buffer.split(String.fromCharCode(10));
        buffer = parts.pop() || "";
        for (const part of parts) {
          // SSE blank line delimits one complete event
          if (part === "") {
            currentEvent = "";
            continue;
          }
          if (part.startsWith("event:")) {
            currentEvent = part.slice(6).trim();
          } else if (part.startsWith("data:")) {
            const raw = part.slice(5).trim();
            try {
              const data = JSON.parse(raw);
              if (isStreamV2Event(currentEvent)) {
                // P4-10: v2 progress events (live text, tool calls, usage) — display only.
                const eventName = currentEvent;
                setLiveStream((prev) => applyStreamEvent(prev, eventName, data));
                if (eventName === "node_start" && data.nodeId) {
                  const nid = data.nodeId;
                  setRunStatuses((prev) => ({ ...prev, [nid]: "running" }));
                  setNodes((cur) =>
                    cur.map((n) => (n.id === nid ? { ...n, data: { ...n.data, runStatus: "running" } } : n)),
                  );
                }
              } else if (currentEvent === "node_update") {
                const nid = data.nodeId;
                const status = data.event === "node_failed" || data.ok === false ? "error" : "success";
                setRunStatuses((prev) => ({ ...prev, [nid]: "running" }));
                setNodes((cur) =>
                  cur.map((n) => (n.id === nid ? { ...n, data: { ...n.data, runStatus: "running" } } : n)),
                );
                setTimeout(() => {
                  setRunStatuses((prev) => ({ ...prev, [nid]: status }));
                  setNodes((cur) =>
                    cur.map((n) => (n.id === nid ? { ...n, data: { ...n.data, runStatus: status } } : n)),
                  );
                }, 300);
              } else if (currentEvent === "degraded") {
                const list = Array.isArray(data.reasons)
                  ? data.reasons.map((item) => String(item || "").trim()).filter(Boolean)
                  : data.reasons
                    ? [String(data.reasons)]
                    : ["部分节点失败或证据不足"];
                setRunDegradedReasons(list);
                showToast(`运行降级：不可直接批准${list.length ? " — " + list.join(", ") : ""}`);
              } else if (currentEvent === "human_interrupt") {
                showToast(data.message || "工作流等待人工审批");
              } else if (currentEvent === "workflow_complete") {
                if (data.degraded) {
                  setRunDegradedReasons((prev) =>
                    prev.length ? prev : ["运行结果降级，草稿不可直接批准"],
                  );
                }
                showToast(data.degraded ? "运行降级结束（草稿不可批准）" : "运行完成");
              } else if (currentEvent === "error") {
                showToast(`运行错误：${data.error || "unknown"}`);
              }
            } catch {
              // skip unparseable data
            }
          }
        }
      }
    } catch {
      return runWorkflowSync();
    }
  };

  const onNodesChange = useCallback((changes) => setNodes((current) => applyNodeChanges(changes, current)), []);
  const onEdgesChange = useCallback((changes) => setEdges((current) => applyEdgeChanges(changes, current)), []);
  const onConnect = useCallback(
    (connection) =>
      setEdges((current) =>
        addEdge(
          { ...connection, type: "smoothstep", label: "normal", markerEnd: { type: MarkerType.ArrowClosed } },
          current,
        ),
      ),
    [],
  );
  const selectNode = (nodeId) => {
    setCatalogDetailNode(null);
    setSelectedNodeId(nodeId);
    setDrawerOpen(true);
    setNodes((current) =>
      current.map((node) => ({
        ...node,
        data: { ...node.data, selected: node.id === nodeId, runStatus: runStatuses[node.id] || "idle" },
      })),
    );
  };
  const openCatalogDetail = (node, groupTitle) => {
    setDrawerOpen(false);
    setCatalogDetailNode({ ...node, groupTitle });
  };

  const addNode = (item) => {
    const id = `${item.nodeType}-${Date.now().toString(36)}`;
    const node = {
      id,
      type: "workflowNode",
      position: { x: 160 + nodes.length * 28, y: 140 + nodes.length * 20 },
      data: {
        label: item.label,
        nodeType: item.nodeType,
        handler: item.handler,
        agentId: item.nodeType === "agent" ? agents[0]?.agentId || "" : "",
        prompt: "",
        inputKeys: [],
        outputKeys: [],
      },
    };
    setNodes((current) => [...current, node]);
    setSelectedNodeId(id);
    setCatalogDetailNode(null);
    setDrawerOpen(true);
  };

  const applyNodeUpdate = (updated) => {
    setNodes((current) => current.map((node) => (node.id === updated.id ? updated : node)));
    setDrawerOpen(false);
    showToast("节点配置已应用到前端状态");
  };
  const currentPreviewMeta = workflow ? workflowPreviewByKey[workflow.workflowKey] : null;
  const displayWorkflowName = currentPreviewMeta?.name || workflow?.name || "工作流";
  const displayVersion = currentPreviewMeta?.versionLabel?.toUpperCase() || `V${workflow?.version || 1}`;
  const readonlyText = workflow?.readonlyFlag ? "只读 · 不可删除" : "可编辑";
  const workflowDescription = workflow?.readonlyFlag
    ? "系统预设工作流由平台统一管理，不可修改布局或节点配置。"
    : workflow?.description || "自定义工作流支持节点编排、配置和运行。";

  if (!workflow) {
    return (
      <div className="bg-white">
        <div className="mb-[11px]">
          <h2 className="text-[26px] font-bold leading-8 text-black">工作流预览</h2>
          <p className="mt-1 text-sm leading-5 text-gray-600">系统预设工作流（只读、不可删除）。</p>
        </div>
        <div className="grid grid-cols-1 gap-3 md:grid-cols-2 xl:grid-cols-3">
          {previewWorkflows.map((item) => (
            <div
              key={item.workflowKey}
              className="relative flex min-h-[146px] flex-col rounded-[14px] border border-[#dedede] bg-white px-4 pb-[18px] pt-[21px]"
            >
              <WorkflowPreviewGlyph className="absolute right-4 top-[21px] h-4 w-4 text-neutral-700" />
              <h3 className="pr-8 text-[16px] font-semibold leading-5 text-black">{item.name}</h3>
              <p className="mt-1 text-[13px] leading-4 text-gray-600">
                {item.workflowKey} · {item.versionLabel}
              </p>
              <p className="mt-3 text-[12px] leading-4 text-gray-600">
                <span>节点 {item.nodes}</span>
                <span className="ml-3">连线 {item.edges}</span>
                <span className="ml-3">系统预设</span>
              </p>
              <button
                onClick={() => openWorkflow(item)}
                className="mt-auto flex h-8 w-full items-center justify-between rounded-lg border border-[#e5e5e5] bg-white px-3 text-[14px] font-semibold text-black transition-colors hover:bg-gray-50"
              >
                <span>打开流程图</span>
                <Icon name="arrowRight" className="h-4 w-4" />
              </button>
            </div>
          ))}
        </div>
      </div>
    );
  }

  return (
    <div className="-m-6 flex h-[calc(100vh-4rem)] min-h-[760px] flex-col bg-white px-6 py-6">
      <section className="bg-white">
        <div className="flex flex-col gap-4 xl:flex-row xl:items-start xl:justify-between">
          <div>
            <h2 className="text-3xl font-bold leading-tight text-black">{displayWorkflowName}</h2>
            <p className="mt-1 text-sm text-gray-700">
              {workflow.workflowKey} · {displayVersion} · {readonlyText}
            </p>
            <p className="mt-5 text-sm text-gray-600">{workflowDescription}</p>
          </div>
          <div className="flex flex-wrap items-end gap-3 pt-2">
            <label className="block">
              <span className="mb-1 block text-xs font-medium text-gray-600">Ticker</span>
              <input
                value={runForm.ticker}
                onChange={(event) => setRunForm((current) => ({ ...current, ticker: event.target.value }))}
                className="h-9 w-24 rounded-lg border border-gray-200 px-3 text-sm outline-none focus:border-gray-300 focus:ring-2 focus:ring-gray-100"
              />
            </label>
            <label className="block">
              <span className="mb-1 block text-xs font-medium text-gray-600">Industry</span>
              <input
                value={runForm.industry}
                onChange={(event) => setRunForm((current) => ({ ...current, industry: event.target.value }))}
                className="h-9 w-44 rounded-lg border border-gray-200 px-3 text-sm outline-none focus:border-gray-300 focus:ring-2 focus:ring-gray-100"
              />
            </label>
            <label className="block">
              <span className="mb-1 block text-xs font-medium text-gray-600">Subject</span>
              <input
                value={runForm.subject}
                onChange={(event) => setRunForm((current) => ({ ...current, subject: event.target.value }))}
                className="h-9 w-60 rounded-lg border border-gray-200 px-3 text-sm outline-none focus:border-gray-300 focus:ring-2 focus:ring-gray-100"
              />
            </label>
            <label className="block">
              <span className="mb-1 block text-xs font-medium text-gray-600">Model</span>
              <input
                value={runForm.model}
                onChange={(event) => setRunForm((current) => ({ ...current, model: event.target.value }))}
                placeholder="默认"
                className="h-9 w-32 rounded-lg border border-gray-200 px-3 text-sm outline-none focus:border-gray-300 focus:ring-2 focus:ring-gray-100"
              />
            </label>
            <label className="block">
              <span className="mb-1 block text-xs font-medium text-gray-600">API Key</span>
              <input
                value={runForm.apiKey}
                onChange={(event) => setRunForm((current) => ({ ...current, apiKey: event.target.value }))}
                placeholder="可选"
                className="h-9 w-32 rounded-lg border border-gray-200 px-3 text-sm outline-none focus:border-gray-300 focus:ring-2 focus:ring-gray-100"
              />
            </label>
            <button
              onClick={runWorkflow}
              className="rounded-lg border border-gray-200 bg-white px-4 py-2 text-sm font-semibold text-black shadow-sm hover:bg-gray-50"
            >
              手动运行
            </button>
            {!workflow.readonlyFlag && (
              <button
                onClick={saveLayout}
                className="rounded-lg border border-gray-200 bg-white px-4 py-2 text-sm font-semibold text-black shadow-sm hover:bg-gray-50"
              >
                保存布局
              </button>
            )}
            <button
              onClick={() => {
                setShowWorkflowList(true);
                setWorkflow(null);
              }}
              className="rounded-lg border border-gray-200 bg-white px-4 py-2 text-sm font-semibold text-black shadow-sm hover:bg-gray-50"
            >
              返回工作流列表
            </button>
          </div>
        </div>
        <div className="mt-5 flex gap-2">
          <button
            onClick={() => setActiveTab("graph")}
            className={`rounded-md px-3 py-2 text-sm font-semibold ${activeTab === "graph" ? "bg-black text-white" : "border border-gray-200 bg-white text-black hover:bg-gray-50"}`}
          >
            图形视图
          </button>
          <button
            onClick={() => setActiveTab("json")}
            className={`rounded-md px-3 py-2 text-sm font-semibold ${activeTab === "json" ? "bg-black text-white" : "border border-gray-200 bg-white text-black hover:bg-gray-50"}`}
          >
            JSON 视图
          </button>
        </div>
        {toast && (
          <div className="mt-3 inline-flex rounded-lg border border-emerald-200 bg-emerald-50 px-3 py-2 text-sm text-emerald-700">
            {toast}
          </div>
        )}
        {(liveStream.tools.length > 0 || liveStream.final || Object.values(liveStream.text).some(Boolean)) && (
          <div className="mt-3 rounded-lg border border-gray-200 bg-white px-3 py-2 text-xs text-gray-700" data-testid="live-stream">
            {liveStream.tools.length > 0 && (
              <div className="mb-1 flex flex-wrap gap-2">
                {liveStream.tools.map((tool) => (
                  <span
                    key={tool.toolCallId || tool.toolName}
                    className={`rounded px-2 py-0.5 ${tool.status === "error" ? "bg-red-50 text-red-700" : tool.status === "running" ? "bg-amber-50 text-amber-700" : "bg-emerald-50 text-emerald-700"}`}
                  >
                    {tool.toolName} · {tool.status === "running" ? "执行中" : tool.status === "error" ? "失败" : "完成"}
                    {typeof tool.durationMs === "number" ? ` · ${tool.durationMs}ms` : ""}
                  </span>
                ))}
              </div>
            )}
            {liveStream.final ? (
              <div className="whitespace-pre-wrap">
                {liveStream.final.withheld && <span className="mr-1 font-semibold text-amber-700">[未通过证据闸门，仅显示结论]</span>}
                {liveStream.final.text}
              </div>
            ) : (
              Object.entries(liveStream.text)
                .filter(([, text]) => text)
                .slice(-1)
                .map(([nid, text]) => (
                  <div key={nid} className="whitespace-pre-wrap">
                    <span className="mr-1 font-semibold">{nid}:</span>
                    {text}
                  </div>
                ))
            )}
          </div>
        )}
        {runDegradedReasons.length > 0 && (
          <DegradedBanner
            className="mt-3"
            title="本次运行已降级（草稿不可直接批准）"
            reasons={runDegradedReasons}
          />
        )}
      </section>
      <section className="mt-5 flex min-h-0 flex-1 gap-3">
        <NodeCatalog
          groupedNodes={catalogGroups}
          search={search}
          setSearch={setSearch}
          selectedNodeId={selectedNodeId}
          onSelectNode={() => {}}
          onAddNode={addNode}
          onOpenDetail={openCatalogDetail}
          readonly={workflow.readonlyFlag}
        />
        <main className="flex min-w-0 flex-1 flex-col overflow-hidden bg-white">
          <div className="mb-3 flex h-11 items-center rounded-xl border border-gray-200 px-4 text-xs text-gray-600">
            <span className="mr-5">图例</span>
            <span className="mr-4">♙ Agent 节点</span>
            <span className="mr-4">◇ Logic 节点</span>
            <span className="mr-4">⌁ 条件节点</span>
            <span className="mr-4">◎ 开始</span>
            <span className="mr-4">⚑ 结束</span>
            <span className="mr-4 text-emerald-600">○ 成功</span>
            <span className="mr-4 text-red-600">△ 错误</span>
            <span className="text-blue-600">◔ 运行中</span>
          </div>
          {activeTab === "graph" ? (
            <div className="min-h-0 flex-1 overflow-hidden rounded-xl border border-gray-200 bg-white">
              <ReactFlow
                nodes={nodes}
                edges={edges}
                nodeTypes={{ workflowNode: WorkflowNodeCard }}
                onNodesChange={onNodesChange}
                onEdgesChange={onEdgesChange}
                onConnect={onConnect}
                onNodeClick={(_, node) => selectNode(node.id)}
                fitView
                fitViewOptions={{ padding: 0.18 }}
                minZoom={0.2}
                maxZoom={1.6}
                defaultEdgeOptions={{ type: "smoothstep" }}
                className="workflow-canvas"
              >
                <Background gap={16} size={1} color="#d6d6d6" />
                <Controls />
                <MiniMap nodeStrokeWidth={3} pannable zoomable />
              </ReactFlow>
            </div>
          ) : (
            <div className="flex min-h-0 flex-1 flex-col gap-2">
              <div className="flex items-center justify-between">
                <span className="text-xs text-gray-500">
                  编辑 JSON 后点击「同步到图形」，确认后点「保存布局」持久化
                </span>
                <button
                  onClick={() => {
                    try {
                      const parsed = JSON.parse(jsonText);
                      if (parsed.nodes) setNodes(normalizeNodes(parsed.nodes));
                      if (parsed.edges) setEdges(normalizeEdges(parsed.edges));
                      showToast("JSON 已应用到图形视图");
                    } catch (e) {
                      showToast("JSON 格式错误: " + e.message);
                    }
                  }}
                  className="rounded-md bg-black px-3 py-1 text-xs font-semibold text-white hover:bg-gray-800"
                >
                  同步到图形
                </button>
              </div>
              <textarea
                className="min-h-0 w-full flex-1 resize-none rounded-lg bg-slate-950 p-3 font-mono text-xs leading-5 text-slate-100 outline-none focus:ring-2 focus:ring-blue-500"
                value={jsonText}
                onChange={(e) => setJsonText(e.target.value)}
                onFocus={() => setJsonText(JSON.stringify(denormalizeForSave(nodes, edges), null, 2))}
                spellCheck={false}
              />
            </div>
          )}
        </main>
      </section>
      {drawerOpen && selectedWorkflowNode && (
        <NodeEditorDrawer
          node={selectedWorkflowNode}
          agents={agents}
          onCancel={() => setDrawerOpen(false)}
          onApply={applyNodeUpdate}
        />
      )}
      {catalogDetailNode && (
        <CatalogNodeDetailDrawer node={catalogDetailNode} api={api} onClose={() => setCatalogDetailNode(null)} />
      )}
    </div>
  );
}
