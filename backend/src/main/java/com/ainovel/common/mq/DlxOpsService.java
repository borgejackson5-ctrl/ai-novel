package com.ainovel.common.mq;

import com.ainovel.common.code.ErrorCode;
import com.ainovel.common.exception.BusinessException;
import com.ainovel.common.metrics.DlxBacklogMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 死信队列的处置：重放与清空。
 *
 * <p>管理端「消息投递」区块原先只能看到积压条数，真正的处置仍需登录服务器执行
 * {@code rabbitmqctl}，而生产环境并没有预留这样的操作入口。此处把两个动作收进管理端。
 *
 * <p>两个动作的语义差别很大，界面上必须分开、不可混为一谈：
 * <ul>
 *   <li><b>重放</b>：把消息重新投回它原本的 exchange / routing key，等价于把那次业务动作再做一遍。
 *       各消费者都是幂等的，重复投递不会写坏数据，因此这是**优先选用**的处置方式；</li>
 *   <li><b>清空</b>：直接丢弃，不可恢复。只在确认「消息指向的数据已不存在」或「重放也没有意义」时使用。</li>
 * </ul>
 *
 * <p>队列名一律走 {@link DlxBacklogMetrics#DLX_QUEUES} 白名单校验：该参数来自前端，
 * 不校验时一个写错的队列名就能把业务队列（如章节索引同步队列）整个清空。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DlxOpsService {

    /**
     * 单次重放上限。
     *
     * <p>一次点到底会把全部积压同时灌回业务队列，反而可能把它压垮（积压本身往往就是
     * 下游处理不过来造成的）。超出部分留在死信队列里，可再次点击继续。
     */
    private static final int REPLAY_LIMIT = 200;

    /** 死信消息投回原位置所需的信息所在的消息头 */
    private static final String HEADER_DEATH = "x-death";

    private final AmqpAdmin amqpAdmin;

    private final RabbitTemplate rabbitTemplate;

    /**
     * 重放结果。
     *
     * @param replayed 成功投回原位置的条数
     * @param failed   投递失败、已放回死信队列的条数（不会丢消息）
     * @param skipped  缺少原始路由信息、无法判断投往何处的条数（已放回队列）
     */
    public record ReplayResult(int replayed, int failed, int skipped) {
    }

    /**
     * 把指定死信队列里的消息重放回原 exchange / routing key。
     *
     * <p>逐条「取出 → 投回」，取出的同时即被确认；投递失败时**主动放回死信队列**，
     * 因此任何一步失败都不会丢消息（最坏情况是消息留在原队列里，下次再点）。
     *
     * @param queue 死信队列名（必须在白名单内）
     */
    public ReplayResult replay(String queue) {
        requireDlxQueue(queue);
        // 先把消息一次性取到内存，再逐条处理。
        //
        // 不能边取边放回：被放回的消息会在同一轮循环里被再次取到（broker 落队虽快但非同步），
        // 实测表现为「同一条消息被反复取出放回」，跳过条数与队列里的实际条数对不上；
        // 若落队恰好及时，还会一直循环到上限。
        List<Message> batch = new ArrayList<>();
        for (int i = 0; i < REPLAY_LIMIT; i++) {
            Message message = rabbitTemplate.receive(queue);
            if (message == null) {
                break;
            }
            batch.add(message);
        }
        int replayed = 0;
        int failed = 0;
        int skipped = 0;
        for (Message message : batch) {
            Map<String, Object> firstDeath = firstDeath(message);
            String exchange = exchangeOf(firstDeath);
            String routingKey = routingKeyOf(firstDeath);
            if (exchange == null || routingKey == null) {
                // 没有死信信息就无从判断该投回哪里，原样放回，交给人工排查
                requeue(queue, message);
                skipped++;
                continue;
            }
            try {
                rabbitTemplate.send(exchange, routingKey, message);
                replayed++;
            } catch (Exception e) {
                log.warn("重放消息失败，已放回死信队列 queue={}: {}", queue, e.getMessage());
                requeue(queue, message);
                failed++;
            }
        }
        log.info("死信重放完成 queue={} 取出={} 成功={} 失败={} 跳过={}",
                queue, batch.size(), replayed, failed, skipped);
        return new ReplayResult(replayed, failed, skipped);
    }

    /**
     * 清空指定死信队列。
     *
     * @param queue 死信队列名（必须在白名单内）
     * @return 被丢弃的条数；队列为空或不存在时返回 0
     */
    public int purge(String queue) {
        requireDlxQueue(queue);
        var info = amqpAdmin.getQueueInfo(queue);
        int before = info == null ? 0 : info.getMessageCount();
        // 第二个参数 noWait=false：等 broker 确认清空，否则返回值与后续读到的条数可能对不上
        amqpAdmin.purgeQueue(queue, false);
        log.warn("死信队列已清空 queue={} 丢弃 {} 条", queue, before);
        return before;
    }

    /** 队列名白名单校验：拒绝任何非死信队列，避免误清业务队列 */
    private static void requireDlxQueue(String queue) {
        if (queue == null || !DlxBacklogMetrics.DLX_QUEUES.contains(queue)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "不是死信队列： " + queue);
        }
    }

    /**
     * 最早一条死信记录：消息可能被死信多次（重放过又失败），
     * 但投回原位只需要最初那次的 exchange 与 routing key。
     */
    private static Map<String, Object> firstDeath(Message message) {
        Object raw = message.getMessageProperties().getHeaders().get(HEADER_DEATH);
        if (!(raw instanceof List<?> deaths) || deaths.isEmpty()) {
            return Map.of();
        }
        Object first = deaths.get(0);
        return first instanceof Map<?, ?> map ? castMap(map) : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    private static String exchangeOf(Map<String, Object> death) {
        Object exchange = death.get("exchange");
        return exchange instanceof String s && !s.isEmpty() ? s : null;
    }

    /** routing-keys 是数组（一条消息可能匹配多个路由键），取第一个即原始投递用的那个 */
    private static String routingKeyOf(Map<String, Object> death) {
        Object keys = death.get("routing-keys");
        if (keys instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof String s) {
            return s;
        }
        return null;
    }

    /**
     * 放回死信队列：默认 exchange + 队列名作路由键，等价于直接投递到该队列。
     *
     * <p>消息本身的 x-death 头保持不变：若它再次被死信，broker 会追加一条记录，
     * 这正好成为「重放过几次」的痕迹。
     */
    private void requeue(String queue, Message message) {
        try {
            rabbitTemplate.send("", queue, message);
        } catch (Exception e) {
            // 连放回都失败（broker 已不可用）：只能记录，消息本身已随确认丢失，
            // 但此时整个消息链路都已不可用，日志是唯一线索
            log.error("重放失败且无法放回死信队列，消息可能丢失 queue={} messageId={}",
                    queue, message.getMessageProperties().getMessageId(), e);
        }
    }
}
