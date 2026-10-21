#!/usr/bin/env python3
"""Load test for the orchestrator help endpoint (``POST /help/execute``).

Python 3 standard library only: no pip install, no external load-test runner.

Example:
    python scripts/loadtest_help.py \
        --url http://127.0.0.1:8799/help/execute \
        --token loadtest-token \
        --requests 200 \
        --concurrency 20

Exit code is 0 when every request succeeded (HTTP 2xx) and 1 when at least one
request failed; failures are reported, never raised.

See docs/superpowers/specs/help-agent-contracts/loadtest.md for how to start the
orchestrator against which this runs.
"""

from __future__ import annotations

import argparse
import json
import statistics
import sys
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from typing import Any

DEFAULT_MESSAGE = "如何运行 published 工作流"
MAX_ERROR_SAMPLES = 5


def _percentiles(latencies_ms: list[float]) -> dict[str, float]:
    """Return mean/p50/p95/p99/max for a list of latencies (milliseconds)."""
    if not latencies_ms:
        return {
            "mean": 0.0,
            "p50": 0.0,
            "p95": 0.0,
            "p99": 0.0,
            "max": 0.0,
            "min": 0.0,
        }

    ordered = sorted(latencies_ms)
    p50 = statistics.median(ordered)
    if len(ordered) >= 2:
        # statistics.quantiles(n=100) returns the 99 interior cut points; with
        # the "inclusive" method these map directly onto percentiles.
        cuts = statistics.quantiles(ordered, n=100, method="inclusive")
        p95 = cuts[94]
        p99 = cuts[98]
    else:
        p95 = p99 = ordered[0]
    return {
        "mean": statistics.mean(ordered),
        "p50": p50,
        "p95": p95,
        "p99": p99,
        "max": ordered[-1],
        "min": ordered[0],
    }


def _post_once(
    url: str, token: str, message: str, timeout: float
) -> dict[str, Any]:
    """Issue one help request. Never raises: all failures come back as errors."""
    body = json.dumps({"message": message, "roles": []}).encode("utf-8")
    request = urllib.request.Request(url, data=body, method="POST")
    request.add_header("Content-Type", "application/json")
    request.add_header("Accept", "application/json")
    request.add_header("Authorization", f"Bearer {token}")

    started = time.perf_counter()
    status: int | None = None
    raw: bytes = b""
    error: str | None = None

    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            status = int(getattr(response, "status", None) or response.getcode())
            raw = response.read()
    except urllib.error.HTTPError as exc:  # non-2xx
        status = int(exc.code)
        try:
            raw = exc.read()
        except Exception:  # pragma: no cover - defensive
            raw = b""
        error = f"HTTP {status}"
    except urllib.error.URLError as exc:
        error = f"URLError: {exc.reason}"
    except TimeoutError:
        error = f"Timeout after {timeout}s"
    except Exception as exc:  # noqa: BLE001 - load test must never crash
        error = f"{type(exc).__name__}: {exc}"

    elapsed_ms = (time.perf_counter() - started) * 1000.0

    escalate: bool | None = None
    if raw:
        try:
            payload = json.loads(raw.decode("utf-8", errors="replace"))
            if isinstance(payload, dict) and "escalate" in payload:
                escalate = bool(payload.get("escalate"))
        except (ValueError, UnicodeDecodeError):
            pass

    ok = error is None and status is not None and 200 <= status < 300
    if not ok and error is None:
        error = f"HTTP {status}"

    return {
        "ok": ok,
        "status": status,
        "latency_ms": elapsed_ms,
        "error": error,
        "escalate": escalate,
    }


def run_loadtest(args: argparse.Namespace) -> dict[str, Any]:
    """Fire ``args.requests`` requests with ``args.concurrency`` workers."""
    started_at = time.strftime("%Y-%m-%dT%H:%M:%S%z")
    wall_start = time.perf_counter()

    results: list[dict[str, Any]] = []
    with ThreadPoolExecutor(max_workers=max(1, args.concurrency)) as pool:
        futures = [
            pool.submit(
                _post_once, args.url, args.token, args.message, args.timeout
            )
            for _ in range(max(1, args.requests))
        ]
        for future in as_completed(futures):
            results.append(future.result())

    wall_seconds = time.perf_counter() - wall_start

    ok_count = sum(1 for r in results if r["ok"])
    error_count = len(results) - ok_count
    latencies = [r["latency_ms"] for r in results]
    stats = _percentiles(latencies)

    status_codes: dict[str, int] = {}
    for r in results:
        key = str(r["status"]) if r["status"] is not None else "no-response"
        status_codes[key] = status_codes.get(key, 0) + 1

    error_samples = [r["error"] for r in results if r["error"]][:MAX_ERROR_SAMPLES]
    escalated = sum(1 for r in results if r["escalate"] is True)

    return {
        "url": args.url,
        "requests": len(results),
        "concurrency": args.concurrency,
        "timeout_s": args.timeout,
        "started_at": started_at,
        "wall_seconds": round(wall_seconds, 4),
        "requests_per_second": round(len(results) / wall_seconds, 2)
        if wall_seconds > 0
        else 0.0,
        "ok": ok_count,
        "error": error_count,
        "error_rate": round(error_count / len(results), 4) if results else 0.0,
        "latency_ms": {k: round(v, 2) for k, v in stats.items()},
        "status_codes": status_codes,
        "escalated": escalated,
        "error_samples": error_samples,
    }


def _print_report(summary: dict[str, Any]) -> None:
    latency = summary["latency_ms"]
    print("Help endpoint load test")
    print(f"  url             : {summary['url']}")
    print(
        f"  requests        : {summary['requests']}"
        f"   concurrency: {summary['concurrency']}"
        f"   timeout: {summary['timeout_s']}s"
    )
    print(f"  wall time       : {summary['wall_seconds']:.3f} s")
    print(f"  throughput      : {summary['requests_per_second']:.2f} req/s")
    print(
        f"  ok / error      : {summary['ok']} / {summary['error']}"
        f"   (error rate {summary['error_rate'] * 100:.2f}%)"
    )
    print(
        "  latency ms      : "
        f"mean {latency['mean']:.2f}   p50 {latency['p50']:.2f}   "
        f"p95 {latency['p95']:.2f}   p99 {latency['p99']:.2f}   "
        f"max {latency['max']:.2f}"
    )
    codes = ", ".join(
        f"{code}={count}" for code, count in sorted(summary["status_codes"].items())
    )
    print(f"  status codes    : {codes or 'none'}")
    print(f"  escalated       : {summary['escalated']} (informational, not an error)")
    if summary["error_samples"]:
        print("  error samples   :")
        for sample in summary["error_samples"]:
            print(f"    - {sample}")
    else:
        print("  error samples   : none")


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Load test the orchestrator help endpoint (POST /help/execute)."
    )
    parser.add_argument(
        "--url",
        default="http://127.0.0.1:8799/help/execute",
        help="Help endpoint URL (default: %(default)s)",
    )
    parser.add_argument(
        "--token",
        default="loadtest-token",
        help="Service token sent as 'Authorization: Bearer <token>' (default: %(default)s)",
    )
    parser.add_argument(
        "--requests",
        type=int,
        default=200,
        help="Total number of requests (default: %(default)s)",
    )
    parser.add_argument(
        "--concurrency",
        type=int,
        default=20,
        help="Number of concurrent worker threads (default: %(default)s)",
    )
    parser.add_argument(
        "--message",
        default=DEFAULT_MESSAGE,
        help="message field sent in the JSON body (default: %(default)s)",
    )
    parser.add_argument(
        "--timeout",
        type=float,
        default=10.0,
        help="Per-request timeout in seconds (default: %(default)s)",
    )
    parser.add_argument(
        "--json-out",
        default=None,
        help="Optional path to write the summary as JSON",
    )
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    # Keep non-ASCII messages printable on legacy Windows consoles.
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):
        pass

    args = parse_args(argv)
    if args.requests < 1:
        print("error: --requests must be >= 1", file=sys.stderr)
        return 2
    if args.concurrency < 1:
        print("error: --concurrency must be >= 1", file=sys.stderr)
        return 2

    summary = run_loadtest(args)
    _print_report(summary)

    if args.json_out:
        with open(args.json_out, "w", encoding="utf-8") as handle:
            json.dump(summary, handle, ensure_ascii=False, indent=2)
            handle.write("\n")
        print(f"  json out        : {args.json_out}")

    return 0 if summary["error"] == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
