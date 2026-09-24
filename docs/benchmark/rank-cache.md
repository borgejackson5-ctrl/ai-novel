# 压测报告：热门榜单接口（三级缓存）

## 实验目的

验证「Caffeine(L1) + Redis ZSet(L2) + MySQL」三级缓存对榜单接口 QPS 的提升。

## 实验设计

- **接口**：`GET /rank/hot`
- **场景**：榜单已预热（ZSet 有 6 条种子小说），L1 缓存 30~40s 随机过期
- **对照组**：
  - A. 关闭 L1+L2（`hotRank()` 直接 `return queryFromDb()`）
  - B. 只开 L2（注释 L1 的 `localCache.getIfPresent`，ZSet 命中后仍回 DB 查实体）
  - C. 全开 L1+L2（生产配置）
- **工具**：JMeter，100 线程，Ramp-up 10s，持续 60s（`-Dhttp.maxConnections=200`，Java HTTP 实现）
- **环境**：Docker Desktop（WSL2，临时调至 8 vCPU / 4GB），MySQL/Redis/后端同 VM；JMeter 跑在宿主机
- **公平性控制**：A/B/C 三组在 **同一时间窗内背靠背执行**（每组改代码重建后跑 1 轮），排除宿主机长时间负载漂移的干扰

## 测试计划

JMeter 测试计划见 `scripts/benchmark/rank.jmx`，命令行跑：

```bash
jmeter -n -t rank.jmx -Jtoken=<token> -Jport=8081 -Dhttp.maxConnections=200 -l rank.jtl
```

**跑前必读的坑（实测踩过）**：

1. **JMeter HttpClient4 实现与 Docker Desktop 端口代理不复用连接**——每请求新建 TCP，Windows 临时端口（16k 个）约 30 秒耗尽，出现 50%+ `BindException` 假错误。本计划已固定 `HTTPSampler.implementation=Java`（HttpURLConnection，实测复用连接），命令行须带 `-Dhttp.maxConnections=200`（默认 5 不够 100 线程）。
2. **限流必须关闭**：榜单接口有每用户 60 次/分钟限流，不关则全是 400。临时注释 `RankService.hotRank()` 里的 `tryAcquire` 判断，跑完恢复。
3. **WSL2 默认 2 vCPU 是吞吐天花板**（实测 ~280 QPS 饱和，缓存差异被排队延迟淹没），压测前把 `~/.wslconfig` 的 `processors` 临时调到 8 并 `wsl --shutdown`，跑完改回。

## 实操步骤

1. 登录拿 token（后端映射宿主机 8081，Sa-Token 无前缀，直接用返回的 `data.token` 原文）：

```bash
curl -X POST http://localhost:8081/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}'
```

2. 三组对照的代码改法：A 组 `hotRank()` 直接 `return queryFromDb();`；B 组注释 L1 的 `localCache.getIfPresent`；C 组不改（生产配置）。每组改完 `docker compose build backend && docker compose up -d backend` 后跑一次；三组尽量在同时间窗内连续跑完（排除宿主机负载漂移）。

3. **跑完务必恢复代码**：限流和缓存相关改动全部还原（`git diff` 确认无残留），重新构建生产镜像。

## 实验结果（2026-09-08，同时间窗背靠背对照集）

| 组                 | 平均 QPS | p50   | p90   | p99   | 最大    | 错误率 |
| ------------------ | -------- | ----- | ----- | ----- | ------- | ----- |
| A. 直查 MySQL      | 479      | 135ms | 411ms | 892ms | 1739ms  | 0%    |
| B. 只开 L2 (Redis) | 508      | 134ms | 372ms | 789ms | 2375ms  | 0%    |
| C. L1+L2 全开      | **1360** | **61ms** | **117ms** | **194ms** | 627ms | 0% |

补充数据：C 组在冷启动环境（WSL 刚重启、宿主机空闲）曾测得峰值 **2422 QPS / p50 34ms**（60s 内 14.5 万请求）；同配置在宿主机持续负载 40 分钟后降至 828~1360 QPS——共享宿主机的吞吐噪声可达 ±40%，但**三组相对排序与量级差距在任何时段都稳定成立**。

**L1 生效的客观验证**：C 组 60s 内 49679 个请求，MySQL `Questions` 计数器仅增长 172（0.35%，即 L1 每 30~40s 过期触发一次回源重建的量）——其余 99.6% 请求被 L1 挡在 JVM 内，零 Redis 零 MySQL。

## 结论

1. **C 组吞吐为 A/B 的 2.7 倍，p99 快 4.5 倍**：L1 命中 = JVM 内 ConcurrentMap 读取 + JSON 序列化，无任何网络往返。
2. **B ≈ A（508 vs 479）——只开 L2 几乎不涨**，这是个反直觉但重要的实测发现：ZSet 只存 novelId，L2「命中」后仍要拿 ID 列表回 MySQL `selectList` 查实体组装 VO——比直查 DB 反而多一跳 Redis 网络往返。**这套三级缓存真正的跃迁来自 L1 缓存完整组装结果**，而非 L2。
3. **尾延迟被削平**：C 组 p99=194ms 且 max 仅 627ms；A/B 组 p99 近 900ms、max 1.7~2.4s——长尾来自 DB 行锁排队与连接池等待，缓存不仅提吞吐更稳延迟。

## 实现要点

1. **L2 只存 ID 的代价**：ZSet 按 score 排序存 novelId 是为了用原子 `ZINCRBY` 维护阅读量，代价是命中后仍要回源查实体：实测只开 L2 的 QPS 与直查 DB 几乎相同（508 vs 479）。若要 L2 也扛量，应缓存完整 VO 列表的序列化 JSON，这是「缓存什么粒度」的常见权衡。
2. **压测方法论**：① 用 MySQL `Questions` 计数器差值客观验证缓存真的生效（50k 请求只有 172 次查询），避免把噪声当成缓存收益；② 三组对照必须同时间窗背靠背跑，共享宿主机吞吐噪声可达 ±40%；③ Windows + Docker Desktop 下 JMeter 默认 HttpClient4 与端口代理不复用连接，需换 Java 实现 + 调大 `http.maxConnections`。
3. **为什么 L1 命中快**：Caffeine 是 JVM 内 ConcurrentMap，无序列化/网络开销；L2 Redis 走 TCP + RESP 协议。
4. **缓存一致性**：`incrRead` 后立即失效 L1，下次请求回源 L2（ZSet 分数已 +1），保证榜单实时性，最长不一致窗口 = L1 过期时间 30~40s（随机抖动防雪崩）。
5. **为什么不只用 L2**：榜单接口是热点，单 Redis 节点会成为瓶颈；L1 把 99%+ 流量挡在应用进程内。
