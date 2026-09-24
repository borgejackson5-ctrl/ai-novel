package com.ainovel.module.subscribe.service;

import com.ainovel.common.util.LoginUserUtil;
import com.ainovel.module.novel.domain.entity.Novel;
import com.ainovel.module.subscribe.domain.entity.SubscribeOrder;
import com.ainovel.module.subscribe.domain.vo.UnlockStatusVO;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 订阅/解锁服务：Redisson 分布式锁 + 幂等 + 虚拟币扣减
 *
 * <p>并发正确性设计（关键）：方法整体不加 @Transactional，
 * 而是用 {@link TransactionTemplate} 把「扣币 + 建单」包成锁内的小事务，
 * 保证 <b>事务提交完成后才释放分布式锁</b>。
 * 若把 @Transactional 加在整个方法上，锁会在事务提交前释放，
 * 并发线程双检时读不到未提交的订单，会导致重复扣币。
 */
public interface SubscribeService {

    public SubscribeOrder unlock(Long userId, Long novelId, Long chapterId);

    /**
     * 是否已解锁（幂等判断）
     */
    public boolean isUnlocked(Long userId, Long novelId, Long chapterId);

    /**
     * 是否可读该章：免付费直读（作者本人/管理员）或 已解锁本章/整本。
     *
     * <p>前端 {@code /subscribe/check} 与正文读取 {@link ChapterService#getContent} 共用此判定，
     * 保证「目录页/阅读器显示可读」与「后端放行」口径一致（若 check 仅查订单，作者/管理员会被误判为未解锁）。
     */
    public boolean canRead(Long userId, Long novelId, Long chapterId);

    /**
     * 批量查询某本书的解锁状态（一次查询，避免前端逐章 checkUnlocked 的 N+1）。
     *
     * <p>整本解锁（存在 chapterId 为 null 的付费订单）时 wholeBook=true，前端据此
     * 把所有章节视为已解锁；否则返回已单独解锁的章节 ID 列表。
     */
    public UnlockStatusVO unlockStatus(Long userId, Long novelId);

    /**
     * 是否免付费直读：作者本人 或 管理员。
     *
     * <p>作者依据 {@link Novel#getUserId()}（发布者 ID；管理员录入的书为 null，无作者概念）；
     * 管理员依据 Sa-Token 角色 admin（见 {@link LoginUserUtil#isAdmin()}）。
     */
    public boolean isFreeRead(Long userId, Novel novel);
}
