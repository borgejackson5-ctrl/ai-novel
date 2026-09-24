package com.ainovel.config;

import com.ainovel.common.constant.MqConstant;
import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * RabbitMQ 配置：AI 审核队列 + 死信队列，实现可靠投递与失败重试
 */
@Configuration
public class RabbitMqConfig {

    /**
     * JSON 消息转换器
     */
    @Bean
    public MessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public DirectExchange aiExchange() {
        return new DirectExchange(MqConstant.AI_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange aiDlxExchange() {
        return new DirectExchange(MqConstant.AI_DLX_EXCHANGE, true, false);
    }

    /**
     * 死信队列
     */
    @Bean
    public Queue aiAuditDlxQueue() {
        return QueueBuilder.durable(MqConstant.AI_AUDIT_DLX_QUEUE).build();
    }

    @Bean
    public Binding aiAuditDlxBinding() {
        return BindingBuilder.bind(aiAuditDlxQueue())
                .to(aiDlxExchange())
                .with(MqConstant.AI_AUDIT_DLX_ROUTING_KEY);
    }

    /**
     * 审核队列，配置死信交换机，消费失败 3 次后转入死信队列
     */
    @Bean
    public Queue aiAuditQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", MqConstant.AI_DLX_EXCHANGE);
        args.put("x-dead-letter-routing-key", MqConstant.AI_AUDIT_DLX_ROUTING_KEY);
        return QueueBuilder.durable(MqConstant.AI_AUDIT_QUEUE).withArguments(args).build();
    }

    @Bean
    public Binding aiAuditBinding() {
        return BindingBuilder.bind(aiAuditQueue())
                .to(aiExchange())
                .with(MqConstant.AI_AUDIT_ROUTING_KEY);
    }

    // ==================== AI 全文审查（章节级消息） ====================

    /**
     * 审查死信队列：单章重试耗尽后留痕。
     *
     * <p>不进行自动消费：进入死信表示该章反复失败（多为模型侧持续不可用），
     * 重投只会继续消耗额度。任务侧由消费者将该章记为「审查失败」并结束任务，
     * 作者可立即看到哪几章未审查，而非进度长期停留在 90%。
     */
    @Bean
    public Queue aiReviewDlxQueue() {
        return QueueBuilder.durable(MqConstant.AI_REVIEW_DLX_QUEUE).build();
    }

    @Bean
    public Binding aiReviewDlxBinding() {
        return BindingBuilder.bind(aiReviewDlxQueue())
                .to(aiDlxExchange())
                .with(MqConstant.AI_REVIEW_DLX_ROUTING_KEY);
    }

    /**
     * 全文审查队列，配死信交换机。
     *
     * <p>消费者并发度限制为 1（见 {@code AiReviewConsumer}）：
     * 全书审查为连续几百次调用，同时执行多个任务会触发平台 Key 限流，
     * 也会使按章累加的进度出现跳变。串行处理 + 一章一条消息，
     * 既保证同一任务内逐章推进，也使多个任务之间自然排队。
     */
    @Bean
    public Queue aiReviewQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", MqConstant.AI_DLX_EXCHANGE);
        args.put("x-dead-letter-routing-key", MqConstant.AI_REVIEW_DLX_ROUTING_KEY);
        return QueueBuilder.durable(MqConstant.AI_REVIEW_QUEUE).withArguments(args).build();
    }

    @Bean
    public Binding aiReviewBinding() {
        return BindingBuilder.bind(aiReviewQueue())
                .to(aiExchange())
                .with(MqConstant.AI_REVIEW_ROUTING_KEY);
    }

    // ==================== 搜索同步（小说 -> ES） ====================

    @Bean
    public DirectExchange searchExchange() {
        return new DirectExchange(MqConstant.SEARCH_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange searchDlxExchange() {
        return new DirectExchange(MqConstant.SEARCH_DLX_EXCHANGE, true, false);
    }

    /**
     * 搜索同步死信队列：接收重试耗尽的同步消息，留痕供排查。
     *
     * <p>不进行自动消费：这些消息对应的索引漂移由定时对账按 DB 兜底补正，
     * 此处仅保证失败可被发现，不再参与重投循环。
     */
    @Bean
    public Queue searchSyncDlxQueue() {
        return QueueBuilder.durable(MqConstant.SEARCH_SYNC_DLX_QUEUE).build();
    }

    @Bean
    public Binding searchSyncDlxBinding() {
        return BindingBuilder.bind(searchSyncDlxQueue())
                .to(searchDlxExchange())
                .with(MqConstant.SEARCH_SYNC_DLX_ROUTING_KEY);
    }

    /**
     * 搜索同步队列，配死信交换机：消费者重试达到上限后 nack(requeue=false) 转入死信，
     * 不再无限重投（ES 长期不可用时无限重投会影响日志与 broker）
     */
    @Bean
    public Queue searchSyncQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", MqConstant.SEARCH_DLX_EXCHANGE);
        args.put("x-dead-letter-routing-key", MqConstant.SEARCH_SYNC_DLX_ROUTING_KEY);
        return QueueBuilder.durable(MqConstant.SEARCH_SYNC_QUEUE).withArguments(args).build();
    }

    @Bean
    public Binding searchSyncBinding() {
        return BindingBuilder.bind(searchSyncQueue())
                .to(searchExchange())
                .with(MqConstant.SEARCH_SYNC_ROUTING_KEY);
    }

    // ==================== 章节块同步（章节正文 -> chapter_chunk 向量索引） ====================

    /**
     * 章节块同步死信队列：重试耗尽的块重建消息在这里留痕。
     *
     * <p>与作品同步不同，块同步没有定时对账兜底（对账仅比对 t_novel 与作品索引），
     * 因此该队列的积压是「RAG 检索缺少依据」的可观测痕迹，需定期检查；
     * 全量补正仍使用 admin 的 {@code POST /novel/vector-reindex}。
     */
    @Bean
    public Queue searchChunkDlxQueue() {
        return QueueBuilder.durable(MqConstant.SEARCH_CHUNK_DLX_QUEUE).build();
    }

    @Bean
    public Binding searchChunkDlxBinding() {
        return BindingBuilder.bind(searchChunkDlxQueue())
                .to(searchDlxExchange())
                .with(MqConstant.SEARCH_CHUNK_DLX_ROUTING_KEY);
    }

    /**
     * 章节块同步队列，配死信交换机（与作品同步同一个 DLX，只是路由键不同）
     */
    @Bean
    public Queue searchChunkQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", MqConstant.SEARCH_DLX_EXCHANGE);
        args.put("x-dead-letter-routing-key", MqConstant.SEARCH_CHUNK_DLX_ROUTING_KEY);
        return QueueBuilder.durable(MqConstant.SEARCH_CHUNK_QUEUE).withArguments(args).build();
    }

    @Bean
    public Binding searchChunkBinding() {
        return BindingBuilder.bind(searchChunkQueue())
                .to(searchExchange())
                .with(MqConstant.SEARCH_CHUNK_ROUTING_KEY);
    }
}
