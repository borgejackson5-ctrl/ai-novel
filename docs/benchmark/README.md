# 压测报告

> 本目录存放核心链路的量化数据，把「实现了三级缓存 / 分布式锁」这类描述换成可复现的实测数字。

## 跑前准备

```bash
# 1. 全栈起容器（含压测目标 backend/redis/mysql）
docker compose up -d --build

# 2. 装压测工具二选一
#    JMeter（跨平台，推荐；本目录报告里的数字都出自它）
#    wrk（轻量，命令行快）
```

## 报告清单

| 文档 | 验证的亮点 | 核心指标 |
|------|-----------|---------|
| [rank-cache.md](./rank-cache.md) | Caffeine(L1) + Redis ZSet(L2) + MySQL(L3) 三级缓存 | 开/关缓存 QPS、P99 对比 |
| [unlock-concurrency.md](./unlock-concurrency.md) | Redisson 分布式锁 + 幂等 + 原子扣减 | 单章 1000 次并发解锁请求（100 线程 × 10 次），0 重复扣费 |
| [rate-limit.md](./rate-limit.md) | Lua 固定窗口限流 | 超过 60 次/分钟的请求拦截率 |
| [async-audit.md](./async-audit.md) | AI 审核异步化（outbox + MQ，接口不等模型） | 接口延迟 vs 审核本身耗时；10 并发同时提交 |

## 数据来源

四份报告的数字都由可重复执行的脚本产出（JMeter 计划与辅助脚本在 `scripts/benchmark/`）。
每份报告都写了实验设计、对照组与已知局限，便于复核。补充或重跑时至少跑 3 轮取中位数，避免单次抖动。
