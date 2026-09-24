package com.ainovel.common.mq;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 全项目唯一的 MQ 投递出口：写库与投递消息在同一事务内完成（outbox 模式）。
 *
 * <p>一条消息的生命周期：
 * <ol>
 *   <li><b>事务内</b>：消息序列化后写入 {@code t_mq_outbox}（待投），与业务数据同一次提交，
 *       二者同时成功或同时失败；</li>
 *   <li><b>提交后</b>：交由 {@link MqOutboxDispatcher} 在独立线程上投递；</li>
 *   <li><b>投递失败</b>：记录保留在表中，由 {@link MqOutboxResendTask} 退避重投，投递成功后才标记为已投。</li>
 * </ol>
 *
 * <p>不在事务的 {@code afterCommit} 回调中直接 {@code convertAndSend}：MQ 不可达时异常会传播给调用方，
 * 而 Spring 的语义是 afterCommit 抛出的异常不改变事务已提交的事实，结果是接口返回 500 而数据已写入
 * （{@code POST /novel/publish} 曾返回 500 而作品与章节已入库，调用方重试将产生重复作品；
 * 下单、保存章节同理），同时消息本身未发出且无记录。
 *
 * <p>上述两个问题均已消除：投递不在请求线程上执行，接口不会假失败（失败仅记表）；
 * 消息持久化在表中，不会丢失。该模式同时覆盖章节块索引、OSS 文件清理、订单关单延迟消息
 * 三条无定时对账兜底的链路（作品→ES 链路自身已有对账任务）。
 *
 * <p>投递必须**在提交之后**：消费者收到消息会回查 MySQL，提交前投递可能使其读到旧数据
 * 或查不到新数据（表现为新发布的书籍漏进 ES 被删文档、审核消息回查为 null 进入死信）。
 * 各调用点见 {@code NovelService} / {@code NovelImportService} / {@code AdminService}。
 *
 * <p>消息可靠性（生产者侧）：每条消息带唯一 {@link org.springframework.amqp.rabbit.connection.CorrelationData}
 * （id 含 outbox 主键与尝试次数），投递后由 confirm 回调感知 broker 是否接收、
 * 由 return 回调感知消息是否因无匹配队列被退回（配合 mandatory=true）。两者失败均留痕告警，
 * 结合 outbox 补投、消费端手动 ack、本地重试与死信队列，实现从生产到消费的闭环。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MqSender {

    private final RabbitTemplate rabbitTemplate;

    private final MqOutboxStore outboxStore;

    private final MqOutboxDispatcher outboxDispatcher;

    /**
     * 初始化消息可靠性回调：confirm（broker 是否接收）与 return（是否可路由）。
     *
     * <p>依赖 application.yaml 开启 publisher-confirm-type=correlated、
     * publisher-returns=true、template.mandatory=true，否则回调不会触发。
     *
     * <p>注意这两条回调只负责**告警**：真正保证消息不丢的是 outbox
     * （投不出去即保留在表中，补投任务会重投）。broker 收到后拒收、或消息无处可路由
     * 这类「投递动作成功但消息未落地」的情况，只能依赖此处日志与对账任务发现。
     */
    @PostConstruct
    public void initReliability() {
        rabbitTemplate.setConfirmCallback((correlationData, ack, cause) -> {
            if (!ack) {
                log.error("MQ 投递未确认(broker 拒收): id={}, cause={}",
                        correlationData == null ? null : correlationData.getId(), cause);
            }
        });
        rabbitTemplate.setReturnsCallback(returned -> {
            log.error("MQ 消息未路由到队列: exchange={}, routingKey={}, replyText={}",
                    returned.getExchange(), returned.getRoutingKey(), returned.getReplyText());
        });
    }

    /**
     * 投递一条消息：消息先写入 outbox，再在事务提交后异步投出。
     *
     * <p>无事务时（少数调用点为自动提交）写入后立即投递，语义不变，
     * 仍是先持久化、再投递。
     *
     * @param exchange   交换机
     * @param routingKey 路由键
     * @param message    消息体（需能被 Jackson 序列化，且类位于 {@code com.ainovel} 下）
     */
    public void sendAfterCommit(String exchange, String routingKey, Object message) {
        MqOutboxRecord row = outboxStore.save(exchange, routingKey, message);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    outboxDispatcher.dispatchAsync(row);
                }
            });
        } else {
            outboxDispatcher.dispatchAsync(row);
        }
        // afterCommit 回调未执行时（线程中断、进程在此期间退出），
        // 该行仍为待投状态，由补投任务兜底，无需额外处理。
    }
}
