export function formatNumber(value) {
  const number = Number(value || 0);
  return Number.isFinite(number) ? number.toLocaleString("zh-CN", { maximumFractionDigits: 4 }) : "-";
}

export function formatMoney(value, currency = "USD") {
  const number = Number(value || 0);
  return `${currency} ${Number.isFinite(number) ? number.toLocaleString("zh-CN", { maximumFractionDigits: 2 }) : "0"}`;
}

export function formatPercent(value) {
  if (value === undefined || value === null || value === "") return "-";
  const number = Number(value);
  if (!Number.isFinite(number)) return "-";
  return `${number >= 0 ? "+" : ""}${number.toFixed(2)}%`;
}

export function csvCell(value) {
  const text = String(value ?? "");
  return /[",\n\r]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text;
}

export function normalizeListResponse(response) {
  if (Array.isArray(response)) return response;
  if (Array.isArray(response?.items)) return response.items;
  if (Array.isArray(response?.records)) return response.records;
  if (Array.isArray(response?.data)) return response.data;
  if (Array.isArray(response?.results)) return response.results;
  return [];
}

export function pickFirst(source, keys) {
  for (const key of keys) {
    const value = source?.[key];
    if (value !== undefined && value !== null && value !== "") return value;
  }
  return "";
}

export function displayValue(value) {
  if (value === undefined || value === null || value === "") return "";
  if (typeof value === "string" || typeof value === "number" || typeof value === "boolean") return String(value);
  if (Array.isArray(value)) return `${value.length} 条`;
  return JSON.stringify(value);
}

export function formatDateTime(value) {
  if (!value) return "-";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return String(value);
  return date.toLocaleString("zh-CN", { hour12: false });
}

export function formatMarketTimestamp(value) {
  if (!value) return "等待查询";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return sanitizeText(value);
  return date.toLocaleString("zh-CN", { hour12: false, timeZoneName: "short" });
}

export function sanitizeText(value) {
  if (value === undefined || value === null) return "";
  return String(value)
    .replace(/â€™|â|&#39;|&apos;/g, "'")
    .replace(/â€œ|â|&ldquo;/g, '"')
    .replace(/â€|â|&rdquo;/g, '"')
    .replace(/â€“|â/g, "-")
    .replace(/â€”|â/g, "-")
    .replace(/&amp;/g, "&")
    .replace(/&quot;/g, '"')
    .trim();
}

export function formatConfidence(value) {
  if (value === undefined || value === null || value === "") return "-";
  const number = Number(value);
  if (!Number.isFinite(number)) return String(value);
  return number <= 1 ? `${Math.round(number * 100)}%` : `${number.toFixed(1)}%`;
}

export function formatDuration(value) {
  const number = Number(value);
  if (!Number.isFinite(number)) return "-";
  return number >= 1000 ? `${(number / 1000).toFixed(2)}s` : `${Math.round(number)}ms`;
}

export function summarizeJson(value) {
  if (typeof value === "string") {
    try {
      return JSON.parse(value);
    } catch {
      return value.length > 1200 ? `${value.slice(0, 1200)}...` : value;
    }
  }
  if (Array.isArray(value)) return value.slice(0, 6);
  if (!value || typeof value !== "object") return value;
  return Object.fromEntries(Object.entries(value).slice(0, 20));
}
