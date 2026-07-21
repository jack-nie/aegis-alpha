"use client";

import React, { useCallback, useEffect, useState } from "react";
import {
  formatMarketTimestamp,
  formatNumber,
  formatPercent,
  sanitizeText,
} from "../../lib/format";
import {
  parseRunResultJson,
  summarizeWorkflowRunResult,
  waitForWorkflowRun,
} from "../../lib/workflowRun";

export function Dashboard({ api }) {
  const [dashboard, setDashboard] = useState(null);
  const [marketSymbol, setMarketSymbol] = useState("AAPL");
  const [marketDataResult, setMarketDataResult] = useState(null);
  const [marketDataLoading, setMarketDataLoading] = useState(false);
  const [marketDataError, setMarketDataError] = useState("");
  useEffect(() => {
    api("/dashboard")
      .then(setDashboard)
      .catch(() => {});
  }, [api]);
  const loadMarketData = useCallback(
    async (symbol = marketSymbol) => {
      const normalized = String(symbol || "")
        .trim()
        .toUpperCase();
      if (!normalized) {
        setMarketDataError("请输入股票代码");
        return;
      }
      setMarketDataLoading(true);
      setMarketDataError("");
      try {
        const response = await api(`/market-data/overview?symbol=${encodeURIComponent(normalized)}`);
        setMarketDataResult(response);
        setMarketSymbol(normalized);
      } catch (ex) {
        setMarketDataError(ex.message || "实时数据查询失败");
      } finally {
        setMarketDataLoading(false);
      }
    },
    [api, marketSymbol],
  );
  const markets = dashboard?.markets?.length
    ? dashboard.markets
    : [
        { symbol: "SPY", name: "S&P 500", changePct: 0.77 },
        { symbol: "QQQ", name: "Nasdaq 100", changePct: 1.91 },
        { symbol: "FXI", name: "China Large Cap", changePct: 1.01 },
      ];
  const macroCards = [
    { label: "GDP 增速", value: "5.2%", change: "+0.3%", status: "up" },
    { label: "CPI 同比", value: "2.1%", change: "-0.1%", status: "down" },
    { label: "PMI", value: "50.8", change: "+0.4", status: "up" },
    { label: "社融规模", value: "3.45万亿", change: "+0.52万亿", status: "up" },
  ];
  const quote = marketDataResult?.quote;
  const financials = marketDataResult?.financials;
  const news = marketDataResult?.news;
  const metrics = Array.isArray(financials?.metrics) ? financials.metrics.slice(0, 5) : [];
  const articles = Array.isArray(news?.articles) ? news.articles.slice(0, 5) : [];
  const [, setAnalysisResult] = useState(null);
  const [, setAnalysisLoading] = useState(false);
  const [, setAnalysisError] = useState("");
  const [, setShowDataRequest] = useState(false);
  const [, setMissingFields] = useState([]);
  const [extraInfo] = useState({ sector: "", industry: "", timeframe: "", notes: "" });

  const REQUIRED_ANALYSIS_FIELDS = [
    { key: "price", label: "\u6700\u65b0\u4ef7\u683c" },
    { key: "changePct", label: "\u6da8\u8dcc\u5e45" },
    { key: "marketCap", label: "\u603b\u5e02\u503c" },
    { key: "pe", label: "\u5e02\u76c8\u7387 PE" },
    { key: "pb", label: "\u5e02\u51c0\u7387 PB" },
    { key: "dividendYield", label: "\u80a1\u606f\u7387" },
    { key: "week52High", label: "52\u5468\u6700\u9ad8" },
    { key: "week52Low", label: "52\u5468\u6700\u4f4e" },
  ];

  function resolveAnalysisFieldValue(key) {
    const q = quote || {};
    const f = financials || {};
    const pick = (v) => (v === undefined || v === null || v === "" ? "" : String(v));
    switch (key) {
      case "price":
        return pick(q.price);
      case "changePct":
        return pick(q.changePct);
      case "marketCap":
        return pick(q.marketCap ?? f.marketCap);
      case "pe":
        return pick(q.pe ?? f.pe);
      case "pb":
        return pick(q.pb ?? f.pb);
      case "dividendYield":
        return pick(q.dividendYield ?? f.dividendYield);
      case "week52High":
        return pick(q.week52High ?? f.week52High);
      case "week52Low":
        return pick(q.week52Low ?? f.week52Low);
      default:
        return "";
    }
  }

  const _openDataRequest = useCallback(() => {
    const missing = REQUIRED_ANALYSIS_FIELDS.filter((field) => !resolveAnalysisFieldValue(field.key));
    setMissingFields(missing);
    setShowDataRequest(true);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [quote, financials]);

  const _generateDeepAnalysis = useCallback(async () => {
    const missing = REQUIRED_ANALYSIS_FIELDS.filter((field) => !resolveAnalysisFieldValue(field.key));
    if (missing.length) {
      setMissingFields(missing);
      setShowDataRequest(true);
      return;
    }
    const payload = [
      `\u80a1\u7968\u4ee3\u7801: ${marketSymbol}`,
      quote?.name ? `\u540d\u79f0: ${quote.name}` : "",
      resolveAnalysisFieldValue("price") ? `\u6700\u65b0\u4ef7\u683c: ${resolveAnalysisFieldValue("price")}` : "",
      resolveAnalysisFieldValue("changePct") ? `\u6da8\u8dcc\u5e45: ${resolveAnalysisFieldValue("changePct")}%` : "",
      resolveAnalysisFieldValue("marketCap") ? `\u603b\u5e02\u503c: ${resolveAnalysisFieldValue("marketCap")}` : "",
      resolveAnalysisFieldValue("pe") ? `PE(TTM): ${resolveAnalysisFieldValue("pe")}` : "",
      resolveAnalysisFieldValue("pb") ? `PB: ${resolveAnalysisFieldValue("pb")}` : "",
      resolveAnalysisFieldValue("dividendYield")
        ? `\u80a1\u606f\u7387: ${resolveAnalysisFieldValue("dividendYield")}`
        : "",
      resolveAnalysisFieldValue("week52High") ? `52\u5468\u6700\u9ad8: ${resolveAnalysisFieldValue("week52High")}` : "",
      resolveAnalysisFieldValue("week52Low") ? `52\u5468\u6700\u4f4e: ${resolveAnalysisFieldValue("week52Low")}` : "",
      extraInfo.sector ? `\u884c\u4e1a: ${extraInfo.sector}` : "",
      extraInfo.industry ? `\u7ec6\u5206\u884c\u4e1a: ${extraInfo.industry}` : "",
      extraInfo.timeframe ? `\u5173\u6ce8\u5468\u671f: ${extraInfo.timeframe}` : "",
      extraInfo.notes ? `\u8865\u5145\u8bf4\u660e: ${extraInfo.notes}` : "",
    ]
      .filter(Boolean)
      .join("\n");

    setAnalysisLoading(true);
    setAnalysisError("");
    try {
      const result = await api("/chat/messages", {
        method: "POST",
        body: JSON.stringify({
          message: payload,
          workflowKey: "deep_dive",
          state: { ticker: marketSymbol, symbol: marketSymbol, sector: extraInfo.sector, industry: extraInfo.industry },
        }),
      });
      let content = result?.content || result?.message || "\u672a\u8fd4\u56de\u5206\u6790\u5185\u5bb9";
      if (result?.routedToWorkflow && result?.runId) {
        try {
          const run = await waitForWorkflowRun(api, result.runId);
          const runResult = parseRunResultJson(run?.resultJson ?? run?.result_json);
          content = summarizeWorkflowRunResult(run, result, runResult);
        } catch (fetchErr) {
          // eslint-disable-next-line no-console
          console.warn("Failed to fetch workflow run result:", fetchErr);
          content = `工作流已启动但获取结果失败: ${fetchErr?.message || fetchErr}`;
        }
      }
      setAnalysisResult(content);
    } catch (ex) {
      setAnalysisError(ex.message || "\u751f\u6210\u6df1\u5ea6\u5206\u6790\u5931\u8d25");
    } finally {
      setAnalysisLoading(false);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [api, marketSymbol, quote, financials, extraInfo]);

  return (
    <div>
      <h2 className="mb-6 text-lg font-semibold text-gray-800">数据中心仪表盘</h2>
      <div className="mb-6 grid grid-cols-1 gap-4 lg:grid-cols-2">
        <div className="card">
          <h3 className="mb-3 text-sm font-medium text-gray-700">宏观象限</h3>
          <div className="rounded-lg border border-gray-200 bg-gray-50 p-4">
            <p className="text-sm font-semibold text-gray-900">数据接入中</p>
            <p className="mt-2 text-sm leading-6 text-gray-500">
              将展示增长、通胀、流动性和风险偏好的最新分区。接口上线前不展示模拟图形。
            </p>
          </div>
        </div>
        <div className="card">
          <h3 className="mb-3 text-sm font-medium text-gray-700">信用脉冲指数</h3>
          <div className="rounded-lg border border-gray-200 bg-gray-50 p-4">
            <p className="text-sm font-semibold text-gray-900">数据接入中</p>
            <p className="mt-2 text-sm leading-6 text-gray-500">等待后端返回信用扩张、社融和期限利差序列后展示趋势。</p>
          </div>
        </div>
      </div>
      <div className="mb-6 grid grid-cols-2 gap-4 md:grid-cols-4">
        {macroCards.map((card) => (
          <div key={card.label} className="card">
            <p className="mb-1 text-xs text-gray-500">{card.label}</p>
            <p className="text-xl font-bold text-gray-800">{card.value}</p>
            <p className={`mt-1 text-xs ${card.status === "up" ? "text-green-600" : "text-red-600"}`}>{card.change}</p>
          </div>
        ))}
      </div>
      <div className="card mb-6">
        <h3 className="mb-3 text-sm font-medium text-gray-700">全球市场快照</h3>
        <div className="grid grid-cols-2 gap-4 md:grid-cols-3 lg:grid-cols-6">
          {markets.slice(0, 6).map((m) => (
            <div key={m.symbol} className="rounded-lg bg-gray-50 p-3 text-center">
              <p className="mb-1 text-xs text-gray-500">{m.symbol}</p>
              <p className="text-sm font-semibold text-gray-800">{m.name}</p>
              <p className={`text-xs ${Number(m.changePct) >= 0 ? "text-green-600" : "text-red-600"}`}>
                {Number(m.changePct).toFixed(2)}%
              </p>
            </div>
          ))}
        </div>
      </div>
      <div className="card" data-testid="market-data-query-panel">
        <div className="flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
          <div>
            <h3 className="text-sm font-medium text-gray-700">个股研究</h3>
            <p className="mt-1 text-xs text-gray-500">行情、SEC 财报和近期新闻实时查询</p>
          </div>
          <form
            className="flex w-full gap-2 lg:w-[360px]"
            onSubmit={(event) => {
              event.preventDefault();
              loadMarketData();
            }}
          >
            <input
              type="text"
              className="input-field flex-1"
              value={marketSymbol}
              onChange={(event) => setMarketSymbol(event.target.value)}
              placeholder="AAPL / MSFT / NVDA"
            />
            <button className="btn-primary text-sm" type="submit" disabled={marketDataLoading}>
              {marketDataLoading ? "查询中" : "查询"}
            </button>
          </form>
        </div>
        {marketDataError && (
          <div className="mt-3 rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
            {marketDataError}
          </div>
        )}
        <div className="mt-4 grid grid-cols-1 gap-4 xl:grid-cols-3">
          <div className="rounded-lg border border-gray-200 bg-white p-4" data-testid="market-data-quote-card">
            <div className="flex items-start justify-between gap-3">
              <div>
                <p className="text-xs text-gray-500">实时行情</p>
                <p className="mt-1 text-lg font-semibold text-gray-900">{quote?.symbol || marketSymbol || "-"}</p>
              </div>
              <span className="rounded-full bg-gray-100 px-2 py-1 text-xs text-gray-600">
                {quote?.provider || "未查询"}
              </span>
            </div>
            <p className="mt-4 text-2xl font-semibold text-gray-950">
              {quote?.price !== undefined ? formatNumber(quote.price) : "-"}
            </p>
            <p className={`mt-1 text-sm ${Number(quote?.changePct || 0) >= 0 ? "text-green-600" : "text-red-600"}`}>
              {quote?.changePct !== undefined ? formatPercent(quote.changePct) : "-"}
            </p>
            <p className="mt-3 text-xs text-gray-500">
              更新时间：{formatMarketTimestamp(quote?.asOf || quote?.retrievedAt)}
            </p>
            <p className="mt-1 text-xs text-gray-400">
              {quote?.delayHint || (quote?.provider ? "延迟由数据源决定" : "等待查询")}
            </p>
          </div>
          <div className="rounded-lg border border-gray-200 bg-white p-4" data-testid="market-data-financials-list">
            <div className="flex items-start justify-between gap-3">
              <div>
                <p className="text-xs text-gray-500">财报数据</p>
                <p className="mt-1 text-sm font-semibold text-gray-900">
                  {financials?.companyName || financials?.symbol || "-"}
                </p>
              </div>
              <span className="rounded-full bg-gray-100 px-2 py-1 text-xs text-gray-600">
                {financials?.provider || "未查询"}
              </span>
            </div>
            <div className="mt-3 space-y-2">
              {metrics.length ? (
                metrics.map((metric) => (
                  <div
                    key={`${metric.metric}-${metric.filed || metric.end || metric.unit}`}
                    className="flex items-center justify-between gap-3 text-sm"
                  >
                    <span className="truncate text-gray-600">{sanitizeText(metric.label || metric.metric)}</span>
                    <span className="shrink-0 font-medium text-gray-900">
                      {formatNumber(metric.value)} {metric.unit || ""}
                    </span>
                  </div>
                ))
              ) : (
                <p className="text-sm text-gray-400">等待查询</p>
              )}
            </div>
          </div>
          <div className="rounded-lg border border-gray-200 bg-white p-4" data-testid="market-data-news-list">
            <div className="flex items-start justify-between gap-3">
              <div>
                <p className="text-xs text-gray-500">近期新闻</p>
                <p className="mt-1 text-sm font-semibold text-gray-900">
                  {articles.length ? `${articles.length} 条` : "-"}
                </p>
              </div>
              <span className="rounded-full bg-gray-100 px-2 py-1 text-xs text-gray-600">
                {news?.provider || "未查询"}
              </span>
            </div>
            <div className="mt-3 space-y-3">
              {articles.length ? (
                articles.map((article, index) => (
                  <a
                    key={`${article.url || article.title}-${index}`}
                    className="block rounded-lg border border-gray-100 px-3 py-2 hover:bg-gray-50"
                    href={article.url || "#"}
                    target="_blank"
                    rel="noreferrer"
                  >
                    <p className="line-clamp-2 text-sm font-medium text-gray-800">
                      {sanitizeText(article.title) || "-"}
                    </p>
                    <p className="mt-1 text-xs text-gray-500">
                      {sanitizeText(article.source || article.provider || "news")} ·{" "}
                      {formatMarketTimestamp(article.publishedAt || news?.asOf || news?.retrievedAt)}
                    </p>
                  </a>
                ))
              ) : (
                <p className="text-sm text-gray-400">等待查询</p>
              )}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
