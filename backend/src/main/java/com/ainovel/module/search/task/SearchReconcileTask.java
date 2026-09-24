package com.ainovel.module.search.task;

import com.ainovel.module.search.domain.vo.ReconcileResultVO;
import com.ainovel.module.search.service.SearchReconcileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 搜索索引定时对账
 *
 * <p>作为兜底机制：MQ 链路的可靠投递与消费重试处理正常异常，本任务处理消息确实丢失的情况。
 * 正常情况下每次执行均为零漂移、零修复；一旦日志出现「发现漂移并已修复」，
 * 说明上游曾漏过一次，需进一步检查（死信队列是否堆积、消费者是否超时转死信）。
 *
 * <p>执行时间安排在低峰期（默认每小时第 17 分），避免与整点的定时任务及业务高峰冲突。
 * 全程捕获异常：对账失败不得影响任何业务请求。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SearchReconcileTask {

    /**
     * 对账任务的分布式锁 key。
     *
     * <p>对账执行的是「全量拉取 DB id + 全量拉取索引 id + 逐条修复」，没有机制阻止两个实例同时执行：
     * 两个实例各自全量查询一次 DB、流式扫描一次索引，再分别写入或删除。
     * 对账本身幂等（覆盖写、按 id 删除），因此不会破坏数据，但重复执行一次全量的开销较大；
     * 单机 compose 环境下不易察觉，多实例或本地 IDE 与服务同时运行时即可出现。
     */
    private static final String RECONCILE_LOCK_KEY = "lock:search:reconcile";

    private final SearchReconcileService searchReconcileService;

    private final RedissonClient redissonClient;

    /**
     * @see SearchReconcileService#reconcile()
     */
    @Scheduled(cron = "${search.reconcile.cron:0 17 * * * ?}")
    public void reconcile() {
        RLock lock = redissonClient.getLock(RECONCILE_LOCK_KEY);
        if (!tryLock(lock)) {
            log.debug("搜索索引对账：另一个实例正在执行，本轮跳过");
            return;
        }
        try {
            doReconcile();
        } finally {
            unlockQuietly(lock);
        }
    }

    private void doReconcile() {
        try {
            ReconcileResultVO result = searchReconcileService.reconcile();
            if (Boolean.TRUE.equals(result.getSkipped())) {
                log.error("搜索索引对账因安全阀跳过修复，需人工确认: 应有 {} 条 / 实际 {} 条",
                        result.getExpectedCount(), result.getIndexedCount());
            } else if (Boolean.FALSE.equals(result.getConsistent())) {
                log.warn("搜索索引对账发现漂移并已修复: 补写 {} 条、移除 {} 条（说明上游漏过一次，"
                                + "请检查死信队列与消费者日志）",
                        result.getRepairedCount(), result.getRemovedCount());
            }
        } catch (Exception e) {
            // 对账是后台任务，任何异常都只记录不外抛
            log.error("搜索索引对账任务异常", e);
        }
    }

    /**
     * 获取不到锁不属于异常：另一个实例正在执行，本轮已由其覆盖。
     *
     * <p>使用 {@code tryLock(0, SECONDS)}：不等待（等待只会占用调度线程），
     * 租期交由 Redisson 看门狗自动续期；对账可能执行几十秒，显式设置短租期会提前释放锁。
     */
    private boolean tryLock(RLock lock) {
        try {
            return lock.tryLock(0, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            // Redis 不可用：跳过本轮而不强行执行。对账是幂等的兜底任务，
            // 延后一轮没有损失（下一小时仍会执行），而多实例同时执行全量对账存在开销
            log.warn("搜索索引对账：取锁失败，本轮跳过: {}", e.toString());
            return false;
        }
    }

    private void unlockQuietly(RLock lock) {
        try {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        } catch (Exception e) {
            // 释放失败仅使锁等待租期自然过期，下一轮照常执行
            log.warn("搜索索引对账：释放锁失败: {}", e.toString());
        }
    }
}
