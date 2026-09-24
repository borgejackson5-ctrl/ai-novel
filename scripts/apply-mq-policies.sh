#!/usr/bin/env bash
# 给死信队列加上限（幂等，可重复执行）
#
# 背景：7 个死信队列均为「只留痕、不自动消费」，只进不出。未设上限时，
# 一类持续失败（模型侧长期不可用、ES 不可达）即可将 broker 的内存/磁盘占满，
# 而 broker 一旦进入 blocked 状态，所有生产者都会被阻塞，业务无法发送消息。
# 且该状态无自动告警：队列无人消费、也没有深度指标，积压量不可观测。
#
# 使用 policy 而非队列参数的原因：RabbitMQ 不允许修改已存在队列的参数。
# 在代码里给 QueueBuilder 添加 x-max-length 后，应用启动声明队列时会触发
# PRECONDITION_FAILED 导致启动失败（配置未开启 ignore-declaration-exceptions）。
# policy 为 broker 侧限制，不改动队列与队列中已有的消息。
#
# 策略保存在 broker 上，不随代码部署：每次更换 broker（新服务器、清理卷、重建容器）都需重新执行一次。
#
# 用法：bash scripts/apply-mq-policies.sh [容器名]
set -euo pipefail

CONTAINER="${1:-ai-novel-rabbitmq}"

# 上限取 10000 条与 7 天：该组队列用于留痕（排查「哪一批消息未处理成功」），
# 7 天足以回溯，而 10000 条远大于正常失败的规模；达到该数量
# 说明某一类失败在持续发生，即为需排查的信号。
# 注意：message-ttl 的单位是毫秒（604800000 = 7 天），写成 604800 会变成 10 分钟。
POLICY_NAME="dlx-bounded"
PATTERN='^.*\.dlx\.queue$'
DEFINITION='{"max-length":10000,"message-ttl":604800000}'

echo "给 $CONTAINER 设置策略 $POLICY_NAME ..."
docker exec "$CONTAINER" rabbitmqctl set_policy "$POLICY_NAME" "$PATTERN" "$DEFINITION" --apply-to queues

echo
echo "当前策略："
docker exec "$CONTAINER" rabbitmqctl list_policies

echo
echo "各死信队列的生效情况（name / messages / policy）："
docker exec "$CONTAINER" rabbitmqctl list_queues name messages policy | grep -E 'dlx\.queue|^name' || true
