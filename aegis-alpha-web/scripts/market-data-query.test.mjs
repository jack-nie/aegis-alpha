import { readFileSync, readdirSync, statSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import assert from "node:assert/strict";

const root = dirname(dirname(fileURLToPath(import.meta.url)));

function collectSourceFiles(dir, acc = []) {
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry);
    const stat = statSync(full);
    if (stat.isDirectory()) {
      collectSourceFiles(full, acc);
      continue;
    }
    if (/\.(jsx?|mjs)$/.test(entry)) acc.push(full);
  }
  return acc;
}

const source = collectSourceFiles(join(root, "app"))
  .map((file) => readFileSync(file, "utf8"))
  .join("\n");

assert.match(
  source,
  /data-testid=["']market-data-query-panel["']/,
  "Dashboard should expose a stable market-data query panel marker.",
);
assert.match(
  source,
  /\/market-data\/overview\?symbol=/,
  "Dashboard should query the backend market-data overview endpoint.",
);
assert.match(
  source,
  /data-testid=["']market-data-quote-card["']/,
  "Dashboard should render a quote card with provider metadata.",
);
assert.match(
  source,
  /data-testid=["']market-data-financials-list["']/,
  "Dashboard should render normalized financial metrics.",
);
assert.match(source, /data-testid=["']market-data-news-list["']/, "Dashboard should render normalized recent news.");
