# 压测报告：并发解锁幂等性（Redisson 分布式锁 + 原子扣减）

## 实验目的

验证高并发下解锁**同一章节**时：
1. **0 重复扣费**（Redisson 锁 + 订单幂等双检）
2. **0 超扣**（`UPDATE ... WHERE coin_balance >= amount` 原子扣减）
3. **0 重复下单**（订单号唯一索引 + 幂等返回）

## 实验设计

- **接口**：`POST /subscribe/unlock`，body `{novelId: 1, chapterId: 1}`
- **用户**：单个测试账号 `user`，初始余额 10000，单章价 5
- **并发**：JMeter 100 线程 × 10 次循环 = 1000 个请求，Ramp-up 0s（瞬时洪峰）
  - 不用 1000 独立线程的原因：Windows 压测机瞬时万级 TCP 建连会耗尽临时端口（实测出现大量 `BindException`/`Connection refused`，压的是本机 TCP 栈而非后端）。100 线程复用 keep-alive 连接，服务端同一章节上的锁竞争强度不变
- **预期**：只应有 **1 次**扣费成功，其余 999 次要么走幂等返回原订单、要么锁等待超时（5s）

## 测试计划

JMeter 测试计划见 `scripts/benchmark/unlock-concurrency.jmx`。

## 实验结果（3 轮取中位数，2026-09-07，Docker 全栈本地环境）

| 指标 | 第 1 轮 | 第 2 轮 | 第 3 轮 | 预期 |
|------|-------|-------|-------|------|
| 总请求数 | 1000 | 1000 | 1000 | 1000 |
| 200（首次 + 幂等返回） | 989 | 960 | 984 | 999 ± 锁超时数 |
| 400（锁等待超时 5s） | 11 | 40 | 16 | 0~999 |
| 200 平均响应时间 | 1226ms | 1416ms | 1151ms | —（锁排队） |
| **t_coin_log 中 UNLOCK 记录数** | **1** | **1** | **1** | **1** |
| **t_subscribe_order 中订单数** | **1** | **1** | **1** | **1** |
| 扣减虚拟币总额 | **5** | **5** | **5** | **5**（单章价） |

**附加极端测试**：调试期间曾因脚本缺陷发生约 **11 万次**请求持续轰炸同一解锁接口（约 5 分钟），事后校验 DB 仍为 1 条 UNLOCK 流水 / 1 个订单 / 余额 9995 —— 幂等保护在远超设计容量下依然成立。

> 200 平均响应 1.2s 的解释：1000 个请求几乎同时到达，全部在 Redisson 锁上排队（首个请求持锁完成「扣币+建单」事务约 50ms，其余等锁释放后依次进入做幂等双检立即返回）。锁等待超时的请求返回 400（SYSTEM_ERROR），前端可安全重试——幂等性保证重试不会重复扣费。

## 关键 SQL 校验

跑完用以下 SQL 验证"零重复"：

```sql
-- 应该只有 1 条 UNLOCK 流水
SELECT COUNT(*) FROM t_coin_log
WHERE user_id = <uid> AND type = 'UNLOCK' AND biz_id = <novelId>;

-- 应该只有 1 个订单
SELECT COUNT(*) FROM t_subscribe_order
WHERE user_id = <uid> AND novel_id = <novelId> AND chapter_id = <chapterId>;

-- 余额应该是 10000 - 5 = 9995
SELECT coin_balance FROM t_user WHERE id = <uid>;
```

## 实现要点与取舍

1. **分布式锁不能完全防超扣**：锁只保证「串行进入临界区」，但若代码先扣币再建单，扣币成功后建单失败仍会超扣。本项目在锁内先做幂等双检（`findPaidOrder`），扣减走 `WHERE coin_balance >= amount` 的原子 UPDATE，两者一起才防住。
2. **为什么不用 `synchronized`**：单机锁在多实例部署下失效；Redisson 跨 JVM 互斥。
3. **锁的粒度**：`lock:subscribe:uid:novel:chapter`，按"用户+书+章"加锁，不同用户/不同书不互斥，避免全局串行。
4. **锁等待超时 5s**：避免线程长时间阻塞拖垮线程池；超时返回 SYSTEM_ERROR 让前端重试，幂等性保证重试安全。
