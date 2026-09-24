package com.ainovel.common.metrics;

import com.ainovel.common.constant.MqConstant;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.QueueInformation;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 死信队列积压指标。
 *
 * <p>该指标的重点不在「加得是否正确」，而在查不到时显示什么：
 * 死信队列是唯一没有任何界面、没有消费者、也没有日志的组件，指标是它唯一的出口。
 * 若 broker 连不上时它显示 0（含义为一切正常），则恰好会在最该报警的时候给出错误的正常信号。
 * 因此下面有一半用例在约束这一点。
 */
@ExtendWith(MockitoExtension.class)
class DlxBacklogMetricsTest {

    @Mock
    private AmqpAdmin amqpAdmin;

    private SimpleMeterRegistry registry;

    private DlxBacklogMetrics metrics;

    @BeforeEach
    void init() {
        registry = new SimpleMeterRegistry();
        metrics = new DlxBacklogMetrics(new BusinessMetrics(registry), amqpAdmin);
    }

    private double gaugeValue() {
        return registry.get("ainovel.mq.dlx.backlog").gauge().value();
    }

    @Test
    @DisplayName("把 7 个死信队列的条数合计成一个值")
    void sumsAllDlxQueues() {
        when(amqpAdmin.getQueueInfo(anyString())).thenAnswer(invocation -> {
            String queue = invocation.getArgument(0);
            // 其中一个队列积压 2 条，其余为 0
            return new QueueInformation(queue, MqConstant.SEARCH_CHUNK_DLX_QUEUE.equals(queue) ? 2 : 0, 0);
        });

        metrics.register();

        // Gauge 是惰性的：注册时不取值，读取时才调用 supplier。
        // 因此「读一次」必须在 verify 之前，否则观察到的是「零次交互」
        double value = gaugeValue();

        assertEquals(2d, value, 0.0001);
        verify(amqpAdmin, times(DlxBacklogMetrics.DLX_QUEUES.size())).getQueueInfo(anyString());
    }

    @Test
    @DisplayName("一个队列都读不到（broker 连不上）→ -1，绝不能显示成 0")
    void allQueuesUnreadableIsMinusOne() {
        when(amqpAdmin.getQueueInfo(anyString())).thenReturn(null);

        metrics.register();

        assertEquals(-1d, gaugeValue(), 0.0001,
                "0 的含义是「一切正常」。broker 连不上时显示 0，"
                        + "等于在最该报警的时候给人一个假安心");
    }

    @Test
    @DisplayName("单个队列查失败不影响其余队列：能读到的照样合计")
    void oneQueueFailingDoesNotHideTheRest() {
        when(amqpAdmin.getQueueInfo(anyString())).thenAnswer(invocation -> {
            String queue = invocation.getArgument(0);
            if (MqConstant.AI_REVIEW_DLX_QUEUE.equals(queue)) {
                throw new IllegalStateException("这个队列刚被删");
            }
            return new QueueInformation(queue, 1, 0);
        });

        metrics.register();

        assertEquals((double) (DlxBacklogMetrics.DLX_QUEUES.size() - 1), gaugeValue(), 0.0001);
    }

    /**
     * 守门测试：新增死信队列时必须同时加入指标清单。
     *
     * <p>遗漏的后果特别隐蔽：该队列的积压永远不会出现在指标中，
     * 而它恰恰属于「没人看、没人消费、没有日志」的那类队列。
     * 因此这里直接扫描 {@code MqConstant} 中所有 {@code *_DLX_QUEUE} 常量做比对。
     */
    @Test
    @DisplayName("MqConstant 里每个 *_DLX_QUEUE 都必须在指标清单里 —— 新增死信队列不会静默漏检")
    void everyDlxQueueConstantIsMonitored() throws Exception {
        List<String> declared = new ArrayList<>();
        for (Field field : MqConstant.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class
                    && field.getName().endsWith("_DLX_QUEUE")) {
                declared.add((String) field.get(null));
            }
        }

        assertTrue(declared.size() >= 7, "至少应有 7 个死信队列常量，实际：" + declared);
        List<String> missing = new ArrayList<>(declared);
        missing.removeAll(DlxBacklogMetrics.DLX_QUEUES);
        assertTrue(missing.isEmpty(),
                "这些死信队列没有被指标覆盖（积压了也没人看得见）：" + missing);
    }
}
