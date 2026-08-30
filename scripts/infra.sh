#!/usr/bin/env bash
# 中间件容器一键管理：up | down | stop | status | logs | clean
#
# 用法:
#   scripts/infra.sh up              # 启动并等待就绪（MySQL/Nacos）
#   scripts/infra.sh down            # 停止并删除容器（保留数据卷）
#   scripts/infra.sh stop            # 停止容器但不删除
#   scripts/infra.sh status          # 容器状态 + 健康
#   scripts/infra.sh logs [name]     # 跟踪日志（name 可选: mysql/nacos/...）
#   scripts/infra.sh clean           # 停止并删除容器+数据卷（⚠ 清空 MySQL 数据）

source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

cmd="${1:-}"
target="${2:-}"

ensure_docker() {
  if ! docker info >/dev/null 2>&1; then
    err "Docker 不可用。先启动 Docker Desktop 或 Colima（colima start）。"
    exit 1
  fi
}

wait_for_middleware() {
  info "等待 MySQL 健康..."
  for _ in $(seq 1 60); do
    if compose ps mysql 2>/dev/null | grep -q 'healthy'; then break; fi
    sleep 2
  done
  if ! compose ps mysql 2>/dev/null | grep -q 'healthy'; then
    warn "MySQL 在 120s 内未进入 healthy，查看日志: scripts/infra.sh logs mysql"
  else
    ok "MySQL 已就绪 (127.0.0.1:3306, root/ddd_root_2026)"
  fi

  info "等待 Nacos 就绪..."
  if wait_for_port 127.0.0.1 8848 90; then
    for _ in $(seq 1 30); do
      if curl -sf 'http://localhost:8848/nacos/v1/console/health/liveness' >/dev/null 2>&1; then
        break
      fi
      sleep 1
    done
    ok "Nacos 已就绪 (http://localhost:8848/nacos, nacos/nacos)"
  else
    warn "Nacos 在 90s 内未监听 8848，查看日志: scripts/infra.sh logs nacos"
  fi
}

init_rocketmq_topics() {
  info "等待 RocketMQ namesrv 就绪..."
  if ! wait_for_port 127.0.0.1 9876 90; then
    warn "namesrv 90s 内未监听 9876，跳过 topic 初始化（应用首次发消息时 broker 会自动建 topic，但首条消息延迟较高）"
    return
  fi
  # broker 启动后需要十几秒才向 namesrv 注册，mqadmin 建 topic 前要等它注册成功
  local topics=("order-events")
  for t in "${topics[@]}"; do
    local ok_flag=0
    for _ in $(seq 1 30); do
      if docker exec ddd-rocketmq-broker sh mqadmin updateTopic \
          -n rocketmq-namesrv:9876 -c DefaultCluster -t "$t" >/dev/null 2>&1; then
        ok_flag=1; break
      fi
      sleep 2
    done
    if [ "$ok_flag" = 1 ]; then
      ok "RocketMQ topic 已就绪: $t（生产实践：topic 预创建，不依赖 broker 自动创建）"
    else
      warn "topic $t 预创建失败（broker 未注册？）；broker 开了 autoCreateTopicEnable，应用首条消息仍会成功，只是首次延迟较高"
    fi
  done
}

case "$cmd" in
  up)
    ensure_docker
    info "拉取镜像并启动中间件（首次较慢）..."
    compose up -d
    wait_for_middleware
    init_rocketmq_topics
    "$0" status
    ;;
  down)
    ensure_docker
    info "停止并删除容器（数据卷保留）..."
    compose down
    ok "中间件已关闭；数据保留在 docker volume，下次 up 继续使用"
    ;;
  stop)
    ensure_docker
    compose stop
    ok "容器已停止（未删除）"
    ;;
  status)
    ensure_docker
    compose ps --format 'table {{.Name}}\t{{.Status}}\t{{.Ports}}'
    ;;
  logs)
    ensure_docker
    if [ -n "$target" ]; then
      compose logs -f --tail=100 "ddd-$target"
    else
      compose logs -f --tail=50
    fi
    ;;
  clean)
    ensure_docker
    warn "即将删除容器 AND 数据卷（MySQL/Nacos 数据全部清空），3 秒后执行，Ctrl-C 取消..."
    sleep 3
    compose down -v
    ok "已清空。下次 scripts/infra.sh up 会重新初始化 4 个 schema。"
    ;;
  *)
    cat <<EOF
用法: scripts/infra.sh <command>

  up       启动中间件并等待 MySQL/Nacos 就绪
  down     停止删除容器（保留数据卷）
  stop     停止容器但不删除
  status   查看容器状态
  logs [n] 跟踪日志（n=mysql/nacos/rocketmq-broker/seata/sentinel/zipkin）
  clean    ⚠ 删除容器 AND 数据卷（清空全部中间件数据）

中间件端口与控制台:
  MySQL      3306   root / ddd_root_2026（4 个 schema: mall_product/inventory/payment/order）
  Nacos      8848   nacos / nacos
  RocketMQ   9876/10911, 控制台 8080
  Seata      8091/7091  （第 10 讲前应用侧 seata.enabled=false，容器重启不影响主流程）
  Sentinel   8858   sentinel / sentinel
  Zipkin     9411
EOF
    exit 1
    ;;
esac
