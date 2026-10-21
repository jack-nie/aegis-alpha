# Help Endpoint Load Test (P1)

Runnable, stdlib-only latency evidence for the orchestrator help endpoint
(`POST /help/execute`).

- **Script:** `scripts/loadtest_help.py`
- **Endpoint under test:** `POST /help/execute` (`aegis-alpha-orchestrator/app/routers/help.py`)
- **Dependencies:** Python 3.8+ standard library only — `argparse`, `json`, `time`,
  `statistics`, `concurrent.futures`, `urllib.request`. No `pip install`, no Locust/k6.

## What this measures

The help loop implemented in `aegis-alpha-orchestrator/app/core/help_loop.py` runs
retrieve → grade → rewrite/re-retrieve (≤3 rounds) → draft or escalate against an
in-process knowledge corpus (`DEFAULT_CORPUS`). Each call therefore exercises:

- ACL pre-filtering before ranking (`visibility=public` + role intersection),
- tokenisation (ASCII words + CJK bigrams) and chunk scoring,
- the bounded retrieval loop and answer assembly.

It does **not** measure LLM inference: `/help/execute` is deterministic and does not
call an LLM by default. The numbers below are retrieval/ACL/loop/HTTP overhead, not
model cost or token spend. See "Caveats" for the full scope statement.

## 1. Start the orchestrator locally (mock mode + service token)

From the repository root, in PowerShell:

```powershell
$env:AEGIS_ALPHA_LANGCHAIN_MOCK = "true"
$env:AEGIS_ALPHA_NODE_EXECUTION_TOKEN = "loadtest-token"
cd aegis-alpha-orchestrator
.venv\Scripts\python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8799
```

Background variant (leave the shell free to run the load test):

```powershell
$env:AEGIS_ALPHA_LANGCHAIN_MOCK = "true"
$env:AEGIS_ALPHA_NODE_EXECUTION_TOKEN = "loadtest-token"
Start-Process -FilePath "aegis-alpha-orchestrator\.venv\Scripts\python.exe" `
  -ArgumentList "-m","uvicorn","app.main:app","--host","127.0.0.1","--port","8799" `
  -WorkingDirectory "aegis-alpha-orchestrator" -PassThru
```

Bash equivalent:

```bash
AEGIS_ALPHA_LANGCHAIN_MOCK=true \
AEGIS_ALPHA_NODE_EXECUTION_TOKEN=loadtest-token \
  aegis-alpha-orchestrator/.venv/Scripts/python.exe -m uvicorn app.main:app \
  --host 127.0.0.1 --port 8799
```

### Why the service token is required

`app/main.py` installs a `service_auth_middleware` that fail-closes every non-public
path. With `AEGIS_ALPHA_NODE_EXECUTION_TOKEN` unset/empty it answers `503`
(`service_token_not_configured`); with a mismatched `Authorization: Bearer …` value it
answers `401` (`unauthorized`). Only `/health`, `/docs`, `/openapi.json` and `/redoc`
bypass the check. So the orchestrator and the load test must agree on the same token —
`loadtest-token` in the commands above.

### Wait for readiness

```powershell
Invoke-RestMethod http://127.0.0.1:8799/health
```

Expected body (subset): `ok=True`, `engine=langgraph`.

`mock` will usually still read `false` even with `AEGIS_ALPHA_LANGCHAIN_MOCK=true`
exported: `app/config.py` loads the repository-root `.env` with `override=True`, and that
file sets `AEGIS_ALPHA_LANGCHAIN_MOCK=false`. This does not affect the load test —
`/help/execute` never touches the LLM — but do not treat `mock=false` as a failed setup.

If port 8799 is already taken, start on **8798** instead and point `--url` at 8798;
nothing in the script or the orchestrator is port-specific.

### Stop it afterwards

```powershell
Get-NetTCPConnection -LocalPort 8799 -State Listen |
  Select-Object -ExpandProperty OwningProcess -Unique |
  ForEach-Object { Stop-Process -Id $_ -Force }
```

or, if you kept the PID from `-PassThru`, `Stop-Process -Id $serverPid -Force` (do not
use `$pid` — that is PowerShell's own automatic variable for the current shell).

## 2. Run the load test

From the repository root:

```powershell
python scripts/loadtest_help.py `
  --url http://127.0.0.1:8799/help/execute `
  --token loadtest-token `
  --requests 200 `
  --concurrency 20
```

### Arguments

| Flag | Default | Meaning |
| --- | --- | --- |
| `--url` | `http://127.0.0.1:8799/help/execute` | Target help endpoint |
| `--token` | `loadtest-token` | Sent as `Authorization: Bearer <token>` |
| `--requests` | `200` | Total requests to issue |
| `--concurrency` | `20` | Worker threads (ThreadPoolExecutor) |
| `--message` | `如何运行 published 工作流` | `message` field of the JSON body |
| `--timeout` | `10` | Per-request timeout in seconds |
| `--json-out` | _(off)_ | Optional path to write the summary as JSON |

Every request sends `POST` with body `{"message": "<message>", "roles": []}` — empty
roles mean only `visibility=public` chunks are eligible, so the ACL pre-filter is on
the measured path.

### Measured reference run

Command exactly as above (200 requests, concurrency 20, default message) against
uvicorn on `127.0.0.1:8799`: Windows dev machine, Python 3.11.2, single uvicorn worker,
in-process `DEFAULT_CORPUS`, token `loadtest-token`.

```text
Help endpoint load test
  url             : http://127.0.0.1:8799/help/execute
  requests        : 200   concurrency: 20   timeout: 10.0s
  wall time       : 0.500 s
  throughput      : 400.32 req/s
  ok / error      : 200 / 0   (error rate 0.00%)
  latency ms      : mean 47.74   p50 47.05   p95 61.13   p99 71.88   max 77.99
  status codes    : 200=200
  escalated       : 0 (informational, not an error)
  error samples   : none
```

Reading of that run: the help path costs ~48 ms mean / ~47 ms median, with a tail at
~61 ms (p95) and ~72 ms (p99) — i.e. the loop returns a drafted, ACL-filtered answer in
well under 85 ms with zero transport or authorisation failures. Responses were the
deterministic `provider=help-loop`, `domain=PRODUCT_HELP`, `routeAction=HELP_GRAPH`
payload with `escalate=false`, `retrievalRounds=1`, `chunkCount=1` for the default
message.

Exit code is `0` when every request returned 2xx, `1` when at least one request failed
(non-2xx, timeout, connection error). Failures are counted and reported — never raised —
so a bad token produces a full error-rate report instead of a traceback.

## 3. Reading the numbers

- **p50 (median)** — half of all requests were faster than this. Typical end-user
  experience for the help endpoint.
- **p95** — 95% of requests were faster; the slow 5% are slower. This is the number to
  watch for the "long tail" a user will occasionally feel.
- **p99** — 99% were faster; the worst 1%. Sensitive to single outliers (GC pause,
  thread scheduling, log flush) and, with only 200 requests, the boundary sits among the
  slowest three calls. Treat it as an indicative tail signal, not a precise quantile.
- **error rate** = failed requests ÷ total requests, where "failed" means non-2xx HTTP,
  a timeout, or a transport exception. `0.00%` means every call completed with a valid
  2xx response. It says nothing about whether the *answer* was good — only that the
  endpoint served it.
- **throughput (req/s)** = requests ÷ wall-clock time of the whole run. Useful as a
  capacity sanity check on one process, but it is bounded by the client's thread pool
  and the uvicorn worker count, not just by server-side work.
- **escalated** counts 2xx responses whose JSON body had `escalate: true` (the loop fell
  back to the human-handoff disclaimer). It is informational only and never counted as
  an error, because it is a valid business outcome of a deterministic loop.

## 4. Caveats

- **No LLM is called.** `/help/execute` answers deterministically from the in-process
  corpus in `help_loop.py`, so these latencies say nothing about LLM latency, token cost,
  or model quality — they measure retrieval/ACL/loop/HTTP overhead only. The
  `AEGIS_ALPHA_LANGCHAIN_MOCK=true` export is belt-and-braces to keep the surrounding app
  in mock mode; it is not required for `/help/execute` to work (and in this repo it is
  overridden by `.env`, see below). If the endpoint is later wired to real retrieval/LLM,
  this script stays valid but the numbers stop being comparable to the baseline above.
- **Loopback, single process.** Client and server share one machine and one CPU pool;
  uvicorn runs as a single worker. Results are a local regression signal, not a
  production capacity forecast. Add `--workers` to uvicorn before quoting any
  throughput number as capacity.
- **In-memory corpus, no I/O.** No database, vector store, or network hop is on the
  measured path, so the numbers isolate retrieval/ACL/loop/HTTP overhead.
- **Small sample sizes.** With 200 requests, p99 is effectively the two slowest calls and
  will move run-to-run. Use `--requests 2000` when you care about the tail.
- **Cold start skew.** The first request in a fresh process pays import/route/JSON-schema
  warm-up cost. If that matters, discard the first run or repeat the run and compare.
- **`/health.port` is cosmetic.** The payload reports `settings.port`
  (`AEGIS_ALPHA_LANGGRAPH_PORT`, 8787 in the repo `.env`), not the `--port` uvicorn
  actually bound. Always trust the URL you launched, not that field.
- **Repo `.env` wins over your shell.** `app/config.py` calls `load_dotenv(..., override=True)`
  on the repository-root `.env`, so a key present there overrides the value you exported.
  `AEGIS_ALPHA_NODE_EXECUTION_TOKEN` is *not* in `.env` today, so exporting it works; if
  someone adds it, the shell value will be silently ignored and you will see `401`s.
  `AEGIS_ALPHA_LANGCHAIN_MOCK` *is* in `.env` (currently `false`) — harmless here because
  the help path never touches the LLM.

## 5. Troubleshooting

| Symptom | Cause |
| --- | --- |
| `401` on every request, `status codes: 401=200` | Token mismatch between `--token` and `AEGIS_ALPHA_NODE_EXECUTION_TOKEN` |
| `503` on every request | Server started with an empty token (`service_token_not_configured`) |
| `URLError: [WinError 10061]` / connection refused | Orchestrator not started, or wrong host/port |
| `Timeout after 10.0s` | Server blocked or `--concurrency` far above what one worker can serve |
| Port already in use on startup | Pick 8798 and pass the matching `--url` |
