package com.ainovel.module.subscribe.service.impl;

import cn.hutool.core.util.IdUtil;
import com.ainovel.common.code.ErrorCode;
import com.ainovel.common.enums.CoinTypeEnum;
import com.ainovel.common.enums.CommonStatusEnum;
import com.ainovel.common.enums.OrderStatusEnum;
import com.ainovel.common.exception.BusinessException;
import com.ainovel.common.util.LoginUserUtil;
import com.ainovel.module.coin.service.CoinService;
import com.ainovel.module.novel.dao.ChapterMapper;
import com.ainovel.module.novel.domain.entity.Novel;
import com.ainovel.module.novel.domain.entity.Chapter;
import com.ainovel.module.novel.spi.ChapterAccessChecker;
import com.ainovel.module.subscribe.dao.SubscribeOrderMapper;
import com.ainovel.module.subscribe.domain.entity.SubscribeOrder;
import com.ainovel.module.subscribe.domain.vo.UnlockStatusVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;
import java.util.concurrent.TimeUnit;
import com.ainovel.module.subscribe.service.SubscribeService;
import com.ainovel.module.novel.service.ChapterService;
import com.ainovel.module.novel.service.NovelService;

/**
 * 订阅/解锁服务：Redisson 分布式锁 + 幂等 + 虚拟币扣减
 *
 * <p>并发正确性设计（关键）：方法整体不加 @Transactional，
 * 而是用 {@link TransactionTemplate} 把「扣币 + 建单」包成锁内的小事务，
 * 保证 <b>事务提交完成后才释放分布式锁</b>。
 * 若把 @Transactional 加在整个方法上，锁会在事务提交前释放，
 * 并发线程双检时读不到未提交的订单，会导致重复扣币。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubscribeServiceImpl implements SubscribeService, ChapterAccessChecker {

    private final SubscribeOrderMapper orderMapper;

    private final NovelService novelService;

    private final ChapterMapper chapterMapper;

    private final CoinService coinService;

    private final RedissonClient redissonClient;

    private final TransactionTemplate transactionTemplate;

    public SubscribeOrder unlock(Long userId, Long novelId, Long chapterId) {
        Novel novel = novelService.getNovel(novelId);
        if (novel == null) {
            throw new BusinessException(ErrorCode.NOVEL_NOT_FOUND);
        }
        if (novel.getStatus() == CommonStatusEnum.DISABLED.getCode()) {
            throw new BusinessException(ErrorCode.NOVEL_OFFLINE);
        }

        // 作者本人 / 管理员：免付费直读，直接视为已解锁（不扣币、不建单）
        if (isFreeRead(userId, novel)) {
            return null;
        }

        // 计算解锁价格
        int price;
        if (chapterId != null) {
            Chapter chapter = chapterMapper.selectById(chapterId);
            if (chapter == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "章节不存在");
            }
            price = chapter.getUnlockCoin();
        } else {
            price = novel.getCoinPrice();
            // 防御：整本价 0 但存在付费章 → 定价异常，拒绝「0 币买断」绕过单章付费墙（兜底历史遗留数据）
            if (price <= 0 && hasPaidChapter(novelId)) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "本书定价异常，请联系管理员");
            }
        }

        // Redisson 分布式锁，防止并发重复解锁
        String lockKey = "lock:subscribe:" + userId + ":" + novelId + ":" + (chapterId == null ? "all" : chapterId);
        RLock lock = redissonClient.getLock(lockKey);
        try {
            boolean locked = lock.tryLock(5, 10, TimeUnit.SECONDS);
            if (!locked) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "系统繁忙，请稍后重试");
            }
            try {
                // 幂等：双检，已解锁则直接返回
                SubscribeOrder exist = findPaidOrder(userId, novelId, chapterId);
                if (exist != null) {
                    return exist;
                }

                // 锁内小事务：扣币 + 建单原子完成，提交后才释放锁
                try {
                    return transactionTemplate.execute(status -> {
                        coinService.deduct(userId, price, CoinTypeEnum.UNLOCK, novelId,
                                "解锁《" + novel.getTitle() + "》");

                        SubscribeOrder order = new SubscribeOrder();
                        order.setOrderNo(IdUtil.getSnowflakeNextIdStr());
                        order.setUserId(userId);
                        order.setNovelId(novelId);
                        order.setChapterId(chapterId);
                        order.setCoinAmount(price);
                        order.setStatus(OrderStatusEnum.PAID.getCode());
                        orderMapper.insert(order);
                        return order;
                    });
                } catch (DuplicateKeyException e) {
                    // 并发兜底：唯一索引 uk_user_novel_chapter 冲突说明另一次请求已完成该章/整本解锁。
                    // 该分支可复现：锁租约 10 秒，事务执行超过租约时锁被自动释放，
                    // 另一线程随即插入同一行。
                    // 此时回查已支付订单并返回，而非向调用方抛出 500：解锁操作需保持幂等，
                    // 与 LoginServiceImpl 处理用户名唯一键冲突的做法一致。
                    // 扣币与建单在同一事务内，唯一键冲突会连带回滚，不会出现重复扣币。
                    SubscribeOrder alreadyPaid = findPaidOrder(userId, novelId, chapterId);
                    if (alreadyPaid != null) {
                        log.info("解锁并发冲突，已由另一次请求完成：userId={} novelId={} chapterId={}",
                                userId, novelId, chapterId);
                        return alreadyPaid;
                    }
                    throw e;
                }
            } finally {
                lock.unlock();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "获取锁被中断", e);
        }
    }

    /**
     * 是否已解锁（幂等判断）
     */
    public boolean isUnlocked(Long userId, Long novelId, Long chapterId) {
        return findPaidOrder(userId, novelId, chapterId) != null;
    }

    /**
     * 是否可读该章：免付费直读（作者本人/管理员）或 已解锁本章/整本。
     *
     * <p>前端 {@code /subscribe/check} 与正文读取 {@link ChapterService#getContent} 共用此判定，
     * 保证「目录页/阅读器显示可读」与「后端放行」口径一致（若 check 仅查订单，作者/管理员会被误判为未解锁）。
     */
    public boolean canRead(Long userId, Long novelId, Long chapterId) {
        Novel novel = novelService.getNovel(novelId);
        if (isFreeRead(userId, novel)) {
            return true;
        }
        if (isUnlocked(userId, novelId, chapterId)) {
            return true;
        }
        // 整本解锁对任意单章同样可读
        return chapterId != null && isUnlocked(userId, novelId, null);
    }

    /**
     * 批量查询某本书的解锁状态（一次查询，避免前端逐章 checkUnlocked 的 N+1）。
     *
     * <p>整本解锁（存在 chapterId 为 null 的付费订单）时 wholeBook=true，前端据此
     * 把所有章节视为已解锁；否则返回已单独解锁的章节 ID 列表。
     */
    public UnlockStatusVO unlockStatus(Long userId, Long novelId) {
        UnlockStatusVO vo = new UnlockStatusVO();
        Novel novel = novelService.getNovel(novelId);
        // 作者本人 / 管理员：整本直接可读，前端目录全部显示「阅读」
        if (isFreeRead(userId, novel)) {
            vo.setWholeBook(true);
            vo.setChapterIds(List.of());
            return vo;
        }
        vo.setWholeBook(findPaidOrder(userId, novelId, null) != null);
        // 此处不加 limit：返回值仅为当前用户在该书已解锁的章节，其上界为本人已购章节数
        // （不超过该书总章数），不构成全表扫描；加上 limit 会导致阅读器漏标已解锁章节。
        vo.setChapterIds(orderMapper.selectList(new LambdaQueryWrapper<SubscribeOrder>()
                        .eq(SubscribeOrder::getUserId, userId)
                        .eq(SubscribeOrder::getNovelId, novelId)
                        .eq(SubscribeOrder::getStatus, OrderStatusEnum.PAID.getCode())
                        .isNotNull(SubscribeOrder::getChapterId))
                .stream().map(SubscribeOrder::getChapterId).toList());
        return vo;
    }

    /**
     * 是否免付费直读：作者本人 或 管理员。
     *
     * <p>作者依据 {@link Novel#getUserId()}（发布者 ID；管理员录入的书为 null，无作者概念）；
     * 管理员依据 Sa-Token 角色 admin（见 {@link LoginUserUtil#isAdmin()}）。
     */
    public boolean isFreeRead(Long userId, Novel novel) {
        if (novel == null) {
            return false;
        }
        if (novel.getUserId() != null && novel.getUserId().equals(userId)) {
            return true;
        }
        return LoginUserUtil.isAdmin();
    }

    /**
     * 是否存在付费章（unlock_coin &gt; 0）。用于整本解锁时的定价异常兜底。
     */
    private boolean hasPaidChapter(Long novelId) {
        return chapterMapper.selectCount(new LambdaQueryWrapper<Chapter>()
                .eq(Chapter::getNovelId, novelId)
                .gt(Chapter::getUnlockCoin, 0)) > 0;
    }

    private SubscribeOrder findPaidOrder(Long userId, Long novelId, Long chapterId) {
        LambdaQueryWrapper<SubscribeOrder> wrapper = new LambdaQueryWrapper<SubscribeOrder>()
                .eq(SubscribeOrder::getUserId, userId)
                .eq(SubscribeOrder::getNovelId, novelId)
                .eq(SubscribeOrder::getStatus, OrderStatusEnum.PAID.getCode());
        if (chapterId != null) {
            wrapper.eq(SubscribeOrder::getChapterId, chapterId);
        } else {
            wrapper.isNull(SubscribeOrder::getChapterId);
        }
        return orderMapper.selectOne(wrapper);
    }
}
