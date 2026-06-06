import http from "node:http";
import { spawn } from "node:child_process";
import { once } from "node:events";

let serverProcess = null;
const externalBaseUrl = process.env.AEGIS_ALPHA_LANGGRAPH_URL;
const port = externalBaseUrl ? Number(new URL(externalBaseUrl).port || 80) : await getFreePort();
const baseUrl = externalBaseUrl || `http://127.0.0.1:${port}`;

if (!externalBaseUrl) {
  serverProcess = spawn(process.execPath, ["server.mjs"], {
    cwd: new URL("..", import.meta.url),
    env: {
      ...process.env,
      AEGIS_ALPHA_LANGGRAPH_PORT: String(port),
      AEGIS_ALPHA_LANGCHAIN_MOCK: "",
    },
    stdio: ["ignore", "pipe", "pipe"],
  });
  serverProcess.stdout.on("data", () => {});
  serverProcess.stderr.on("data", (chunk) => process.stderr.write(chunk));
  await waitForHealth();
}

process.on("exit", () => {
  if (serverProcess && !serverProcess.killed) {
    serverProcess.kill();
  }
});

async function request(path, options = {}) {
  const response = await fetch(`${baseUrl}${path}`, {
    ...options,
    headers: {
      "content-type": "application/json",
      ...(options.headers || {}),
    },
  });
  const body = await response.json().catch(() => ({}));
  if (!response.ok) {
    throw new Error(`${path} returned ${response.status}: ${JSON.stringify(body)}`);
  }
  return body;
}

function assert(condition, message) {
  if (!condition) {
    throw new Error(message);
  }
}

async function getFreePort() {
  const server = http.createServer();
  server.listen(0, "127.0.0.1");
  await once(server, "listening");
  const address = server.address();
  const freePort = address.port;
  await new Promise((resolve) => server.close(resolve));
  return freePort;
}

async function waitForHealth() {
  const deadline = Date.now() + 15000;
  while (Date.now() < deadline) {
    if (serverProcess?.exitCode !== null) {
      throw new Error(`LangGraph server exited during smoke startup with code ${serverProcess.exitCode}`);
    }
    try {
      const health = await request("/health");
      if (health.ok === true) return;
    } catch {
      await new Promise((resolve) => setTimeout(resolve, 250));
    }
  }
  throw new Error(`LangGraph server did not become healthy at ${baseUrl}`);
}

function assertNodeResult(result, expectedHandler, options = {}) {
  const required = [
    "ok",
    "status",
    "provider",
    "model",
    "handler",
    "nodeId",
    "nodeName",
    "subject",
    "summary",
    "signals",
    "sources",
    "confidence",
    "data",
    "startedAt",
    "completedAt",
    "durationMs",
  ];
  for (const key of required) {
    assert(Object.prototype.hasOwnProperty.call(result, key), `missing execute-node key: ${key}`);
  }
  if (Object.prototype.hasOwnProperty.call(options, "ok")) {
    assert(result.ok === options.ok, `expected ok=${options.ok}, got ${result.ok}`);
  } else {
    assert(result.ok === true, "execute-node did not return ok=true");
  }
  assert(result.handler === expectedHandler, `expected handler ${expectedHandler}, got ${result.handler}`);
  assert(Array.isArray(result.signals), "signals must be an array");
  assert(Array.isArray(result.sources), "sources must be an array");
}

function startMalformedModelServer() {
  const server = http.createServer((req, res) => {
    if (req.method !== "POST" || !req.url.endsWith("/chat/completions")) {
      res.writeHead(404, { "content-type": "application/json" });
      res.end(JSON.stringify({ error: "not found" }));
      return;
    }
    req.resume();
    res.writeHead(200, { "content-type": "application/json" });
    res.end(
      JSON.stringify({
        id: "chatcmpl-smoke",
        object: "chat.completion",
        created: Math.floor(Date.now() / 1000),
        model: "deepseek-v4-flash",
        choices: [
          {
            index: 0,
            finish_reason: "stop",
            message: { role: "assistant", content: "this is not json" },
          },
        ],
        usage: {
          prompt_tokens: 11,
          completion_tokens: 7,
          total_tokens: 18,
        },
      }),
    );
  });
  return new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => {
      resolve({
        url: `http://127.0.0.1:${server.address().port}/v1`,
        close: () => new Promise((done) => server.close(done)),
      });
    });
  });
}

const health = await request("/health");
assert(health.ok === true, "health check did not return ok=true");

const nodeResult = await request("/execute-node", {
  method: "POST",
  body: JSON.stringify({
    provider: "openai",
    apiKey: "",
    subject: "NVDA",
    node: {
      id: "analysis",
      data: {
        label: "Market Analysis",
        handler: "finance.market_analysis",
      },
    },
    state: { subject: "NVDA" },
  }),
});
assertNodeResult(nodeResult, "finance.market_analysis");
assert(nodeResult.status === "mock", `expected missing key mock status, got ${nodeResult.status}`);
assert(nodeResult.degraded === true, "missing key mock result must be marked degraded");
assert(nodeResult.data.reason === "missing_api_key", "missing key mock reason not preserved");

const copilotResult = await request("/execute-node", {
  method: "POST",
  body: JSON.stringify({
    provider: "openai",
    apiKey: "",
    subject: "copilot chat",
    node: {
      id: "aegis-copilot",
      data: {
        label: "Aegis Alpha Copilot",
        handler: "general.agent",
      },
    },
    state: { message: "测试对话窗口输出" },
  }),
});
assertNodeResult(copilotResult, "general.agent");
assert(
  copilotResult.summary.includes("模拟模式"),
  `copilot mock summary should be user-facing Chinese, got ${copilotResult.summary}`,
);

const copilotFallback = await request("/execute-node", {
  method: "POST",
  body: JSON.stringify({
    provider: "openai",
    apiKey: "test-key",
    baseUrl: "http://127.0.0.1:1/v1",
    model: "deepseek-v4-flash",
    subject: "copilot chat",
    node: {
      id: "aegis-copilot",
      data: {
        label: "Aegis Alpha Copilot",
        handler: "general.agent",
      },
    },
    state: { message: "测试真实模型异常时的输出" },
  }),
});
assertNodeResult(copilotFallback, "general.agent");
assert(copilotFallback.provider === "fallback", `expected fallback provider, got ${copilotFallback.provider}`);
assert(
  copilotFallback.summary.includes("暂时无法连接真实模型"),
  `copilot fallback should be user-facing Chinese, got ${copilotFallback.summary}`,
);

const unsupported = await request("/execute-node", {
  method: "POST",
  body: JSON.stringify({
    provider: "unsupported-ai",
    apiKey: "test-key",
    subject: "NVDA",
    node: {
      id: "unsupported",
      data: {
        label: "Unsupported Provider",
        handler: "finance.market_analysis",
      },
    },
    state: {},
  }),
});
assertNodeResult(unsupported, "finance.market_analysis", { ok: false });
assert(unsupported.status === "unsupported_provider", `expected unsupported_provider, got ${unsupported.status}`);
assert(unsupported.degraded === true, "unsupported provider result must be marked degraded");

const malformedModel = await startMalformedModelServer();
try {
  const malformed = await request("/execute-node", {
    method: "POST",
    body: JSON.stringify({
      provider: "openai",
      apiKey: "test-key",
      baseUrl: malformedModel.url,
      model: "deepseek-v4-flash",
      subject: "NVDA",
      node: {
        id: "malformed",
        data: {
          label: "Malformed Model",
          handler: "finance.market_analysis",
        },
      },
      state: {},
    }),
  });
  assertNodeResult(malformed, "finance.market_analysis");
  assert(malformed.status === "completed", `expected completed malformed normalization, got ${malformed.status}`);
  assert(malformed.data.rawContent === "this is not json", "malformed output rawContent not preserved");
  assert(malformed.data.usage.prompt_tokens === 11, "prompt token usage not extracted");
  assert(malformed.data.usage.completion_tokens === 7, "completion token usage not extracted");
  assert(malformed.data.usage.total_tokens === 18, "total token usage not extracted");
} finally {
  await malformedModel.close();
}

const workflow = await request("/execute-workflow", {
  method: "POST",
  body: JSON.stringify({
    subject: "NVDA",
    apiKey: "",
    nodes: [
      { id: "start", data: { label: "Start", handler: "start" } },
      { id: "analysis", data: { label: "Market Analysis", handler: "finance.market_analysis" } },
    ],
    edges: [{ source: "start", target: "analysis" }],
    state: {},
  }),
});
assert(workflow.ok === true, "workflow did not return ok=true");
assert(workflow.finalState && typeof workflow.finalState === "object", "workflow missing finalState");
assert(Array.isArray(workflow.trace), "workflow trace must be an array");
assert(workflow.trace.length === 2, `expected 2 trace entries, got ${workflow.trace.length}`);
assert(workflow.nodeOutputs && workflow.nodeOutputs.analysis, "workflow missing nodeOutputs.analysis");
assertNodeResult(workflow.nodeOutputs.analysis, "finance.market_analysis");
assert(workflow.trace[1].degraded === true, "workflow trace must preserve degraded node status");

console.log("smoke ok");
if (serverProcess) {
  serverProcess.kill();
}
