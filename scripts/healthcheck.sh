#!/usr/bin/env bash
# 环境健康自检：JDK/Maven/Docker/中间件端口/Nacos/数据库 schema。
# 启动遇到问题时先跑这个，逐项定位。退出码 0 表示全部就绪。

source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

fail=0
pass() { ok "$1"; }
miss() { err "$1"; fail=1; }

echo "${C_BOLD}== 本地工具链 ==${C_RESET}"
if /usr/libexec/java_home -v 21 >/dev/null 2>&1; then
  pass "JDK 21: $(/usr/libexec/java_home -v 21)"
else
  miss "未找到 JDK 21（brew install openjdk@21）"
fi
[ -x "$PROJECT_ROOT/mvnw" ] && pass "Maven wrapper: ./mvnw" || miss "mvnw 不可执行"
if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
  pass "Docker daemon 运行中"
else
  miss "Docker daemon 未运行（colima start 或启动 Docker Desktop）"
fi

echo
echo "${C_BOLD}== 中间件端口 ==${C_RESET}"
for spec in "MySQL:3306" "Nacos:8848" "RocketMQ-namesrv:9876" "RocketMQ-console:8080" \
            "Seata:8091" "Sentinel:8858" "Zipkin:9411"; do
  name="${spec%%:*}"; port="${spec##*:}"
  if (exec 3<>"/dev/tcp/127.0.0.1/$port") 2>/dev/null; then pass "$name :$port 可达"; exec 3>&- 2>/dev/null || true
  else miss "$name :$port 不可达"; fi
done

echo
echo "${C_BOLD}== 容器状态 ==${C_RESET}"
if docker info >/dev/null 2>&1; then
  compose ps --format 'table {{.Name}}\t{{.Status}}' 2>/dev/null
fi

echo
echo "${C_BOLD}== Nacos / 数据库 ==${C_RESET}"
count="$(curl -s 'http://localhost:8848/nacos/v1/ns/service/list?pageNo=1&pageSize=20' 2>/dev/null \
  | sed -E 's/.*"count":([0-9]+).*/\1/')"
if [ -n "$count" ]; then pass "Nacos 已注册服务数: $count"; else miss "Nacos 无响应"; fi

if (exec 3<>"/dev/tcp/127.0.0.1/3306") 2>/dev/null; then
  schemas=$(docker exec ddd-mysql mysql -uroot -pddd_root_2026 -N -e \
    "SELECT GROUP_CONCAT(schema_name SEPARATOR ', ') FROM information_schema.schemata WHERE schema_name LIKE 'mall\\_%';" 2>/dev/null)
  if [ -n "$schemas" ]; then pass "mall_* schema: $schemas"; else miss "未查到 mall_* schema（init 脚本是否执行过?）"; fi
fi

echo
if [ "$fail" -eq 0 ]; then
  ok "全部检查通过。启动整套环境: scripts/run.sh up"
else
  err "存在未就绪项；中间件问题先执行 scripts/infra.sh up"
fi
exit "$fail"
