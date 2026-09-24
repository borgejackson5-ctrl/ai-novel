package com.ainovel.common.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * outbox 消息的实际投递者：在独立线程上投递，投递失败不向业务调用方抛出异常。
 *
 * <p>不使用 {@code afterCommit} 同步投递，原因有二，其二是关键：
 * <ol>
 *   <li>同步投递会把 broker 的往返时间计入接口耗时（broker 阻塞即接口阻塞）；</li>
 *   <li>在 {@code afterCommit} 中无法写库：此时事务已提交，但连接仍绑定在当前线程上
 *       （Spring 需等 {@code cleanupAfterCompletion} 才释放），
 *       因此 {@code UPDATE ... SET status='已投'} 会执行在一条 autoCommit=false、
 *       且不再提交的连接上，连接归还连接池时该更新被回滚，标记**静默丢失**，
 *       补投任务随后将这批消息**再投一次**。</li>
 * </ol>
 * 改为独立线程后不存在事务绑定，标记即为一次普通的自动提交更新。
 *
 * <p>**投递语义为 at-least-once。**「已投出但标记未写入数据库」（进程在两步之间退出）
 * 会导致重投一次，因此消息的消费者必须幂等，项目内各消费者均按此前提实现
 * （作品与块索引为覆盖写，审查任务按章节唯一索引判重）。唯一例外是阅读计数这类
 * 「收到即 +1」的链路，重投会使其多计一次，该代价可接受。
 */
@Slf4j
@Component
public class MqOutboxDispatcher {

    /** 退避起点：第一次失败后 10 秒重投 */
    static final int BASE_BACKOFF_SECONDS = 10;
    /** 退避封顶：最多等 5 分钟 */
    static final int MAX_BACKOFF_SECONDS = 300;
    /**
     * 允许反序列化的包名前缀。
     *
     * <p>重投需按存储的类名还原对象，等价于按库中字符串反射加载类。
     * 库中内容均由本项目写入，但前缀校验成本极低，
     * 与项目内「意图缓存需过白名单」的做法一致。
     */
    private static final String ALLOWED_TYPE_PREFIX = "com.ainovel.";

    private static final AtomicLong THREAD_SEQ = new AtomicLong();

    private final MqOutboxStore outboxStore;

    private final RabbitTemplate rabbitTemplate;

    private final ObjectMapper objectMapper;

    /** 重试超过该次数后放弃（放弃的记录保留在表中、日志记 ERROR，需人工处理） */
    @Value("${app.mq.outbox.max-attempts:8}")
    private int maxAttempts = 8;

    private final ExecutorService pool;

    /**
     * 手写构造器（不使用 {@code @RequiredArgsConstructor}）：需同时创建投递线程池。
     *
     * <p>线程池规模刻意取小：出站消息为「秒级几十条」的量级，投递本身开销很低（本地 broker 亚毫秒）。
     * 队列满时**不阻塞、不丢弃**，消息已在表中，直接交由补投任务处理，不会丢失。
     * 线程设为守护线程，避免阻塞应用退出。
     */
    public MqOutboxDispatcher(MqOutboxStore outboxStore, RabbitTemplate rabbitTemplate,
                              ObjectMapper objectMapper) {
        this.outboxStore = outboxStore;
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.pool = new ThreadPoolExecutor(2, 4, 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(500),
                r -> {
                    Thread t = new Thread(r, "mq-outbox-" + THREAD_SEQ.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                });
    }

    /**
     * 提交后触发：异步投递一条消息，不向调用方抛出异常。
     * 抛出异常会导致「接口报错但数据已写入」（见 {@link MqSender} 的说明）。
     */
    public void dispatchAsync(MqOutboxRecord row) {
        try {
            pool.execute(() -> send(row));
        } catch (RejectedExecutionException e) {
            log.warn("MQ 出站队列已满，这条消息交给补投任务：outboxId={}, rk={}",
                    row.id(), row.routingKey());
        }
    }

    /**
     * 投递一条消息（同步，供补投任务与单测直接调用）。
     *
     * <p>本方法不抛出异常：所有失败均在内部写入表中（记录 attempts、下次重投时间与
     * 失败原因），返回 false 仅用于日志与断言。
     */
    public boolean send(MqOutboxRecord row) {
        Object payload;
        try {
            payload = deserialize(row);
        } catch (Exception e) {
            // 消息体无法还原（类已删除、JSON 损坏、类型不在白名单）属于确定性错误，重投不会成功，
            // 直接判定为放弃投递，避免在表中反复重试。
            markDead(row, "消息体无法还原：" + e.getMessage());
            return false;
        }
        int attempt = row.attempts() + 1;
        try {
            // correlationId 携带 outbox 主键与尝试次数：confirm 回调打日志时可直接定位到该行
            rabbitTemplate.convertAndSend(row.exchange(), row.routingKey(), payload,
                    new CorrelationData(row.id() + "#" + attempt));
        } catch (Exception e) {
            // 投递确实失败：记录失败并按退避策略安排下次重投
            if (attempt >= maxAttempts) {
                markDead(row, "投递 " + attempt + " 次仍未成功：" + e);
            } else {
                LocalDateTime next = LocalDateTime.now().plusSeconds(backoffSeconds(attempt));
                try {
                    outboxStore.markFailed(row.id(), attempt, next, e.toString());
                } catch (Exception markError) {
                    // 记录失败的操作也失败（数据库异常）：仅记录日志。该行仍为待投状态，
                    // 补投任务下一轮会再次尝试，消息不会丢失，仅退避时间未更新。
                    log.error("MQ 投递失败后写回流控信息也失败：outboxId={}", row.id(), markError);
                }
                log.warn("MQ 投递失败（第 {} 次，{} 秒后重投）：outboxId={}, exchange={}, rk={}, cause={}",
                        attempt, backoffSeconds(attempt), row.id(), row.exchange(), row.routingKey(), e.toString());
            }
            return false;
        }

        // 执行到此说明消息已提交给 broker，下面仅做状态标记。
        //
        // 该标记刻意与上方的发送分离：若二者同处一个 try 块，标记失败仍会累加 attempts，
        // 一条实际已投出的消息会被累加至上限并最终标记为「放弃投递」，
        // 运维据此排查「哪条消息未发出」得到的结论是错的。
        // 分离后标记失败仅记录 ERROR，行仍为待投，下一轮补投会再发一次；
        // 消费者幂等，符合本类声明的 at-least-once 语义。
        try {
            outboxStore.markSent(row.id());
        } catch (Exception e) {
            log.error("消息已投出但标记失败，下一轮补投会重复投递（消费端幂等）: "
                    + "outboxId={}, exchange={}, rk={}", row.id(), row.exchange(), row.routingKey(), e);
        }
        return true;
    }

    /** 指数退避：10s、20s、40s… 封顶 5 分钟 */
    static int backoffSeconds(int attempts) {
        int shift = Math.min(Math.max(attempts, 1) - 1, 6);
        return Math.min(BASE_BACKOFF_SECONDS << shift, MAX_BACKOFF_SECONDS);
    }

    private Object deserialize(MqOutboxRecord row) throws Exception {
        if (!row.payloadType().startsWith(ALLOWED_TYPE_PREFIX)) {
            throw new IllegalArgumentException("消息类型不在允许的包名下：" + row.payloadType());
        }
        Class<?> type = Class.forName(row.payloadType());
        return objectMapper.readValue(row.payload(), type);
    }

    private void markDead(MqOutboxRecord row, String reason) {
        log.error("MQ 消息放弃投递（需人工处理）：outboxId={}, exchange={}, rk={}, 原因：{}",
                row.id(), row.exchange(), row.routingKey(), reason);
        try {
            outboxStore.markDead(row.id(), row.attempts() + 1, reason);
        } catch (Exception e) {
            log.error("标记放弃投递失败：outboxId={}", row.id(), e);
        }
    }

    @PreDestroy
    void shutdown() {
        pool.shutdownNow();
    }
}
