#!/usr/bin/env bash
# 公共函数与常量：被 scripts/ 下其他脚本 source，不直接执行。

set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="$PROJECT_ROOT/docker-compose/docker-compose.yml"
LOG_DIR="$PROJECT_ROOT/logs"

# 模块名（启动顺序：product → ... → order）
ALL_MODULES=(product inventory payment order)

# 模块名 -> 端口（不用关联数组，兼容 macOS bash 3.2）
module_port() {
  case "$1" in
    product)   echo 8081 ;;
    inventory) echo 8082 ;;
    payment)   echo 8083 ;;
    order)     echo 8084 ;;
    *) echo ""; return 1 ;;
  esac
}

# 中间件容器名（与 docker-compose.yml 的 container_name 一致）
ALL_CONTAINERS=(
  ddd-mysql ddd-nacos
  ddd-rocketmq-namesrv ddd-rocketmq-broker ddd-rocketmq-console
  ddd-seata ddd-sentinel ddd-zipkin
)

mkdir -p "$LOG_DIR"

# --- 颜色（非 TTY 时自动关闭）---
if [ -t 1 ]; then
  C_RED=$'\033[31m'; C_GREEN=$'\033[32m'; C_YELLOW=$'\033[33m'
  C_BLUE=$'\033[34m'; C_BOLD=$'\033[1m'; C_RESET=$'\033[0m'
else
  C_RED=""; C_GREEN=""; C_YELLOW=""; C_BLUE=""; C_BOLD=""; C_RESET=""
fi

log()  { printf '%s\n' "$*"; }
info() { printf '%s[infra]%s %s\n' "$C_BLUE" "$C_RESET" "$*"; }
ok()   { printf '%s[ ok ]%s %s\n' "$C_GREEN" "$C_RESET" "$*"; }
warn() { printf '%s[warn]%s %s\n' "$C_YELLOW" "$C_RESET" "$*"; }
err()  { printf '%s[fail]%s %s\n' "$C_RED" "$C_RESET" "$*" >&2; }

# --- Java / Maven ---
detect_java() {
  if [ -z "${JAVA_HOME:-}" ] || [ ! -x "$JAVA_HOME/bin/java" ]; then
    if /usr/libexec/java_home -v 21 >/dev/null 2>&1; then
      export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
    elif command -v java >/dev/null 2>&1; then
      local jbin; jbin="$(command -v java)"
      local jreal; jreal="$([ -L "$jbin" ] && readlink "$jbin" || echo "$jbin")"
      export JAVA_HOME="$(cd "$(dirname "$jreal")/.." && pwd)"
    else
      err "未找到 JDK 21。请设置 JAVA_HOME，或在 macOS 执行: brew install openjdk@21"
      exit 1
    fi
  fi
  local major
  major="$("$JAVA_HOME/bin/java" -version 2>&1 | head -1 | sed -E 's/.*version "?([0-9]+).*/\1/')"
  if [ "$major" != "21" ]; then
    err "需要 JDK 21，当前为 $(head -1 <<<"$($JAVA_HOME/bin/java -version 2>&1)")"
    err "macOS 临时切换: export JAVA_HOME=\$(/usr/libexec/java_home -v 21)"
    exit 1
  fi
}

mvn_cmd() {
  if [ -x "$PROJECT_ROOT/mvnw" ]; then
    echo "$PROJECT_ROOT/mvnw"
  elif command -v mvn >/dev/null 2>&1; then
    echo "mvn"
  else
    err "未找到 Maven：仓库自带 ./mvnw 不可执行，且 PATH 中无 mvn"
    exit 1
  fi
}

jar_path() {
  local m="$1"
  echo "$PROJECT_ROOT/mall-$m/target/mall-$m-0.1.0-SNAPSHOT.jar"
}

pid_file() { echo "$LOG_DIR/mall-$1.pid"; }
out_file() { echo "$LOG_DIR/mall-$1.out"; }

compose() {
  docker compose -f "$COMPOSE_FILE" "$@"
}

# 等待 TCP 端口可连（用于就绪探测）
wait_for_port() {
  local host="$1" port="$2" timeout="${3:-60}"
  local i=0
  while ! (exec 3<>"/dev/tcp/$host/$port") 2>/dev/null; do
    i=$((i + 1))
    if [ "$i" -ge "$timeout" ]; then
      return 1
    fi
    sleep 1
  done
}

# 检查某模块 PID 是否存活
module_running() {
  local m="$1" pid
  local pf; pf="$(pid_file "$m")"
  [ -f "$pf" ] || return 1
  pid="$(cat "$pf" 2>/dev/null || true)"
  [ -n "$pid" ] || return 1
  kill -0 "$pid" 2>/dev/null
}
