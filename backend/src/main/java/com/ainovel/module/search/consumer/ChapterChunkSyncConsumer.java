package com.ainovel.module.search.consumer;

import com.ainovel.common.constant.MqConstant;
import com.ainovel.common.exception.BusinessException;
import com.ainovel.common.message.ChapterChunkSyncMessage;
import com.ainovel.module.search.service.ChapterVectorService;
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
 * 章节块同步消费者：将「章节正文已变更」事件应用到 {@code chapter_chunk} 向量索引。
 *
 * <p>该层的必要性：索引此前仅支持 admin 手动重建，作者新写的章节不会进入索引，
 * 而检索不到是静默的：不报错，只是跨章核对缺少一层依据。
 *
 * <p>可靠性设计与 {@link SearchSyncConsumer} 一致，两处刻意保持相同：
 * <ol>
 *   <li>以 MySQL 为唯一事实源：不信任消息内容，仅取 id 回查章节正文
 *       （审查读取的是 {@code Chapter.currentBody()}，索引必须使用同一份口径，
 *       否则会出现「工具读取待审稿、RAG 读取旧正文」的不一致）。</li>
 *   <li>幂等且与顺序无关：块主键为 {@code novelId-chapterId-seq} 业务键，
 *       重复消费即为覆盖；多条消息乱序也读取同一份当前正文。</li>
 *   <li>手动 ack + 退避重试 + 转死信：ES 或向量服务短暂抖动时退避重投，
 *       达到上限后进入死信队列留痕，不无限重投。</li>
 *   <li>配置或参数类失败不重投：{@link BusinessException} 重投多次仍为同一错误
 *       （典型为未配置向量模型的 Key），直接 ack 并记录 WARN；重投只会使日志刷满，
 *       并阻塞队列中后续章节的处理。</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChapterChunkSyncConsumer {

    /** 失败重投前退避时间（毫秒） */
    private static final long REQUEUE_BACKOFF_MS = 1000L;

    /** 单条消息最大重试次数，超过则转死信（可由人工通过 /novel/vector-reindex 补录） */
    private static final int MAX_RETRY = 5;

    /** 重试计数 key 前缀：按章节维度计，1 小时自动过期 */
    private static final String RETRY_KEY_PREFIX = "search:chunk:retry:";

    private final ChapterVectorService chapterVectorService;

    private final StringRedisTemplate stringRedisTemplate;

    @RabbitListener(queues = MqConstant.SEARCH_CHUNK_QUEUE)
    public void onMessage(ChapterChunkSyncMessage message, Channel channel,
                          @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        Long chapterId = message == null ? null : message.getChapterId();
        Long novelId = message == null ? null : message.getNovelId();
        if (chapterId == null || novelId == null) {
            // 消息体不完整时字段将始终反序列化为 null，进入死信也无法修复，因此直接丢弃并留痕
            log.warn("章节块同步消息缺少 id，已丢弃：novelId={} chapterId={}", novelId, chapterId);
            channel.basicAck(deliveryTag, false);
            return;
        }
        try {
            int chunks = chapterVectorService.indexChapter(novelId, chapterId);
            log.info("章节块同步完成：novelId={} chapterId={} 共 {} 块", novelId, chapterId, chunks);
            clearRetry(chapterId);
            channel.basicAck(deliveryTag, false);
        } catch (BusinessException e) {
            // 配置或参数类错误重投无意义（例如未配置向量模型的 Key），直接 ack，避免阻塞队列
            log.warn("章节块同步因配置/参数问题跳过（不重投）：novelId={} chapterId={} 原因={}",
                    novelId, chapterId, e.getMessage());
            clearRetry(chapterId);
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            int retried = bumpRetry(chapterId);
            if (retried >= MAX_RETRY) {
                log.error("章节块同步连续失败 {} 次，转入死信队列（该章检索不到，"
                        + "需要时用 POST /novel/vector-reindex?novelId={} 全量重建）: chapterId={}",
                        retried, novelId, chapterId, e);
                clearRetry(chapterId);
                // requeue=false → 走队列上配的死信交换机
                channel.basicNack(deliveryTag, false, false);
                return;
            }
            log.error("章节块同步失败（第 {} 次），{}ms 后重投: novelId={} chapterId={}",
                    retried, REQUEUE_BACKOFF_MS, novelId, chapterId, e);
            sleepQuietly(REQUEUE_BACKOFF_MS);
            channel.basicNack(deliveryTag, false, true);
        }
    }

    /** 累加一次重试计数 */
    private int bumpRetry(Long chapterId) {
        try {
            String key = RETRY_KEY_PREFIX + chapterId;
            Long count = stringRedisTemplate.opsForValue().increment(key);
            // TTL 仅在计数从 0 变为 1 时设置一次（与 SearchSyncConsumer 一致）：
            // 无条件 expire 会使「1 小时自动过期」变为「持续失败则永不过期」。
            if (count != null && count == 1L) {
                stringRedisTemplate.expire(key, Duration.ofHours(1));
            }
            return count == null ? 1 : count.intValue();
        } catch (Exception e) {
            // 计数读取失败不应使消息直接进入死信：按第一次处理，仍可继续重投若干轮
            log.warn("读取章节块同步重试计数失败，按首次处理: chapterId={}", chapterId);
            return 1;
        }
    }

    /** 清理重试计数（成功后、跳过时、或转死信时） */
    private void clearRetry(Long chapterId) {
        try {
            stringRedisTemplate.delete(RETRY_KEY_PREFIX + chapterId);
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
