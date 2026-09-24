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
 * 封面删除 MQ 拓扑声明（独立类，@Bean 显式声明，与消费者 {@code OssDeleteConsumer} 解耦）
 *
 * <p>删除队列配置死信交换机，消费重试耗尽后消息转入 DLX 留痕，供人工/对账兜底清理。
 * exchange / queue 均持久化（durable=true），配合消息持久化实现 broker 侧三级持久化。
 */
@Configuration
public class OssMqConfig {

    @Bean
    public DirectExchange ossExchange() {
        return new DirectExchange(MqConstant.OSS_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange ossDlxExchange() {
        return new DirectExchange(MqConstant.OSS_DLX_EXCHANGE, true, false);
    }

    /** 死信队列 */
    @Bean
    public Queue ossDeleteDlxQueue() {
        return QueueBuilder.durable(MqConstant.OSS_DELETE_DLX_QUEUE).build();
    }

    @Bean
    public Binding ossDeleteDlxBinding() {
        return BindingBuilder.bind(ossDeleteDlxQueue())
                .to(ossDlxExchange())
                .with(MqConstant.OSS_DELETE_DLX_ROUTING_KEY);
    }

    /** 删除队列：配置死信交换机，消费重试耗尽后转死信 */
    @Bean
    public Queue ossDeleteQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", MqConstant.OSS_DLX_EXCHANGE);
        args.put("x-dead-letter-routing-key", MqConstant.OSS_DELETE_DLX_ROUTING_KEY);
        return QueueBuilder.durable(MqConstant.OSS_DELETE_QUEUE).withArguments(args).build();
    }

    @Bean
    public Binding ossDeleteBinding() {
        return BindingBuilder.bind(ossDeleteQueue())
                .to(ossExchange())
                .with(MqConstant.OSS_DELETE_ROUTING_KEY);
    }
}
