package com.ainovel.common.metrics;

import com.ainovel.common.constant.MqConstant;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 将「死信队列积压条数」注册为 Prometheus 指标。
 *
 * <p><b>该指标单独存在的理由</b>：死信队列只留痕、不自动消费，只进不出，且无消费者
 * 意味着**没有任何日志或接口会体现其状态**。一类消息持续失败（模型侧长期不可用、ES 不可达）时，
 * 系统表面正常，直至耗尽 broker 的内存或磁盘。broker 一旦转入 blocked，
 * **所有生产者一并被阻塞**，整个业务无法发出消息。该曲线是唯一能提前发现此情况的地方。
 * 此外这几条队列已配置长度上限（见 {@code scripts/apply-mq-policies.sh}），构成双重保险。
 *
 * <p><b>读队列深度必须访问 broker</b>：这几条队列在应用侧无消费者，DB 中亦无对应记录，
 * 当前的积压条数只有 broker 掌握，只能向其查询。7 个队列各发起一次被动声明查询，
 * Prometheus 默认 15 秒抓取一次，即每分钟 28 次轻量查询，开销低于一次业务查询。
 *
 * <p>查询失败返回 -1 而非 0：0 表示「一切正常」。broker 不可达时将「未知」显示为 0，
 * 恰好在最需要告警时产生误导。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DlxBacklogMetrics {

    /**
     * 全部死信队列。**新增死信队列时需同步添加到此列表**，这是唯一的清单，
     * 遗漏的后果是该队列的积压永远不会出现在指标中（而它恰是无人关注的队列）。
     */
    static final List<String> DLX_QUEUES = List.of(
            MqConstant.AI_AUDIT_DLX_QUEUE,
            MqConstant.AI_REVIEW_DLX_QUEUE,
            MqConstant.SEARCH_SYNC_DLX_QUEUE,
            MqConstant.SEARCH_CHUNK_DLX_QUEUE,
            MqConstant.OSS_DELETE_DLX_QUEUE,
            MqConstant.PAY_CLOSE_DLX_QUEUE,
            MqConstant.RANK_READ_DLX_QUEUE);

    private final BusinessMetrics businessMetrics;

    private final AmqpAdmin amqpAdmin;

    @PostConstruct
    void register() {
        businessMetrics.registerDlxBacklog(() -> {
            long total = 0;
            int readable = 0;
            for (String queue : DLX_QUEUES) {
                try {
                    QueueInformation info = amqpAdmin.getQueueInfo(queue);
                    if (info != null) {
                        total += info.getMessageCount();
                        readable++;
                    }
                } catch (Exception e) {
                    // 单个队列查询失败不影响其它队列：broker 抖动时也应尽量给出数值，
                    // 且此处不得向外抛出异常，否则会使整个 /actuator/prometheus 不可用
                    log.debug("读死信队列深度失败 queue={}: {}", queue, e.getMessage());
                }
            }
            // 全部读取失败时返回 -1（表示未知），而非 0（表示一切正常）
            return readable == 0 ? -1 : total;
        });
    }
}
