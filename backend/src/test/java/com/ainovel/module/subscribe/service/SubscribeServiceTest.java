package com.ainovel.module.subscribe.service;

import com.ainovel.common.code.ErrorCode;
import com.ainovel.common.enums.CoinTypeEnum;
import com.ainovel.common.exception.BusinessException;
import com.ainovel.common.util.LoginUserUtil;
import com.ainovel.module.coin.service.CoinService;
import com.ainovel.module.novel.dao.ChapterMapper;
import com.ainovel.module.novel.domain.entity.Novel;
import com.ainovel.module.novel.domain.entity.Chapter;
import com.ainovel.module.novel.service.NovelService;
import com.ainovel.module.subscribe.dao.SubscribeOrderMapper;
import com.ainovel.module.subscribe.domain.entity.SubscribeOrder;
import com.ainovel.module.subscribe.domain.vo.UnlockStatusVO;
import com.ainovel.module.subscribe.service.impl.SubscribeServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 付费解锁服务单测：覆盖幂等双检、分布式锁、各类异常分支
 *
 * <p>设计要点：
 * <ul>
 *   <li>并发重复解锁的幂等性由 Redisson 真锁在集成测试中验证；
 *       本单测聚焦逻辑层：拿到锁之后的幂等双检、扣币建单顺序、异常路径。</li>
 *   <li>Mockito mock 掉所有外部依赖，纯单测无 Spring 容器，毫秒级跑完。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class SubscribeServiceTest {

    @Mock
    private SubscribeOrderMapper orderMapper;
    @Mock
    private NovelService novelService;
    @Mock
    private ChapterMapper chapterMapper;
    @Mock
    private CoinService coinService;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RLock lock;
    @Mock
    private TransactionTemplate transactionTemplate;

    private SubscribeService subscribeService;

    private static final Long USER_ID = 1L;
    private static final Long NOVEL_ID = 100L;
    private static final Long CHAPTER_ID = 200L;

    @BeforeEach
    void setUpLock() {
        subscribeService = new SubscribeServiceImpl(orderMapper, novelService, chapterMapper, coinService, redissonClient, transactionTemplate);
        // 所有用例默认能拿到锁；具体用例按需覆盖
        lenient().when(redissonClient.getLock(anyString())).thenReturn(lock);
        // 事务模板：直接执行回调内逻辑，模拟「锁内小事务」提交
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<SubscribeOrder> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
    }

    // ============ 异常分支 ============

    @Test
    @DisplayName("小说不存在 → 抛 NOVEL_NOT_FOUND")
    void unlock_novelNotFound_throws() {
        when(novelService.getNovel(NOVEL_ID)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> subscribeService.unlock(USER_ID, NOVEL_ID, null));
        assertEquals(ErrorCode.NOVEL_NOT_FOUND, ex.getErrorCode());
        verifyNoInteractions(redissonClient, coinService, orderMapper);
    }

    @Test
    @DisplayName("小说已下架 → 抛 NOVEL_OFFLINE")
    void unlock_novelOffline_throws() {
        Novel novel = buildNovel(0, 10); // status=0 下架
        when(novelService.getNovel(NOVEL_ID)).thenReturn(novel);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> subscribeService.unlock(USER_ID, NOVEL_ID, null));
        assertEquals(ErrorCode.NOVEL_OFFLINE, ex.getErrorCode());
        verifyNoInteractions(coinService, orderMapper);
    }

    @Test
    @DisplayName("单章解锁但章节不存在 → 抛 NOT_FOUND")
    void unlock_chapterNotFound_throws() {
        Novel novel = buildNovel(1, 10);
        when(novelService.getNovel(NOVEL_ID)).thenReturn(novel);
        when(chapterMapper.selectById(CHAPTER_ID)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> subscribeService.unlock(USER_ID, NOVEL_ID, CHAPTER_ID));
        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
    }

    @Test
    @DisplayName("分布式锁获取失败 → 抛 SYSTEM_ERROR，不扣币不下单")
    void unlock_lockFail_throws() throws Exception {
        Novel novel = buildNovel(1, 10);
        when(novelService.getNovel(NOVEL_ID)).thenReturn(novel);
        when(lock.tryLock(5, 10, TimeUnit.SECONDS)).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> subscribeService.unlock(USER_ID, NOVEL_ID, null));
        assertEquals(ErrorCode.SYSTEM_ERROR, ex.getErrorCode());
        // 关键：锁失败时不得扣币、不得下单
        verify(coinService, never()).deduct(anyLong(), anyInt(), any(CoinTypeEnum.class), any(), anyString());
        verify(orderMapper, never()).insert(any(SubscribeOrder.class));
    }

    // ============ 正常/幂等路径 ============

    @Test
    @DisplayName("已存在已付订单 → 幂等返回原订单，不扣币不建新单")
    void unlock_idempotentReturn() throws Exception {
        Novel novel = buildNovel(1, 10);
        when(novelService.getNovel(NOVEL_ID)).thenReturn(novel);
        when(lock.tryLock(5, 10, TimeUnit.SECONDS)).thenReturn(true);

        SubscribeOrder existOrder = new SubscribeOrder();
        existOrder.setId(999L);
        existOrder.setUserId(USER_ID);
        existOrder.setNovelId(NOVEL_ID);
        existOrder.setCoinAmount(10);
        existOrder.setStatus(1); // 已付
        // 幂等双检命中
        when(orderMapper.selectOne(any())).thenReturn(existOrder);

        SubscribeOrder result = subscribeService.unlock(USER_ID, NOVEL_ID, null);

        // 返回的是原订单，不是新建的
        assertEquals(999L, result.getId());
        // 幂等的核心：零扣币、零下单
        verify(coinService, never()).deduct(anyLong(), anyInt(), any(CoinTypeEnum.class), any(), anyString());
        verify(orderMapper, never()).insert(any(SubscribeOrder.class));
        // 但锁要正常释放
        verify(lock).unlock();
    }

    @Test
    @DisplayName("整本首次解锁 → 扣币一次 + 建单一次（按 novel.coinPrice 计价）")
    void unlock_wholeNovel_success() throws Exception {
        Novel novel = buildNovel(1, 10);
        when(novelService.getNovel(NOVEL_ID)).thenReturn(novel);
        when(lock.tryLock(5, 10, TimeUnit.SECONDS)).thenReturn(true);
        // 幂等双检未命中
        when(orderMapper.selectOne(any())).thenReturn(null);
        // orderMapper.insert 返回 1 行受影响
        when(orderMapper.insert(any(SubscribeOrder.class))).thenReturn(1);

        SubscribeOrder result = subscribeService.unlock(USER_ID, NOVEL_ID, null);

        // 1. 按整本价扣币
        verify(coinService).deduct(eq(USER_ID), eq(10), eq(CoinTypeEnum.UNLOCK),
                eq(NOVEL_ID), eq("解锁《重生之我在小说当顶流》"));
        // 2. 建单
        verify(orderMapper).insert(argThat((SubscribeOrder o) ->
                USER_ID.equals(o.getUserId()) &&
                NOVEL_ID.equals(o.getNovelId()) &&
                o.getChapterId() == null &&
                Integer.valueOf(10).equals(o.getCoinAmount()) &&
                Integer.valueOf(1).equals(o.getStatus()) &&
                o.getOrderNo() != null));
        // 3. chapterMapper 不该被调用（整本解锁）
        verifyNoInteractions(chapterMapper);
        // 4. 锁释放
        verify(lock).unlock();
        // 5. 订单号非空（雪花 ID）
        assertNotNull(result.getOrderNo());
    }

    @Test
    @DisplayName("解锁：唯一索引冲突（另一请求抢先完成）⇒ 回查已付费单返回，不把 500 抛给用户")
    void unlock_duplicateKey_returnsPaidOrder() throws Exception {
        Novel novel = buildNovel(1, 10);
        when(novelService.getNovel(NOVEL_ID)).thenReturn(novel);
        when(lock.tryLock(5, 10, TimeUnit.SECONDS)).thenReturn(true);
        // 第一次双检没查到 → 走到 insert；insert 撞 uk_user_novel_chapter（锁租约到期时真会发生）
        SubscribeOrder paidByOther = new SubscribeOrder();
        paidByOther.setId(999L);
        when(orderMapper.selectOne(any())).thenReturn(null).thenReturn(paidByOther);
        when(orderMapper.insert(any(SubscribeOrder.class)))
                .thenThrow(new DuplicateKeyException("uk_user_novel_chapter"));

        SubscribeOrder result = subscribeService.unlock(USER_ID, NOVEL_ID, null);

        // 幂等返回：拿到的是另一次请求建的那张单，而不是异常
        assertSame(paidByOther, result);
        verify(lock).unlock();
    }

    @Test
    @DisplayName("单章首次解锁 → 按 chapter.unlockCoin 计价，chapterId 写入订单")
    void unlock_singleChapter_success() throws Exception {
        Novel novel = buildNovel(1, 10);
        Chapter chapter = new Chapter();
        chapter.setId(CHAPTER_ID);
        chapter.setNovelId(NOVEL_ID);
        chapter.setUnlockCoin(5);
        when(novelService.getNovel(NOVEL_ID)).thenReturn(novel);
        when(chapterMapper.selectById(CHAPTER_ID)).thenReturn(chapter);
        when(lock.tryLock(5, 10, TimeUnit.SECONDS)).thenReturn(true);
        when(orderMapper.selectOne(any())).thenReturn(null);
        when(orderMapper.insert(any(SubscribeOrder.class))).thenReturn(1);

        SubscribeOrder result = subscribeService.unlock(USER_ID, NOVEL_ID, CHAPTER_ID);

        // 按单章价 5 扣币，而非整本价 10
        verify(coinService).deduct(eq(USER_ID), eq(5), eq(CoinTypeEnum.UNLOCK),
                eq(NOVEL_ID), anyString());
        verify(orderMapper).insert(argThat((SubscribeOrder o) ->
                CHAPTER_ID.equals(o.getChapterId()) &&
                Integer.valueOf(5).equals(o.getCoinAmount())));
        verify(lock).unlock();
        assertEquals(CHAPTER_ID, result.getChapterId());
    }

    // ============ 批量解锁状态（详情页 N+1 治理） ============

    @Test
    @DisplayName("解锁状态 → 整本已解锁时 wholeBook=true")
    void unlockStatus_wholeBook() {
        SubscribeOrder whole = new SubscribeOrder();
        whole.setChapterId(null);
        when(orderMapper.selectOne(any())).thenReturn(whole);

        UnlockStatusVO vo = subscribeService.unlockStatus(USER_ID, NOVEL_ID);

        assertTrue(vo.isWholeBook());
    }

    @Test
    @DisplayName("解锁状态 → 未整本解锁时返回已单独解锁的章节 ID 列表")
    void unlockStatus_chapterIds() {
        when(orderMapper.selectOne(any())).thenReturn(null); // 未整本解锁
        SubscribeOrder o1 = new SubscribeOrder();
        o1.setChapterId(200L);
        SubscribeOrder o2 = new SubscribeOrder();
        o2.setChapterId(201L);
        when(orderMapper.selectList(any())).thenReturn(List.of(o1, o2));

        UnlockStatusVO vo = subscribeService.unlockStatus(USER_ID, NOVEL_ID);

        assertFalse(vo.isWholeBook());
        assertEquals(List.of(200L, 201L), vo.getChapterIds());
    }

    // ============ 免付费直读（作者本人 / 管理员） ============

    @Test
    @DisplayName("作者本人解锁 → 免付费短路，不扣币不建单")
    void unlock_authorFreeRead_shortCircuit() {
        Novel novel = buildNovel(1, 10);
        novel.setUserId(USER_ID); // 作者本人
        when(novelService.getNovel(NOVEL_ID)).thenReturn(novel);

        SubscribeOrder result = subscribeService.unlock(USER_ID, NOVEL_ID, null);

        assertNull(result);
        verify(coinService, never()).deduct(anyLong(), anyInt(), any(CoinTypeEnum.class), any(), anyString());
        verify(orderMapper, never()).insert(any(SubscribeOrder.class));
        // 豁免路径不走分布式锁
        verifyNoInteractions(redissonClient);
    }

    @Test
    @DisplayName("管理员解锁他人书 → 免付费短路，不扣币不建单")
    void unlock_adminFreeRead_shortCircuit() {
        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::isAdmin).thenReturn(true);
            Novel novel = buildNovel(1, 10);
            novel.setUserId(999L); // 他人的书
            when(novelService.getNovel(NOVEL_ID)).thenReturn(novel);

            SubscribeOrder result = subscribeService.unlock(USER_ID, NOVEL_ID, null);

            assertNull(result);
            verify(coinService, never()).deduct(anyLong(), anyInt(), any(CoinTypeEnum.class), any(), anyString());
            verify(orderMapper, never()).insert(any(SubscribeOrder.class));
            verifyNoInteractions(redissonClient);
        }
    }

    @Test
    @DisplayName("解锁状态 → 作者本人整本直读 wholeBook=true，不查订单")
    void unlockStatus_authorFreeRead_wholeBook() {
        Novel novel = buildNovel(1, 10);
        novel.setUserId(USER_ID);
        when(novelService.getNovel(NOVEL_ID)).thenReturn(novel);

        UnlockStatusVO vo = subscribeService.unlockStatus(USER_ID, NOVEL_ID);

        assertTrue(vo.isWholeBook());
        assertTrue(vo.getChapterIds().isEmpty());
        verifyNoInteractions(orderMapper);
    }

    @Test
    @DisplayName("解锁状态 → 管理员整本直读 wholeBook=true")
    void unlockStatus_adminFreeRead_wholeBook() {
        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::isAdmin).thenReturn(true);
            Novel novel = buildNovel(1, 10);
            novel.setUserId(999L);
            when(novelService.getNovel(NOVEL_ID)).thenReturn(novel);

            UnlockStatusVO vo = subscribeService.unlockStatus(USER_ID, NOVEL_ID);

            assertTrue(vo.isWholeBook());
            verifyNoInteractions(orderMapper);
        }
    }

    // ============ canRead（阅读器/正文统一可读判定） ============

    @Test
    @DisplayName("canRead → 作者本人免付费直读，不查订单")
    void canRead_authorFreeRead_true() {
        Novel novel = buildNovel(1, 10);
        novel.setUserId(USER_ID);
        when(novelService.getNovel(NOVEL_ID)).thenReturn(novel);

        assertTrue(subscribeService.canRead(USER_ID, NOVEL_ID, CHAPTER_ID));
        verifyNoInteractions(orderMapper);
    }

    @Test
    @DisplayName("canRead → 管理员免付费直读，不查订单")
    void canRead_adminFreeRead_true() {
        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::isAdmin).thenReturn(true);
            Novel novel = buildNovel(1, 10);
            novel.setUserId(999L);
            when(novelService.getNovel(NOVEL_ID)).thenReturn(novel);

            assertTrue(subscribeService.canRead(USER_ID, NOVEL_ID, CHAPTER_ID));
            verifyNoInteractions(orderMapper);
        }
    }

    @Test
    @DisplayName("canRead → 本章已解锁返回 true")
    void canRead_unlockedChapter_true() {
        Novel novel = buildNovel(1, 10); // userId=null，非作者
        when(novelService.getNovel(NOVEL_ID)).thenReturn(novel);
        when(orderMapper.selectOne(any())).thenReturn(new SubscribeOrder());

        assertTrue(subscribeService.canRead(USER_ID, NOVEL_ID, CHAPTER_ID));
    }

    @Test
    @DisplayName("canRead → 本章未解锁但整本已解锁返回 true")
    void canRead_wholeBook_true() {
        Novel novel = buildNovel(1, 10);
        when(novelService.getNovel(NOVEL_ID)).thenReturn(novel);
        // 单章订单无 → 整本订单有
        when(orderMapper.selectOne(any())).thenReturn(null).thenReturn(new SubscribeOrder());

        assertTrue(subscribeService.canRead(USER_ID, NOVEL_ID, CHAPTER_ID));
    }

    @Test
    @DisplayName("canRead → 未解锁且未整本解锁返回 false")
    void canRead_locked_false() {
        Novel novel = buildNovel(1, 10);
        when(novelService.getNovel(NOVEL_ID)).thenReturn(novel);
        when(orderMapper.selectOne(any())).thenReturn(null);

        assertFalse(subscribeService.canRead(USER_ID, NOVEL_ID, CHAPTER_ID));
    }

    // ============ 工具方法 ============

    private Novel buildNovel(int status, int coinPrice) {
        Novel novel = new Novel();
        novel.setId(NOVEL_ID);
        novel.setTitle("重生之我在小说当顶流");
        novel.setStatus(status);
        novel.setCoinPrice(coinPrice);
        return novel;
    }
}
