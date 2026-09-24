package com.ainovel.common.mq;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * MQ 补投任务：重投 {@code t_mq_outbox} 中未投出的消息。
 *
 * <p>该任务是消息不丢失保证的最后一环。上一环是 {@link MqSender}，负责将消息与业务数据
 * 在同一事务内写入数据库；本环负责投递。中间任一步中断（broker 不可用、投递线程未执行、
 * 应用重启、机器断电），消息均保留在表中，由本任务到点重投。
 *
 * <p>正常情况下每轮执行都应**没有待投**。日志中出现「补投 N 条」即表示上游遗漏过一次投递，
 * 需排查该时段 broker 的状态。其机制与搜索索引对账任务（{@code SearchReconcileTask}）相同，
 * 区别在于本任务兜底的是发送侧，对账任务兜底的是接收侧。
 *
 * <p>执行频率高于对账任务（默认 30 秒一次，退避由每条记录自身的 {@code next_retry_at} 控制），
 * 因为「作者刚发布的书搜不到」是作者可立即感知的问题。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MqOutboxResendTask {

    /**
     * 补投任务的分布式锁 key。
     *
     * <p>补投的语义是「将表中待投记录投递一遍」，没有任何机制阻止两个实例同时对同一批记录执行：
     * 双方各自 {@code findDue} 会取到相同的行并各投一次，造成消息重复（消费端幂等，结果正确，
     * 但存在一次无效投递），且双方会并发更新 attempts 与 next_retry_at。
     * 单机 compose 部署不会触发，多实例或本地 IDE 与服务同时运行时会出现。
     */
    private static final String RESEND_LOCK_KEY = "lock:mq:outbox:resend";

    private final MqOutboxStore outboxStore;

    private final MqOutboxDispatcher dispatcher;

    private final RedissonClient redissonClient;

    /** 一轮最多补投几条 */
    @Value("${app.mq.outbox.batch-size:200}")
    private int batchSize = 200;

    @Scheduled(fixedDelayString = "${app.mq.outbox.resend-interval-ms:30000}")
    public void resend() {
        RLock lock = redissonClient.getLock(RESEND_LOCK_KEY);
        if (!tryLock(lock)) {
            // 取不到锁并非异常：另一实例正在执行，该批记录已由其处理。
            // 不等待（等待只会占用调度线程），30 秒后的下一轮会再次执行。
            log.debug("MQ 补投：另一个实例正在执行，本轮跳过");
            return;
        }
        try {
            doResend();
        } finally {
            unlockQuietly(lock);
        }
    }

    private void doResend() {
        try {
            List<MqOutboxRecord> due = outboxStore.findDue(batchSize);
            int ok = 0;
            for (MqOutboxRecord row : due) {
                if (dispatcher.send(row)) {
                    ok++;
                }
            }
            int purged = outboxStore.purgeSent();
            if (!due.isEmpty()) {
                // 待投非空即为异常信号：说明正常投递路径遗漏过消息，输出条数便于判断规模
                log.warn("MQ 补投：本轮待投 {} 条，成功 {} 条（出现待投说明正常投递漏过消息，"
                        + "应检查 broker 与投递线程日志）", due.size(), ok);
            }
            if (purged > 0) {
                log.info("MQ 补投：清理已投记录 {} 条", purged);
            }
        } catch (Exception e) {
            // 后台任务，所有异常仅记录不外抛；未投成的记录下一轮会再次尝试
            log.error("MQ 补投任务异常", e);
        }
    }

    /**
     * 取不到锁不视为失败，返回 false 使本轮跳过。
     *
     * <p>使用 {@code tryLock(0, SECONDS)}：等待 0 秒，租期由 Redisson 看门狗自动续期
     * （显式指定租期时，任务执行时间超过租期会提前释放锁，另一实例随即进入并投递同一批消息）。
     */
    private boolean tryLock(RLock lock) {
        try {
            return lock.tryLock(0, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            // Redis 不可用：补投**不放行**，与限流的 fail-open 相反。
            // 放行的后果是多实例并发投递同一批消息，而消息仍保留在表中，
            // 跳过一轮不会有任何损失。
            log.warn("MQ 补投：取锁失败，本轮跳过（redis 不可用？）: {}", e.toString());
            return false;
        }
    }

    private void unlockQuietly(RLock lock) {
        try {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        } catch (Exception e) {
            // 释放失败时锁会在租期到期后自然失效，下一次补投不受影响
            log.warn("MQ 补投：释放锁失败: {}", e.toString());
        }
    }
}
