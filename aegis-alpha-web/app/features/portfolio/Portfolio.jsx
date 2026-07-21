"use client";

import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { csvCell, formatMoney, formatNumber, formatPercent } from "../../lib/format";
import { DrawerShell, JsonViewer, ModalShell, SummaryCard } from "../../components/ui";
import { TradeTable, readTradeWorkbook } from "./TradeTable";
import {
  emptyTradeForm,
  normalizeTradePayload,
  totalFees,
  tradeFieldGroups,
} from "./tradeUtils";

export function Portfolio({ api, navigate, path }) {
  const [portfolios, setPortfolios] = useState([]);
  const [trades, setTrades] = useState([]);
  const [portfolioContract, setPortfolioContract] = useState(null);
  const [form, setForm] = useState(emptyTradeForm);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");
  const [importPreview, setImportPreview] = useState([]);
  const [includeClosedPositions, setIncludeClosedPositions] = useState(false);
  const [holdingView, setHoldingView] = useState("list");
  const [tradeDrawerOpen, setTradeDrawerOpen] = useState(false);
  const [createModalOpen, setCreateModalOpen] = useState(false);
  const [manualTradeModalOpen, setManualTradeModalOpen] = useState(false);
  const [tradeSearch, setTradeSearch] = useState("");
  const [tradeFilterSide, setTradeFilterSide] = useState("ALL");
  const [tradeFilterMarket, setTradeFilterMarket] = useState("ALL");
  const [tradePage, setTradePage] = useState(1);
  const [expandedTradeIdx, setExpandedTradeIdx] = useState(-1);
  const TRADE_PAGE_SIZE = 20;
  const [newPortfolioName, setNewPortfolioName] = useState("");
  const fileInputRef = useRef(null);

  const selectedPortfolioId = form.portfolioId || portfolios[0]?.id || "";
  const selectedPortfolio = useMemo(
    () => portfolios.find((portfolio) => portfolio.id === selectedPortfolioId) || portfolios[0] || null,
    [portfolios, selectedPortfolioId],
  );
  const contractTrades = Array.isArray(portfolioContract?.trades) ? portfolioContract.trades : trades;
  const visibleTrades = useMemo(() => {
    if (!selectedPortfolioId) return contractTrades;
    return contractTrades.filter((trade) => !trade.portfolioId || trade.portfolioId === selectedPortfolioId);
  }, [contractTrades, selectedPortfolioId]);

  const summary = useMemo(() => {
    return visibleTrades.reduce(
      (acc, trade) => {
        const quantity = Number(trade.quantity || 0);
        const price = Number(trade.price || 0);
        const gross = Number(trade.grossAmount || quantity * price || 0);
        const net = Number(trade.netAmount || 0);
        acc.count += 1;
        acc.gross += gross;
        acc.net += net;
        if (trade.side === "BUY") acc.buys += 1;
        if (trade.side === "SELL") acc.sells += 1;
        return acc;
      },
      { count: 0, buys: 0, sells: 0, gross: 0, net: 0 },
    );
  }, [visibleTrades]);

  const holdingRows = useMemo(() => {
    if (Array.isArray(portfolioContract?.positions)) {
      return portfolioContract.positions
        .map((row) => ({
          symbol: row.symbol,
          name: row.securityName || row.name || "",
          market: row.market || "",
          currency: row.currency || "USD",
          quantity: Number(row.quantity || 0),
          cost: Number(row.costBasis || row.cost || 0),
          averageCost: Number(row.quantity || 0)
            ? Number(row.costBasis || row.cost || 0) / Number(row.quantity || 0)
            : 0,
        }))
        .filter((row) => includeClosedPositions || Math.abs(row.quantity) > 0.000001);
    }
    const positions = new Map();
    visibleTrades.forEach((trade) => {
      const symbol = String(trade.symbol || "")
        .trim()
        .toUpperCase();
      if (!symbol) return;
      const quantity = Number(trade.quantity || 0);
      const signedQuantity = trade.side === "SELL" ? -quantity : quantity;
      const gross = Number(trade.grossAmount || quantity * Number(trade.price || 0) || 0);
      const current = positions.get(symbol) || {
        symbol,
        name: trade.securityName || "",
        market: trade.market || "",
        currency: trade.currency || "USD",
        quantity: 0,
        cost: 0,
      };
      current.quantity += signedQuantity;
      current.cost += trade.side === "SELL" ? -gross : gross;
      if (!current.name && trade.securityName) current.name = trade.securityName;
      if (!current.market && trade.market) current.market = trade.market;
      positions.set(symbol, current);
    });
    return Array.from(positions.values())
      .map((row) => ({ ...row, averageCost: row.quantity ? row.cost / row.quantity : 0 }))
      .filter((row) => includeClosedPositions || Math.abs(row.quantity) > 0.000001);
  }, [visibleTrades, includeClosedPositions, portfolioContract]);
  const dataCompleteness = useMemo(() => {
    if (portfolioContract?.dataCompleteness) return portfolioContract.dataCompleteness;
    const expectedAssets = Number(selectedPortfolio?.assets || 0);
    const expectedTransactions = Number(selectedPortfolio?.transactions || 0);
    if (!selectedPortfolio) return "NO_PORTFOLIO";
    if (!visibleTrades.length && (expectedAssets > 0 || expectedTransactions > 0)) return "SEEDED_SUMMARY_ONLY";
    if (!holdingRows.length && visibleTrades.length) return "NO_OPEN_POSITIONS";
    return "DETAILS_SYNCED";
  }, [holdingRows.length, selectedPortfolio, visibleTrades.length, portfolioContract]);
  const sourceStatus = {
    NO_PORTFOLIO: "尚未创建组合",
    SEEDED_SUMMARY_ONLY: "摘要已导入，交易与持仓明细待同步",
    NO_OPEN_POSITIONS: "交易明细已同步，当前没有未平仓仓位",
    DETAILS_SYNCED: "明细已同步",
  }[dataCompleteness];
  const contractSourceStatus = portfolioContract?.sourceStatus || sourceStatus;

  const load = useCallback(async () => {
    const [portfolioList, tradeList] = await Promise.all([
      api("/portfolio/portfolios"),
      api("/portfolio/trades").catch(() => []),
    ]);
    setPortfolios(portfolioList || []);
    setTrades(tradeList || []);
    setForm((current) => ({ ...current, portfolioId: current.portfolioId || portfolioList?.[0]?.id || "" }));
  }, [api]);

  useEffect(() => {
    load().catch((ex) => setMessage(ex.message));
  }, [load]);

  useEffect(() => {
    if (!selectedPortfolioId) {
      setPortfolioContract(null);
      return;
    }
    let cancelled = false;
    Promise.all([
      api(`/portfolio/${selectedPortfolioId}/positions`).catch(() => null),
      api(`/portfolio/${selectedPortfolioId}/trades`).catch(() => null),
    ])
      .then(([positionsContract, tradesContract]) => {
        if (cancelled) return;
        setPortfolioContract({
          ...(positionsContract || {}),
          trades: Array.isArray(tradesContract?.trades) ? tradesContract.trades : undefined,
          tradesContract,
        });
      })
      .catch(() => {
        if (!cancelled) setPortfolioContract(null);
      });
    return () => {
      cancelled = true;
    };
  }, [api, selectedPortfolioId]);

  const patchForm = (patch) => setForm((current) => ({ ...current, ...patch }));

  async function createPortfolio(name) {
    const portfolioName = (name || "").trim() || `Trading Portfolio ${portfolios.length + 1}`;
    setBusy(true);
    setMessage("");
    try {
      const created = await api("/portfolio/portfolios", {
        method: "POST",
        body: JSON.stringify({ name: portfolioName }),
      });
      await load();
      patchForm({ portfolioId: created.id });
      setCreateModalOpen(false);
      setNewPortfolioName("");
      setMessage("已创建组合，可以开始录入交易。");
    } catch (ex) {
      setMessage(ex.message);
    } finally {
      setBusy(false);
    }
  }

  async function _submitManualTrade(event) {
    event.preventDefault();
    setBusy(true);
    setMessage("");
    try {
      const payload = normalizeTradePayload(form, selectedPortfolioId);
      const saved = await api("/portfolio/trades", { method: "POST", body: JSON.stringify(payload) });
      setTrades((current) => [saved, ...current]);
      setForm((current) => ({
        ...emptyTradeForm,
        portfolioId: current.portfolioId || selectedPortfolioId,
        tradeDate: new Date().toISOString().slice(0, 10),
      }));
      setMessage("交易已保存。");
    } catch (ex) {
      setMessage(ex.message);
    } finally {
      setBusy(false);
    }
  }

  async function handleFileImport(event) {
    const file = event.target.files?.[0];
    if (!file) return;
    setBusy(true);
    setMessage("");
    setImportPreview([]);
    try {
      const rows = await readTradeWorkbook(file, selectedPortfolioId);
      if (!rows.length) throw new Error("文件里没有可导入的交易行。");
      const result = await api("/portfolio/trades/import", { method: "POST", body: JSON.stringify({ rows }) });
      const savedRows = Array.isArray(result) ? result : result?.rows || result?.trades || [];
      setImportPreview(rows.slice(0, 5));
      setMessage(`已从 ${file.name} 导入 ${savedRows.length || rows.length} 条交易。`);
      await load();
    } catch (ex) {
      setMessage(ex.message);
    } finally {
      setBusy(false);
      event.target.value = "";
    }
  }

  async function submitCompactTrade(event) {
    event.preventDefault();
    setBusy(true);
    setMessage("");
    try {
      const tradeDate = form.tradeDateTime ? form.tradeDateTime.slice(0, 10) : new Date().toISOString().slice(0, 10);
      const payload = normalizeTradePayload(
        { ...form, tradeDate, commission: form.commission || form.fee || 0 },
        form.portfolioId,
      );
      const saved = await api("/portfolio/trades", { method: "POST", body: JSON.stringify(payload) });
      setTrades((current) => [saved, ...current]);
      setForm((current) => ({ ...emptyTradeForm, portfolioId: current.portfolioId || selectedPortfolioId }));
      setMessage("交易已创建。");
      setTradeDrawerOpen(false);
    } catch (ex) {
      setMessage(ex.message);
    } finally {
      setBusy(false);
    }
  }

  const clearCompactTrade = () =>
    setForm((current) => ({
      ...current,
      tradeDateTime: "",
      symbol: "",
      side: "BUY",
      action: "",
      quantity: "",
      price: "",
      fee: "",
      currency: "USD",
      notes: "",
      strategy: "",
    }));

  function exportAssets() {
    if (!holdingRows.length) {
      setMessage("暂无资产可导出。");
      return;
    }
    const headers = ["symbol", "name", "market", "quantity", "averageCost", "cost", "currency"];
    const lines = [headers.join(",")].concat(
      holdingRows.map((row) => headers.map((key) => csvCell(row[key])).join(",")),
    );
    const blob = new Blob([lines.join("\n")], { type: "text/csv;charset=utf-8" });
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = "portfolio-assets.csv";
    link.click();
    URL.revokeObjectURL(url);
  }

  function exportPortfolios() {
    if (!portfolios.length) {
      setMessage("暂无组合可导出。");
      return;
    }
    const headers = ["id", "name", "nav", "returnPct", "assets", "transactions", "optionCombos", "updatedAt"];
    const lines = [headers.join(",")].concat(
      portfolios.map((portfolio) => headers.map((key) => csvCell(portfolio[key])).join(",")),
    );
    const blob = new Blob([lines.join("\n")], { type: "text/csv;charset=utf-8" });
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = "portfolios.csv";
    link.click();
    URL.revokeObjectURL(url);
  }

  const renderTradeEditor = (onBack) => {
    const createDisabled = busy || !form.symbol.trim() || !form.quantity || !form.price;
    return (
      <div className="bg-white">
        <div className="mb-4 flex items-center justify-between">
          <h2 className="text-[26px] font-bold leading-8 text-black">交易</h2>
          <button
            type="button"
            onClick={onBack}
            className="h-8 rounded-lg border border-gray-200 bg-white px-3 text-sm font-semibold text-black hover:bg-gray-50"
          >
            返回历史
          </button>
        </div>

        <form onSubmit={submitCompactTrade} className="rounded-[14px] border border-gray-200 bg-white p-4">
          <div className="rounded-lg border border-gray-200 bg-white p-3">
            <div className="grid grid-cols-1 gap-2 lg:grid-cols-[1fr_1fr_1fr_1fr_1fr_1fr_1fr]">
              <input
                className="input-field h-10"
                value={form.symbol}
                onChange={(event) => patchForm({ symbol: event.target.value.toUpperCase() })}
                placeholder="代码"
              />
              <select
                className="input-field h-10"
                aria-label="买卖方向"
                value={form.side}
                onChange={(event) => patchForm({ side: event.target.value })}
              >
                <option value="BUY">BUY</option>
                <option value="SELL">SELL</option>
              </select>
              <input
                className="input-field h-10"
                value={form.action}
                onChange={(event) => patchForm({ action: event.target.value })}
                placeholder="动作（可选）"
              />
              <input
                className="input-field h-10"
                value={form.quantity}
                onChange={(event) => patchForm({ quantity: event.target.value })}
                placeholder="数量（如 1 / 100）"
              />
              <input
                className="input-field h-10"
                value={form.price}
                onChange={(event) => patchForm({ price: event.target.value })}
                placeholder="单价（如 12.5）"
              />
              <input
                className="input-field h-10"
                value={form.fee === "0" ? "" : form.fee}
                onChange={(event) => patchForm({ fee: event.target.value, commission: event.target.value })}
                placeholder="手续费（可选，默认 0）"
              />
              <select
                className="input-field h-10 font-semibold text-black"
                aria-label="交易币种"
                value={form.currency}
                onChange={(event) => patchForm({ currency: event.target.value })}
              >
                <option value="USD">USD</option>
                <option value="HKD">HKD</option>
                <option value="CNY">CNY</option>
              </select>
            </div>
            <p className="mt-2 text-xs text-gray-500">
              数量/价格/手续费/币种分别代表仓位规模、成交价、费用与计价币种。手续费可留空，默认 0。
            </p>
            <div className="mt-2 grid grid-cols-1 gap-2 lg:grid-cols-[minmax(0,1fr)_minmax(0,1fr)]">
              <input
                type="datetime-local"
                className="input-field h-10"
                aria-label="交易时间"
                value={form.tradeDateTime}
                onChange={(event) => patchForm({ tradeDateTime: event.target.value })}
              />
              <input
                className="input-field h-10"
                value={form.strategy}
                onChange={(event) => patchForm({ strategy: event.target.value })}
                placeholder="标签（可选）"
              />
            </div>
            <textarea
              className="input-field mt-2 min-h-16 resize-y"
              value={form.notes}
              onChange={(event) => patchForm({ notes: event.target.value })}
              placeholder="备注（可选）：执行备注 / 交易日志..."
            />
            <div className="mt-3 flex justify-end">
              <button
                type="button"
                onClick={clearCompactTrade}
                className="min-h-8 px-3 text-sm font-semibold text-black hover:text-gray-600"
              >
                移除
              </button>
            </div>
          </div>

          <div className="mt-4 max-w-xl">
            <label className="block text-sm text-gray-700">关联组合</label>
            <select
              className="input-field mt-1 h-10 font-semibold text-black"
              aria-label="关联组合"
              value={form.portfolioId}
              onChange={(event) => patchForm({ portfolioId: event.target.value })}
            >
              <option value="">无组合</option>
              {portfolios.map((portfolio) => (
                <option key={portfolio.id} value={portfolio.id}>
                  {portfolio.name}
                </option>
              ))}
            </select>
          </div>

          <div className="mt-4">
            <label className="block text-sm text-gray-700">策略（可选）</label>
            <select
              className="input-field mt-1 h-10 font-semibold text-black"
              aria-label="交易策略"
              value={form.tradeType}
              onChange={(event) => patchForm({ tradeType: event.target.value })}
            >
              <option value="STOCK">无策略</option>
              <option value="CORE">Core</option>
              <option value="SWING">Swing</option>
              <option value="HEDGE">Hedge</option>
            </select>
          </div>

          <h3 className="mt-5 text-base font-semibold text-black">关联投资组合</h3>
          <div className="mt-8 flex items-center justify-between">
            <button
              type="button"
              onClick={() => setMessage("当前页面支持单笔交易录入。")}
              className="h-8 rounded-lg border border-gray-200 bg-white px-3 text-sm font-semibold text-black hover:bg-gray-50"
            >
              新增一行
            </button>
            <button
              type="submit"
              disabled={createDisabled}
              className="h-8 rounded-lg bg-gray-500 px-4 text-sm font-semibold text-white disabled:cursor-not-allowed disabled:opacity-80"
            >
              创建交易
            </button>
          </div>
        </form>

        {message && (
          <div className="mt-4 rounded-lg border border-blue-200 bg-blue-50 px-4 py-3 text-sm text-blue-800">
            {message}
          </div>
        )}
      </div>
    );
  };

  if (path === "/portfolio/trades") {
    const filteredTrades = visibleTrades.filter((trade) => {
      if (tradeSearch) {
        const q = tradeSearch.toLowerCase();
        const matchesSymbol = (trade.symbol || "").toLowerCase().includes(q);
        const matchesName = (trade.securityName || "").toLowerCase().includes(q);
        const matchesStrategy = (trade.strategy || "").toLowerCase().includes(q);
        if (!matchesSymbol && !matchesName && !matchesStrategy) return false;
      }
      if (tradeFilterSide !== "ALL" && trade.side !== tradeFilterSide) return false;
      if (tradeFilterMarket !== "ALL" && (trade.market || "").toUpperCase() !== tradeFilterMarket) return false;
      return true;
    });
    const tradeTotalPages = Math.max(1, Math.ceil(filteredTrades.length / TRADE_PAGE_SIZE));
    const tradeCurrentPage = filteredTrades.slice((tradePage - 1) * TRADE_PAGE_SIZE, tradePage * TRADE_PAGE_SIZE);
    const tradePageBtns = [];
    for (let i = 1; i <= tradeTotalPages; i += 1) {
      if (tradeTotalPages <= 7 || i === 1 || i === tradeTotalPages || Math.abs(i - tradePage) <= 1) {
        tradePageBtns.push(i);
      } else if (tradePageBtns[tradePageBtns.length - 1] !== "...") {
        tradePageBtns.push("...");
      }
    }

    const tradeTotalFees = filteredTrades.reduce((s, t) => s + totalFees(t), 0);
    const tradeNetFlow = filteredTrades.reduce((s, t) => s + Number(t.netAmount || 0), 0);

    return (
      <>
        <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
          <h2 className="text-[26px] font-bold leading-8 text-black">交易流水</h2>
          <div className="flex flex-wrap items-center gap-2">
            <button
              type="button"
              onClick={() => setManualTradeModalOpen(true)}
              className="h-8 rounded-lg bg-blue-600 px-4 text-sm font-semibold text-white hover:bg-blue-700 disabled:opacity-50"
              disabled={busy}
            >
              录入交易
            </button>
            <button
              type="button"
              onClick={() => fileInputRef.current?.click()}
              className="h-8 rounded-lg border border-gray-200 bg-white px-3 text-sm font-semibold text-black hover:bg-gray-50"
            >
              导入 Excel
            </button>
            <button
              type="button"
              onClick={exportAssets}
              className="h-8 rounded-lg border border-gray-200 bg-white px-3 text-sm font-semibold text-black hover:bg-gray-50"
            >
              导出 CSV
            </button>
          </div>
        </div>

        <div className="mb-4 grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-5">
          {[
            { label: "总交易数", value: filteredTrades.length },
            {
              label: "买入/卖出",
              value: `${filteredTrades.filter((t) => t.side === "BUY").length}/${filteredTrades.filter((t) => t.side === "SELL").length}`,
            },
            {
              label: "总成交额",
              value: formatMoney(
                filteredTrades.reduce(
                  (s, t) => s + Number(t.grossAmount || Number(t.quantity || 0) * Number(t.price || 0)),
                  0,
                ),
              ),
            },
            { label: "总费用", value: formatNumber(tradeTotalFees) },
            { label: "净流入", value: formatMoney(tradeNetFlow) },
          ].map((card) => (
            <div key={card.label} className="rounded-lg border border-gray-200 bg-white p-4">
              <div className="text-xs text-gray-500">{card.label}</div>
              <div className="mt-1 text-lg font-bold text-gray-900">{card.value}</div>
            </div>
          ))}
        </div>

        <div className="mb-4 flex flex-wrap items-center gap-3">
          <input
            value={tradeSearch}
            onChange={(e) => {
              setTradeSearch(e.target.value);
              setTradePage(1);
            }}
            placeholder="搜索代码 / 名称 / 策略..."
            className="h-8 w-64 rounded-lg border border-gray-200 px-3 text-sm focus:border-blue-500 focus:outline-none"
          />
          <select
            value={tradeFilterSide}
            onChange={(e) => {
              setTradeFilterSide(e.target.value);
              setTradePage(1);
            }}
            className="h-8 rounded-lg border border-gray-200 px-2 text-sm"
          >
            <option value="ALL">全部方向</option>
            <option value="BUY">买入</option>
            <option value="SELL">卖出</option>
          </select>
          <select
            value={tradeFilterMarket}
            onChange={(e) => {
              setTradeFilterMarket(e.target.value);
              setTradePage(1);
            }}
            className="h-8 rounded-lg border border-gray-200 px-2 text-sm"
          >
            <option value="ALL">全部市场</option>
            <option value="US">美股</option>
            <option value="HK">港股</option>
            <option value="CN">A股</option>
          </select>
        </div>

        {tradeCurrentPage.length === 0 ? (
          <div className="rounded-lg border border-dashed border-gray-300 bg-gray-50 py-14 text-center text-sm text-gray-400">
            暂无交易记录，先导入 Excel 或手动录入一笔交易。
          </div>
        ) : (
          <div className="overflow-auto rounded-lg border border-gray-200">
            <table className="w-full min-w-[980px] divide-y divide-gray-200">
              <thead className="bg-gray-50">
                <tr>
                  {["交易日", "代码", "名称", "方向", "数量", "成交价", "成交额", "费用", "净金额", "策略"].map(
                    (head) => (
                      <th key={head} className="table-header">
                        {head}
                      </th>
                    ),
                  )}
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100 bg-white">
                {tradeCurrentPage.map((trade, idx) => {
                  const globalIdx = (tradePage - 1) * TRADE_PAGE_SIZE + idx;
                  return (
                    <React.Fragment key={trade.tradeId || `${trade.symbol}-${trade.tradeDate}-${idx}`}>
                      <tr
                        className="cursor-pointer hover:bg-blue-50/40"
                        onClick={() => setExpandedTradeIdx(expandedTradeIdx === globalIdx ? -1 : globalIdx)}
                      >
                        <td className="table-cell">{trade.tradeDate}</td>
                        <td className="table-cell font-semibold text-gray-900">{trade.symbol}</td>
                        <td className="table-cell">{trade.securityName || "-"}</td>
                        <td className="table-cell">
                          <span className={trade.side === "SELL" ? "badge-red" : "badge-green"}>
                            {trade.side === "SELL" ? "卖出" : "买入"}
                          </span>
                        </td>
                        <td className="table-cell">{formatNumber(trade.quantity)}</td>
                        <td className="table-cell">{formatNumber(trade.price)}</td>
                        <td className="table-cell">
                          {formatMoney(
                            Number(trade.grossAmount || 0) || Number(trade.quantity || 0) * Number(trade.price || 0),
                            trade.currency || "USD",
                          )}
                        </td>
                        <td className="table-cell">{formatNumber(totalFees(trade))}</td>
                        <td className="table-cell font-medium">
                          {formatMoney(trade.netAmount, trade.currency || "USD")}
                        </td>
                        <td className="table-cell">{trade.strategy || "-"}</td>
                      </tr>
                      {expandedTradeIdx === globalIdx && (
                        <tr>
                          <td colSpan={10} className="bg-gray-50 px-6 py-4">
                            <div className="grid grid-cols-2 gap-x-8 gap-y-2 text-sm sm:grid-cols-3 lg:grid-cols-5">
                              <div>
                                <span className="text-gray-500">结算日：</span>
                                {trade.settlementDate || "-"}
                              </div>
                              <div>
                                <span className="text-gray-500">市场：</span>
                                {trade.market || "-"}
                              </div>
                              <div>
                                <span className="text-gray-500">券商：</span>
                                {trade.broker || "-"}
                              </div>
                              <div>
                                <span className="text-gray-500">币种：</span>
                                {trade.currency || "USD"}
                              </div>
                              <div>
                                <span className="text-gray-500">佣金：</span>
                                {formatNumber(trade.commission)}
                              </div>
                              <div>
                                <span className="text-gray-500">税费：</span>
                                {formatNumber(trade.tax)}
                              </div>
                              <div>
                                <span className="text-gray-500">汇率：</span>
                                {trade.fxRate || "-"}
                              </div>
                              <div>
                                <span className="text-gray-500">来源：</span>
                                {trade.sourceType || "MANUAL"}
                              </div>
                              <div className="col-span-2 sm:col-span-3 lg:col-span-5">
                                <span className="text-gray-500">备注：</span>
                                {trade.notes || "-"}
                              </div>
                            </div>
                          </td>
                        </tr>
                      )}
                    </React.Fragment>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}

        {tradeTotalPages > 1 && (
          <div className="mt-4 flex items-center justify-center gap-1">
            <button
              type="button"
              disabled={tradePage <= 1}
              onClick={() => setTradePage(tradePage - 1)}
              className="h-8 rounded-lg border border-gray-200 bg-white px-3 text-sm text-gray-700 hover:bg-gray-50 disabled:opacity-50"
            >
              上一页
            </button>
            {tradePageBtns.map((p, i) =>
              p === "..." ? (
                <span key={`ellipsis-${i}`} className="px-2 text-sm text-gray-400">
                  ...
                </span>
              ) : (
                <button
                  type="button"
                  key={p}
                  onClick={() => setTradePage(p)}
                  className={`h-8 min-w-[32px] rounded-lg px-2 text-sm ${
                    p === tradePage
                      ? "bg-blue-600 font-semibold text-white"
                      : "border border-gray-200 bg-white text-gray-700 hover:bg-gray-50"
                  }`}
                >
                  {p}
                </button>
              ),
            )}
            <button
              type="button"
              disabled={tradePage >= tradeTotalPages}
              onClick={() => setTradePage(tradePage + 1)}
              className="h-8 rounded-lg border border-gray-200 bg-white px-3 text-sm text-gray-700 hover:bg-gray-50 disabled:opacity-50"
            >
              下一页
            </button>
          </div>
        )}
      </>
    );
  }

  if (path === "/portfolio/portfolios") {
    return (
      <div className="bg-white">
        <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
          <h2 className="text-[26px] font-bold leading-8 text-black">组合列表</h2>
          <div className="flex flex-wrap items-center gap-2">
            <button
              type="button"
              onClick={exportPortfolios}
              className="h-8 rounded-lg border border-gray-200 bg-white px-3 text-sm font-semibold text-black hover:bg-gray-50"
            >
              导出组合
            </button>
            <button
              type="button"
              onClick={() => {
                setNewPortfolioName("");
                setCreateModalOpen(true);
              }}
              className="h-8 rounded-lg bg-neutral-950 px-3 text-sm font-semibold text-white hover:bg-neutral-800"
            >
              新建组合
            </button>
          </div>
        </div>

        {message && (
          <div className="mb-4 rounded-lg border border-blue-200 bg-blue-50 px-4 py-3 text-sm text-blue-800">
            {message}
          </div>
        )}

        {portfolios.length ? (
          <div data-testid="portfolio-list-table" className="overflow-auto rounded-lg border border-gray-200 bg-white">
            <table className="w-full min-w-[980px] divide-y divide-gray-200 text-sm">
              <thead className="bg-gray-50 text-left text-xs font-semibold text-gray-500">
                <tr>
                  {["组合名称", "净资产", "收益率", "资产", "交易", "期权组合", "更新日期", "操作"].map((item) => (
                    <th key={item} className="px-4 py-3">
                      {item}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100 bg-white">
                {portfolios.map((portfolio, index) => {
                  const returnPct = Number(portfolio.returnPct);
                  const returnTone = Number.isFinite(returnPct) && returnPct < 0 ? "text-red-600" : "text-green-600";
                  return (
                    <tr key={portfolio.id || `${portfolio.name}-${index}`} className="hover:bg-gray-50">
                      <td className="px-4 py-3">
                        <p className="font-semibold text-gray-900">{portfolio.name || "未命名组合"}</p>
                        <p className="mt-1 max-w-[220px] truncate font-mono text-xs text-gray-400">
                          {portfolio.id || "-"}
                        </p>
                      </td>
                      <td className="px-4 py-3 font-medium text-gray-900">{formatMoney(portfolio.nav)}</td>
                      <td className={`px-4 py-3 font-semibold ${returnTone}`}>{formatPercent(portfolio.returnPct)}</td>
                      <td className="px-4 py-3 text-gray-700">{formatNumber(portfolio.assets)}</td>
                      <td className="px-4 py-3 text-gray-700">{formatNumber(portfolio.transactions)}</td>
                      <td className="px-4 py-3 text-gray-700">{formatNumber(portfolio.optionCombos)}</td>
                      <td className="px-4 py-3 text-gray-700">{portfolio.updatedAt || "-"}</td>
                      <td className="px-4 py-3">
                        <div className="flex flex-wrap items-center gap-2">
                          <button
                            type="button"
                            onClick={() => {
                              patchForm({ portfolioId: portfolio.id });
                              navigate("/portfolio/assets");
                            }}
                            className="h-8 rounded-lg border border-gray-200 bg-white px-3 text-xs font-semibold text-black hover:bg-gray-50"
                          >
                            查看持仓
                          </button>
                          <button
                            type="button"
                            onClick={() => {
                              patchForm({ portfolioId: portfolio.id });
                              navigate("/portfolio/trades");
                            }}
                            className="h-8 rounded-lg bg-neutral-950 px-3 text-xs font-semibold text-white hover:bg-neutral-800"
                          >
                            记录交易
                          </button>
                        </div>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        ) : (
          <div className="rounded-lg border border-gray-200 bg-white px-6 py-7 text-sm text-gray-600">
            还没有组合，请先创建并开始记录资产与交易。
          </div>
        )}
      </div>
    );
  }

  if (path === "/portfolio/assets") {
    return (
      <div className="bg-white">
        <div>
          <h2 className="text-[26px] font-bold leading-8 text-black">持仓</h2>
          <div className="mt-3 inline-flex h-10 items-center rounded-lg bg-gray-100 p-1">
            <button type="button" className="h-8 rounded-md bg-white px-5 text-sm font-semibold text-black shadow-sm">
              持仓
            </button>
            <button
              type="button"
              onClick={() => navigate("/portfolio/trades")}
              className="h-8 rounded-md px-5 text-sm text-gray-700"
            >
              交易
            </button>
          </div>
        </div>

        <div className="mt-4 flex flex-wrap items-center justify-end gap-2">
          <label className="flex h-10 overflow-hidden rounded-lg border border-gray-200 bg-white text-sm">
            <span className="flex items-center border-r border-gray-200 px-3 text-gray-500">视图</span>
            <select
              value={holdingView}
              aria-label="持仓视图"
              onChange={(event) => setHoldingView(event.target.value)}
              className="bg-white px-3 pr-8 font-semibold text-black outline-none"
            >
              <option value="list">仓位列表</option>
              <option value="group">分组视图</option>
            </select>
          </label>
          <label className="flex h-10 items-center gap-2 rounded-lg border border-gray-200 bg-white px-3 text-sm font-semibold text-black">
            <input
              type="checkbox"
              checked={includeClosedPositions}
              onChange={(event) => setIncludeClosedPositions(event.target.checked)}
              className="h-4 w-4 rounded border-gray-300"
            />
            <span>包含已平仓仓位</span>
          </label>
          <button
            type="button"
            onClick={() => {
              setMessage("");
              setTradeDrawerOpen(true);
            }}
            className="h-10 rounded-lg border border-gray-200 bg-white px-4 text-sm font-semibold text-black hover:bg-gray-50"
          >
            新建交易
          </button>
          <button
            type="button"
            onClick={() => fileInputRef.current?.click()}
            disabled={busy}
            className="h-10 rounded-lg border border-gray-200 bg-white px-4 text-sm font-semibold text-black hover:bg-gray-50 disabled:opacity-50"
          >
            CSV 批量导入
          </button>
          <button
            type="button"
            onClick={exportAssets}
            className="h-10 rounded-lg border border-gray-200 bg-white px-4 text-sm font-semibold text-black hover:bg-gray-50"
          >
            导出资产
          </button>
          <input
            ref={fileInputRef}
            type="file"
            accept=".csv,.xlsx"
            aria-label="导入交易文件"
            onChange={handleFileImport}
            className="sr-only"
          />
        </div>

        {message && (
          <div className="mt-4 rounded-lg border border-blue-200 bg-blue-50 px-4 py-3 text-sm text-blue-800">
            {message}
          </div>
        )}

        {holdingRows.length ? (
          <div className="mt-5 overflow-auto rounded-lg border border-gray-200">
            <table className="w-full min-w-[880px] divide-y divide-gray-200 text-sm">
              <thead className="bg-gray-50 text-left text-xs font-semibold text-gray-500">
                <tr>
                  {["代码", "名称", "市场", "数量", "平均成本", "成本", "币种"].map((item) => (
                    <th key={item} className="px-4 py-3">
                      {item}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100 bg-white">
                {holdingRows.map((row) => (
                  <tr key={row.symbol}>
                    <td className="px-4 py-3 font-semibold text-gray-900">{row.symbol}</td>
                    <td className="px-4 py-3 text-gray-700">{row.name || "-"}</td>
                    <td className="px-4 py-3 text-gray-700">{row.market || "-"}</td>
                    <td className="px-4 py-3 text-gray-700">{formatNumber(row.quantity)}</td>
                    <td className="px-4 py-3 text-gray-700">{formatNumber(row.averageCost)}</td>
                    <td className="px-4 py-3 text-gray-700">{formatMoney(row.cost, row.currency)}</td>
                    <td className="px-4 py-3 text-gray-700">{row.currency}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <div className="mt-5 rounded-lg border border-dashed border-gray-300 bg-gray-50 px-4 py-5 text-sm text-gray-600">
            <p className="font-medium text-gray-900">{contractSourceStatus}</p>
            <p className="mt-1 text-gray-500">
              当前组合：{selectedPortfolio?.name || "未选择组合"}。
              {dataCompleteness === "SEEDED_SUMMARY_ONLY"
                ? "组合列表已有摘要计数，但明细接口尚未同步，暂不展示为空持仓。"
                : "明细同步后会在这里展示持仓、成本和币种。"}
            </p>
          </div>
        )}

        {tradeDrawerOpen && (
          <DrawerShell
            title="新建交易"
            onClose={() => setTradeDrawerOpen(false)}
            widthClass="max-w-4xl"
            topClass="top-16 bottom-0"
          >
            <div className="min-h-0 flex-1 overflow-y-auto p-6">
              {renderTradeEditor(() => setTradeDrawerOpen(false))}
            </div>
          </DrawerShell>
        )}
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h2 className="text-lg font-semibold text-gray-800">投资组合总览</h2>
          <p className="mt-1 text-sm text-gray-500">记录股票成交流水，支持 Excel 批量导入和手动补录。</p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <button
            onClick={() => fileInputRef.current?.click()}
            disabled={busy || !selectedPortfolioId}
            className="btn-primary"
          >
            导入 Excel
          </button>
          <button
            onClick={() => {
              setNewPortfolioName("");
              setCreateModalOpen(true);
            }}
            className="btn-secondary"
          >
            新建组合
          </button>
          <button onClick={() => navigate("/portfolio")} className="btn-secondary">
            刷新总览
          </button>
        </div>
        <input
          ref={fileInputRef}
          type="file"
          accept=".xlsx,.csv"
          aria-label="导入交易文件"
          onChange={handleFileImport}
          className="sr-only"
        />
      </div>

      {message && (
        <div className="rounded-lg border border-blue-200 bg-blue-50 px-4 py-3 text-sm text-blue-800">{message}</div>
      )}

      <div className="grid grid-cols-1 gap-4 md:grid-cols-4">
        <SummaryCard label="交易笔数" value={summary.count} />
        <SummaryCard label="买入 / 卖出" value={`${summary.buys} / ${summary.sells}`} />
        <SummaryCard label="成交金额" value={formatMoney(summary.gross, form.currency || "USD")} />
        <SummaryCard label="净现金流" value={formatMoney(summary.net, form.currency || "USD")} />
      </div>

      <div className="grid grid-cols-1 gap-6 xl:grid-cols-[minmax(0,1fr)_420px]">
        <section className="card min-w-0">
          <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
            <div>
              <h3 className="text-sm font-semibold text-gray-800">交易流水</h3>
              <p className="mt-1 text-xs text-gray-500">后端会按数量、价格和费用重新计算成交金额与净金额。</p>
            </div>
            <select
              className="input-field w-64"
              aria-label="选择组合"
              value={selectedPortfolioId}
              onChange={(event) => patchForm({ portfolioId: event.target.value })}
            >
              <option value="">选择组合</option>
              {portfolios.map((portfolio) => (
                <option key={portfolio.id} value={portfolio.id}>
                  {portfolio.name}
                </option>
              ))}
            </select>
          </div>
          <TradeTable trades={visibleTrades} />
        </section>

        <section className="card">
          <div className="flex items-center justify-between">
            <h3 className="text-sm font-semibold text-gray-800">手动录入交易</h3>
            <button
              type="button"
              onClick={() => setManualTradeModalOpen(true)}
              disabled={!selectedPortfolioId}
              className="rounded-lg bg-neutral-950 px-4 py-2 text-sm font-semibold text-white hover:bg-neutral-800 disabled:cursor-not-allowed disabled:opacity-60"
            >
              录入交易
            </button>
          </div>
          <p className="mt-2 text-xs text-gray-400">手动录入单笔交易，支持美股、港股、A股市场。</p>
        </section>
      </div>

      <section className="card">
        <div className="mb-4 flex items-center justify-between gap-3">
          <h3 className="text-sm font-semibold text-gray-800">Excel 字段需求</h3>
          <span className="text-xs text-gray-400">必填：交易日、股票代码、买卖方向、数量、成交价</span>
        </div>
        <div className="grid grid-cols-1 gap-3 md:grid-cols-3">
          {tradeFieldGroups.map((group) => (
            <div key={group.title} className="rounded-lg border border-gray-200 bg-gray-50 p-4">
              <p className="text-sm font-semibold text-gray-800">{group.title}</p>
              <p className="mt-2 text-sm leading-6 text-gray-500">{group.fields}</p>
            </div>
          ))}
        </div>
        {importPreview.length > 0 && <JsonViewer className="mt-4" value={importPreview} compact />}
      </section>

      {createModalOpen && (
        <ModalShell title="新建组合" onClose={() => setCreateModalOpen(false)}>
          <form
            onSubmit={(e) => {
              e.preventDefault();
              createPortfolio(newPortfolioName);
            }}
            className="p-6"
          >
            <div className="space-y-4">
              <label className="block">
                <span className="mb-1 block text-sm font-medium text-gray-700">组合名称</span>
                <input
                  value={newPortfolioName}
                  onChange={(e) => setNewPortfolioName(e.target.value)}
                  className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none focus:ring-2 focus:ring-blue-500"
                  placeholder="例如：美股核心持仓 / A股量化组合"
                  autoFocus
                />
              </label>
              <p className="text-xs text-gray-400">留空将自动生成默认名称。</p>
            </div>
            <div className="mt-6 flex justify-end gap-3">
              <button
                type="button"
                onClick={() => setCreateModalOpen(false)}
                className="rounded-lg border border-gray-200 px-4 py-2 text-sm text-gray-600 hover:bg-gray-50"
              >
                取消
              </button>
              <button
                type="submit"
                disabled={busy}
                className="rounded-lg bg-neutral-950 px-4 py-2 text-sm font-semibold text-white hover:bg-neutral-800 disabled:cursor-not-allowed disabled:opacity-60"
              >
                {busy ? "创建中..." : "确认创建"}
              </button>
            </div>
          </form>
        </ModalShell>
      )}

      {manualTradeModalOpen && (
        <ModalShell title="录入交易" onClose={() => setManualTradeModalOpen(false)} widthClass="max-w-4xl">
          <div className="min-h-0 flex-1 overflow-y-auto p-6">
            {renderTradeEditor(() => setManualTradeModalOpen(false))}
          </div>
        </ModalShell>
      )}
    </div>
  );
}
