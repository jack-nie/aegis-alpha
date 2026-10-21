"use client";

import React, { useEffect, useState } from "react";
import { Handle, MarkerType, Position } from "@xyflow/react";
import {
  Button,
  Field,
  JsonViewer,
  Panel,
  ReadOnlyField,
} from "../../components/ui";
import {
  nodeTypeStyles,
  splitCsv,
  workflowCatalogDetailMap,
} from "./catalog";

export function normalizeNodes(rawNodes) {
  return rawNodes.map((node) => {
    const data = node.data || {};
    return {
      ...node,
      type: "workflowNode",
      position: node.position || { x: 120, y: 120 },
      data: {
        label: data.label || data.title || node.id,
        desc: data.desc || data.description || "",
        nodeType: data.nodeType || (node.type === "workflowNode" ? "logic" : node.type) || "logic",
        handler: data.handler || data.functionName || "general.node",
        agentId: data.agentId || data.agent_id || "",
        prompt: data.prompt || "",
        inputKeys: data.inputKeys || data.input_keys || [],
        outputKeys: data.outputKeys || data.output_keys || [],
        selected: false,
        runStatus: "idle",
      },
    };
  });
}

export function normalizeEdges(rawEdges) {
  return rawEdges.map((edge) => ({
    ...edge,
    label: edge.label || "normal",
    type: "smoothstep",
    style: { stroke: "#9ca3af", strokeWidth: 1 },
    labelStyle: { fill: "#4b5563", fontSize: 7, fontWeight: 500 },
    labelBgStyle: { fill: "#fff", fillOpacity: 0.95 },
    markerEnd: { type: MarkerType.ArrowClosed, width: 10, height: 10 },
  }));
}

export function NodeCatalog({
  groupedNodes,
  search,
  setSearch,
  _selectedNodeId,
  _onSelectNode,
  _onAddNode,
  onOpenDetail,
  _readonly = false,
}) {
  return (
    <aside className="flex w-[288px] flex-shrink-0 flex-col rounded-xl border border-gray-200 bg-white p-3">
      <h3 className="mb-3 text-sm font-semibold text-black">节点目录</h3>
      <input
        value={search}
        onChange={(event) => setSearch(event.target.value)}
        className="mb-3 h-9 rounded-lg border border-gray-200 px-3 text-sm outline-none placeholder:text-gray-400 focus:border-gray-300 focus:ring-2 focus:ring-gray-100"
        placeholder="搜索节点名称或标识"
      />
      <div className="workflow-catalog-scroll min-h-0 flex-1 space-y-2 overflow-auto pr-1">
        {groupedNodes.map((group) => (
          <div key={group.title} className="rounded-xl border border-gray-200 bg-white p-2">
            <div className="mb-2 flex items-center text-sm font-bold text-black">
              <span className="mr-1 text-xs">▼</span>
              <span>
                {group.title} ({group.nodes.length})
              </span>
            </div>
            <div className="space-y-1.5">
              {group.nodes.map((node) => (
                <button
                  key={node.id}
                  onClick={() => onOpenDetail?.(node, group.title)}
                  className="w-full rounded-lg border border-gray-200 bg-white px-3 py-2.5 text-left transition hover:border-gray-300 hover:bg-gray-50"
                >
                  <span className="block truncate text-sm font-semibold text-black">{node.label}</span>
                  <span className="mt-1 block truncate font-mono text-xs text-gray-600">{node.id}</span>
                </button>
              ))}
            </div>
          </div>
        ))}
      </div>
    </aside>
  );
}

export function catalogNodeTypeLabel(node) {
  if (node.id?.startsWith("fdb.")) return "数据";
  if (node.nodeType === "start") return "开始";
  if (node.nodeType === "end") return "结束";
  if (node.nodeType === "agent") return "Agent";
  return node.nodeType || "Logic";
}

export function catalogNodeDescription(node) {
  return (
    workflowCatalogDetailMap[node.id]?.description || `${node.label} 节点，可通过 ${node.handler || node.id} 执行。`
  );
}

export function CatalogNodeDetailDrawer({ node, api, onClose }) {
  const [action, setAction] = useState("");
  const [params, setParams] = useState("");
  const [extraJson, setExtraJson] = useState("{}");
  const [running, setRunning] = useState(false);
  const [result, setResult] = useState(null);
  const [error, setError] = useState("");

  useEffect(() => {
    setAction("");
    setParams("");
    setExtraJson("{}");
    setResult(null);
    setError("");
  }, [node.id]);

  const runCatalogNode = async () => {
    setRunning(true);
    setError("");
    setResult(null);
    try {
      const extra = extraJson.trim() ? JSON.parse(extraJson) : {};
      if (extra === null || Array.isArray(extra) || typeof extra !== "object") {
        throw new Error("附加 JSON 必须是对象");
      }
      const payload = {
        nodeId: node.id,
        functionName: node.handler || node.id,
        action,
        params,
        extra,
      };
      const response = await api("/workflow-nodes/execute", { method: "POST", body: JSON.stringify(payload) });
      setResult(response);
    } catch (ex) {
      setError(ex.message || "运行节点失败");
    } finally {
      setRunning(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50">
      <div className="absolute inset-0 bg-white/55 backdrop-blur-[5px]" onClick={onClose} />
      <aside className="absolute inset-y-0 right-0 flex w-full max-w-[384px] flex-col border-l border-gray-100 bg-white px-4 py-5 shadow-2xl">
        <div className="mb-7 flex items-start justify-between gap-4">
          <div>
            <h3 className="text-lg font-semibold leading-6 text-black">节点详情</h3>
            <p className="mt-1 text-sm text-gray-600">{node.label}</p>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="rounded-md px-2 py-1 text-2xl leading-none text-black hover:bg-gray-100"
            aria-label="关闭节点详情"
          >
            ×
          </button>
        </div>

        <div className="min-h-0 flex-1 overflow-auto">
          <div className="rounded-lg border border-gray-200 bg-white p-3 text-sm leading-6 text-black">
            <p className="font-semibold">{node.label}</p>
            <p className="font-mono text-xs text-gray-700">{node.id}</p>
            <p className="mt-2">节点类型: {catalogNodeTypeLabel(node)}</p>
            <p>节点目录: {node.groupTitle || "节点"}</p>
            <p>描述: {catalogNodeDescription(node)}</p>
          </div>

          <div className="mt-5 space-y-3">
            <p className="text-sm font-medium text-gray-600">Contract 自动字段</p>
            <label className="block">
              <span className="mb-1.5 block text-sm text-gray-700">action * (string)</span>
              <input
                value={action}
                onChange={(event) => setAction(event.target.value)}
                className="h-9 w-full rounded-lg border border-gray-200 px-3 text-sm outline-none placeholder:text-gray-400 focus:border-gray-300 focus:ring-2 focus:ring-gray-100"
                placeholder="输入参数值"
              />
            </label>
            <label className="block">
              <span className="mb-1.5 block text-sm text-gray-700">params * (string)</span>
              <input
                value={params}
                onChange={(event) => setParams(event.target.value)}
                className="h-9 w-full rounded-lg border border-gray-200 px-3 text-sm outline-none placeholder:text-gray-400 focus:border-gray-300 focus:ring-2 focus:ring-gray-100"
                placeholder="输入参数值"
              />
            </label>
            <label className="block">
              <span className="mb-1.5 block text-sm text-gray-700">附加 JSON（对象）</span>
              <textarea
                value={extraJson}
                onChange={(event) => setExtraJson(event.target.value)}
                className="h-24 w-full resize-none rounded-lg border border-gray-200 px-3 py-2 font-mono text-sm outline-none focus:border-gray-300 focus:ring-2 focus:ring-gray-100"
              />
            </label>
            {error && <p className="text-sm text-red-600">{error}</p>}
            <button
              type="button"
              onClick={runCatalogNode}
              disabled={running}
              className="h-10 w-full rounded-lg bg-[#171717] px-4 text-sm font-semibold text-white hover:bg-black disabled:cursor-wait disabled:opacity-70"
            >
              {running ? "运行中..." : "运行该节点"}
            </button>
            {result && (
              <pre className="max-h-48 overflow-auto rounded-lg border border-gray-200 bg-gray-50 p-3 text-xs leading-5 text-gray-700">
                {JSON.stringify(result, null, 2)}
              </pre>
            )}
          </div>
        </div>
      </aside>
    </div>
  );
}

export function WorkflowNodeCard({ data }) {
  const type = data.nodeType || "logic";
  const specialNeutral = data.handler === "workflow.run_exit";
  const style = specialNeutral
    ? { border: "border-gray-600", bg: "bg-white", icon: "", label: "Logic" }
    : nodeTypeStyles[type] || nodeTypeStyles.logic;
  const statusRing =
    data.runStatus === "running"
      ? "ring-2 ring-blue-400"
      : data.runStatus === "success"
        ? "ring-2 ring-emerald-400"
        : data.selected
          ? "ring-2 ring-gray-900"
          : "";
  return (
    <div
      className={`workflow-node-card min-w-[102px] max-w-[170px] rounded-md border ${style.border} ${style.bg} ${statusRing} px-2.5 py-2 shadow-sm`}
    >
      <Handle type="target" position={Position.Left} />
      <div className="flex items-start justify-between gap-1.5">
        <div className="min-w-0">
          <p className="truncate text-[10px] font-bold leading-4 text-black">
            <span className="mr-1">{style.icon}</span>
            {data.label}
          </p>
          <p className="mt-0.5 line-clamp-1 text-[8px] leading-3 text-gray-600">
            {data.desc || data.handler || "no handler"}
          </p>
        </div>
        {data.runStatus === "running" && <span className="h-3 w-3 animate-pulse rounded-full bg-blue-500" />}
        {data.runStatus === "success" && <span className="h-3 w-3 rounded-full bg-emerald-500" />}
      </div>
      <Handle type="source" position={Position.Right} />
    </div>
  );
}

export function NodeEditorDrawer({ node, agents, onCancel, onApply }) {
  const [draft, setDraft] = useState(node);
  useEffect(() => {
    setDraft(node);
  }, [node]);
  const patchData = (patch) => setDraft((current) => ({ ...current, data: { ...current.data, ...patch } }));
  return (
    <aside className="fixed inset-y-0 right-0 z-50 flex w-[560px] flex-col border-l border-slate-200 bg-white shadow-2xl">
      <div className="border-b border-slate-200 px-5 py-4">
        <div className="flex items-start justify-between gap-3">
          <div>
            <h3 className="text-base font-semibold text-slate-950">节点编辑</h3>
            <p className="mt-1 font-mono text-xs text-slate-500">{draft.id}</p>
          </div>
          <button
            onClick={onCancel}
            className="rounded-lg px-2 py-1 text-xl leading-none text-slate-400 hover:bg-slate-100 hover:text-slate-700"
          >
            ×
          </button>
        </div>
      </div>
      <div className="min-h-0 flex-1 space-y-5 overflow-auto px-5 py-4">
        <Panel title="基本信息">
          <ReadOnlyField label="id" value={draft.id} />
          <Field label="label">
            <input
              className="input-field"
              value={draft.data.label || ""}
              onChange={(event) => patchData({ label: event.target.value })}
            />
          </Field>
          <div className="grid grid-cols-2 gap-3">
            <Field label="type">
              <select
                className="input-field"
                value={draft.data.nodeType || "logic"}
                onChange={(event) => patchData({ nodeType: event.target.value })}
              >
                {Object.keys(nodeTypeStyles).map((type) => (
                  <option key={type} value={type}>
                    {type}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="handler">
              <input
                className="input-field font-mono"
                value={draft.data.handler || ""}
                onChange={(event) => patchData({ handler: event.target.value })}
              />
            </Field>
          </div>
          {draft.data.nodeType === "agent" && (
            <>
              <Field label="绑定 Agent">
                <select
                  className="input-field"
                  value={draft.data.agentId || ""}
                  onChange={(event) => patchData({ agentId: event.target.value })}
                >
                  <option value="">内联 Agent</option>
                  {agents.map((agent) => (
                    <option key={agent.agentId} value={agent.agentId}>
                      {agent.name}
                    </option>
                  ))}
                </select>
              </Field>
              <Field label="prompt">
                <textarea
                  className="input-field min-h-[160px] resize-y whitespace-pre-wrap"
                  value={draft.data.prompt || ""}
                  onChange={(event) => patchData({ prompt: event.target.value })}
                />
              </Field>
            </>
          )}
          <div className="grid grid-cols-2 gap-3">
            <Field label="input_keys">
              <input
                className="input-field"
                value={(draft.data.inputKeys || []).join(",")}
                onChange={(event) => patchData({ inputKeys: splitCsv(event.target.value) })}
              />
            </Field>
            <Field label="output_keys">
              <input
                className="input-field"
                value={(draft.data.outputKeys || []).join(",")}
                onChange={(event) => patchData({ outputKeys: splitCsv(event.target.value) })}
              />
            </Field>
          </div>
        </Panel>
        <JsonViewer value={draft} compact />
      </div>
      <div className="flex flex-wrap items-center justify-between gap-2 border-t border-slate-200 px-5 py-4">
        <Button onClick={onCancel}>取消</Button>
        <Button variant="primary" onClick={() => onApply(draft)}>
          应用修改
        </Button>
      </div>
    </aside>
  );
}
