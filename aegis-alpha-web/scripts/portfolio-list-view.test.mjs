import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import assert from "node:assert/strict";

const root = dirname(dirname(fileURLToPath(import.meta.url)));
const source = readFileSync(join(root, "app", "App.jsx"), "utf8");

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
