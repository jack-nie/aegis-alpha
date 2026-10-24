#!/usr/bin/env bash
# Production deploy: build images (reusing Docker layer cache), start them, and wait
# until every service reports healthy. Exits non-zero with diagnostics otherwise.
#
# Invoked by the "Deploy to production" job in .github/workflows/aegis-ci.yml after
# `git reset --hard origin/main` in /opt/aegis-alpha. Can also be run by hand there.
#
# Overridable env (also readable from .env next to the compose file):
#   AEGIS_ALPHA_COMPOSE_FILE      compose file (default docker-compose.aegis-alpha.yml)
#   AEGIS_ALPHA_HEALTH_TIMEOUT    seconds to wait for healthy containers (default 300)
#   AEGIS_ALPHA_PIP_INDEX_URL / AEGIS_ALPHA_NPM_REGISTRY / AEGIS_ALPHA_MAVEN_MIRROR_URL
#                                 build mirrors (defaults live in the compose file)
set -euo pipefail

cd "$(dirname "$0")/.."

COMPOSE_FILE="${AEGIS_ALPHA_COMPOSE_FILE:-docker-compose.aegis-alpha.yml}"
HEALTH_TIMEOUT="${AEGIS_ALPHA_HEALTH_TIMEOUT:-300}"
export DOCKER_BUILDKIT=1 COMPOSE_DOCKER_CLI_BUILD=1

compose() { docker compose -f "$COMPOSE_FILE" "$@"; }

log() { printf '[deploy %s] %s\n' "$(date '+%H:%M:%S')" "$*"; }

dump_diagnostics() {
  log "Service status:"
  compose ps -a || true
  log "Recent logs:"
  compose logs --no-color --tail=80 || true
}

log "Commit: $(git rev-parse --short HEAD 2>/dev/null || echo unknown)"

log "Building images (layer cache enabled)..."
compose build

log "Starting services..."
# `up -d` itself fails if a depends_on service never becomes healthy; show why.
if ! compose up -d --remove-orphans; then
  log "docker compose up failed."
  dump_diagnostics
  exit 1
fi

log "Waiting up to ${HEALTH_TIMEOUT}s for services to become healthy..."
mapfile -t services < <(compose config --services)
deadline=$(( $(date +%s) + HEALTH_TIMEOUT ))
while :; do
  pending=()
  for svc in "${services[@]}"; do
    cid="$(compose ps -q "$svc" 2>/dev/null | head -n1 || true)"
    if [ -z "$cid" ]; then
      pending+=("$svc(no container)")
      continue
    fi
    status="$(docker inspect -f '{{.State.Status}}|{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$cid")"
    state="${status%%|*}"
    health="${status##*|}"
    if [ "$state" = "exited" ] || [ "$state" = "dead" ] || [ "$health" = "unhealthy" ]; then
      log "Service '$svc' failed: state=$state health=$health"
      dump_diagnostics
      exit 1
    fi
    if [ "$state" != "running" ] || { [ "$health" != "healthy" ] && [ "$health" != "none" ]; }; then
      pending+=("$svc($state/$health)")
    fi
  done
  if [ "${#pending[@]}" -eq 0 ]; then
    break
  fi
  if [ "$(date +%s)" -ge "$deadline" ]; then
    log "Timed out waiting for: ${pending[*]}"
    dump_diagnostics
    exit 1
  fi
  sleep 5
done
log "All containers healthy."

# Host-side smoke checks on the published ports (best effort only if curl exists).
if command -v curl >/dev/null 2>&1; then
  check() {
    local name="$1" url="$2" code
    code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "$url" || true)"
    # Any HTTP response below 500 means the service is up (backend /auth/me answers 401).
    if [ -z "$code" ] || [ "$code" = "000" ] || [ "$code" -ge 500 ]; then
      log "Smoke check failed: $name $url -> ${code:-none}"
      dump_diagnostics
      exit 1
    fi
    log "Smoke check ok: $name $url -> $code"
  }
  check orchestrator "http://127.0.0.1:8787/health"
  check backend "http://127.0.0.1:5178/_backend/auth/me"
  check frontend "http://127.0.0.1:5174/"
fi

log "Pruning dangling images..."
docker image prune -f >/dev/null || true

log "Deploy complete."
