package com.ainovel.module.rank.service;

import com.ainovel.common.code.ErrorCode;
import com.ainovel.common.enums.RankTypeEnum;
import com.ainovel.common.exception.BusinessException;
import com.ainovel.common.ratelimit.FixedWindowRateLimiter;
import com.ainovel.common.util.LoginUserUtil;
import com.ainovel.module.category.service.CategoryService;
import com.ainovel.module.novel.service.NovelService;
import com.ainovel.module.novel.spi.NovelCollectCounter;
import com.ainovel.module.novel.domain.entity.Novel;
import com.ainovel.module.novel.domain.vo.NovelVO;
import com.ainovel.module.rank.service.impl.RankServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 热门榜单服务单测：覆盖 Lua 限流、三级缓存回源链路
 *
 * <p>缓存链路：Caffeine(L1) → Redis ZSet(L2) → MySQL
 * <p>重点验证：
 * <ul>
 *   <li>限流命中直接拒绝，绝不查缓存/DB</li>
 *   <li>L1 命中时不打 L2/DB（验证"本地缓存挡量"的核心价值）</li>
 *   <li>L2 空时回源 MySQL，并把结果重建回 ZSet、写 L1</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class RankServiceTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private FixedWindowRateLimiter rateLimiter;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private ZSetOperations<String, String> zSetOps;
    @Mock
    private NovelService novelService;
    @Mock
    private NovelCollectCounter novelCollectCounter;
    @Mock
    private CategoryService categoryService;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RLock lock;

    private RankService rankService;

    @BeforeEach
    void init() {
        rankService = new RankServiceImpl(stringRedisTemplate, rateLimiter, novelService, novelCollectCounter, categoryService, redissonClient);
        // 手动触发 @PostConstruct 初始化本地 Caffeine 缓存
        rankService.init();
        lenient().when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOps);
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        lenient().when(redissonClient.getLock(anyString())).thenReturn(lock);
    }

    @Test
    @DisplayName("限流命中 → 抛 RATE_LIMIT，绝不查 Redis/DB")
    void hotRank_rateLimited() {
        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserIdOrNull).thenReturn(1L);
            // Lua 脚本返回 0 → 限流触发
            when(rateLimiter.tryAcquire(eq("rank"), anyString(), eq(60), eq(60)))
                    .thenReturn(false);

            BusinessException ex = assertThrows(BusinessException.class,
                    rankService::hotRank);
            assertEquals(ErrorCode.RATE_LIMIT, ex.getErrorCode());

            // 关键断言：限流后不得触碰缓存与 DB
            verifyNoInteractions(zSetOps, novelService, categoryService);
        }
    }

    @Test
    @DisplayName("L1 命中 → 直接返回，不打 L2/DB（验证本地缓存挡量价值）")
    void hotRank_l1Hit() {
        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserIdOrNull).thenReturn(1L);
            when(rateLimiter.tryAcquire(eq("rank"), anyString(), eq(60), eq(60)))
                    .thenReturn(true); // 放行

            // 预置 L1 缓存（模拟上一轮已写入）
            List<NovelVO> preloaded = List.of(buildVo(100L, "榜单1"));
            // 反射注入 L1
            injectLocalCache(preloaded);

            List<NovelVO> result = rankService.hotRank();

            assertEquals(1, result.size());
            assertEquals(100L, result.get(0).getId());
            // 关键：L1 命中后，L2/DB 一次都不能调
            verify(zSetOps, never()).reverseRange(anyString(), anyLong(), anyLong());
            verify(novelService, never()).listVisibleHottest(anyInt());
        }
    }

    @Test
    @DisplayName("L2 空 → 回源 MySQL TopN，重建 ZSet 并写 L1")
    void hotRank_l2Empty_fallbackDb() throws InterruptedException {
        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserIdOrNull).thenReturn(1L);
            when(rateLimiter.tryAcquire(eq("rank"), anyString(), eq(60), eq(60)))
                    .thenReturn(true); // 放行
            // L2 返回空 → 触发回源
            when(zSetOps.reverseRange(anyString(), eq(0L), eq(19L)))
                    .thenReturn(Set.of());
            // 击穿防护：拿到重建锁
            when(lock.tryLock(3, TimeUnit.SECONDS)).thenReturn(true);
            // DB TopN
            Novel d1 = buildNovel(101L, 1, 1000L, "仙尊归来");
            Novel d2 = buildNovel(102L, 1, 900L, "炼气三千层");
            when(novelService.listVisibleHottest(anyInt())).thenReturn(List.of(d1, d2));
            lenient().when(categoryService.getNameMap())
                    .thenReturn(Map.of(1L, "玄幻修真", 3L, "古装仙侠"));

            List<NovelVO> result = rankService.hotRank();

            // 1. 返回 DB 顺序的 TopN
            assertEquals(2, result.size());
            assertEquals(101L, result.get(0).getId());
            // 2. 关键：把 DB 结果重建回 ZSet（每条都 add 一次）
            verify(zSetOps, times(2)).add(anyString(), anyString(), anyDouble());
            verify(zSetOps).add(anyString(), eq("101"), eq(1000d));
            verify(zSetOps).add(anyString(), eq("102"), eq(900d));
            // 3. DB 只查一次（不是每条都查）
            verify(novelService, times(1)).listVisibleHottest(anyInt());
        }
    }

    @Test
    @DisplayName("L2 命中 → 按 ZSet 顺序回填 VO，不打 DB")
    void hotRank_l2Hit() {
        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserIdOrNull).thenReturn(1L);
            when(rateLimiter.tryAcquire(eq("rank"), anyString(), eq(60), eq(60)))
                    .thenReturn(true); // 放行
            // L2 返回有序 ID 集（LinkedHashSet 保序）
            Set<String> ids = new LinkedHashSet<>();
            ids.add("101");
            ids.add("102");
            when(zSetOps.reverseRange(anyString(), eq(0L), eq(19L))).thenReturn(ids);
            // ZSet 中只有 id，整行数据需回查 novel：这一步是「L2 命中」与「回源 DB」的分界
            Novel d1 = buildNovel(101L, 1, 1000L, "仙尊归来");
            Novel d2 = buildNovel(102L, 1, 900L, "炼气三千层");
            when(novelService.listVisibleByIds(anyList())).thenReturn(List.of(d1, d2));
            when(categoryService.getNameMap()).thenReturn(Map.of(1L, "玄幻修真"));

            List<NovelVO> result = rankService.hotRank();

            // 按 ZSet 返回的顺序 101 在前
            assertEquals(2, result.size());
            assertEquals(101L, result.get(0).getId());
            assertEquals(102L, result.get(1).getId());
            // 关键：L2 命中后不应重建 ZSet（避免无谓写操作）
            verify(zSetOps, never()).add(anyString(), anyString(), anyDouble());
            // 下面这条不可省略。若未 stub listVisibleByIds，queryFromRedis 会返回空、
            // 被当作「L2 也为空」，用例会顺着「击穿防护 → 拿不到重建锁 → 降级直查 DB」走完：
            // 断言全部通过，但验证的是另一件事（补上该 stub 后本用例立刻失败）。
            // 这正是「能通过但没有验证到目标行为」的测试。
            verify(novelService, never()).listVisibleHottest(anyInt());
        }
    }

    @Test
    @DisplayName("ZSet 里混进非数字成员 → 跳过它并顺手剔除，榜单接口不能因此 500")
    void hotRank_zsetGarbageMember_skippedAndRemoved() {
        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserIdOrNull).thenReturn(1L);
            when(rateLimiter.tryAcquire(eq("rank"), anyString(), eq(60), eq(60)))
                    .thenReturn(true); // 放行
            Set<String> ids = new LinkedHashSet<>();
            ids.add("101");
            ids.add("not-a-number");   // 脏成员：手工塞过、或历史版本写过别的格式
            when(zSetOps.reverseRange(anyString(), eq(0L), eq(19L))).thenReturn(ids);
            when(novelService.listVisibleByIds(anyList()))
                    .thenReturn(List.of(buildNovel(101L, 1, 1000L, "仙尊归来")));
            when(categoryService.getNameMap()).thenReturn(Map.of(1L, "玄幻修真"));

            List<NovelVO> result = rankService.hotRank();

            // 若使用 Long::valueOf：一条脏成员就会让整个榜单返回 500，所有用户都看不到榜，
            // 而原因只是某一条数据。现在跳过它，其余成员照常返回
            assertEquals(1, result.size());
            assertEquals(101L, result.get(0).getId());
            verify(zSetOps).remove(anyString(), eq("not-a-number"));
        }
    }

    @Test
    @DisplayName("穿透防护：空标记命中 → 返回空，不打 L2/DB")
    void hotRank_emptyMarkHit() {
        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserIdOrNull).thenReturn(1L);
            when(rateLimiter.tryAcquire(eq("rank"), anyString(), eq(60), eq(60)))
                    .thenReturn(true); // 放行
            // 空标记命中（DB 无数据时 Redis 已缓存空标记）
            when(stringRedisTemplate.hasKey("novel:rank:hot:empty")).thenReturn(true);

            List<NovelVO> result = rankService.hotRank();

            assertTrue(result.isEmpty());
            // 关键：穿透命中后不得查 L2/DB
            verify(zSetOps, never()).reverseRange(anyString(), anyLong(), anyLong());
            verify(novelService, never()).listVisibleHottest(anyInt());
        }
    }

    @Test
    @DisplayName("击穿防护：重建锁获取失败 → 降级直查 DB")
    void hotRank_lockFailFallbackDb() throws InterruptedException {
        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserIdOrNull).thenReturn(1L);
            when(rateLimiter.tryAcquire(eq("rank"), anyString(), eq(60), eq(60)))
                    .thenReturn(true); // 放行
            when(zSetOps.reverseRange(anyString(), eq(0L), eq(19L))).thenReturn(Set.of());
            // 拿不到重建锁 → 走降级分支
            when(lock.tryLock(3, TimeUnit.SECONDS)).thenReturn(false);
            Novel d1 = buildNovel(101L, 1, 1000L, "仙尊归来");
            when(novelService.listVisibleHottest(anyInt())).thenReturn(List.of(d1));
            when(categoryService.getNameMap()).thenReturn(Map.of(1L, "玄幻修真"));

            List<NovelVO> result = rankService.hotRank();

            assertEquals(1, result.size());
            assertEquals(101L, result.get(0).getId());
            // 降级直查 DB 一次
            verify(novelService, times(1)).listVisibleHottest(anyInt());
            // 降级路径不重建 ZSet
            verify(zSetOps, never()).add(anyString(), anyString(), anyDouble());
        }
    }

    @Test
    @DisplayName("热度 +1 → 榜单已存在时给 ZSet 加分并清空标记（榜单实时性）")
    void incrHot_rankExists_addsScore() {
        // 榜单已存在，才谈得上「实时加分」
        when(stringRedisTemplate.hasKey("novel:rank:hot")).thenReturn(true);

        rankService.incrHot(100L);

        // 只动 Redis：ZSet 分数 +1 + 清空空标记。
        // 阅读量入库与去重都在 novel 侧（NovelService#recordRead），这里不再碰 DB。
        verify(zSetOps).incrementScore("novel:rank:hot", "100", 1d);
        verify(stringRedisTemplate).delete("novel:rank:hot:empty");
    }

    @Test
    @DisplayName("热度 +1 但榜单尚未建立 → 不在 Redis 里凭空建出榜单（避免无 TTL 的僵尸榜单卡死）")
    void incrHot_rankNotBuilt_doesNotCreateZSet() {
        // 榜单 key 不存在：incrementScore 会同时把它创建出来，且不带 TTL，
        // 那样 ZSet 会永久停在「只有一个成员」，rank() 见非空便不再重建，榜单卡死
        when(stringRedisTemplate.hasKey("novel:rank:hot")).thenReturn(false);

        rankService.incrHot(100L);

        // 什么都不做，交给下一次回源重建（那时会带上这次阅读量）
        verify(zSetOps, never()).incrementScore(anyString(), anyString(), anyDouble());
    }

    @Test
    @DisplayName("收藏榜回源 → 走收藏端口要 id，再回 novel 批量取整行")
    void collectRank_fallbackDb_asksCounterThenNovel() throws InterruptedException {
        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserIdOrNull).thenReturn(1L);
            when(rateLimiter.tryAcquire(eq("rank"), anyString(), eq(60), eq(60))).thenReturn(true);
            when(zSetOps.reverseRange(anyString(), eq(0L), eq(19L))).thenReturn(Set.of());
            when(lock.tryLock(3, TimeUnit.SECONDS)).thenReturn(true);
            when(novelCollectCounter.topNovelIdsByCollect(20)).thenReturn(List.of(101L, 102L));
            when(novelService.listVisibleByIds(any())).thenReturn(List.of(
                    buildNovel(101L, 1, 1000L, "仙尊归来"), buildNovel(102L, 1, 900L, "炼气三千层")));
            lenient().when(categoryService.getNameMap()).thenReturn(Map.of(1L, "玄幻修真"));

            List<NovelVO> result = rankService.rank(RankTypeEnum.COLLECT);

            assertEquals(2, result.size());
            assertEquals(101L, result.get(0).getId(), "应按收藏端口给的顺序返回");
            verify(novelCollectCounter).topNovelIdsByCollect(20);
        }
    }

    // ============ 工具方法 ============

    @SuppressWarnings("unchecked")
    private void injectLocalCache(List<NovelVO> preloaded) {
        try {
            var field = RankServiceImpl.class.getDeclaredField("localCache");
            field.setAccessible(true);
            com.github.benmanes.caffeine.cache.Cache<String, List<NovelVO>> cache =
                    (com.github.benmanes.caffeine.cache.Cache<String, List<NovelVO>>) field.get(rankService);
            // RankService.RANK_KEY = "novel:rank:hot"
            cache.put("novel:rank:hot", preloaded);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private NovelVO buildVo(Long id, String title) {
        NovelVO vo = new NovelVO();
        vo.setId(id);
        vo.setTitle(title);
        return vo;
    }

    private Novel buildNovel(Long id, Integer categoryId, Long readCount, String title) {
        Novel d = new Novel();
        d.setId(id);
        d.setCategoryId(categoryId.longValue());
        d.setReadCount(readCount);
        d.setTitle(title);
        d.setStatus(1);
        return d;
    }
}
