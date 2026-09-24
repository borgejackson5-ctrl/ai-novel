package com.ainovel.module.search.consumer;

import com.ainovel.common.constant.MqConstant;
import com.ainovel.common.message.SearchSyncMessage;
import com.ainovel.module.search.service.SearchService;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;

/**
 * 小说搜索同步消费者：消费 DB 变更消息，增量同步到 Elasticsearch
 *
 * <p>可靠性设计（一致性保障主要落在该层与定时对账上）：
 * <ol>
 *   <li>以 MySQL 为唯一事实源：消费时不信任消息中的数据，仅取 novelId 回查最新记录。
 *       消息仅负责通知有变更，不承载状态；否则消息一旦乱序，旧数据会被写回索引。</li>
 *   <li>同步逻辑与对账共用（{@link SearchService#syncOne}）：可见性口径只有一份实现，
 *       两条路径不会产生分歧。「可搜索、访问返回 404」多源于口径不统一。</li>
 *   <li>不可见即删除：下架 / 审核驳回的作品必须从索引移除。</li>
 *   <li>手动 ack + 退避重试 + 重试上限：ES 短暂不可用时退避重投；
 *       连续失败达到上限后 nack(requeue=false) 转入死信，不再无限重投。
 *       长期故障期间的重投空转只会同时拖垮日志与 broker。</li>
 * </ol>
 *
 * <p>即使该层全部失效（消息丢失或进入死信无人处理），仍有定时对账按 DB 兜底。
 * 最终一致成立的原因是该兜底机制的存在，而非偶然。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SearchSyncConsumer {

    /** 失败重投前退避时间（毫秒），避免 ES 故障期间高频空转重投 */
    private static final long REQUEUE_BACKOFF_MS = 1000L;

    /** 单条消息最大重试次数，超过则转死信，交由定时对账补正 */
    private static final int MAX_RETRY = 5;

    /** 重试计数 key 前缀：按作品维度计，1 小时自动过期，不残留 */
    private static final String RETRY_KEY_PREFIX = "search:sync:retry:";

    private final SearchService searchService;

    private final StringRedisTemplate stringRedisTemplate;

    @RabbitListener(queues = MqConstant.SEARCH_SYNC_QUEUE)
    public void onMessage(SearchSyncMessage message, Channel channel,
                          @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        Long novelId = message.getNovelId();
        try {
            boolean indexed = searchService.syncOne(novelId);
            log.info("搜索同步{}: novelId={}", indexed ? "写入" : "移除", novelId);
            clearRetry(novelId);
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            int retried = bumpRetry(novelId);
            if (retried >= MAX_RETRY) {
                log.error("搜索同步连续失败 {} 次，转入死信队列（后续由定时对账补正）: novelId={}, operation={}",
                        retried, novelId, message.getOperation(), e);
                clearRetry(novelId);
                // requeue=false → 走队列上配的死信交换机
                channel.basicNack(deliveryTag, false, false);
                return;
            }
            log.error("搜索同步失败（第 {} 次），{}ms 后重投: novelId={}, operation={}",
                    retried, REQUEUE_BACKOFF_MS, novelId, message.getOperation(), e);
            sleepQuietly(REQUEUE_BACKOFF_MS);
            channel.basicNack(deliveryTag, false, true);
        }
    }

    /** 累加一次重试计数 */
    private int bumpRetry(Long novelId) {
        try {
            String key = RETRY_KEY_PREFIX + novelId;
            Long count = stringRedisTemplate.opsForValue().increment(key);
            // TTL 仅在计数从 0 变为 1 时设置一次。
            // 无条件 expire 会在每次失败时续期至 1 小时，使「1 小时自动过期，不残留」
            // 变为「持续失败则永不过期」：
            // 反复失败的消息会在 Redis 中残留一个无法清除的 key。
            // 与 FixedWindowRateLimiter 的 Lua、VerifyCodeServiceImpl 的尝试计数采用同一判据。
            if (count != null && count == 1L) {
                stringRedisTemplate.expire(key, Duration.ofHours(1));
            }
            return count == null ? 1 : count.intValue();
        } catch (Exception e) {
            // 计数读取失败不应使消息直接进入死信：按第一次处理，仍可继续重投若干轮
            log.warn("读取搜索同步重试计数失败，按首次处理: novelId={}", novelId);
            return 1;
        }
    }

    /** 清理重试计数（成功后或转死信时） */
    private void clearRetry(Long novelId) {
        try {
            stringRedisTemplate.delete(RETRY_KEY_PREFIX + novelId);
        } catch (Exception ignored) {
            // 删除失败仅残留一个会自动过期的 key，不影响正确性
        }
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
