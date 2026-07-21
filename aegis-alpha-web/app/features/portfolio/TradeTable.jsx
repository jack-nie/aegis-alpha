"use client";

import React from "react";
import { formatMoney, formatNumber } from "../../lib/format";
import {
  normalizeTradePayload,
  parseTradeCsv,
  totalFees,
} from "./tradeUtils";

export function _TradeInput({ label, required, children }) {
  return (
    <label className="block">
      <span className="mb-1.5 block text-xs font-medium text-gray-600">
        {label}
        {required && <span className="text-red-500"> *</span>}
      </span>
      {children}
    </label>
  );
}

export function TradeTable({ trades }) {
  if (!trades.length)
    return (
      <div className="rounded-lg border border-dashed border-gray-300 bg-gray-50 py-14 text-center text-sm text-gray-400">
        暂无交易记录，先导入 Excel 或手动录入一笔交易。
      </div>
    );
  return (
    <div className="overflow-auto rounded-lg border border-gray-200">
      <table className="w-full min-w-[980px] divide-y divide-gray-200">
        <thead className="bg-gray-50">
          <tr>
            {["交易日", "代码", "名称", "方向", "数量", "成交价", "费用", "净金额", "策略", "来源"].map((head) => (
              <th key={head} className="table-header">
                {head}
              </th>
            ))}
          </tr>
        </thead>
        <tbody className="divide-y divide-gray-100 bg-white">
          {trades.slice(0, 80).map((trade) => (
            <tr key={trade.tradeId || `${trade.symbol}-${trade.tradeDate}-${trade.quantity}`}>
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
              <td className="table-cell">{formatNumber(totalFees(trade))}</td>
              <td className="table-cell font-medium">{formatMoney(trade.netAmount, trade.currency || "USD")}</td>
              <td className="table-cell">{trade.strategy || "-"}</td>
              <td className="table-cell">{trade.sourceType || "MANUAL"}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export async function readTradeWorkbook(file, defaultPortfolioId) {
  const fileName = file.name.toLowerCase();
  if (fileName.endsWith(".csv")) {
    const text = await file.text();
    return parseTradeCsv(text)
      .map((row) => normalizeTradePayload(row, defaultPortfolioId, "CSV"))
      .filter((row) => row.symbol && row.tradeDate);
  }
  if (!fileName.endsWith(".xlsx")) {
    throw new Error("当前支持 .xlsx 和 .csv 文件，请先另存为 xlsx 后导入。");
  }
  const { default: readXlsxFile } = await import("read-excel-file/browser");
  const rows = await readXlsxFile(file);
  if (!rows.length) return [];
  const headers = rows[0].map((cell) => String(cell || "").trim());
  const rawRows = rows.slice(1).map((row) =>
    headers.reduce((record, header, index) => {
      if (header) record[header] = row[index] ?? "";
      return record;
    }, {}),
  );
  return rawRows
    .map((row) => normalizeTradePayload(row, defaultPortfolioId, "EXCEL"))
    .filter((row) => row.symbol && row.tradeDate);
}
