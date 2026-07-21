export const tradeFieldGroups = [
  { title: "成交识别", fields: "交易日、结算日、股票代码、交易所、市场、证券名称、买卖方向" },
  { title: "成交金额", fields: "数量、成交价、成交金额、佣金、印花税、平台费、其他费用、净金额、币种、汇率" },
  { title: "账户策略", fields: "组合、券商、账户、策略、交易类型、订单类型、备注" },
];

export const emptyTradeForm = {
  portfolioId: "",
  tradeDate: new Date().toISOString().slice(0, 10),
  tradeDateTime: "",
  settlementDate: "",
  symbol: "",
  exchange: "",
  market: "US",
  securityName: "",
  side: "BUY",
  quantity: "",
  price: "",
  fee: "0",
  tax: "0",
  commission: "0",
  otherFee: "0",
  currency: "USD",
  fxRate: "1",
  broker: "",
  accountNo: "",
  strategy: "",
  tradeType: "STOCK",
  orderType: "MARKET",
  action: "",
  notes: "",
};

export const tradeHeaderAliases = {
  portfolioId: ["portfolioId", "portfolio_id", "组合ID", "组合", "投资组合"],
  tradeDate: ["tradeDate", "trade_date", "交易日期", "交易日", "成交日期", "成交日"],
  settlementDate: ["settlementDate", "settlement_date", "结算日期", "结算日"],
  symbol: ["symbol", "ticker", "代码", "股票代码", "证券代码", "标的代码"],
  exchange: ["exchange", "交易所", "市场代码"],
  market: ["market", "市场", "地区"],
  securityName: ["securityName", "security_name", "证券名称", "股票名称", "名称"],
  side: ["side", "方向", "买卖", "交易方向", "买卖方向"],
  quantity: ["quantity", "qty", "shares", "数量", "股数", "成交数量"],
  price: ["price", "成交价", "价格", "成交价格"],
  fee: ["fee", "费用", "平台费", "交易费"],
  tax: ["tax", "税费", "印花税"],
  commission: ["commission", "佣金", "手续费"],
  otherFee: ["otherFee", "other_fee", "其他费用", "杂费"],
  currency: ["currency", "币种", "货币"],
  fxRate: ["fxRate", "fx_rate", "汇率"],
  broker: ["broker", "券商", "经纪商"],
  accountNo: ["accountNo", "account_no", "账户", "账号", "资金账户"],
  strategy: ["strategy", "策略", "交易策略"],
  tradeType: ["tradeType", "trade_type", "交易类型", "资产类型"],
  orderType: ["orderType", "order_type", "订单类型", "委托类型"],
  notes: ["notes", "备注", "说明"],
};


export function parseTradeCsv(text) {
  const rows = parseCsvRows(text).filter((row) => row.some((cell) => String(cell).trim()));
  if (!rows.length) return [];
  const headers = rows[0].map((cell) => String(cell || "").trim());
  return rows.slice(1).map((row) =>
    headers.reduce((record, header, index) => {
      if (header) record[header] = row[index] ?? "";
      return record;
    }, {}),
  );
}

export function parseCsvRows(text) {
  const rows = [];
  let row = [];
  let cell = "";
  let inQuotes = false;
  for (let index = 0; index < text.length; index += 1) {
    const char = text[index];
    const next = text[index + 1];
    if (char === '"' && next === '"') {
      cell += '"';
      index += 1;
    } else if (char === '"') {
      inQuotes = !inQuotes;
    } else if (char === "," && !inQuotes) {
      row.push(cell);
      cell = "";
    } else if ((char === "\n" || char === "\r") && !inQuotes) {
      if (char === "\r" && next === "\n") index += 1;
      row.push(cell);
      rows.push(row);
      row = [];
      cell = "";
    } else {
      cell += char;
    }
  }
  row.push(cell);
  rows.push(row);
  return rows;
}

export function normalizeTradePayload(row, defaultPortfolioId, sourceType = "MANUAL") {
  const pick = (field) => pickTradeValue(row, field);
  const quantity = positiveNumber(pick("quantity") || row.quantity);
  const price = positiveNumber(pick("price") || row.price);
  const side = normalizeSide(pick("side") || row.side);
  return {
    portfolioId: pick("portfolioId") || row.portfolioId || defaultPortfolioId,
    tradeDate: normalizeTradeDate(pick("tradeDate") || row.tradeDate),
    settlementDate: normalizeTradeDate(pick("settlementDate") || row.settlementDate),
    symbol: String(pick("symbol") || row.symbol || "")
      .trim()
      .toUpperCase(),
    exchange: String(pick("exchange") || row.exchange || "").trim(),
    market: String(pick("market") || row.market || "US")
      .trim()
      .toUpperCase(),
    securityName: String(pick("securityName") || row.securityName || "").trim(),
    side,
    quantity,
    price,
    fee: positiveNumber(pick("fee") || row.fee),
    tax: positiveNumber(pick("tax") || row.tax),
    commission: positiveNumber(pick("commission") || row.commission),
    otherFee: positiveNumber(pick("otherFee") || row.otherFee),
    currency: String(pick("currency") || row.currency || "USD")
      .trim()
      .toUpperCase(),
    fxRate: positiveNumber(pick("fxRate") || row.fxRate || 1) || 1,
    broker: String(pick("broker") || row.broker || "").trim(),
    accountNo: String(pick("accountNo") || row.accountNo || "").trim(),
    strategy: String(pick("strategy") || row.strategy || "").trim(),
    tradeType: String(pick("tradeType") || row.tradeType || "STOCK")
      .trim()
      .toUpperCase(),
    orderType: String(pick("orderType") || row.orderType || "MARKET")
      .trim()
      .toUpperCase(),
    notes: String(pick("notes") || row.notes || "").trim(),
    sourceType,
  };
}

export function pickTradeValue(row, field) {
  const aliases = tradeHeaderAliases[field] || [field];
  for (const alias of aliases) {
    if (row[alias] !== undefined && row[alias] !== null && String(row[alias]).trim() !== "") return row[alias];
  }
  const normalized = Object.entries(row).reduce((acc, [key, value]) => {
    acc[String(key).trim().toLowerCase()] = value;
    return acc;
  }, {});
  for (const alias of aliases) {
    const value = normalized[String(alias).trim().toLowerCase()];
    if (value !== undefined && value !== null && String(value).trim() !== "") return value;
  }
  return "";
}

export function normalizeSide(value) {
  const text = String(value || "BUY")
    .trim()
    .toUpperCase();
  if (["SELL", "S", "卖", "卖出", "出售"].includes(text)) return "SELL";
  return "BUY";
}

export function normalizeTradeDate(value) {
  if (!value) return "";
  if (value instanceof Date && !Number.isNaN(value.getTime())) return value.toISOString().slice(0, 10);
  const text = String(value).trim();
  if (!text) return "";
  const normalized = text.replace(/[./]/g, "-");
  const date = new Date(normalized);
  if (!Number.isNaN(date.getTime())) return date.toISOString().slice(0, 10);
  return normalized.slice(0, 10);
}

export function positiveNumber(value) {
  if (value === undefined || value === null || value === "") return 0;
  const parsed = Number(
    String(value)
      .replace(/,/g, "")
      .replace(/[^\d.-]/g, ""),
  );
  return Number.isFinite(parsed) ? Math.abs(parsed) : 0;
}

export function totalFees(trade) {
  return Number(trade.fee || 0) + Number(trade.tax || 0) + Number(trade.commission || 0) + Number(trade.otherFee || 0);
}
