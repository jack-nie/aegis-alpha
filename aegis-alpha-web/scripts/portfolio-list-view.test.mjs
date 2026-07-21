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
  /if\s*\(\s*path\s*===\s*["']\/portfolio\/portfolios["']\s*\)/,
  "Portfolio component should render a dedicated /portfolio/portfolios list view.",
);
assert.match(
  source,
  /data-testid=["']portfolio-list-table["']/,
  "Portfolio list view should expose a stable table marker for verification.",
);
for (const header of ["组合名称", "净资产", "收益率", "资产", "交易", "期权组合", "更新日期"]) {
  assert.ok(source.includes(header), `Portfolio list should include ${header} column.`);
}
