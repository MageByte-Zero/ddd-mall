#!/usr/bin/env bash
# 业务模块（jar）启停：start | stop | restart | status | logs | build
#
# 用法:
#   scripts/app.sh build                       # 全量打包（跳过测试）
#   scripts/app.sh start <module|all>          # 后台启动；all=四模块
#   scripts/app.sh stop  <module|all>
#   scripts/app.sh restart <module|all>
#   scripts/app.sh status                      # 四模块 PID/端口/健康
#   scripts/app.sh logs  <module>              # 跟踪 logs/mall-<m>.out
#
# module ∈ product | inventory | payment | order

source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

cmd="${1:-}"
target="${2:-}"

resolve_modules() {
  if [ -z "$target" ]; then
    err "缺少模块名（product|inventory|payment|order|all）"
    exit 1
  fi
  if [ "$target" = "all" ]; then
    echo "${ALL_MODULES[@]}"
  else
    case "$target" in
      product|inventory|payment|order) echo "$target" ;;
      *) err "未知模块: $target"; exit 1 ;;
    esac
  fi
}

build_all() {
  detect_java
  info "执行 mvn clean package -DskipTests..."
  "$(mvn_cmd)" clean package -DskipTests
  ok "打包完成，jar 位于各模块 target/"
}

start_one() {
  local m="$1"
  if module_running "$m"; then
    warn "mall-$m 已在运行 (pid=$(cat "$(pid_file "$m")"))"
    return 0
  fi
  local jar; jar="$(jar_path "$m")"
  if [ ! -f "$jar" ]; then
    err "未找到 $jar，请先执行: scripts/app.sh build"
    return 1
  fi
  local port="$(module_port "$m")"
  if lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
    err "端口 $port 已被占用（mall-$m 需用该端口）"
    return 1
  fi
  info "启动 mall-$m (port $port)..."
  nohup "$JAVA_HOME/bin/java" -jar "$jar" >"$(out_file "$m")" 2>&1 &
  echo $! >"$(pid_file "$m")"
}

wait_one() {
  local m="$1" port; port="$(module_port "$m")"; local pid; pid="$(cat "$(pid_file "$m")")"
  local i=0
  while kill -0 "$pid" 2>/dev/null; do
    if lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
      ok "mall-$m 已监听 $port (pid $pid)"
      return 0
    fi
    i=$((i + 1)); [ "$i" -ge 60 ] && break
    sleep 1
  done
  err "mall-$m 启动失败或 60s 内未监听 $port，查看日志: scripts/app.sh logs $m"
  return 1
}

stop_one() {
  local m="$1" pf; pf="$(pid_file "$m")"
  if ! module_running "$m"; then
    warn "mall-$m 未运行"
    rm -f "$pf"
    return 0
  fi
  local pid; pid="$(cat "$pf")"
  info "停止 mall-$m (pid $pid)..."
  kill "$pid" 2>/dev/null || true
  for _ in $(seq 1 20); do
    kill -0 "$pid" 2>/dev/null || break
    sleep 0.5
  done
  if kill -0 "$pid" 2>/dev/null; then
    warn "优雅停止超时，强制 kill"
    kill -9 "$pid" 2>/dev/null || true
  fi
  rm -f "$pf"
  ok "mall-$m 已停止"
}

status_all() {
  printf '%-12s %-8s %-8s %s\n' MODULE PORT PID STATE
  local rc=0
  for m in "${ALL_MODULES[@]}"; do
    local port="$(module_port "$m")" pid="-" state="stopped"
    if module_running "$m"; then
      pid="$(cat "$(pid_file "$m")")"
      if lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
        state="running"
      else
        state="starting"; rc=1
      fi
    else
      rc=1
    fi
    printf '%-12s %-8s %-8s %s\n' "mall-$m" "$port" "$pid" "$state"
  done
  return "$rc"
}

case "$cmd" in
  build) build_all ;;
  start)
    detect_java
    mods=$(resolve_modules)
    for m in $mods; do start_one "$m" || true; done
    for m in $mods; do module_running "$m" && wait_one "$m" || true; done
    status_all || true
    ;;
  stop)
    mods=$(resolve_modules)
    for m in $mods; do stop_one "$m"; done
    ;;
  restart)
    mods=$(resolve_modules)
    for m in $mods; do stop_one "$m"; done
    detect_java
    for m in $mods; do start_one "$m" || true; done
    for m in $mods; do module_running "$m" && wait_one "$m" || true; done
    status_all || true
    ;;
  status) status_all ;;
  logs)
    [ -n "$target" ] || { err "用法: scripts/app.sh logs <module>"; exit 1; }
    case "$target" in product|inventory|payment|order) ;; *) err "未知模块"; exit 1 ;; esac
    tail -f "$(out_file "$target")"
    ;;
  *)
    cat <<EOF
用法: scripts/app.sh <command> [module]

  build                mvn clean package -DskipTests
  start <m|all>        后台启动（自动探测 JDK 21），日志在 logs/mall-<m>.out
  stop  <m|all>        优雅停止（20s 超时后 kill -9）
  restart <m|all>
  status               四模块 PID/端口/运行状态
  logs  <m>            跟踪模块日志

module: product(8081) inventory(8082) payment(8083) order(8084)
EOF
    exit 1 ;;
esac
