import http from "http";
http
  .createServer((q, r) => {
    r.writeHead(200, { "Content-Type": "application/json" });
    r.end(JSON.stringify(q.url === "/health" ? { ok: true, engine: "langgraph", mock: true } : { ok: true }));
  })
  .listen(8787, () => console.log("Mock LangGraph 8787"));
