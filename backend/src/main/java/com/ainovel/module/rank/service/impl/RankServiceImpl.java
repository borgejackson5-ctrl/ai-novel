package com.ainovel.module.rank.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.ainovel.common.enums.RankTypeEnum;
import com.ainovel.common.enums.SerialStatusEnum;
import com.ainovel.common.exception.RateLimitException;
import com.ainovel.common.ratelimit.FixedWindowRateLimiter;
import com.ainovel.common.util.IpUtil;
import com.ainovel.common.util.LoginUserUtil;
import com.ainovel.module.category.service.CategoryService;
import com.ainovel.module.novel.domain.NovelVisibility;
import com.ainovel.module.novel.domain.entity.Novel;
import com.ainovel.module.novel.domain.vo.NovelVO;
import com.ainovel.module.novel.service.NovelService;
import com.ainovel.module.novel.spi.NovelCollectCounter;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import com.ainovel.module.rank.service.RankService;

/**
 * 热门榜单服务：Caffeine(L1) + Redis ZSet(L2) + MySQL 构成的**三级缓存** + Lua 限流 + 缓存三防
 *
 * <p>层级口径：本项目统一称其为「三级缓存」，即 L1 本地缓存、L2 分布式缓存、L3 数据库兜底
 * （README、`docs/benchmark/rank-cache.md` 及压测对照表均按 L1/L2/L3 分组）。
 * 本类注释此前写作「二级缓存 + MySQL」，与该口径不一致，现已对齐。
 *
 * <p>缓存链路：Caffeine(L1) -> Redis ZSet(L2) -> MySQL
 * <p>缓存一致性：写操作更新 DB 后同步维护 ZSet 分数
 *
 * <p>缓存三项异常防御：
 * <ul>
 *   <li><b>穿透</b>：DB/ZSet 都为空时设置 Redis 空标记（随机 TTL），命中直接返回空，不重复回源 DB</li>
 *   <li><b>击穿</b>：热点缓存失效回源 DB 时用 Redisson 互斥锁，仅单线程重建，其余降级/等待</li>
 *   <li><b>雪崩</b>：L1 本地缓存用 Caffeine Expiry 随机过期（30~40s），空标记 TTL 也随机，避免批量同时过期</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RankServiceImpl implements RankService {

    /** 榜单缓存的 key 前缀：完整 key = 前缀 + 类型码，如 novel:rank:hot */
    private static final String RANK_KEY_PREFIX = "novel:rank:";
    private static final String EMPTY_MARK_SUFFIX = ":empty";
    private static final String REBUILD_LOCK_PREFIX = "lock:rank:rebuild:";
    private static final int TOP_N = 20;

    /**
     * 榜单 ZSet 的存活时间。
     *
     * <p>ZSet 是「只增不减」的结构：新上架的书要能被加进来、下架的书要能退出去，
     * 就不能让它永久命中缓存。给它一个 TTL，到期后下次请求会回源 DB 重建。
     */
    private static final long RANK_ZSET_TTL_MINUTES = 10;

    private static String rankKey(RankTypeEnum type) {
        return RANK_KEY_PREFIX + type.getCode();
    }

    private static String emptyMarkKey(RankTypeEnum type) {
        return rankKey(type) + EMPTY_MARK_SUFFIX;
    }

    private static String rebuildLockKey(RankTypeEnum type) {
        return REBUILD_LOCK_PREFIX + type.getCode();
    }

    /** 计数限流窗口（秒） */
    private static final int RATE_LIMIT_WINDOW_SECONDS = 60;

    /** L1 基础过期秒数 + 随机抖动上限（雪崩防护） */
    private static final long L1_BASE_SECONDS = 30;
    private static final long L1_JITTER_SECONDS = 10;

    /** 空标记基础 TTL + 随机抖动（穿透 + 雪崩） */
    private static final long EMPTY_MARK_BASE_TTL = 20;
    private static final long EMPTY_MARK_JITTER = 20;

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 限流用公共组件（{@code common/ratelimit}）。
     *
     * <p>原实现自行维护一份 Lua 脚本与 key 前缀（{@code novel:rate:}），与接口级限流构成
     * **同一逻辑的两份实现**：两份 Lua 意味着两处可能被修改而不一致（例如「TTL 仅在计数
     * 从 0 变为 1 时设置」这一判据，遗漏会使某个维度被永久限制）。现共用同一份实现。
     *
     * <p>未改为 {@code @RateLimit} 注解：注解的限额为编译期常量，而榜单限额需可通过
     * {@code novel.rank-rate-limit} 调整，该接口是最容易被刷的读接口。
     */
    private final FixedWindowRateLimiter rateLimiter;

    /**
     * 计数限流阈值（次/分钟），可通过 novel.rank-rate-limit 配置
     *
     * <p>字段默认值 60 保证单测（无 Spring 容器）下同样生效
     */
    @Value("${novel.rank-rate-limit:60}")
    private int rankRateLimit = 60;

    private final NovelService novelService;

    private final NovelCollectCounter novelCollectCounter;

    private final CategoryService categoryService;

    private final RedissonClient redissonClient;

    /** 本地缓存 L1（随机过期，防雪崩） */
    private Cache<String, List<NovelVO>> localCache;

    @PostConstruct
    public void init() {
        localCache = Caffeine.newBuilder()
                .maximumSize(100)
                .expireAfter(new Expiry<String, List<NovelVO>>() {
                    @Override
                    public long expireAfterCreate(String key, List<NovelVO> value, long currentTime) {
                        // 30s + 随机 0~10s，避免批量缓存同时过期引发雪崩
                        long seconds = L1_BASE_SECONDS + ThreadLocalRandom.current().nextLong(L1_JITTER_SECONDS + 1);
                        return TimeUnit.SECONDS.toNanos(seconds);
                    }

                    @Override
                    public long expireAfterUpdate(String key, List<NovelVO> value, long currentTime, long currentDuration) {
                        return currentDuration;
                    }

                    @Override
                    public long expireAfterRead(String key, List<NovelVO> value, long currentTime, long currentDuration) {
                        return currentDuration;
                    }
                })
                .build();
    }

    /**
     * 获取榜单。四种榜共用同一条链路，只有回源 SQL 的排序口径不同。
     */
    public List<NovelVO> rank(RankTypeEnum type) {
        String cacheKey = rankKey(type);
        // 游客同样可查看榜单（只读、不含个人数据），但限流维度不能因此缺失：
        // 登录用户按 userId 计数，未登录按客户端 IP 计数，否则游客路径等同不限流
        Long userId = LoginUserUtil.getUserIdOrNull();
        String rateDimension = userId != null ? String.valueOf(userId) : "ip:" + IpUtil.getClientIp();
        // 1. 限流。复用接口级限流组件，key 前缀与之统一，不再单独维护一份 Lua
        if (!rateLimiter.tryAcquire("rank", rateDimension, rankRateLimit, RATE_LIMIT_WINDOW_SECONDS)) {
            throw new RateLimitException(RATE_LIMIT_WINDOW_SECONDS);
        }

        // 2. 查询 L1 本地缓存（随机过期，防雪崩）
        List<NovelVO> cached = localCache.getIfPresent(cacheKey);
        if (cached != null) {
            return cached;
        }

        // 3. 穿透防护：DB 无数据时命中空标记，直接返回空，不重复回源
        if (isEmptyMark(type)) {
            return List.of();
        }

        // 4. 查询 L2 Redis ZSet
        List<NovelVO> result = queryFromRedis(type);
        if (result != null && !result.isEmpty()) {
            localCache.put(cacheKey, result);
            return result;
        }

        // 5. 击穿防护：互斥锁，只允许一个请求回源 DB 重建
        RLock lock = redissonClient.getLock(rebuildLockKey(type));
        boolean locked = tryLock(lock);
        if (locked) {
            try {
                // double-check：拿到锁后可能已被其他线程重建
                result = queryFromRedis(type);
                if (result != null && !result.isEmpty()) {
                    localCache.put(cacheKey, result);
                    return result;
                }

                result = queryFromDb(type);
                if (result == null || result.isEmpty()) {
                    // 穿透防护：DB 也空，设空标记（随机 TTL，防雪崩）
                    setEmptyMark(type);
                    return List.of();
                }

                rebuildRedisRank(result, type);
                clearEmptyMark(type);
                localCache.put(cacheKey, result);
                return result;
            } finally {
                lock.unlock();
            }
        }

        // 拿不到锁：降级兜底直接查 DB，保证可用性（牺牲一点一致性）
        log.warn("榜单重建锁获取失败，降级直查 DB: type={}", type.getCode());
        result = queryFromDb(type);
        return result == null ? List.of() : result;
    }

    /** 热门榜（兼容旧接口） */
    public List<NovelVO> hotRank() {
        return rank(RankTypeEnum.HOT);
    }

    /**
     * 热度 +1：仅更新 Redis（热门榜 ZSet）。
     *
     * <p>阅读量由 novel 模块写入其自身表，此处仅负责将「发生了一次阅读」反映到榜单，
     * 即各模块维护自身数据，两件事通过 MQ 解耦，模块依赖保持单向。
     *
     * <p>不做去重：去重（同一用户 24h 内只计一次）已在发布方完成，消息为同一次阅读的产物。
     * 消费端最多重复投递一次，热度可能多加 1；热度为派生指标，该偏差可接受。
     *
     * <p>**仅在榜单已存在时加分**：incrementScore 在 key 不存在时会一并创建该 key，
     * 且**不设置 TTL**，此时 ZSet 会长期停留在「仅一个成员」的状态：
     * rank() 中 queryFromRedis 见非空即返回，不会进入重建分支，
     * 设置 TTL 的语句也不会执行，ZSet 因而永不过期，榜单固定不动。
     * 榜单不存在时不执行任何操作，交由下一次 rank() 回源 DB 重建，此时会包含本次阅读量。
     */
    public void incrHot(Long novelId) {
        String hotKey = rankKey(RankTypeEnum.HOT);
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(hotKey))) {
            stringRedisTemplate.opsForZSet().incrementScore(hotKey, novelId.toString(), 1);
        }
        // 失效本地缓存以触发下次回源重建；已有阅读数据，清除空标记
        localCache.invalidate(hotKey);
        clearEmptyMark(RankTypeEnum.HOT);
    }

    private boolean tryLock(RLock lock) {
        try {
            return lock.tryLock(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private boolean isEmptyMark(RankTypeEnum type) {
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(emptyMarkKey(type)));
    }

    private void setEmptyMark(RankTypeEnum type) {
        long ttl = EMPTY_MARK_BASE_TTL + ThreadLocalRandom.current().nextLong(EMPTY_MARK_JITTER + 1);
        stringRedisTemplate.opsForValue().set(emptyMarkKey(type), "1", Duration.ofSeconds(ttl));
        log.warn("榜单 {} 回源为空，设置空标记 {}s 内不重复回源", type.getCode(), ttl);
    }

    private void clearEmptyMark(RankTypeEnum type) {
        stringRedisTemplate.delete(emptyMarkKey(type));
    }

    private List<NovelVO> queryFromRedis(RankTypeEnum type) {
        Set<String> ids = stringRedisTemplate.opsForZSet().reverseRange(rankKey(type), 0, TOP_N - 1);
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        // 逐条转换而非 Long::valueOf：ZSet 成员为字符串，单条非数字成员会使整个榜单接口
        // 抛出 NumberFormatException 并返回 500，所有用户无法查看榜单，而原因仅为一条脏数据
        // （手工写入、或历史版本写入过其他格式）。跳过并剔除该成员，读路径不应受数据质量影响。
        List<Long> idList = new ArrayList<>();
        for (String raw : ids) {
            try {
                idList.add(Long.valueOf(raw));
            } catch (NumberFormatException e) {
                log.warn("榜单 ZSet 里出现非数字成员，已剔除: type={}, member={}", type.getCode(), raw);
                stringRedisTemplate.opsForZSet().remove(rankKey(type), raw);
            }
        }
        if (idList.isEmpty()) {
            return List.of();
        }
        // 可见性统一使用 NovelVisibility：原实现仅判断 status=1，未包含审核口径，
        // 被驳回/下架的作品只要仍在 ZSet 中（TTL 窗口内）就会被榜单展示，点击后却被拦截。
        // 榜单与书库、搜索必须对「何种内容可对外」保持一致。
        List<Novel> novels = novelService.listVisibleByIds(idList);
        // 按 ZSet 顺序返回
        Map<Long, Novel> novelMap = novels.stream()
                .collect(java.util.stream.Collectors.toMap(Novel::getId, d -> d, (a, b) -> a));
        return buildVoList(idList, novelMap);
    }

    /**
     * 回源 DB 取榜。四种榜的可见性口径一致（已上架 + 审核通过或变更待审），只有排序不同。
     */
    private List<NovelVO> queryFromDb(RankTypeEnum type) {
        if (type == RankTypeEnum.COLLECT) {
            // 收藏数需聚合书架数据，仅书架侧可提供：通过收藏端口获取 id，
            // 再由 novel 模块取整行（返回的 id 已可见，此处再次应用可见性口径是幂等的）
            List<Long> ids = novelCollectCounter.topNovelIdsByCollect(TOP_N);
            if (ids.isEmpty()) {
                return List.of();
            }
            List<Novel> novels = novelService.listVisibleByIds(ids);
            Map<Long, Novel> map = novels.stream()
                    .collect(java.util.stream.Collectors.toMap(Novel::getId, d -> d, (a, b) -> a));
            return buildVoList(ids, map);
        }

        List<Novel> novels = switch (type) {
            // 新书榜：雪花 ID 单调递增，按 id 倒序即上架时间倒序，同时走主键索引
            case NEW -> novelService.listVisibleLatest(TOP_N);
            case FINISHED -> novelService.listVisibleFinishedHottest(TOP_N);
            default -> novelService.listVisibleHottest(TOP_N);
        };
        return novels.stream().map(d -> {
            NovelVO vo = BeanUtil.copyProperties(d, NovelVO.class);
            vo.setCategoryName(categoryService.getNameMap().get(d.getCategoryId()));
            vo.setSerialStatusText(SerialStatusEnum.textOf(d.getSerialStatus()));
            return vo;
        }).toList();
    }

    /**
     * 将榜单写入 ZSet，其为三级缓存中的 **L2（第二级）**：L1 为进程内 Caffeine，L3 为 MySQL。
     * 「二级」指**层级序号**，并非表示整个缓存方案只有两级（整体为三级，见类注释）。
     *
     * <p>热门榜的 score 使用真实阅读量，由 {@code incrRead} 实时累加，以保证榜单实时性。
     * 其余三种榜无此实时增量，score 以「倒序位次」表示名次，保证 ZSet 读出的顺序正确
     * （若同样使用阅读量，新书榜/收藏榜的顺序会被阅读量覆盖）。
     *
     * <p>**必须为 ZSet 设置 TTL**：该结构只增不减，未设置过期时一旦建成便永远命中缓存，
     * 后续上架的新书、下架的老书均无法进出，榜单会长期停留在第一版。
     */
    private void rebuildRedisRank(List<NovelVO> result, RankTypeEnum type) {
        String key = rankKey(type);
        for (int i = 0; i < result.size(); i++) {
            NovelVO vo = result.get(i);
            double score = type == RankTypeEnum.HOT
                    ? (vo.getReadCount() == null ? 0 : vo.getReadCount())
                    : (double) (TOP_N - i);
            stringRedisTemplate.opsForZSet().add(key, vo.getId().toString(), score);
        }
        stringRedisTemplate.expire(key, Duration.ofMinutes(RANK_ZSET_TTL_MINUTES));
    }

    private List<NovelVO> buildVoList(List<Long> idList, Map<Long, Novel> novelMap) {
        Map<Long, String> names = categoryService.getNameMap();
        List<NovelVO> list = new ArrayList<>();
        for (Long id : idList) {
            Novel d = novelMap.get(id);
            if (d == null) {
                continue;
            }
            NovelVO vo = BeanUtil.copyProperties(d, NovelVO.class);
            vo.setCategoryName(names.get(d.getCategoryId()));
            list.add(vo);
        }
        return list;
    }
}
