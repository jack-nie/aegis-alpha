#!/usr/bin/env bash
# aegis.sh — Aegis Alpha 本地开发统一入口（Linux / macOS / Git Bash）
#
# 用法: ./aegis.sh <命令> [服务...] [选项]
#   命令: up | down | restart | status | logs | smoke | setup | infra | help
#   服务: orchestrator | backend | web | all（默认 all；别名 orch/langgraph、api、frontend/fe）
#   选项: --env-file <文件>   读取的 env 文件（默认 ./.env，可用 AEGIS_ENV_FILE 指定）
#         --web-mode dev|prod 前端用 next dev（默认）或 build + next start
#         --skip-install       缺少 node_modules / .venv 时不自动安装
#         --force              down/up 时也结束占用端口的非本脚本进程
#         -f, --follow         logs 持续跟踪
#         -n <行数>            logs 显示的行数（默认 80）
#
# Windows PowerShell 请用 .\aegis.ps1（参数相同）。详见 docs/本地启动说明.md。

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RUN_DIR="$ROOT/.run"
LOG_DIR="$ROOT/logs"
ALL_SERVICES=(orchestrator backend web)

ENV_FILE="${AEGIS_ENV_FILE:-$ROOT/.env}"
WEB_MODE="${AEGIS_WEB_MODE:-dev}"
SKIP_INSTALL=0
FORCE=0
FOLLOW=0
LOG_LINES=80

c_info=""; c_ok=""; c_warn=""; c_err=""; c_off=""
if [ -t 1 ]; then
  c_info=$'\033[36m'; c_ok=$'\033[32m'; c_warn=$'\033[33m'; c_err=$'\033[31m'; c_off=$'\033[0m'
fi
info() { printf '%s[aegis]%s %s\n' "$c_info" "$c_off" "$*"; }
ok()   { printf '%s[aegis]%s %s\n' "$c_ok" "$c_off" "$*"; }
warn() { printf '%s[aegis] WARN:%s %s\n' "$c_warn" "$c_off" "$*" >&2; }
die()  { printf '%s[aegis] ERROR:%s %s\n' "$c_err" "$c_off" "$*" >&2; exit 1; }

usage() { sed -n '2,15p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; }

# ---------------------------------------------------------------- env loading

# 读取 KEY=VALUE 形式的 env 文件（不 source：JDBC URL 中的 & 会被 shell 当成后台符）。
# 已在进程环境里的非空变量优先，不会被文件覆盖。
load_env_file() {
  local file="$1" line key val
  [ -f "$file" ] || return 0
  while IFS= read -r line || [ -n "$line" ]; do
    line="${line%$'\r'}"
    case "$line" in ''|'#'*) continue ;; esac
    line="${line#export }"
    [[ "$line" == *=* ]] || continue
    key="${line%%=*}"; val="${line#*=}"
    key="${key//[[:space:]]/}"
    [[ "$key" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || continue
    if [[ "$val" =~ ^\"(.*)\"$ ]] || [[ "$val" =~ ^\'(.*)\'$ ]]; then val="${BASH_REMATCH[1]}"; fi
    if [ -z "${!key:-}" ]; then export "$key=$val"; fi
  done < "$file"
}

gen_secret() {
  if command -v openssl >/dev/null 2>&1; then openssl rand -hex 24
  else od -An -tx1 -N24 /dev/urandom | tr -d ' \n'; fi
}

is_prod_marker() {
  local v
  v="$(printf '%s' "${1:-}" | tr '[:upper:]' '[:lower:]' | tr -d '[:space:]')"
  [ "$v" = "prod" ] || [ "$v" = "production" ]
}

# 本地开发默认值。不写死任何密码；缺少的共享密钥按工作区随机生成并保存在 .run/（已 gitignore）。
prepare_env() {
  load_env_file "$ENV_FILE"
  mkdir -p "$RUN_DIR" "$LOG_DIR"

  ORCH_PORT="${AEGIS_ALPHA_LANGGRAPH_PORT:-8787}"
  BACKEND_PORT="${AEGIS_ALPHA_PORT:-5178}"
  WEB_PORT="${AEGIS_ALPHA_FRONTEND_PORT:-5174}"
  ORCH_HOST="${AEGIS_ORCHESTRATOR_BIND_HOST:-127.0.0.1}"
  export AEGIS_ALPHA_LANGGRAPH_PORT="$ORCH_PORT" AEGIS_ALPHA_PORT="$BACKEND_PORT"

  # .env.example 面向 docker compose（主机名 mysql / redis / langgraph）；本地直接跑时改指本机端口。
  if [[ "${AEGIS_ALPHA_DB_URL:-}" == *"//mysql:"* ]]; then
    export AEGIS_ALPHA_DB_URL="${AEGIS_ALPHA_DB_URL/\/\/mysql:/\/\/127.0.0.1:}"
    [ "${QUIET_ENV:-0}" = 1 ] || info "AEGIS_ALPHA_DB_URL 指向 compose 主机名 mysql，本地运行改为 127.0.0.1"
  fi
  if [ "${AEGIS_ALPHA_REDIS_HOST:-}" = "redis" ]; then
    export AEGIS_ALPHA_REDIS_HOST=127.0.0.1
  fi
  export AEGIS_ALPHA_LANGCHAIN_ENGINE_URL="http://127.0.0.1:$ORCH_PORT"
  export AEGIS_ALPHA_BACKEND_URL="http://127.0.0.1:$BACKEND_PORT"
  export AEGIS_ALPHA_NODE_CALLBACK_BASE_URL="${AEGIS_ALPHA_NODE_CALLBACK_BASE_URL:-http://127.0.0.1:$BACKEND_PORT}"
  export AEGIS_ALPHA_LANGCHAIN_ENABLED="${AEGIS_ALPHA_LANGCHAIN_ENABLED:-true}"

  # 共享服务令牌 / 登录令牌签名密钥：未配置时生成随机值（不使用示例令牌，因此无需 DEV_MODE）。
  local secrets="$RUN_DIR/secrets.env"
  if [ -z "${AEGIS_ALPHA_NODE_EXECUTION_TOKEN:-}" ] || [ -z "${AEGIS_ALPHA_TOKEN_SECRET:-}" ]; then
    if [ ! -f "$secrets" ]; then
      ( umask 077
        { echo "# 由 aegis.sh 生成的本地开发密钥，仅本工作区使用，勿提交。"
          echo "AEGIS_ALPHA_NODE_EXECUTION_TOKEN=$(gen_secret)"
          echo "AEGIS_ALPHA_TOKEN_SECRET=$(gen_secret)"; } > "$secrets" )
      [ "${QUIET_ENV:-0}" = 1 ] || info "已生成本地开发密钥: ${secrets#"$ROOT"/}"
    fi
    load_env_file "$secrets"
  fi

  # 没有模型 key 时默认走 mock，保证不配 key 也能起来。
  if [ -z "${AEGIS_ALPHA_LANGCHAIN_MOCK:-}" ]; then
    if [ -z "${AEGIS_ALPHA_LANGCHAIN_API_KEY:-}" ] && [ -z "${OPENAI_API_KEY:-}" ]; then
      export AEGIS_ALPHA_LANGCHAIN_MOCK=true
      [ "${QUIET_ENV:-0}" = 1 ] || info "未配置 AEGIS_ALPHA_LANGCHAIN_API_KEY / OPENAI_API_KEY，orchestrator 使用 mock 模式"
    fi
  fi

  # 与 ProductionSafetyGuard（Java）/ production_safety_problems（Python）同一规则，提前给出清晰提示。
  if is_prod_marker "${AEGIS_ALPHA_ENV:-}" || [[ ",$(printf '%s' "${SPRING_PROFILES_ACTIVE:-}" | tr '[:upper:]' '[:lower:]' | tr -d ' ')," =~ ,(prod|production), ]]; then
    local problems=()
    [ "$(printf '%s' "${AEGIS_ALPHA_DEV_MODE:-}" | tr '[:upper:]' '[:lower:]')" = "true" ] && problems+=("AEGIS_ALPHA_DEV_MODE=true")
    [ "$(printf '%s' "${AEGIS_ALPHA_NODE_EXECUTION_TOKEN:-}" | tr -d '[:space:]')" = "local-workflow-node-token" ] && problems+=("示例 AEGIS_ALPHA_NODE_EXECUTION_TOKEN")
    [ "$(printf '%s' "${AEGIS_ALPHA_TOKEN_SECRET:-}" | tr -d '[:space:]')" = "local-aegis-demo-secret" ] && problems+=("demo AEGIS_ALPHA_TOKEN_SECRET")
    if [ "${#problems[@]}" -gt 0 ]; then
      die "AEGIS_ALPHA_ENV / SPRING_PROFILES_ACTIVE 标记为生产，但配置了: ${problems[*]}；服务会被 ProductionSafetyGuard 拒绝启动。本地开发请去掉生产标记。"
    fi
    warn "检测到生产标记（AEGIS_ALPHA_ENV / SPRING_PROFILES_ACTIVE）。本脚本面向本地开发，生产请用 docker compose。"
  fi
  if [ "${AEGIS_ALPHA_REPLICAS:-1}" != "1" ]; then
    warn "AEGIS_ALPHA_REPLICAS=${AEGIS_ALPHA_REPLICAS}，本脚本只启动单个 orchestrator 进程（见 docs/多副本设计.md）"
  fi

  # 本机健康检查不走代理。
  export NO_PROXY="127.0.0.1,localhost${NO_PROXY:+,$NO_PROXY}" no_proxy="127.0.0.1,localhost${no_proxy:+,$no_proxy}"
}

# ---------------------------------------------------------------- helpers

normalize_service() {
  case "$1" in
    orchestrator|orch|langgraph|python) echo orchestrator ;;
    backend|api|java|spring) echo backend ;;
    web|frontend|fe|ui) echo web ;;
    *) return 1 ;;
  esac
}

svc_port() {
  case "$1" in orchestrator) echo "$ORCH_PORT" ;; backend) echo "$BACKEND_PORT" ;; web) echo "$WEB_PORT" ;; esac
}

svc_health_url() {
  case "$1" in
    orchestrator) echo "http://127.0.0.1:$ORCH_PORT/health" ;;
    backend) echo "http://127.0.0.1:$BACKEND_PORT/_backend/auth/me" ;; # 未登录返回 401/403 也算已就绪
    web) echo "http://127.0.0.1:$WEB_PORT/" ;;
  esac
}

svc_timeout() {
  case "$1" in
    orchestrator) echo "${AEGIS_ORCHESTRATOR_TIMEOUT:-60}" ;;
    backend) echo "${AEGIS_BACKEND_TIMEOUT:-300}" ;;
    web) echo "${AEGIS_WEB_TIMEOUT:-180}" ;;
  esac
}

pid_file() { echo "$RUN_DIR/$1.pid"; }
log_file() { echo "$LOG_DIR/$1.log"; }

svc_pid() {
  local f; f="$(pid_file "$1")"
  [ -f "$f" ] || return 1
  local pid; pid="$(cat "$f" 2>/dev/null || true)"
  [[ "$pid" =~ ^[0-9]+$ ]] || return 1
  kill -0 "$pid" 2>/dev/null || return 1
  echo "$pid"
}

port_open() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }

port_owner() {
  local port="$1" out=""
  if command -v ss >/dev/null 2>&1; then
    out="$(ss -ltnpH "sport = :$port" 2>/dev/null | grep -o 'pid=[0-9]*' | head -1 | cut -d= -f2 || true)"
  fi
  if [ -z "$out" ] && command -v lsof >/dev/null 2>&1; then
    out="$(lsof -nP -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null | head -1 || true)"
  fi
  if [ -z "$out" ] && command -v netstat >/dev/null 2>&1; then # Git Bash / Windows
    out="$(netstat -ano 2>/dev/null | grep -E "[:.]${port}[[:space:]].*LISTEN" | awk '{print $NF}' | head -1 || true)"
  fi
  echo "$out"
}

http_code() {
  curl -s --noproxy '*' -o /dev/null -m "${2:-3}" -w '%{http_code}' "$1" 2>/dev/null || true
}

code_ok() {
  case "$1" in
    orchestrator) [ "$2" = "200" ] ;;
    *) [[ "$2" =~ ^[234][0-9][0-9]$ ]] ;;
  esac
}

healthy() { code_ok "$1" "$(http_code "$(svc_health_url "$1")" 5)"; }

# 以独立进程组启动，便于 down 时整组结束（mvn -> java fork、npm -> next 子进程）。
spawn() {
  local svc="$1" dir="$2"; shift 2
  local log; log="$(log_file "$svc")"
  [ -f "$log" ] && mv -f "$log" "$log.1"
  {
    echo "### $(date '+%F %T') aegis.sh up $svc"
    echo "### cwd: $dir"
    echo "### cmd: $*"
  } > "$log"
  if command -v setsid >/dev/null 2>&1; then
    (cd "$dir" && exec setsid "$@" >>"$log" 2>&1 </dev/null) &
  else
    set -m
    (cd "$dir" && exec "$@" >>"$log" 2>&1 </dev/null) &
    set +m
  fi
  echo $! > "$(pid_file "$svc")"
}

kill_tree() {
  local pid="$1" sig="$2"
  # 优先按进程组结束；拿不到进程组时退回逐个结束子进程。
  if kill "-$sig" -- "-$pid" 2>/dev/null; then return 0; fi
  if command -v pkill >/dev/null 2>&1; then pkill "-$sig" -P "$pid" 2>/dev/null || true; fi
  kill "-$sig" "$pid" 2>/dev/null || true
}

stop_pid() {
  local pid="$1" i
  kill_tree "$pid" TERM
  for i in $(seq 1 30); do
    if ! kill -0 "$pid" 2>/dev/null && ! kill -0 -- "-$pid" 2>/dev/null; then return 0; fi
    sleep 0.5
  done
  kill_tree "$pid" KILL
  sleep 0.5
}

# ---------------------------------------------------------------- service commands

python_bin() {
  local d="$ROOT/aegis-alpha-orchestrator"
  if [ -x "$d/.venv/bin/python" ]; then echo "$d/.venv/bin/python"
  elif [ -x "$d/.venv/Scripts/python.exe" ]; then echo "$d/.venv/Scripts/python.exe"
  elif command -v python3 >/dev/null 2>&1; then echo python3
  else echo python; fi
}

ensure_prereqs() {
  local svc="$1"
  case "$svc" in
    orchestrator)
      local py; py="$(python_bin)"
      if ! "$py" -c 'import fastapi, uvicorn, langgraph' >/dev/null 2>&1; then
        if [ "$SKIP_INSTALL" = 1 ]; then die "orchestrator 依赖缺失（$py），请先运行 ./aegis.sh setup orchestrator"; fi
        info "orchestrator 依赖缺失，执行 setup ..."
        setup_orchestrator
      fi ;;
    backend)
      command -v mvn >/dev/null 2>&1 || die "未找到 mvn（Maven），请安装 Maven 3.6+ 与 JDK 8+"
      command -v java >/dev/null 2>&1 || die "未找到 java，请安装 JDK 8+"
      port_open 3306 || warn "MySQL 3306 未监听，backend 可能启动失败（可用 ./aegis.sh infra up 启动 mysql/redis）"
      port_open "${AEGIS_ALPHA_REDIS_PORT:-6379}" || warn "Redis ${AEGIS_ALPHA_REDIS_PORT:-6379} 未监听，dashboard 缓存会回退到数据库" ;;
    web)
      command -v npm >/dev/null 2>&1 || die "未找到 npm，请安装 Node.js 20+"
      if [ ! -d "$ROOT/aegis-alpha-web/node_modules" ]; then
        [ "$SKIP_INSTALL" = 1 ] && die "aegis-alpha-web/node_modules 不存在，请先运行 ./aegis.sh setup web"
        setup_web
      fi
      if [ "$BACKEND_PORT" != "5178" ]; then
        warn "前端 next.config.mjs 把 /_backend 固定代理到 127.0.0.1:5178，当前 backend 端口为 $BACKEND_PORT"
      fi ;;
  esac
}

start_service() {
  local svc="$1" port pid owner
  port="$(svc_port "$svc")"
  if pid="$(svc_pid "$svc")"; then
    if healthy "$svc"; then ok "$svc 已在运行 (PID $pid, 端口 $port)，跳过"; return 0; fi
    info "$svc 进程 $pid 存在但尚未就绪，继续等待"
    wait_healthy "$svc" "$pid"; return
  fi
  rm -f "$(pid_file "$svc")"
  if port_open "$port"; then
    owner="$(port_owner "$port")"
    if [ "$FORCE" = 1 ] && [ -n "$owner" ]; then
      warn "端口 $port 被 PID $owner 占用，--force 结束它"
      stop_pid "$owner"
    else
      die "端口 $port 已被占用${owner:+（PID $owner）}，不是本脚本启动的 $svc。请先停止该进程，或使用 --force。"
    fi
  fi
  ensure_prereqs "$svc"

  case "$svc" in
    orchestrator)
      info "启动 orchestrator: http://$ORCH_HOST:$port (mock=${AEGIS_ALPHA_LANGCHAIN_MOCK:-false})"
      spawn orchestrator "$ROOT/aegis-alpha-orchestrator" "$(python_bin)" -m uvicorn app.main:app --host "$ORCH_HOST" --port "$port" ;;
    backend)
      local profiles="${AEGIS_SPRING_PROFILES:-local}"
      info "启动 backend: http://127.0.0.1:$port (Spring profile: $profiles，首次需编译，较慢)"
      spawn backend "$ROOT/aegis-alpha-api" mvn -B -ntp -q -Dmaven.test.skip=true spring-boot:run "-Dspring-boot.run.profiles=$profiles" ;;
    web)
      local next="$ROOT/aegis-alpha-web/node_modules/.bin/next"
      if [ "$WEB_MODE" = "prod" ]; then
        info "构建前端 (npm run build) ..."
        (cd "$ROOT/aegis-alpha-web" && npm run build) > "$(log_file web).build" 2>&1 \
          || { tail -n 40 "$(log_file web).build" >&2; die "前端构建失败，日志: logs/web.log.build"; }
        info "启动 web (next start): http://127.0.0.1:$port"
        spawn web "$ROOT/aegis-alpha-web" "$next" start --hostname 127.0.0.1 --port "$port"
      else
        info "启动 web (next dev): http://127.0.0.1:$port"
        spawn web "$ROOT/aegis-alpha-web" "$next" dev --hostname 127.0.0.1 --port "$port"
      fi ;;
  esac
  wait_healthy "$svc" "$(cat "$(pid_file "$svc")")"
}

wait_healthy() {
  local svc="$1" pid="$2" timeout waited=0
  timeout="$(svc_timeout "$svc")"
  while [ "$waited" -lt "$timeout" ]; do
    if ! kill -0 "$pid" 2>/dev/null; then
      rm -f "$(pid_file "$svc")"
      warn "$svc 进程已退出，日志末尾："
      tail -n 40 "$(log_file "$svc")" >&2 || true
      die "$svc 启动失败，完整日志: logs/$svc.log"
    fi
    if healthy "$svc"; then ok "$svc 就绪 (PID $pid, ${waited}s)"; return 0; fi
    sleep 1; waited=$((waited + 1))
    if [ $((waited % 30)) -eq 0 ]; then info "  ... $svc 仍在启动 (${waited}s)"; fi
  done
  warn "$svc 在 ${timeout}s 内未就绪，日志末尾："
  tail -n 20 "$(log_file "$svc")" >&2 || true
  die "$svc 启动超时（进程 $pid 仍在运行，可用 ./aegis.sh logs $svc 查看，./aegis.sh down $svc 停止）"
}

stop_service() {
  local svc="$1" pid port owner
  port="$(svc_port "$svc")"
  if pid="$(svc_pid "$svc")"; then
    info "停止 $svc (PID $pid)"
    stop_pid "$pid"
    ok "$svc 已停止"
  else
    info "$svc 未由本脚本运行"
  fi
  rm -f "$(pid_file "$svc")"
  if port_open "$port"; then
    owner="$(port_owner "$port")"
    if [ "$FORCE" = 1 ] && [ -n "$owner" ]; then
      warn "端口 $port 仍被 PID $owner 占用，--force 结束它"; stop_pid "$owner"
    else
      warn "端口 $port 仍被占用${owner:+（PID $owner）}，不是本脚本启动的进程；需要时用 ./aegis.sh down $svc --force"
    fi
  fi
}

status_service() {
  local svc="$1" pid port state code
  port="$(svc_port "$svc")"
  code="$(http_code "$(svc_health_url "$svc")" 5)"
  if pid="$(svc_pid "$svc")"; then
    if code_ok "$svc" "$code"; then state="${c_ok}running${c_off}"; else state="${c_warn}starting/unhealthy${c_off}"; fi
  elif port_open "$port"; then
    state="${c_warn}外部进程占用端口${c_off}"; pid="$(port_owner "$port")"
  else
    state="${c_err}stopped${c_off}"; pid="-"
  fi
  printf '  %-13s %-28s pid=%-8s port=%-5s health=%s  log=logs/%s.log\n' "$svc" "$state" "${pid:--}" "$port" "${code:-000}" "$svc"
}

# ---------------------------------------------------------------- setup / smoke / infra

setup_orchestrator() {
  local d="$ROOT/aegis-alpha-orchestrator" base
  base="$(command -v python3 || command -v python || true)"
  [ -n "$base" ] || die "未找到 python3（需要 Python 3.11+）"
  if [ ! -d "$d/.venv" ]; then info "创建虚拟环境 aegis-alpha-orchestrator/.venv"; "$base" -m venv "$d/.venv"; fi
  local py; py="$(python_bin)"
  "$py" -m pip install -q --upgrade pip
  "$py" -m pip install -q -c "$d/constraints.txt" -r "$d/requirements.txt" -r "$d/requirements-dev.txt"
  "$py" -c 'import langgraph, fastapi' && ok "orchestrator 依赖已安装 ($py)"
}

setup_web() {
  info "安装前端依赖 (npm ci) ..."
  if ! (cd "$ROOT/aegis-alpha-web" && npm ci --no-audit --no-fund) > "$LOG_DIR/web.setup.log" 2>&1; then
    # 较新的 npm（11+）对 lock 文件里缺少的可选平台依赖更严格；退回 npm install 且不改写 lock 文件。
    warn "npm ci 失败（详见 logs/web.setup.log），改用 npm install --no-save（不修改 package-lock.json）"
    (cd "$ROOT/aegis-alpha-web" && npm install --no-save --no-audit --no-fund)
  fi
}

setup_backend() {
  info "预取 backend Maven 依赖并编译 ..."
  (cd "$ROOT/aegis-alpha-api" && mvn -B -ntp -q -Dmaven.test.skip=true compile)
}

cmd_smoke() {
  local fails=0 code token="${AEGIS_ALPHA_NODE_EXECUTION_TOKEN:-}"
  check() { if [ "$2" = 1 ]; then ok "[PASS] $1"; else warn "[FAIL] $1"; fails=$((fails + 1)); fi; }
  code="$(http_code "http://127.0.0.1:$WEB_PORT/" 10)"; check "web / -> $code" "$([[ "$code" == 200 ]] && echo 1)"
  code="$(http_code "http://127.0.0.1:$BACKEND_PORT/_backend/auth/me")"; check "backend /_backend/auth/me 未登录 -> $code (期望 401/403)" "$([[ "$code" =~ ^40[13]$ ]] && echo 1)"
  code="$(http_code "http://127.0.0.1:$ORCH_PORT/health")"; check "orchestrator /health -> $code" "$([[ "$code" == 200 ]] && echo 1)"
  code="$(curl -s --noproxy '*' -o /dev/null -m 5 -w '%{http_code}' -X POST -H 'Content-Type: application/json' -d '{}' "http://127.0.0.1:$ORCH_PORT/classify-intent" || true)"
  check "orchestrator /classify-intent 无令牌 -> $code (期望 401)" "$([[ "$code" == 401 ]] && echo 1)"
  code="$(curl -s --noproxy '*' -o /dev/null -m 10 -w '%{http_code}' -X POST -H 'Content-Type: application/json' -H "Authorization: Bearer $token" -d '{}' "http://127.0.0.1:$ORCH_PORT/classify-intent" || true)"
  check "orchestrator /classify-intent 带服务令牌 -> $code (期望非 401)" "$([[ "$code" != 401 && "$code" != 000 ]] && echo 1)"
  [ "$fails" = 0 ] || die "smoke 失败 $fails 项"
  ok "smoke 全部通过"
}

cmd_infra() {
  local action="${1:-up}" compose=(docker compose)
  command -v docker >/dev/null 2>&1 || die "未找到 docker；也可以自行安装 MySQL 8 / Redis 7"
  docker compose version >/dev/null 2>&1 || compose=(docker-compose)
  local args=(-f "$ROOT/docker-compose.aegis-alpha.yml")
  [ -f "$ENV_FILE" ] && args+=(--env-file "$ENV_FILE")
  case "$action" in
    up) "${compose[@]}" "${args[@]}" up -d mysql redis ;;
    down) "${compose[@]}" "${args[@]}" stop mysql redis ;;
    status) "${compose[@]}" "${args[@]}" ps mysql redis ;;
    *) die "infra 只支持 up | down | status" ;;
  esac
}

# ---------------------------------------------------------------- main

main() {
  local cmd="${1:-help}"; [ $# -gt 0 ] && shift
  local services=() arg s
  while [ $# -gt 0 ]; do
    arg="$1"; shift
    case "$arg" in
      --env-file) [ $# -gt 0 ] || die "--env-file 需要参数"; ENV_FILE="$1"; shift ;;
      --env-file=*) ENV_FILE="${arg#*=}" ;;
      --web-mode) [ $# -gt 0 ] || die "--web-mode 需要参数"; WEB_MODE="$1"; shift ;;
      --web-mode=*) WEB_MODE="${arg#*=}" ;;
      --skip-install) SKIP_INSTALL=1 ;;
      --force) FORCE=1 ;;
      -f|--follow) FOLLOW=1 ;;
      -n) [ $# -gt 0 ] || die "-n 需要参数"; LOG_LINES="$1"; shift ;;
      -h|--help) usage; exit 0 ;;
      all) services=("${ALL_SERVICES[@]}") ;;
      -*) die "未知选项: $arg（./aegis.sh help 查看用法）" ;;
      *)
        if [ "$cmd" = infra ]; then services+=("$arg"); continue; fi
        s="$(normalize_service "$arg")" || die "未知服务: $arg（可选 orchestrator | backend | web | all）"
        services+=("$s") ;;
    esac
  done
  case "$WEB_MODE" in dev|prod) ;; *) die "--web-mode 只能是 dev 或 prod" ;; esac

  case "$cmd" in
    help|-h|--help) usage; return 0 ;;
    infra) prepare_env; cmd_infra "${services[@]:-up}"; return ;;
  esac
  [ "${#services[@]}" -gt 0 ] || services=("${ALL_SERVICES[@]}")
  case "$cmd" in up|start|restart|setup|install) QUIET_ENV=0 ;; *) QUIET_ENV=1 ;; esac
  prepare_env

  # 按依赖顺序排序：orchestrator -> backend -> web；停止时反向。
  local ordered=() rev=() x y
  for x in "${ALL_SERVICES[@]}"; do for y in "${services[@]}"; do [ "$x" = "$y" ] && { ordered+=("$x"); break; }; done; done
  for ((i = ${#ordered[@]} - 1; i >= 0; i--)); do rev+=("${ordered[$i]}"); done

  case "$cmd" in
    up|start)
      for s in "${ordered[@]}"; do start_service "$s"; done
      echo; cmd_status_all "${ordered[@]}"
      echo
      if [[ " ${ordered[*]} " == *" web "* ]]; then ok "完成。打开 http://127.0.0.1:$WEB_PORT ；停止: ./aegis.sh down"
      else ok "完成。停止: ./aegis.sh down ${ordered[*]}"; fi ;;
    down|stop)
      for s in "${rev[@]}"; do stop_service "$s"; done ;;
    restart)
      for s in "${rev[@]}"; do stop_service "$s"; done
      for s in "${ordered[@]}"; do start_service "$s"; done
      echo; cmd_status_all "${ordered[@]}" ;;
    status|ps)
      cmd_status_all "${ordered[@]}" ;;
    logs|log)
      local files=()
      for s in "${ordered[@]}"; do [ -f "$(log_file "$s")" ] && files+=("$(log_file "$s")"); done
      [ "${#files[@]}" -gt 0 ] || die "还没有日志（logs/ 下无对应文件）"
      if [ "$FOLLOW" = 1 ]; then tail -n "$LOG_LINES" -F "${files[@]}"; else tail -n "$LOG_LINES" "${files[@]}"; fi ;;
    smoke)
      cmd_smoke ;;
    setup|install)
      for s in "${ordered[@]}"; do
        case "$s" in orchestrator) setup_orchestrator ;; backend) setup_backend ;; web) setup_web ;; esac
      done ;;
    *) usage; die "未知命令: $cmd" ;;
  esac
}

cmd_status_all() {
  info "服务状态（env: ${ENV_FILE#"$ROOT"/}$([ -f "$ENV_FILE" ] || echo '，未找到，使用默认值')）"
  local s; for s in "$@"; do status_service "$s"; done
}

main "$@"
