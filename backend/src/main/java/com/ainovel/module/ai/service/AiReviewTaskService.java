package com.ainovel.module.ai.service;

import com.ainovel.common.domain.PageResult;
import com.ainovel.module.ai.domain.form.AiReviewStartForm;
import com.ainovel.module.ai.domain.vo.AiReviewIssueVO;
import com.ainovel.module.ai.domain.vo.AiReviewOverviewVO;
import com.ainovel.module.ai.domain.vo.AiReviewTaskVO;

/**
 * 全文审查任务（阶段 5）：将「整本审一遍」拆解为可查进度、可中断、可续跑的异步任务。
 *
 * <p>与单章审查（{@link ChapterReviewService}）的分工：本接口管理**任务的账**
 * （派发了哪些章、执行到哪、扣费多少、哪几章未审成）；「单章如何审查」仍由
 * {@link ChapterReviewService#reviewChapterForTask} 承担，两侧不重复实现。
 *
 * <p>必须异步的原因：几百章的书对应几百次模型调用，占用 HTTP 线程执行分钟级请求
 * 既会超出网关超时，也使作者面对无进度的页面而无法判断执行状态。
 */
public interface AiReviewTaskService {

    /**
     * 进入页面时一次取全：最近一次任务 + 本书规模 + 今日剩余字数。
     *
     * <p>发起前的确认框需同时显示「将审查 N 章 / 约 M 字」与「今天还剩 K 字」：
     * 三个数来自三处（章节表、任务表、额度计数器），分三次请求容易出现
     * 「按弹窗中的数字点击确认，实际扣费与显示不一致」。
     */
    AiReviewOverviewVO overview(Long novelId);

    /**
     * 发起全文审查，可指定范围（{@code form} 传 null 即为整本）。
     *
     * <p>**幂等**：本书已存在排队中/审查中的任务时，直接返回该任务，
     * 不再创建第二个：重复点击、两个标签页同时点击均不应产生两份任务与两倍扣费。
     *
     * <p>范围见 {@link com.ainovel.module.ai.domain.form.AiReviewStartForm}：
     * 整本 / 最近 N 章 / 指定章号区间。长篇整本一次需两万多字额度，额度耗尽即停止在个位数百分比，
     * 按范围发起才可一次执行完成并取得完整结论，剩余部分次日继续。
     *
     * <p>刻意**不提供** {@code start(novelId)} 的单参重载：若以 default 方法转调本方法，
     * 该转调为 this 调用、不经代理，实现类上的 {@code @Transactional} 会失效。
     */
    AiReviewTaskVO start(Long novelId, AiReviewStartForm form);

    /** 任务进度（供页面轮询） */
    AiReviewTaskVO detail(Long taskId);

    /**
     * 问题清单（分页）。
     *
     * <p>同一处问题出现在多章时**只占一行**，另以出现的章号列出其余章节（跨章归并）：
     * 某个人名始终写错这类问题，逐条铺开会使作者在几百条中反复看到同一问题。
     */
    PageResult<AiReviewIssueVO> pageIssues(Long taskId, long pageNum, long pageSize);

    /**
     * 继续审查：仅补「尚未审成的章」与「任务之后新增的章」。
     *
     * <p>同时是**额度用尽后的续跑入口**（免费额度按天重置，当日未审完则次日继续），
     * 以及「任务阻塞」的兜底：重复派发是安全的，已审完的章会被幂等判据拦截。
     */
    AiReviewTaskVO resume(Long taskId);

    /** 取消：已审部分保留，队列中剩余消息按任务状态直接丢弃 */
    void cancel(Long taskId);

    /**
     * 处理一章（MQ 消费入口）。
     *
     * <p>不抛业务异常：模型侧失败、章节被删除、正文为空均记为「该章未审成」写入数据库，
     * 使作者能看到是哪几章、原因是什么；额度用尽与平台忙则**中止整个任务**
     * （继续执行每章都会失败，只会把同一错误重复 N 次）。真正的**重试**留给
     * 基础设施异常（DB/网络），由消费端按退避重投：消息重投时该章已被记为
     * 处理过，不会重复扣费。
     */
    void processChapter(Long taskId, Long chapterId);

    /**
     * 消费重试耗尽后对该章执行收尾。
     *
     * <p>不执行该步骤时，反复抛异常的章节会一直停留在「审查中」占位状态：
     * 任务进度无法到达 100%，页面表现为持续加载的进度条，
     * 而后台日志中的异常与界面现象无法对应（典型静默失败）。
     *
     * <p>该方法本身也可能失败（DB 不可用）；失败时仅留有日志，
     * 作者仍可通过「继续审查」将该章重新纳入。
     */
    void abandonChapter(Long taskId, Long chapterId, String reason);
}
