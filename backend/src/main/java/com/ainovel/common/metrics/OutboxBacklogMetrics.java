package com.ainovel.common.metrics;

import com.ainovel.common.mq.MqOutboxStore;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 将「尚未投出的 outbox 消息条数」注册为 Prometheus 指标。
 *
 * <p>该指标单独存在的理由：MQ 不可达时接口仍返回成功（消息已写入本地表），
 * 用户无法察觉异常，日志中也仅有补投任务的一行 warn，而**积压条数曲线会立即上升**。
 * 这是「MQ 故障」唯一能提前暴露的地方。
 *
 * <p>独立为组件而非置于 {@code MqOutboxDispatcher} 内：后者使用手写构造器（需一并创建线程池），
 * 为一项指标修改其依赖会牵动其测试；此处仅需存储层的一次 count。
 */
@Component
@RequiredArgsConstructor
public class OutboxBacklogMetrics {

    private final BusinessMetrics businessMetrics;

    private final MqOutboxStore outboxStore;

    @PostConstruct
    void register() {
        businessMetrics.registerOutboxBacklog(() -> {
            try {
                return outboxStore.countPending();
            } catch (Exception e) {
                // 查询失败返回 -1 而非 0：0 表示「一切正常」，
                // 将「查询失败」也显示为 0 会产生误导，-1 在图表上可直观识别为异常
                return -1;
            }
        });
    }
}
