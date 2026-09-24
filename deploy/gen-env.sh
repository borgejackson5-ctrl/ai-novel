#!/usr/bin/env bash
# 一键生成服务器 .env（自动生成强随机口令，无需手动改密码）
# 用法：在项目根目录执行  bash deploy/gen-env.sh
set -euo pipefail

cd "$(dirname "$0")/.."   # 回到项目根目录

if [ -f .env ]; then
  echo "⚠️  .env 已存在，备份为 .env.bak 后重新生成"
  mv .env .env.bak
fi

# 随机口令：32 位十六进制（128 bit 熵），不含特殊字符，避免 JDBC/URL 转义问题
rand() { openssl rand -hex 16; }

cat > .env <<EOF
# MySQL
MYSQL_ROOT_PASSWORD=$(rand)

# Redis
REDIS_PASSWORD=$(rand)

# RabbitMQ
RABBITMQ_USERNAME=lingyue
RABBITMQ_PASSWORD=$(rand)

# Druid 监控台（prod profile 已关闭监控台，此值仅占位）
DRUID_USER=druid
DRUID_PASSWORD=$(rand)

# AI（D4 未定，留空 = Mock 降级）
AI_API_KEY=

# 支付回调验签密钥
PAY_NOTIFY_SECRET=$(rand)

# CORS（生产同源反代，此值基本不生效，占位）
CORS_ALLOWED_ORIGINS=http://localhost:5173

# ES 数据目录（默认项目内 ./docker-data/elasticsearch）
# ES_DATA_DIR=
EOF

echo "✅ 已生成 .env，所有口令均为随机强口令"
echo "----------------------------------------"
echo "记住这 4 个（后面要看日志/排查会用到）："
grep -E 'MYSQL_ROOT_PASSWORD|REDIS_PASSWORD|RABBITMQ_PASSWORD|PAY_NOTIFY_SECRET' .env
echo "----------------------------------------"
