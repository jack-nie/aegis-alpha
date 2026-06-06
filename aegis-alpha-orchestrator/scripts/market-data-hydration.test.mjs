import http from "node:http";
import { spawn } from "node:child_process";
import { once } from "node:events";

const backend = await startBackendStub();
const langgraphPort = await getFreePort();
const baseUrl = `http://127.0.0.1:${langgraphPort}`;
let serverProcess = null;

try {
  serverProcess = spawn(process.execPath, ["server.mjs"], {
    cwd: new URL("..", import.meta.url),
    env: {
      ...process.env,
      MARKETMIND_LANGGRAPH_PORT: String(langgraphPort),
      MARKETMIND_BACKEND_URL: backend.url,
      MARKETMIND_NODE_EXECUTION_TOKEN: "test-node-token",
      MARKETMIND_LANGCHAIN_MOCK: "false",
    },
    stdio: ["ignore", "pipe", "pipe"],
  });
  serverProcess.stderr.on("data", (chunk) => process.stderr.write(chunk));
  await waitForHealth(baseUrl, serverProcess);

  const result = await request(`${baseUrl}/execute-node`, {
    method: "POST",
    body: JSON.stringify({
      subject: "AAPL",
      provider: "openai",
      apiKey: "",
      node: {
        id: "market_analysis",
        data: {
          label: "Market Analysis",
          nodeType: "logic",
          handler: "finance.market_analysis",
        },
      },
      state: { symbol: "AAPL", subject: "AAPL" },
    }),
  });

  assert(result.ok === true, "node should remain executable in mock mode");
  assert(result.data.marketDataContext, "missing hydrated marketDataContext");
  assert(result.data.marketDataContext.provider === "yahoo-chart", "wrong hydrated provider");
  assert(result.data.marketDataContext.rows[0].symbol === "AAPL", "wrong hydrated symbol");
  assert(backend.calls === 1, `expected one backend hydration call, got ${backend.calls}`);

  const fallback = await request(`${baseUrl}/execute-node`, {
    method: "POST",
    body: JSON.stringify({
      subject: "AAPL",
      provider: "openai",
      apiKey: "test-key",
      baseUrl: "http://127.0.0.1:1/v1",
      node: {
        id: "copilot",
        data: {
          label: "Aegis Alpha Copilot",
          nodeType: "agent",
          handler: "general.agent",
        },
      },
      state: { ticker: "AAPL", message: "Analyze AAPL with live market data" },
    }),
  });

  assert(fallback.provider === "fallback", `expected fallback provider, got ${fallback.provider}`);
  assert(fallback.data.marketDataContext, "fallback lost hydrated marketDataContext");
  assert(fallback.data.marketDataContext.rows[0].symbol === "AAPL", "fallback hydrated wrong symbol");

  const cachedFallback = await request(`${baseUrl}/execute-node`, {
    method: "POST",
    body: JSON.stringify({
      subject: "AAPL",
      provider: "openai",
      apiKey: "test-key",
      baseUrl: "http://127.0.0.1:1/v1",
      node: {
        id: "copilot",
        data: {
          label: "Aegis Alpha Copilot",
          nodeType: "agent",
          handler: "general.agent",
        },
      },
      state: {
        ticker: "AAPL",
        message: "Analyze AAPL with live market data",
        marketDataOverview: {
          ok: true,
          symbol: "AAPL",
          quote: { provider: "chat-cache", symbol: "AAPL", price: 205.35 },
        },
      },
    }),
  });

  assert(
    cachedFallback.data.marketDataContext.quote.provider === "chat-cache",
    "did not reuse chat marketDataOverview",
  );
  assert(backend.calls === 2, `expected cached chat hydration to avoid a backend call, got ${backend.calls}`);
  console.log("market data hydration ok");
} finally {
  if (serverProcess && !serverProcess.killed) {
    serverProcess.kill();
  }
  await backend.close();
}

async function request(url, options = {}) {
  const response = await fetch(url, {
    ...options,
    headers: {
      "content-type": "application/json",
      ...(options.headers || {}),
    },
  });
  const body = await response.json().catch(() => ({}));
  if (!response.ok) {
    throw new Error(`${url} returned ${response.status}: ${JSON.stringify(body)}`);
  }
  return body;
}

async function startBackendStub() {
  let calls = 0;
  const server = http.createServer((req, res) => {
    if (req.method !== "POST" || req.url !== "/_backend/internal/workflow-nodes/execute") {
      res.writeHead(404, { "content-type": "application/json" });
      res.end(JSON.stringify({ error: "not found" }));
      return;
    }
    assert(req.headers["x-marketmind-workflow-token"] === "test-node-token", "missing workflow token header");
    let raw = "";
    req.on("data", (chunk) => {
      raw += chunk;
    });
    req.on("end", () => {
      const payload = JSON.parse(raw || "{}");
      calls += 1;
      res.writeHead(200, { "content-type": "application/json" });
      res.end(
        JSON.stringify({
          functionName: payload.functionName,
          status: "ok",
          provider: "yahoo-chart",
          rows: [{ symbol: "AAPL", price: 205.35, provider: "yahoo-chart" }],
          sources: [{ title: "Yahoo Finance chart", type: "quote" }],
        }),
      );
    });
  });
  await new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", resolve);
  });
  return {
    get calls() {
      return calls;
    },
    url: `http://127.0.0.1:${server.address().port}`,
    close: () => new Promise((resolve) => server.close(resolve)),
  };
}

async function getFreePort() {
  const server = http.createServer();
  server.listen(0, "127.0.0.1");
  await once(server, "listening");
  const port = server.address().port;
  await new Promise((resolve) => server.close(resolve));
  return port;
}

async function waitForHealth(baseUrl, processRef) {
  const deadline = Date.now() + 15000;
  while (Date.now() < deadline) {
    if (processRef?.exitCode !== null) {
      throw new Error(`LangGraph server exited with code ${processRef.exitCode}`);
    }
    try {
      const health = await request(`${baseUrl}/health`);
      if (health.ok === true) return;
    } catch {
      await new Promise((resolve) => setTimeout(resolve, 250));
    }
  }
  throw new Error("LangGraph server did not become healthy");
}

function assert(condition, message) {
  if (!condition) {
    throw new Error(message);
  }
}
