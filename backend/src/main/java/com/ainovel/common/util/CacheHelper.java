package com.ainovel.common.util;

import com.ainovel.common.metrics.BusinessMetrics;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 通用 Redis 缓存助手：封装「Cache-Aside + 缓存三兄弟防御」的统一回源模板。
 *
 * <p>不使用 Spring 的 {@code @Cacheable} 的原因：
 * <ul>
 *   <li>{@code @Cacheable} 无防击穿能力：小说详情/章节目录为热点数据，过期瞬间的并发回源会压垮 DB；</li>
 *   <li>精细 TTL（详情 30min / 章节目录 10min / 空值 30s）使用注解需维护多个 cacheManager，实现繁琐；</li>
 *   <li>手写实现可显式表达「空值防穿透 + 互斥锁防击穿 + 随机抖动防雪崩」三项策略。</li>
 * </ul>
 *
 * <p>缓存三大问题的防御方式：
 * <ul>
 *   <li><b>穿透</b>：回源结果为 null（数据不存在）时缓存 {@link #NULL_FLAG} 空标记（短随机 TTL），
 *       后续相同 key 直接命中并返回 null，不再重复回源 DB；</li>
 *   <li><b>击穿</b>：缓存失效回源时使用 Redisson 互斥锁 + double-check，仅单线程回源重建，
 *       其余线程等待后命中新值；取不到锁时降级为直查 DB 以保证可用性；</li>
 *   <li><b>雪崩</b>：写入缓存时在基础 TTL 上叠加随机抖动（±10%），避免批量 key 同时过期而集中回源。</li>
 * </ul>
 *
 * <p>序列化选型：使用 {@link StringRedisTemplate} + 独立 {@link ObjectMapper} 存储纯 JSON（不含
 * {@code @class} 类型信息），反序列化时显式传入 {@link TypeReference}。不使用
 * {@code RedisTemplate<String,Object>} + GenericJackson2JsonRedisSerializer：后者默认向 value 写入
 * {@code @class} 元数据，既增大体积，又暴露「任意类型反序列化」的攻击面（Redis 被攻陷后可构造
 * gadget 链 RCE）。缓存属于内部存储，无需自描述类型，显式类型更安全且更省空间。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CacheHelper {

    /** 空值标记：穿透防护（不存在的数据缓存为 NULL_FLAG，短 TTL 内不重复回源） */
    private static final String NULL_FLAG = "__NULL__";

    /** 互斥锁 key 前缀（击穿防护用） */
    private static final String LOCK_PREFIX = "lock:cache:";

    /** 空值基础 TTL + 随机抖动（穿透 + 雪崩） */
    private static final long NULL_TTL_SECONDS = 30;
    private static final long NULL_JITTER_SECONDS = 10;

    /** 重建锁最大等待时间 */
    private static final long LOCK_WAIT_SECONDS = 3;

    private final StringRedisTemplate stringRedisTemplate;

    private final RedissonClient redissonClient;

    /** 缓存命中 / 未命中指标（命中率跌了就是回源风暴的前兆） */
    private final BusinessMetrics businessMetrics;

    /**
     * 缓存专用 ObjectMapper：独立于 HTTP 层的 Long→String 定制（见 JacksonConfig），
     * 缓存值保留 Long 原样（内部存储，无 JS 精度问题）；LocalDateTime 用 ISO 字符串。
     */
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    /**
     * 从 key 中提取「缓存名」供指标使用：取前两段（{@code novel:detail:123} → {@code novel:detail}）。
     *
     * <p>**必须按前缀聚合，不能以整个 key 作为标签**：否则每个作品、每一章都会成为一条独立
     * 时间序列，指标基数随数据量增长，抓取端将先出现故障。
     */
    private static String cacheName(String key) {
        int first = key.indexOf(':');
        if (first < 0) {
            return key;
        }
        int second = key.indexOf(':', first + 1);
        return second < 0 ? key : key.substring(0, second);
    }

    /**
     * 读缓存：命中返回缓存值（含空值命中返回 null），未命中走 loader 回源并写入缓存。
     *
     * @param key    缓存 key
     * @param type   反序列化目标类型（List 等泛型用 new TypeReference&lt;List&lt;X&gt;&gt;(){} 表达）
     * @param loader 回源函数（返回 null 表示数据不存在，触发空值防穿透）
     * @param ttl    缓存时长
     * @param unit   ttl 单位
     * @param <T>    缓存值类型
     */
    public <T> T get(String key, TypeReference<T> type, Supplier<T> loader, long ttl, TimeUnit unit) {
        String cache = cacheName(key);
        // 1) 先读缓存（含空值标记）
        String json = stringRedisTemplate.opsForValue().get(key);
        if (json != null) {
            if (NULL_FLAG.equals(json)) {
                businessMetrics.cacheAccess(cache, true); // 空值命中也视为拦截了一次回源
                return null; // 空值命中，防穿透
            }
            T cached = readValue(json, type);
            if (cached != null) {
                businessMetrics.cacheAccess(cache, true);
                return cached;
            }
            // 反序列化失败按未命中处理，走回源重建
        }
        businessMetrics.cacheAccess(cache, false);

        // 2) 未命中：互斥锁防击穿，仅单线程回源
        RLock lock = redissonClient.getLock(LOCK_PREFIX + key);
        if (tryLock(lock)) {
            try {
                // double-check：拿锁期间可能已被其他线程重建
                json = stringRedisTemplate.opsForValue().get(key);
                if (json != null) {
                    if (NULL_FLAG.equals(json)) {
                        return null;
                    }
                    T cached = readValue(json, type);
                    if (cached != null) {
                        return cached;
                    }
                }

                T value = loader.get();
                if (value == null) {
                    setNullFlag(key); // 空值缓存，防穿透
                } else {
                    setValue(key, value, ttl, unit); // 随机 TTL 防雪崩
                }
                return value;
            } finally {
                unlockQuietly(lock);
            }
        }

        // 3) 取不到锁：另一线程正在重建。等待期间其很可能已完成写入，
        //    因此先重读一次缓存，仍未命中才降级为直接回源（可用性优先，避免请求被重建阻塞）。
        //    增加这一次读取是必要的：取不到锁通常意味着重建较慢或持锁线程异常，
        //    若此时让所有并发请求直查 DB，一次热点过期即会演变为回源风暴。
        log.warn("缓存重建锁获取失败，转降级路径: key={}", key);
        String latest = stringRedisTemplate.opsForValue().get(key);
        if (NULL_FLAG.equals(latest)) {
            return null;
        }
        if (latest != null) {
            T cached = readValue(latest, type);
            if (cached != null) {
                return cached;
            }
        }
        return loader.get();
    }

    /**
     * 主动失效（写操作后立即删除缓存，保持 Cache-Aside 一致性）。
     */
    public void evict(String key) {
        stringRedisTemplate.delete(key);
    }

    /**
     * 按模式批量失效（如分页缓存 {@code novel:chapter:page:{id}:*}）。使用 SCAN 而非 keys()：
     * {@code keys(pattern)} 复杂度为 O(N) 且阻塞 Redis 单线程，生产库 key 量大时会阻塞主线程；
     * SCAN 通过游标分批迭代，并通过 count 限制每批数量，不阻塞。
     */
    public void evictByPattern(String pattern) {
        Set<String> keys = stringRedisTemplate.execute((RedisCallback<Set<String>>) connection -> {
            Set<String> matched = new HashSet<>();
            ScanOptions options = ScanOptions.scanOptions().match(pattern).count(100).build();
            try (Cursor<byte[]> cursor = connection.scan(options)) {
                while (cursor.hasNext()) {
                    matched.add(new String(cursor.next(), StandardCharsets.UTF_8));
                }
            } catch (Exception e) {
                log.warn("SCAN 失效缓存异常: pattern={}", pattern, e);
            }
            return matched;
        });
        if (keys != null && !keys.isEmpty()) {
            stringRedisTemplate.delete(keys);
        }
    }

    /**
     * 事务提交后再失效 key（无事务时立即失效，等价于 {@link #evict}）。
     *
     * <p><b>不能在事务内直接删除的原因</b>：Cache-Aside 的经典竞态：写请求在事务内删除缓存后
     * **尚未提交**，此时并发的读请求回源读到的是**旧值**，并将该旧值回填至缓存；
     * 写请求提交后，缓存中留存的是脏数据，只能等待 TTL 过期。
     * 将删除操作排入 afterCommit 后，回源必然读到新值。
     *
     * <p>代价：事务回滚时不会删除缓存（此处行为正确：数据未变更，缓存也未变脏）。
     */
    public void evictAfterCommit(String key) {
        afterCommit(() -> evict(key));
    }

    /** 事务提交后按模式批量失效，语义同 {@link #evictByPattern}，时序见 {@link #evictAfterCommit} */
    public void evictByPatternAfterCommit(String pattern) {
        afterCommit(() -> evictByPattern(pattern));
    }

    private void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    private void setValue(String key, Object value, long ttl, TimeUnit unit) {
        long base = unit.toSeconds(ttl);
        // 雪崩防护：基础 TTL 上叠加随机抖动（最多 +10%），避免批量 key 同时过期
        long jitter = ThreadLocalRandom.current().nextLong(Math.max(1, base / 10) + 1);
        stringRedisTemplate.opsForValue().set(key, toJson(value), Duration.ofSeconds(base + jitter));
    }

    private void setNullFlag(String key) {
        long ttl = NULL_TTL_SECONDS + ThreadLocalRandom.current().nextLong(NULL_JITTER_SECONDS + 1);
        stringRedisTemplate.opsForValue().set(key, NULL_FLAG, Duration.ofSeconds(ttl));
        log.debug("缓存回源为空，设置空标记 {}s 内不重复回源: key={}", ttl, key);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("缓存序列化失败: " + e.getMessage(), e);
        }
    }

    private <T> T readValue(String json, TypeReference<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            log.warn("缓存反序列化失败，按未命中处理: {}", e.getMessage());
            return null;
        }
    }

    private boolean tryLock(RLock lock) {
        try {
            return lock.tryLock(LOCK_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void unlockQuietly(RLock lock) {
        try {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        } catch (Exception e) {
            log.warn("缓存重建锁释放失败: {}", e.getMessage());
        }
    }
}
