package com.ainovel.config;

import com.ainovel.common.constant.MqConstant;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * 阅读上报 MQ 拓扑（novel 写库后通知 rank 更新热度）
 *
 * <p>使用消息而非直接调用的原因：阅读量存储于 {@code t_novel}（novel 的表），热度存储于
 * Redis ZSet（rank 的）。同一次阅读需写入两处，但「谁写谁的表」不应被打破，
 * 否则 rank 反向写入 novel 的表将构成反向依赖。通过一条消息拆分两件事：
 * novel 写入自己的库后结束，rank 收到通知后仅操作自己的 Redis。
 *
 * <p>队列配置了死信交换机，消费重试耗尽后消息转入 DLX 留痕。但热度为派生指标，
 * 丢失仅意味着榜单少一次加分，下一次重建即可补回，因此此处仅保证失败可被发现，
 * 不再像搜索同步那样另配定时对账。
 */
@Configuration
public class RankMqConfig {

    @Bean
    public DirectExchange rankReadExchange() {
        return new DirectExchange(MqConstant.RANK_READ_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange rankReadDlxExchange() {
        return new DirectExchange(MqConstant.RANK_READ_DLX_EXCHANGE, true, false);
    }

    /** 死信队列：只留痕，不自动消费 */
    @Bean
    public Queue rankReadDlxQueue() {
        return QueueBuilder.durable(MqConstant.RANK_READ_DLX_QUEUE).build();
    }

    @Bean
    public Binding rankReadDlxBinding() {
        return BindingBuilder.bind(rankReadDlxQueue())
                .to(rankReadDlxExchange())
                .with(MqConstant.RANK_READ_DLX_ROUTING_KEY);
    }

    @Bean
    public Queue rankReadQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", MqConstant.RANK_READ_DLX_EXCHANGE);
        args.put("x-dead-letter-routing-key", MqConstant.RANK_READ_DLX_ROUTING_KEY);
        return QueueBuilder.durable(MqConstant.RANK_READ_QUEUE).withArguments(args).build();
    }

    @Bean
    public Binding rankReadBinding() {
        return BindingBuilder.bind(rankReadQueue())
                .to(rankReadExchange())
                .with(MqConstant.RANK_READ_ROUTING_KEY);
    }
}
