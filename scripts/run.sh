#!/usr/bin/env bash
# 一键编排：把中间件 + 四个业务服务作为一个整体启停。
#
# 用法:
#   scripts/run.sh up        # 中间件 up → 打包 → 启动四模块
#   scripts/run.sh start     # 仅启动业务模块（中间件需已在运行）
#   scripts/run.sh stop      # 停止业务模块（保留中间件）
#   scripts/run.sh restart   # 重启业务模块
#   scripts/run.sh status    # 容器 + 四模块 + Nacos 注册 一览
#   scripts/run.sh down      # 业务模块 stop + 中间件 down（保留数据）
#   scripts/run.sh clean     # 全停 + 删除中间件数据卷（⚠ 清空数据库）

source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

SCRIPTS="$PROJECT_ROOT/scripts"
cmd="${1:-}"

nacos_registered_count() {
  curl -s 'http://localhost:8848/nacos/v1/ns/service/list?pageNo=1&pageSize=20&groupName=DEFAULT_GROUP' 2>/dev/null \
    | sed -E 's/.*"count":([0-9]+).*/\1/' | grep -E '^[0-9]+$' || echo "-"
}

case "$cmd" in
  up)
    "$SCRIPTS/infra.sh" up
    "$SCRIPTS/app.sh" build
    "$SCRIPTS/app.sh" start all
    "$SCRIPTS/run.sh" status
    ;;
  start)   "$SCRIPTS/app.sh" start all ;;
  stop)    "$SCRIPTS/app.sh" stop all ;;
  restart) "$SCRIPTS/app.sh" restart all ;;
  down)
    "$SCRIPTS/app.sh" stop all
    "$SCRIPTS/infra.sh" down
    ;;
  status)
    echo "${C_BOLD}── 中间件容器 ──${C_RESET}"
    compose ps --format 'table {{.Name}}\t{{.Status}}' 2>/dev/null || warn "Docker 未运行"
    echo
    echo "${C_BOLD}── 业务模块 ──${C_RESET}"
    "$SCRIPTS/app.sh" status || true
    echo
    echo "${C_BOLD}── Nacos 注册 ──${C_RESET}"
    c="$(nacos_registered_count)"
    if [ "$c" = "4" ]; then
      ok "已注册服务数: $c / 4（mall-product/inventory/payment/order 全部在线）"
    elif [ "$c" = "-" ]; then
      warn "无法连接 Nacos（中间件是否已 up?）"
    else
      warn "已注册服务数: $c / 4（模块可能仍在启动，稍后 scripts/run.sh status 复查）"
    fi
    ;;
  clean)
    "$SCRIPTS/app.sh" stop all || true
    "$SCRIPTS/infra.sh" clean
    rm -f "$LOG_DIR"/*.pid
    ;;
  *)
    cat <<EOF
用法: scripts/run.sh <command>

  up       中间件 up → 打包 → 启动四模块（从零到全运行的唯一命令）
  start    仅启动业务模块
  stop     停止业务模块
  restart  重启业务模块
  status   容器 + 模块 + Nacos 注册一览
  down     业务 stop + 中间件 down（保留数据）
  clean    ⚠ 全停 + 删除数据卷（清空 MySQL 等全部中间件数据）

细粒度控制: scripts/infra.sh（中间件）、scripts/app.sh（业务模块）
EOF
    exit 1 ;;
esac
