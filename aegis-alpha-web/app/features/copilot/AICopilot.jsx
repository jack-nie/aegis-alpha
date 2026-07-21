"use client";

import React, { useCallback, useEffect, useRef, useState } from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import {
  parseRunResultJson,
  summarizeWorkflowRunResult,
  waitForWorkflowRun,
} from "../../lib/workflowRun";

const NODE_LABELS = {
  start: "开始",
  end: "结束",
  fundamental_analysis: "基本面分析",
  technical_analysis: "技术面分析",
  valuation_analysis: "估值分析",
  money_flow_analysis: "资金面分析",
  sentiment_monitor: "市场情绪",
  risk_assessment: "风险评估",
  recommendation: "综合推荐",
  industry_analysis: "行业分析",
  news_analysis: "新闻分析",
  peer_comparison: "同行比较",
  catalyst_analysis: "催化剂分析",
  thesis_builder: "投资论点",
  risk_reward_analysis: "风险收益分析",
  entry_strategy: "入场策略",
};

const NODE_ICONS = {
  NODE_STARTED: "⏳",
  NODE_COMPLETED: "✅",
  NODE_FAILED: "❌",
  NODE_RETRYING: "🔄",
  RUN_COMPLETED: "🏁",
};

const NODE_DETAIL_LABELS = {
  fundamental_analysis: "分析财务报表、盈利能力、成长性等核心指标",
  technical_analysis: "识别趋势、支撑阻力位、技术指标信号",
  valuation_analysis: "评估PE/PB/EV等估值指标并判断高估/低估",
  money_flow_analysis: "追踪主力资金流入流出、换手率和大单动向",
  sentiment_monitor: "监测市场情绪指标、舆情和社交媒体热度",
  risk_assessment: "量化下行风险、波动率、最大回撤等风险维度",
  recommendation: "综合所有分析维度生成最终投资建议",
  industry_analysis: "分析行业景气度、竞争格局和发展趋势",
  news_analysis: "筛选和解读影响股价的重要新闻事件",
  peer_comparison: "对比同行业公司的关键财务和估值指标",
  catalyst_analysis: "识别可能驱动股价变化的催化剂事件",
  thesis_builder: "构建多空投资论点和核心逻辑",
  risk_reward_analysis: "评估潜在收益与风险的非对称性",
  entry_strategy: "确定最优入场时机和仓位管理策略",
};

function extractNodeSummary(evt) {
  try {
    if (evt.payloadJson && typeof evt.payloadJson === "string") {
      const data = JSON.parse(evt.payloadJson);
      return data.summary || data.content || "";
    }
    if (evt.payloadJson && typeof evt.payloadJson === "object") {
      return evt.payloadJson.summary || evt.payloadJson.content || "";
    }
  } catch (_e) {
    return "";
  }
  return "";
}

function ThinkingProcess({ events }) {
  if (!events || events.length === 0) return null;
  const nodeEvents = events.filter((e) => e.eventType !== "RUN_COMPLETED");
  const hasCompleted = events.some((e) => e.eventType === "RUN_COMPLETED");
  if (nodeEvents.length === 0 && !hasCompleted) return null;

  const formatTime = (ts) => {
    if (!ts) return "";
    try {
      const d = new Date(ts);
      return d.toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit", second: "2-digit" });
    } catch (_) {
      return "";
    }
  };

  return (
    <div className="my-2 overflow-hidden rounded-lg border border-blue-100 bg-gradient-to-br from-blue-50/60 to-white">
      <div className="thinking-shimmer border-b border-blue-100 px-4 py-2.5">
        <div className="flex items-center gap-2.5">
          {hasCompleted ? (
            <span className="flex h-5 w-5 items-center justify-center rounded-full bg-green-100 text-[11px]">✓</span>
          ) : (
            <span className="flex h-5 w-5 items-center justify-center">
              <span className="thinking-dot inline-block h-2.5 w-2.5 rounded-full bg-blue-500" />
            </span>
          )}
          <span className="text-xs font-semibold text-blue-800">{hasCompleted ? "分析完成" : "正在分析"}</span>
          {!hasCompleted && (
            <span className="flex items-center gap-0.5">
              <span className="thinking-dot-delay-1 inline-block h-1.5 w-1.5 rounded-full bg-blue-400" />
              <span className="thinking-dot-delay-2 inline-block h-1.5 w-1.5 rounded-full bg-blue-400" />
              <span className="thinking-dot-delay-1 inline-block h-1.5 w-1.5 rounded-full bg-blue-400" />
            </span>
          )}
        </div>
      </div>
      <div className="space-y-0 px-4 py-2">
        {nodeEvents.map((evt, idx) => {
          const label = NODE_LABELS[evt.nodeId] || evt.nodeName || evt.nodeId;
          const detailLabel = NODE_DETAIL_LABELS[evt.nodeId] || "";
          const icon = NODE_ICONS[evt.eventType] || "·";
          const isRunning = evt.eventType === "NODE_STARTED";
          const isFailed = evt.eventType === "NODE_FAILED";
          const isCompleted = evt.eventType === "NODE_COMPLETED";
          const summary = isCompleted ? extractNodeSummary(evt) : "";
          const timeStr = formatTime(evt.createdAt);

          return (
            <div
              key={evt.eventId || idx}
              className={`flex items-start gap-2.5 py-1.5 ${idx < nodeEvents.length - 1 ? "border-b border-gray-100/60" : ""}`}
            >
              <div className="mt-0.5 flex h-5 w-5 flex-shrink-0 items-center justify-center">
                {isRunning ? (
                  <span className="thinking-dot inline-block h-3 w-3 rounded-full bg-blue-500" />
                ) : isFailed ? (
                  <span className="text-[11px]">❌</span>
                ) : (
                  <span className="text-[11px]">{icon}</span>
                )}
              </div>
              <div className="min-w-0 flex-1">
                <div className="flex items-center gap-2">
                  <span
                    className={`text-[13px] ${isRunning ? "font-semibold text-blue-700" : isFailed ? "font-medium text-red-600" : "font-medium text-gray-700"}`}
                  >
                    {label}
                  </span>
                  {isRunning && (
                    <span className="thinking-dot-delay-1 inline-block h-1.5 w-1.5 rounded-full bg-blue-400" />
                  )}
                  {isCompleted && (
                    <span className="rounded-full bg-green-50 px-1.5 py-0.5 text-[10px] font-medium text-green-700">
                      完成
                    </span>
                  )}
                  {isFailed && (
                    <span className="rounded-full bg-red-50 px-1.5 py-0.5 text-[10px] font-medium text-red-600">
                      失败
                    </span>
                  )}
                </div>
                {isRunning && detailLabel && <p className="mt-0.5 truncate text-[11px] text-gray-400">{detailLabel}</p>}
                {isCompleted && summary && <p className="mt-0.5 truncate text-[11px] text-gray-500">{summary}</p>}
                {timeStr && <span className="text-[10px] text-gray-300">{timeStr}</span>}
              </div>
            </div>
          );
        })}
      </div>
      {hasCompleted && (
        <div className="border-t border-green-100 bg-green-50/40 px-4 py-2">
          <div className="flex items-center gap-2">
            <span className="text-[11px]">🏁</span>
            <span className="text-xs font-medium text-green-700">全部分析节点已完成，正在生成报告...</span>
          </div>
        </div>
      )}
    </div>
  );
}

function buildPageContext(pageContext) {
  if (!pageContext || typeof pageContext !== "object") return {};
  const ctx = {};
  if (pageContext.path) ctx.path = pageContext.path;
  if (pageContext.runId) ctx.runId = pageContext.runId;
  if (pageContext.workflowKey) ctx.workflowKey = pageContext.workflowKey;
  if (pageContext.ticker) ctx.ticker = pageContext.ticker;
  if (pageContext.recommendationId) ctx.recommendationId = pageContext.recommendationId;
  if (pageContext.portfolioId) ctx.portfolioId = pageContext.portfolioId;
  return ctx;
}

export function AICopilot({ setCopilotOpen, api, promptRequest, onPromptHandled, pageContext }) {
  const initialMessages = [
    {
      role: "assistant",
      content:
        "你好！我是 Aegis 助手，可以帮你做投资研究、产品操作引导，以及运行失败/DEGRADED 诊断。有什么可以帮你的？",
    },
  ];
  const suggestions = [
    "什么是 DEGRADED？",
    "如何打开 AI Copilot？",
    "推荐审批在哪里做？",
    "怎么运行一个工作流？",
    "深度分析 NVDA",
    "给我一份盘前日报",
  ];
  const [messages, setMessages] = useState(initialMessages);
  const [input, setInput] = useState("");
  const [loading, setLoading] = useState(false);
  const [thinkingEvents, setThinkingEvents] = useState([]);
  const messagesEndRef = useRef(null);
  const handledPromptIdRef = useRef(null);
  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages, thinkingEvents]);

  const sendMessage = useCallback(
    async (rawText) => {
      const text = (rawText || "").trim();
      if (!text) return;
      setMessages((prev) => [...prev, { role: "user", content: text }]);
      setLoading(true);
      try {
        const payload = {
          message: text,
          pageContext: buildPageContext(pageContext),
        };
        const result = await api("/chat/messages", { method: "POST", body: JSON.stringify(payload) });
        let content = result?.content || result?.message || "后端没有返回内容。";
        if (result?.needsClarification && result?.clarificationPrompt) {
          content = result.clarificationPrompt;
        }
        let replyOk = result?.ok !== false;
        if (result?.routedToWorkflow && result?.runId) {
          try {
            setThinkingEvents([]);
            // Show routing ack immediately while workflow runs
            setMessages((prev) => [
              ...prev,
              {
                role: "assistant",
                content: content + String.fromCharCode(10) + "正在等待工作流执行结果…",
                ok: true,
                provider: "workflow-router",
              },
            ]);
            const run = await waitForWorkflowRun(api, result.runId, {
              onEvents: (events) => {
                const nodeEvents = events.filter(
                  (e) => e.eventType && (e.eventType.startsWith("NODE_") || e.eventType === "RUN_COMPLETED"),
                );
                setThinkingEvents(nodeEvents);
              },
            });
            let runResult = parseRunResultJson(run?.resultJson ?? run?.result_json);
            // If still empty after COMPLETED, try node list as last resort
            if (!runResult && String(run?.status || "").toUpperCase() === "COMPLETED") {
              try {
                const nodes = await api(`/workflow/runs/${result.runId}/nodes`);
                const list = Array.isArray(nodes) ? nodes : nodes?.items || [];
                if (list.length) {
                  runResult = Object.fromEntries(
                    list.map((n) => {
                      const id = n.nodeId || n.node_id || n.id || "node";
                      let output = n.outputJson ?? n.output_json;
                      if (typeof output === "string") {
                        try {
                          output = JSON.parse(output);
                        } catch {
                          output = { summary: output };
                        }
                      }
                      return [id, { ...(output || {}), nodeId: id, nodeName: n.nodeName || n.node_name || id, status: n.status }];
                    }),
                  );
                }
              } catch {
                /* ignore */
              }
            }
            content = summarizeWorkflowRunResult(run, result, runResult);
            const st = String(run?.status || "").toUpperCase();
            replyOk = st === "COMPLETED" || st === "DEGRADED" || st === "COMPLETED_DEGRADED";
          } catch (fetchErr) {
            // eslint-disable-next-line no-console
            console.warn("Failed to fetch workflow run result:", fetchErr);
            content =
              (result?.content || "工作流已启动，但获取结果失败。") +
              String.fromCharCode(10) +
              `运行 ID: ${result.runId}` +
              String.fromCharCode(10) +
              (fetchErr?.message || String(fetchErr));
            replyOk = false;
          }
        }
        setThinkingEvents([]);
        setMessages((prev) => {
          // Drop the temporary "等待结果" bubble if we added one
          const withoutPending = prev.filter(
            (m) => !(m.role === "assistant" && m.provider === "workflow-router" && String(m.content || "").includes("正在等待工作流")),
          );
          return [
            ...withoutPending,
            {
              role: "assistant",
              content,
              ok: replyOk,
              provider: result?.provider || (result?.routedToWorkflow ? "workflow" : undefined),
              reason: result?.reason || result?.routeReason,
              domain: result?.domain,
              routeAction: result?.routeAction,
              needsClarification: !!result?.needsClarification,
              escalateHuman: !!result?.escalateHuman,
              disclaimer: result?.disclaimer,
              sourceRefs: Array.isArray(result?.sourceRefs) ? result.sourceRefs : [],
            },
          ];
        });
      } catch (error) {
        setThinkingEvents([]);
        setMessages((prev) => [
          ...prev,
          {
            role: "assistant",
            content: error.message || "发送失败，请稍后重试。",
            ok: false,
            provider: "request-error",
          },
        ]);
      } finally {
        setLoading(false);
      }
    },
    [api, pageContext],
  );

  useEffect(() => {
    if (!promptRequest?.id || handledPromptIdRef.current === promptRequest.id) return;
    handledPromptIdRef.current = promptRequest.id;
    // sendMessage is async; do not return its Promise from useEffect
    void sendMessage(promptRequest.text);
    onPromptHandled?.();
  }, [promptRequest, sendMessage, onPromptHandled]);

  const handleSend = () => {
    if (!input.trim() || loading) return;
    const text = input.trim();
    setInput("");
    requestAnimationFrame(() => {
      const ta = document.querySelector(".copilot-input");
      if (ta) ta.style.height = "auto";
    });
    sendMessage(text);
  };
  const handleSuggestion = (text) => {
    if (loading) return;
    sendMessage(text);
  };

  return (
    <div className="flex w-[48rem] flex-shrink-0 flex-col border-l border-gray-200 bg-white shadow-lg">
      <div className="flex h-14 items-center justify-between border-b border-gray-200 px-4">
        <div className="flex items-center gap-2">
          <Icon name="bolt" className="h-5 w-5 text-blue-600" />
          <span className="text-sm font-semibold">AI Copilot</span>
        </div>
        <button
          type="button"
          onClick={() => setCopilotOpen(false)}
          className="rounded-lg p-1.5 text-gray-400 transition-colors hover:bg-gray-100 hover:text-gray-600"
          aria-label="关闭 AI Copilot"
        >
          <Icon name="close" />
        </button>
      </div>
      <div className="flex-1 space-y-2 overflow-y-auto bg-white p-3">
        {messages.map((msg, idx) => (
          <div key={idx} className={`flex ${msg.role === "user" ? "justify-end" : "justify-start"}`}>
            <div
              className={`whitespace-pre-wrap rounded-md px-3 py-2 text-sm leading-snug ${msg.role === "user" ? "max-w-[75%] bg-blue-50 text-blue-900" : msg.ok === false ? "max-w-[95%] border border-red-200 bg-red-50 text-red-700" : "max-w-[95%] bg-gray-50 text-gray-800"}`}
            >
              <div className="chat-markdown text-sm leading-snug">
                <ReactMarkdown
                  remarkPlugins={[remarkGfm]}
                  components={{
                    h1: ({ children }) => (
                      <h1 className="mb-1.5 mt-3 border-b border-gray-200 pb-1 text-lg font-bold text-gray-900">
                        {children}
                      </h1>
                    ),
                    h2: ({ children }) => <h2 className="mb-1 mt-2 text-base font-bold text-gray-800">{children}</h2>,
                    h3: ({ children }) => (
                      <h3 className="mb-0.5 mt-1.5 text-sm font-semibold text-gray-700">{children}</h3>
                    ),
                    p: ({ children }) => <p className="my-0.5 text-gray-700">{children}</p>,
                    ul: ({ children, ordered }) => (
                      <ul className={`my-0.5 ml-4 space-y-0.5 ${ordered ? "list-decimal" : "list-disc"}`}>
                        {children}
                      </ul>
                    ),
                    ol: ({ children }) => <ol className="my-0.5 ml-4 list-decimal space-y-0.5">{children}</ol>,
                    li: ({ children, checked }) => {
                      if (checked !== null && checked !== undefined) {
                        return (
                          <li className="flex items-start gap-2 text-gray-700">
                            <input
                              type="checkbox"
                              checked={checked}
                              readOnly
                              className="mt-1 h-3.5 w-3.5 flex-shrink-0 rounded border-gray-300"
                            />
                            <span className={checked ? "text-gray-400 line-through" : ""}>{children}</span>
                          </li>
                        );
                      }
                      return <li className="text-gray-700">{children}</li>;
                    },
                    strong: ({ children }) => <strong className="font-semibold text-gray-900">{children}</strong>,
                    em: ({ children }) => <em className="italic text-gray-600">{children}</em>,
                    blockquote: ({ children }) => (
                      <blockquote className="border-l-3 my-1 border-blue-300 pl-3 text-xs italic text-gray-500">
                        {children}
                      </blockquote>
                    ),
                    table: ({ children }) => (
                      <div className="my-1.5 overflow-x-auto rounded-md border border-gray-100">
                        <table className="w-full text-xs">{children}</table>
                      </div>
                    ),
                    thead: ({ children }) => <thead className="bg-gray-50">{children}</thead>,
                    th: ({ children }) => (
                      <th className="border-b border-gray-100 px-2.5 py-1.5 text-left text-[11px] font-semibold text-gray-500">
                        {children}
                      </th>
                    ),
                    td: ({ children }) => (
                      <td className="border-b border-gray-50 px-2.5 py-1.5 text-gray-700">{children}</td>
                    ),
                    tr: ({ children }) => <tr>{children}</tr>,
                    code: ({ inline, className, children }) => {
                      const match = /language-(\w+)/.exec(className || "");
                      const lang = match ? match[1] : "";
                      if (inline)
                        return (
                          <code className="rounded bg-gray-100 px-1.5 py-0.5 font-mono text-[11px] text-gray-700">
                            {children}
                          </code>
                        );
                      return (
                        <div className="my-1 overflow-hidden rounded-md bg-gray-50">
                          {lang && (
                            <div className="border-b border-gray-100 px-3 py-1 text-[10px] font-medium uppercase tracking-wider text-gray-400">
                              {lang}
                            </div>
                          )}
                          <pre className="overflow-x-auto p-2">
                            <code className="font-mono text-[11px] leading-relaxed text-gray-700">{children}</code>
                          </pre>
                        </div>
                      );
                    },
                    hr: () => <hr className="my-1 border-gray-100" />,
                    a: ({ href, children }) => (
                      <a
                        href={href}
                        target="_blank"
                        rel="noopener noreferrer"
                        className="text-blue-600 underline underline-offset-1 hover:text-blue-800"
                      >
                        {children}
                      </a>
                    ),
                    img: ({ src, alt }) => <img src={src} alt={alt} className="my-1 max-w-full rounded-md" />,
                  }}
                >
                  {String(msg.content)}
                </ReactMarkdown>
              </div>
              {msg.role === "assistant" && (msg.provider || msg.domain) && (
                <div className={`mt-1 text-[11px] ${msg.ok === false ? "text-red-500" : "text-gray-400"}`}>
                  {msg.ok === false ? "ERROR" : "INFO"}
                  {msg.domain ? ` · ${msg.domain}` : ""}
                  {msg.provider ? ` · ${msg.provider}` : ""}
                  {msg.routeAction ? ` · ${msg.routeAction}` : ""}
                </div>
              )}
              {msg.role === "assistant" && Array.isArray(msg.sourceRefs) && msg.sourceRefs.length > 0 && (
                <div className="mt-1.5 flex flex-wrap gap-1">
                  {msg.sourceRefs.map((ref, refIdx) => (
                    <span
                      key={`${ref?.id || refIdx}-${refIdx}`}
                      className="rounded-full border border-gray-200 bg-white px-2 py-0.5 text-[10px] text-gray-500"
                      title={ref?.path || ref?.type || ""}
                    >
                      {ref?.title || ref?.id || ref?.type || "source"}
                    </span>
                  ))}
                </div>
              )}
              {msg.needsClarification && (
                <div className="mt-1 text-[11px] font-medium text-amber-600">需要补充信息后继续</div>
              )}
              {msg.escalateHuman && (
                <div className="mt-1 rounded border border-amber-200 bg-amber-50 px-2 py-1 text-[11px] text-amber-800">
                  建议转人工 / Desk 复核
                  {msg.disclaimer ? `：${msg.disclaimer}` : ""}
                </div>
              )}
              {msg.reason && <div className="mt-1 text-[11px] text-red-500">{msg.reason}</div>}
            </div>
          </div>
        ))}
        {loading && thinkingEvents.length > 0 && <ThinkingProcess events={thinkingEvents} />}
        {loading && thinkingEvents.length === 0 && (
          <div className="flex justify-start">
            <div className="thinking-shimmer rounded-lg border border-blue-100 bg-gradient-to-br from-blue-50/60 to-white px-4 py-3">
              <div className="flex items-center gap-3">
                <span className="flex items-center gap-1">
                  <span className="thinking-dot-delay-1 inline-block h-2 w-2 rounded-full bg-blue-500" />
                  <span className="thinking-dot-delay-2 inline-block h-2 w-2 rounded-full bg-blue-400" />
                  <span className="thinking-dot-delay-1 inline-block h-2 w-2 rounded-full bg-blue-400" />
                </span>
                <span className="text-sm font-medium text-blue-700">正在分析...</span>
              </div>
            </div>
          </div>
        )}
        <div ref={messagesEndRef} />
        {messages.length === 1 && (
          <div className="mt-4">
            <p className="mb-2 text-center text-xs text-gray-400">试试这些问题</p>
            <div className="space-y-1.5">
              {suggestions.map((s) => (
                <button
                  key={s}
                  onClick={() => handleSuggestion(s)}
                  className="w-full rounded-lg border border-gray-200 bg-white px-3 py-2 text-left text-sm text-gray-600 shadow-sm transition-colors hover:border-blue-300 hover:text-blue-600"
                >
                  {s}
                </button>
              ))}
            </div>
          </div>
        )}
      </div>
      <div className="border-t border-gray-200 bg-white p-3">
        <div className="flex gap-2">
          <textarea
            value={input}
            onChange={(e) => {
              setInput(e.target.value);
              e.target.style.height = "auto";
              e.target.style.height = Math.min(e.target.scrollHeight, 120) + "px";
            }}
            onKeyDown={(e) => {
              if (e.key === "Enter" && !e.shiftKey) {
                e.preventDefault();
                handleSend();
              }
            }}
            placeholder="输入你的问题... (Shift+Enter 换行)"
            rows={1}
            className="copilot-input flex-1 resize-none rounded-lg border border-gray-300 px-3 py-2 text-sm leading-relaxed focus:border-blue-500 focus:outline-none focus:ring-2 focus:ring-blue-500"
          />
          <button
            type="button"
            onClick={handleSend}
            disabled={!input.trim() || loading}
            className="rounded-lg bg-blue-600 px-3 py-2 text-white transition-colors hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-50"
            aria-label="发送 AI Copilot 消息"
          >
            <Icon name="send" className="h-4 w-4" />
          </button>
        </div>
      </div>
    </div>
  );
}
