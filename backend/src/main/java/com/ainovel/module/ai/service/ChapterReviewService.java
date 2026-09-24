package com.ainovel.module.ai.service;

import com.ainovel.module.ai.domain.ChapterReviewResult;
import com.ainovel.module.ai.domain.vo.ChapterReviewVO;

/**
 * 章节审查：检查错别字、语病、标点与前后不一致，按正文字数扣免费额度。
 *
 * <p>两个入口共用同一套审查逻辑，区别仅在于**如何确定「这是谁的稿件」**：
 * <ul>
 *   <li>{@link #reviewChapter(Long)}：作者在页面上点击「审查」，权限取自登录上下文；</li>
 *   <li>{@link #reviewChapterForTask}：全文审查的异步任务消费到某一章，
 *       MQ 线程中没有登录上下文，发起人由任务行带入，权限在任务创建时已校验一次。</li>
 * </ul>
 */
public interface ChapterReviewService {

    /**
     * 审查一章（页面按钮入口，同步返回结果）。
     *
     * @throws com.ainovel.common.exception.BusinessException 章节不存在 / 非本人作品 / 正文为空 /
     *                                                       额度不足 / 平台忙
     */
    ChapterReviewVO reviewChapter(Long chapterId);

    /**
     * 审查一章（全文审查任务入口）。
     *
     * <p>与上一条的差别在于**失败不再抛异常**：异步任务中抛异常只会使消息重试，
     * 而重试结果通常相同。模型侧失败、章节被删除、正文为空均转为
     * {@code ok=false} 的结果写入数据库，使作者直接看到「哪几章未审成、原因是什么」。
     * 仅「额度不足 / 平台忙」仍抛出，该情形需中止整个任务，而非记录一条失败即可。
     *
     * @param chapterId  要审的章节
     * @param userId     发起人（额度记在他头上）
     * @param novelTitle 书名快照（任务里存的，省一次作品查询）
     */
    ChapterReviewResult reviewChapterForTask(Long chapterId, Long userId, String novelTitle);
}
