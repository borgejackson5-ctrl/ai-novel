package com.ainovel.common.mq;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * MQ 出站消息表的读写（{@code t_mq_outbox}）。
 *
 * <p>使用 JdbcTemplate 而非 MyBatis-Plus，原因有三：
 * <ol>
 *   <li>该表不是业务表，不需要实体映射、逻辑删除与分页，仅有 6 条固定 SQL；</li>
 *   <li>它必须位于 {@code common/mq}：投递出口 {@link MqSender} 在此包内，
 *       而 {@code @MapperScan} 仅扫描 {@code com.ainovel.module.**.dao}，
 *       为该表单独开一个模块并补一层 SPI 端口，成本大于收益；</li>
 *   <li>反之将 Mapper 放入某个 module 会使 {@code common} 依赖 {@code module}，
 *       形成架构守护测试所拦截的环（{@link com.ainovel.ModularityTests}）。</li>
 * </ol>
 *
 * <p>表中不含 {@code is_deleted}：该记录仅有「待投/已投/放弃」三种状态，
 * 已投记录按天清理（见 {@link #purgeSent()}）。手写 SQL 本身不触发逻辑删除注入，
 * 不保留该字段可减少一处误解。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MqOutboxStore {

    /** 待投 */
    public static final int STATUS_PENDING = 0;
    /** 已投 */
    public static final int STATUS_SENT = 1;
    /** 放弃：重试用尽，需人工处理（日志记 ERROR） */
    public static final int STATUS_DEAD = 2;

    private final JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper;

    /** 已投记录保留天数（由补投任务一并清理，避免表无限增长） */
    @Value("${app.mq.outbox.retain-days:7}")
    private int retainDays = 7;

    /**
     * 写入一条待投消息。**在业务事务内调用**，与业务数据同一事务提交。
     *
     * @return 完整的 outbox 记录（含 id），交由 {@link MqOutboxDispatcher} 投递
     */
    public MqOutboxRecord save(String exchange, String routingKey, Object message) {
        long id = IdWorker.getId();
        // 去重标识必须在**序列化之前**写入消息体：投递时使用的是下方这份 JSON
        // （MqOutboxDispatcher 按 payloadType 反序列化后投递），序列化后再修改对象已无效。
        // 未实现 OutboxIdAware 的消息类型跳过，该链路维持原样。
        if (message instanceof OutboxIdAware aware) {
            aware.setOutboxId(id);
        }
        LocalDateTime now = LocalDateTime.now();
        String payloadType = message.getClass().getName();
        String payload;
        try {
            payload = objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            // 无法序列化的消息**必须使调用方失败**（事务随之回滚）。写入表中等待补投没有意义，
            // 重投不会成功，只会积压队列。与消费端「配置类错误不重投」判据一致。
            throw new IllegalStateException("MQ 消息序列化失败，已中止本次操作：" + payloadType, e);
        }
        jdbcTemplate.update("""
                INSERT INTO t_mq_outbox
                    (id, exchange, routing_key, payload_type, payload, status, attempts,
                     next_retry_at, create_time)
                VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?)
                """, id, exchange, routingKey, payloadType, payload, STATUS_PENDING, now, now);
        return new MqOutboxRecord(id, exchange, routingKey, payloadType, payload, 0);
    }

    /**
     * 尚未投递出去的消息条数（供 Prometheus 积压指标使用；每次实时查询）。
     *
     * <p>查询条件为 {@code status = 0}：投递成功改为 1、重试用尽改为 2，
     * 因此状态为 0 即为等待投递。该值持续不为 0 表示 MQ 链路存在异常。
     */
    public int countPending() {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_mq_outbox WHERE status = ?", Integer.class, STATUS_PENDING);
        return n == null ? 0 : n;
    }

    /** 标记投递成功 */
    public void markSent(long id) {
        jdbcTemplate.update("""
                UPDATE t_mq_outbox SET status = ?, sent_time = ?, last_error = NULL WHERE id = ?
                """, STATUS_SENT, LocalDateTime.now(), id);
    }

    /** 记录一次失败并安排下次重投 */
    public void markFailed(long id, int attempts, LocalDateTime nextRetryAt, String error) {
        jdbcTemplate.update("""
                UPDATE t_mq_outbox SET attempts = ?, next_retry_at = ?, last_error = ? WHERE id = ?
                """, attempts, nextRetryAt, truncate(error), id);
    }

    /** 重试用尽：标记为放弃，等待人工处理（不删除，保留以排查原因） */
    public void markDead(long id, int attempts, String error) {
        jdbcTemplate.update("""
                UPDATE t_mq_outbox SET status = ?, attempts = ?, last_error = ? WHERE id = ?
                """, STATUS_DEAD, attempts, truncate(error), id);
    }

    /** 取到达重投时间的待投消息（按 id 升序，先写入的先投） */
    public List<MqOutboxRecord> findDue(int limit) {
        return jdbcTemplate.query("""
                SELECT id, exchange, routing_key, payload_type, payload, attempts
                  FROM t_mq_outbox
                 WHERE status = ? AND next_retry_at <= ?
                 ORDER BY id
                 LIMIT ?
                """, (rs, i) -> new MqOutboxRecord(
                        rs.getLong("id"), rs.getString("exchange"), rs.getString("routing_key"),
                        rs.getString("payload_type"), rs.getString("payload"), rs.getInt("attempts")),
                STATUS_PENDING, LocalDateTime.now(), limit);
    }

    /** 清理已投递超过保留期的记录，返回删除行数 */
    public int purgeSent() {
        return jdbcTemplate.update("""
                DELETE FROM t_mq_outbox WHERE status = ? AND sent_time < ?
                """, STATUS_SENT, LocalDateTime.now().minusDays(Math.max(retainDays, 1)));
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= 500 ? s : s.substring(0, 500);
    }
}
