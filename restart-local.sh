#!/usr/bin/env bash
# restart-local.sh — Start all Aegis Alpha services for local development
# Usage: bash restart-local.sh

set -e
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

# Load .env into current shell
set -a
source .env
set +a

echo "=== Aegis Alpha Local Dev Restart ==="
echo ""

# ── 1. Kill existing processes ──
echo "[1/3] Stopping existing processes..."
for pidfile in /tmp/aegis-orchestrator.pid /tmp/aegis-backend.pid /tmp/aegis-frontend.pid; do
  if [ -f "$pidfile" ]; then
    kill "$(cat "$pidfile")" 2>/dev/null && echo "  Stopped $(cat "$pidfile")" || true
    rm -f "$pidfile"
  fi
done
for port in 8787 5178 5174; do
  # Cross-platform: try lsof first (macOS/Linux), fall back to netstat (Windows/Git Bash)
  pid=$(lsof -ti :$port 2>/dev/null || netstat -ano 2>/dev/null | grep ":$port " | grep LISTENING | awk '{print $5}' | head -1 || true)
  if [ -n "$pid" ] && [ "$pid" != "0" ]; then
    kill $pid 2>/dev/null || taskkill //PID $pid //F 2>/dev/null || true
    echo "  Killed process on port $port (PID $pid)"
  fi
done
sleep 1

# ── 2. Start orchestrator ──
echo "[2/3] Starting orchestrator on port 8787..."
cd "$ROOT/aegis-alpha-orchestrator"
nohup python -m uvicorn app.main:app --host 0.0.0.0 --port 8787 > /tmp/aegis-orchestrator.log 2>&1 &
echo $! > /tmp/aegis-orchestrator.pid
echo "  PID: $(cat /tmp/aegis-orchestrator.pid)"

for i in $(seq 1 15); do
  if curl -s http://127.0.0.1:8787/health > /dev/null 2>&1; then
    echo "  Orchestrator ready!"
    break
  fi
  if [ "$i" = "15" ]; then
    echo "  WARNING: Orchestrator not responding after 15s, check /tmp/aegis-orchestrator.log"
  fi
  sleep 1
done

# ── 3. Start Spring Boot ──
echo "[3/3] Starting Spring Boot on port 5178 (profile: local)..."
cd "$ROOT/aegis-alpha-api"
nohup mvn spring-boot:run -Dspring-boot.run.profiles=local > /tmp/aegis-backend.log 2>&1 &
echo $! > /tmp/aegis-backend.pid
echo "  PID: $(cat /tmp/aegis-backend.pid)"

echo ""
echo "=== All services starting ==="
echo "  Orchestrator : http://127.0.0.1:8787  (log: /tmp/aegis-orchestrator.log)"
echo "  Backend      : http://127.0.0.1:5178  (log: /tmp/aegis-backend.log)"
echo ""
echo "  Frontend     : cd aegis-alpha-web && npm run dev  ->  http://localhost:5174"
echo ""
echo "  Stop all: kill \$(cat /tmp/aegis-orchestrator.pid) \$(cat /tmp/aegis-backend.pid)"
