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
 * 充值订单超时关单 MQ 拓扑（延迟队列 TTL + DLX，不依赖 x-delayed-message 插件）
 *
 * <p>投递路径：下单后发消息到 {@code pay.order.delay.queue}（无消费者），消息静置
 * {@code x-message-ttl}（15 分钟）后到期转为死信，路由到关单交换机 → 关单队列 →
 * {@code OrderCloseConsumer} 消费，把仍未支付的订单置为已取消。
 *
 * <p>为什么不用 {@code x-delayed-message} 插件：需要额外装插件，且本项目所有超时关单
 * 的延时一致（统一 15 分钟），队列级 TTL + DLX 即可精确满足、风格与既有死信队列一致。
 */
@Configuration
public class PayMqConfig {

    @Bean
    public DirectExchange payDelayExchange() {
        return new DirectExchange(MqConstant.PAY_DELAY_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange payCloseExchange() {
        return new DirectExchange(MqConstant.PAY_CLOSE_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange payCloseDlxExchange() {
        return new DirectExchange(MqConstant.PAY_CLOSE_DLX_EXCHANGE, true, false);
    }

    /** 关单死信队列（消费重试耗尽后留痕兜底） */
    @Bean
    public Queue payCloseDlxQueue() {
        return QueueBuilder.durable(MqConstant.PAY_CLOSE_DLX_QUEUE).build();
    }

    @Bean
    public Binding payCloseDlxBinding() {
        return BindingBuilder.bind(payCloseDlxQueue())
                .to(payCloseDlxExchange())
                .with(MqConstant.PAY_CLOSE_DLX_ROUTING_KEY);
    }

    /**
     * 延迟队列：无消费者，靠 x-message-ttl 到期转死信实现延时
     */
    @Bean
    public Queue payDelayQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-message-ttl", MqConstant.PAY_ORDER_CLOSE_DELAY_MS);
        args.put("x-dead-letter-exchange", MqConstant.PAY_CLOSE_EXCHANGE);
        args.put("x-dead-letter-routing-key", MqConstant.PAY_CLOSE_ROUTING_KEY);
        return QueueBuilder.durable(MqConstant.PAY_DELAY_QUEUE).withArguments(args).build();
    }

    @Bean
    public Binding payDelayBinding() {
        return BindingBuilder.bind(payDelayQueue())
                .to(payDelayExchange())
                .with(MqConstant.PAY_DELAY_ROUTING_KEY);
    }

    /** 关单队列：配置死信交换机，消费重试耗尽后转死信 */
    @Bean
    public Queue payCloseQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", MqConstant.PAY_CLOSE_DLX_EXCHANGE);
        args.put("x-dead-letter-routing-key", MqConstant.PAY_CLOSE_DLX_ROUTING_KEY);
        return QueueBuilder.durable(MqConstant.PAY_CLOSE_QUEUE).withArguments(args).build();
    }

    @Bean
    public Binding payCloseBinding() {
        return BindingBuilder.bind(payCloseQueue())
                .to(payCloseExchange())
                .with(MqConstant.PAY_CLOSE_ROUTING_KEY);
    }
}
