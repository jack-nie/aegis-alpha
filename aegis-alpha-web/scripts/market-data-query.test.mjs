import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import assert from "node:assert/strict";

const root = dirname(dirname(fileURLToPath(import.meta.url)));
const source = readFileSync(join(root, "app", "App.jsx"), "utf8");

assert.match(
  source,
  /data-testid=["']market-data-query-panel["']/,
  "Dashboard should expose a stable market-data query panel marker."
);
assert.match(
  source,
  /\/market-data\/overview\?symbol=/,
  "Dashboard should query the backend market-data overview endpoint."
);
assert.match(
  source,
  /data-testid=["']market-data-quote-card["']/,
  "Dashboard should render a quote card with provider metadata."
);
assert.match(
  source,
  /data-testid=["']market-data-financials-list["']/,
  "Dashboard should render normalized financial metrics."
);
assert.match(
  source,
  /data-testid=["']market-data-news-list["']/,
  "Dashboard should render normalized recent news."
);
