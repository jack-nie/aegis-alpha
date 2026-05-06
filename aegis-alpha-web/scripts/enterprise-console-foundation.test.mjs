import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import assert from "node:assert/strict";

const root = dirname(dirname(fileURLToPath(import.meta.url)));
const source = readFileSync(join(root, "app", "App.jsx"), "utf8");

for (const label of ["运行中心", "治理", "审计日志"]) {
  assert.ok(source.includes(label), `Enterprise console navigation should include ${label}.`);
}

assert.match(
  source,
  /path\s*===\s*["']\/workflow\/runs["']/,
  "App routing should render the workflow run center page."
);

assert.match(
  source,
  /path\s*===\s*["']\/governance\/audit["']/,
  "App routing should render the governance audit log page."
);

assert.match(
  source,
  /data-testid=["']workflow-run-center-table["']/,
  "Workflow run center should expose a stable table marker."
);

assert.match(
  source,
  /\/admin\/audit-events/,
  "Audit log page should call the admin audit-events API."
);

assert.match(
  source,
  /`\/workflow\/runs\/\$\{runId\}\/\$\{action\}`/,
  "Workflow run center should call the generic run control endpoint."
);

const hasDynamicRunActionMarker = /data-testid=\{`workflow-run-\$\{action\}`\}/.test(source);
for (const action of ["dispatch", "pause", "resume", "cancel"]) {
  assert.ok(source.includes(`data-testid="workflow-run-${action}"`) || hasDynamicRunActionMarker, `Workflow run center should render ${action} control.`);
}

for (const field of ["workflowVersionId", "idempotencyKey", "controlStatus"]) {
  assert.ok(source.includes(field), `Workflow run center should surface ${field}.`);
}

for (const label of ["模型治理", "推荐历史"]) {
  assert.ok(source.includes(label), `Phase 3 governance navigation should include ${label}.`);
}

for (const route of ["/governance/models", "/recommendations"]) {
  assert.ok(source.includes(route), `App should expose ${route}.`);
}

for (const marker of ["model-governance-table", "recommendation-history-table", "recommendation-approve", "recommendation-reject"]) {
  assert.ok(source.includes(`data-testid="${marker}"`), `Phase 3 page should expose ${marker}.`);
}

for (const endpoint of ["/governance/models", "/recommendations", "/approve", "/reject"]) {
  assert.ok(source.includes(endpoint), `Phase 3 frontend should call ${endpoint}.`);
}
