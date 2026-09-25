package com.ainovel.module.ai.service.impl;

import com.ainovel.common.code.ErrorCode;
import com.ainovel.common.constant.MqConstant;
import com.ainovel.common.domain.PageParam;
import com.ainovel.common.domain.PageResult;
import com.ainovel.common.exception.BusinessException;
import com.ainovel.common.message.AiReviewMessage;
import com.ainovel.common.mq.MqSender;
import com.ainovel.common.util.LoginUserUtil;
import com.ainovel.module.ai.dao.AiReviewChapterMapper;
import com.ainovel.module.ai.dao.AiReviewIssueMapper;
import com.ainovel.module.ai.dao.AiReviewTaskMapper;
import com.ainovel.module.ai.domain.ChapterReviewResult;
import com.ainovel.module.ai.domain.entity.AiReviewChapter;
import com.ainovel.module.ai.domain.entity.AiReviewIssue;
import com.ainovel.module.ai.domain.entity.AiReviewTask;
import com.ainovel.module.ai.domain.form.AiReviewStartForm;
import com.ainovel.module.ai.domain.vo.AiReviewIssueVO;
import com.ainovel.module.ai.domain.vo.AiReviewOverviewVO;
import com.ainovel.module.ai.domain.vo.AiReviewTaskVO;
import com.ainovel.module.ai.domain.vo.UserAiConfigVO;
import com.ainovel.module.ai.service.AiConfigService;
import com.ainovel.module.ai.service.AiReviewTaskService;
import com.ainovel.module.ai.service.ChapterReviewService;
import com.ainovel.module.novel.domain.entity.Novel;
import com.ainovel.module.novel.domain.vo.ChapterVO;
import com.ainovel.module.novel.service.ChapterService;
import com.ainovel.module.novel.service.NovelService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * 全文审查任务实现。
 *
 * <p>三个关键取舍：
 * <ol>
 *   <li>**一章一条消息**（而非一条消息处理完整本书）：ack 粒度为单章，
 *       进程重启仅丢失当前那一章；进度即「已处理章数」的累加值；</li>
 *   <li>**额度按章扣、失败按章退**（而非「一次性预扣全书字数」）：
 *       预扣方式下任务一旦中途停止，作者会为一整本未审的书锁住额度，
 *       而中途停止在本项目属常态（免费额度每天只有 3 万字，一本书往往审不完）。
 *       按章结算即「执行多少、扣费多少」，停止时无需补偿逻辑；</li>
 *   <li>**幂等由唯一索引保证**（{@code uk_task_chapter}），不依赖内存记账。
 *       消息重投、重复派发、重复点击继续审查，均由该索引拦截。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiReviewTaskServiceImpl implements AiReviewTaskService {

    private static final String ABORT_QUOTA_MSG = "今天的免费字数用完了，剩下的章节明天可以在这里继续";
    private static final String ABORT_BUSY_MSG = "现在使用的人有点多，剩下的章节稍后可以在这里继续";
    private static final String CANCEL_MSG = "已取消，已经审过的章节保留";
    private static final String DONE_MSG = "全部 %d 章已审查完";
    private static final String DONE_WITH_FAILED_MSG = "有 %d 章没能完成审查，可以只重试这几章";
    private static final String ALL_FAILED_MSG = "这次没能完成审查，稍后可以重试";
    private static final String CHAPTER_FAILED_MSG = "这次审查没能完成，稍后可以重试";
    private static final String CHAPTER_GONE_MSG = "这一章已经不存在了，可能已被删除";

    /** 单次任务最多取出多少条问题参与跨章归并：超出该量说明稿件需先整体修订，非列表所能覆盖 */
    private static final int ISSUE_SCAN_LIMIT = 3000;

    /** 失败章节明细最多列出多少条；超出的部分通过 failedListTruncated 返回数量 */
    private static final int FAILED_LIST_LIMIT = 50;

    /** 章号摘要中最多列出的章号个数 */
    private static final int CHAPTER_NO_LIMIT = 8;

    private final NovelService novelService;

    private final ChapterService chapterService;

    private final AiReviewTaskMapper taskMapper;

    private final AiReviewChapterMapper chapterMapper;

    private final AiReviewIssueMapper issueMapper;

    private final ChapterReviewService chapterReviewService;

    private final AiConfigService aiConfigService;

    private final MqSender mqSender;

    /** 「最近 N 章」未传入时的默认值 */
    private static final int DEFAULT_RECENT_CHAPTERS = 20;

    /** 「最近 N 章」的上限：超过该值与整本无区别，同时拦截明显的错误传参 */
    private static final int MAX_RECENT_CHAPTERS = 500;

    // ==================== 页面入口 ====================

    @Override
    public AiReviewOverviewVO overview(Long novelId) {
        novelService.requireOwnerNovel(novelId);
        Long userId = LoginUserUtil.getUserId();

        AiReviewOverviewVO vo = new AiReviewOverviewVO();
        List<ChapterVO> chapters = chapterService.listChapterBriefs(novelId);
        vo.setChapterCount(chapters.size());
        vo.setTotalWords(chapters.stream().mapToLong(c -> c.getWordCount() == null ? 0L : c.getWordCount()).sum());

        AiReviewTask latest = selectLatest(new QueryWrapper<AiReviewTask>()
                .eq("user_id", userId).eq("novel_id", novelId)
                .orderByDesc("create_time"));
        vo.setTask(toVO(latest));

        UserAiConfigVO mine = aiConfigService.getUserConfigMasked(userId);
        Long remaining = null;
        if (mine != null) {
            boolean ownKey = Boolean.TRUE.equals(mine.getHasOwnKey());
            vo.setUseOwnKey(ownKey);
            // 自带 Key 不限量，「今天还剩多少字」对该用户无意义，故下发 null，
            // 避免页面显示一个永远用不完的数字，使作者误认为其他环节另有计费
            remaining = ownKey ? null : mine.getRemainingUnits();
            vo.setRemainingUnits(remaining);
        }
        // 按范围预估：使作者在点击「开始审查」前即可看到「整本需要多少字、额度是否足够」，
        // 而非执行到一半因额度不足才中断（该书 233 章整本需两万多字，无法一次审完）
        vo.setScopeOptions(buildScopeEstimates(chapters, remaining));
        return vo;
    }

    /**
     * 几个可选范围的预估消耗（章数 + 字数 + 今日额度够不够）。
     *
     * <p>仅列出「整本」与「最近 N 章」两类：这两类覆盖绝大多数场景，
     * 额度足够时选整本一次审完，不足时先审最近写作的部分（新章出现新问题的可能性最大）。
     */
    private List<AiReviewOverviewVO.ScopeEstimate> buildScopeEstimates(List<ChapterVO> all, Long remainingUnits) {
        List<AiReviewOverviewVO.ScopeEstimate> options = new ArrayList<>();
        options.add(scopeEstimate("ALL", null, all, remainingUnits));
        // 10 章一档用于「免费额度较少」的场景：该长篇最近 20 章即需 3.5 万字，
        // 而每日额度只有 3 万字，若仅保留 20/50 两档，额度较小的作者会看到「各档均不足」
        for (int n : List.of(10, 20, 50)) {
            // 与整本规模相同（或更大）的档位无意义，不列出
            if (n < all.size()) {
                options.add(scopeEstimate("RECENT", n, all, remainingUnits));
            }
        }
        return options;
    }

    private AiReviewOverviewVO.ScopeEstimate scopeEstimate(String scope, Integer recentCount,
                                                           List<ChapterVO> all, Long remainingUnits) {
        List<ChapterVO> picked = recentCount == null
                ? all : all.subList(Math.max(0, all.size() - recentCount), all.size());
        long words = picked.stream().mapToLong(c -> c.getWordCount() == null ? 0L : c.getWordCount()).sum();

        AiReviewOverviewVO.ScopeEstimate estimate = new AiReviewOverviewVO.ScopeEstimate();
        estimate.setScope(scope);
        estimate.setRecentCount(recentCount);
        estimate.setChapters(picked.size());
        estimate.setWords(words);
        // 额度未知（自带 Key / 未取到配置）时不判断是否足够，否则会产生误导
        estimate.setEnoughQuota(remainingUnits == null || words <= remainingUnits);
        return estimate;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiReviewTaskVO start(Long novelId, AiReviewStartForm form) {
        novelService.requireOwnerNovel(novelId);
        Long userId = LoginUserUtil.getUserId();
        // 执行前确认模型可用：平台未配置 Key 时**不应创建任务**，
        // 否则任务会逐章失败、零产出，作者看到的是一个「已跑完但未查出任何问题」的任务，
        // 且不知道原因是 AI 未开放（降级冒烟测试发现：无 Key 时 2 章任务报「已完成 1/2」）。
        aiConfigService.requireModelAvailable(userId);

        // 已存在运行中的任务时直接复用：重复点击不应产生两份任务与两倍扣费。
        // **但需补一次派发**：该任务的进度可能已经停止，原因有两类，
        //   ① 派发时投递失败（消息现会写入 outbox 等待补投，该情况基本已被覆盖，
        //      但补投需等待一轮扫描；作者点击一次即可立即推进，无需等待）；
        //   ② 消费者中途退出，该章的消息丢失。
        // 若不补派发，用户再次点击「全文审查」只会取回这个永不推进的任务，**无法创建新任务**
        // （降级冒烟测试发现，且为永久性）。
        // 补派发是幂等的：消费端按「该章是否已处理」判重，另有 uk_task_chapter 唯一索引
        // 保证并发正确（见 AiReviewChapter 的说明），重复消息最多产生一次空转。
        AiReviewTask running = selectLatest(new QueryWrapper<AiReviewTask>()
                .eq("user_id", userId).eq("novel_id", novelId)
                .in("status", AiReviewTask.STATUS_QUEUED, AiReviewTask.STATUS_RUNNING)
                .orderByDesc("create_time"));
        if (running != null) {
            log.info("全文审查：已有进行中的任务 {}，直接复用", running.getId());
            redispatchPending(running);
            return toVO(running);
        }

        List<ChapterVO> all = chapterService.listChapterBriefs(novelId);
        if (all.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "这本书还没有章节，先写点内容再审查");
        }
        List<ChapterVO> chapters = selectScope(all, form);
        if (chapters.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "所选范围里没有章节，换个范围试试");
        }
        // 将范围固化在任务上：补派发（复用进行中任务时）与续跑均需按该范围过滤，
        // 否则「只审最近 N 章」的任务会被补派为整本（曾出现：最近 1 章的任务执行完成后 done 变为 2）
        boolean wholeBook = chapters.size() == all.size();
        Integer scopeFrom = wholeBook ? null : chapters.stream()
                .map(ChapterVO::getChapterNo).filter(Objects::nonNull)
                .min(Integer::compareTo).orElse(null);
        Integer scopeTo = wholeBook ? null : chapters.stream()
                .map(ChapterVO::getChapterNo).filter(Objects::nonNull)
                .max(Integer::compareTo).orElse(null);

        AiReviewTask task = new AiReviewTask();
        task.setUserId(userId);
        task.setNovelId(novelId);
        Novel novel = novelService.getNovel(novelId);
        task.setNovelTitle(novel == null ? null : novel.getTitle());
        task.setScopeFromChapterNo(scopeFrom);
        task.setScopeToChapterNo(scopeTo);
        task.setStatus(AiReviewTask.STATUS_QUEUED);
        task.setTotalChapters(chapters.size());
        task.setDoneChapters(0);
        task.setFailedChapters(0);
        task.setIssueCount(0);
        task.setChargedUnits(0);
        task.setRefundedUnits(0);
        task.setReviewedChars(0);
        taskMapper.insert(task);

        dispatch(task, chapters);
        return toVO(task);
    }

    /**
     * 按范围选出本次需审查的章节。
     *
     * <p>支持选择范围的原因：233 章的书整本审查需两万多字额度，而免费额度为每天 3 万字，
     * 单次发起往往执行到 3%~8% 即因额度不足停止，作者首次使用看到的不是结论，而是「仅执行了很少一部分」。
     * 按范围发起可一次执行完成并取得完整结论，剩余部分次日通过「继续审查」接续。
     *
     * <p>{@code all} 来自 {@code listChapterBriefs}，**按章号升序**，「最近 N 章」取尾部即可。
     */
    private List<ChapterVO> selectScope(List<ChapterVO> all, AiReviewStartForm form) {
        String scope = (form == null || form.getScope() == null || form.getScope().isBlank())
                ? "ALL" : form.getScope().trim().toUpperCase(Locale.ROOT);
        return switch (scope) {
            case "RECENT" -> {
                int n = form.getRecentCount() == null ? DEFAULT_RECENT_CHAPTERS : form.getRecentCount();
                n = Math.max(1, Math.min(n, MAX_RECENT_CHAPTERS));
                yield List.copyOf(all.subList(Math.max(0, all.size() - n), all.size()));
            }
            case "RANGE" -> {
                int from = form.getFromChapterNo() == null ? Integer.MIN_VALUE : form.getFromChapterNo();
                int to = form.getToChapterNo() == null ? Integer.MAX_VALUE : form.getToChapterNo();
                if (from > to) {
                    throw new BusinessException(ErrorCode.PARAM_ERROR, "起始章号不能大于结束章号");
                }
                yield all.stream()
                        .filter(c -> c.getChapterNo() != null && c.getChapterNo() >= from && c.getChapterNo() <= to)
                        .toList();
            }
            // ALL 及任何无法识别的取值均按整本处理（优先覆盖最全范围，不静默缩小范围）
            default -> List.copyOf(all);
        };
    }

    /**
     * 判断该章是否落在任务发起时选择的范围之内。
     *
     * <p>{@code from}/{@code to} 均为 null（整本任务）时不限制范围：补派发与续跑仍会带上
     * 作者新写的章节，此为原有行为；一旦发起时选择了范围，则必须严格限制在该范围内，
     * 否则「只审最近 N 章」会被补派发为整本（曾出现：1 章的任务执行完成后 done 变为 2）。
     */
    private static boolean inScope(AiReviewTask task, ChapterVO chapter) {
        Integer no = chapter.getChapterNo();
        if (no == null) {
            return true;
        }
        if (task.getScopeFromChapterNo() != null && no < task.getScopeFromChapterNo()) {
            return false;
        }
        return task.getScopeToChapterNo() == null || no <= task.getScopeToChapterNo();
    }

    @Override
    public AiReviewTaskVO detail(Long taskId) {
        return toVO(requireOwnTask(taskId));
    }

    @Override
    public PageResult<AiReviewIssueVO> pageIssues(Long taskId, long pageNum, long pageSize) {
        requireOwnTask(taskId);
        long page = PageParam.clampPage(pageNum);
        long size = PageParam.clampSize(pageSize);

        // 一次性取出任务的全部问题再做归并：归并要求「先看到重复才能合并」，分页后各页互不可见，
        // 同一问题会在每页各出现一次，表现为分页异常。
        // 使用 LIMIT 而非分页插件：插件会静默改小 size，此处上限将失去意义。
        List<AiReviewIssue> all = issueMapper.selectList(new QueryWrapper<AiReviewIssue>()
                .eq("task_id", taskId)
                .orderByAsc("chapter_no", "segment_no", "id")
                .last("LIMIT " + ISSUE_SCAN_LIMIT));
        if (all.size() >= ISSUE_SCAN_LIMIT) {
            log.warn("全文审查任务 {} 的问题条目达到扫描上限 {}，后面的未纳入列表", taskId, ISSUE_SCAN_LIMIT);
        }

        List<AiReviewIssueVO> reduced = reduceByDedupKey(all);
        int from = (int) Math.min((page - 1) * size, reduced.size());
        int to = (int) Math.min(from + size, reduced.size());
        return PageResult.of(reduced.size(), page, size, new ArrayList<>(reduced.subList(from, to)));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiReviewTaskVO resume(Long taskId) {
        AiReviewTask task = requireOwnTask(taskId);
        List<ChapterVO> chapters = chapterService.listChapterBriefs(task.getNovelId());
        if (chapters.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "这本书还没有章节");
        }

        Set<Long> done = doneChapterIds(taskId);

        // 上次失败的章需可重审：先将其记录行**物理删除**（见 mapper 上的说明：
        // 唯一索引不识别逻辑删除，保留记录行将永远无法插入新记录，表现为「续跑时无法补上这几章」）
        chapterMapper.deleteFailed(taskId);

        // 同时带上「任务创建后新写的章节」：作者续跑时通常刚又写了几章。
        // 但**仅在任务原本即为整本时**才带上：选择了「最近 N 章」的任务，
        // 续跑不应扩展为整本（属于另一范围，需重新发起）
        List<ChapterVO> pending = chapters.stream()
                .filter(c -> inScope(task, c))
                .filter(c -> !done.contains(c.getId()))
                .toList();

        // done_chapters 记录「含失败在内已处理完的章数」，续跑时重置为「实际审成的章数」
        taskMapper.update(null, new UpdateWrapper<AiReviewTask>()
                .eq("id", taskId)
                .set("total_chapters", done.size() + pending.size())
                .set("done_chapters", done.size())
                .set("failed_chapters", 0)
                .set("status", AiReviewTask.STATUS_QUEUED)
                .set("message", null)
                .set("finish_time", null));

        if (pending.isEmpty()) {
            log.info("全文审查：任务 {} 没有待审章节，直接收尾", taskId);
            finishIfComplete(taskId);
            return toVO(taskMapper.selectById(taskId));
        }

        AiReviewTask fresh = taskMapper.selectById(taskId);
        dispatch(fresh, pending);
        return toVO(fresh);
    }

    @Override
    public void cancel(Long taskId) {
        AiReviewTask task = requireOwnTask(taskId);
        if (!isActive(task.getStatus())) {
            return;   // 已结束的任务再次取消无意义：保持原状，不覆盖作者已看到的结果
        }
        taskMapper.update(null, new UpdateWrapper<AiReviewTask>()
                .eq("id", taskId)
                .set("status", AiReviewTask.STATUS_ABORTED)
                .set("message", CANCEL_MSG)
                .set("finish_time", LocalDateTime.now()));
        log.info("全文审查：任务 {} 已被作者取消", taskId);
    }

    // ==================== 消费一章 ====================

    @Override
    public void processChapter(Long taskId, Long chapterId) {
        AiReviewTask task = taskMapper.selectById(taskId);
        if (task == null) {
            log.warn("全文审查：任务 {} 不存在，丢弃消息", taskId);
            return;
        }
        if (!isActive(task.getStatus())) {
            // 任务已结束（完成/中止/失败）：队列中剩余消息直接丢弃。
            // 这是「取消」与「额度用尽中止」能够立即生效的原因：无需实际清理队列。
            log.info("全文审查：任务 {} 已结束（status={}），跳过章节 {}", taskId, task.getStatus(), chapterId);
            return;
        }
        if (alreadyHandled(taskId, chapterId)) {
            log.info("全文审查：章节 {} 已处理过，跳过（消息重投）", chapterId);
            return;
        }

        // 章节元信息：写入结果表需要它提供「第几章、标题」，
        // 同时可验证「该章是否仍存在」（作者可能在任务执行过程中删除章节）
        ChapterVO meta = chapterOf(chapterId);

        AiReviewChapter row = newRow(task, chapterId, meta);
        try {
            chapterMapper.insert(row);
        } catch (DuplicateKeyException e) {
            // 另一消费者正在处理同一章：交由该消费者处理，本处不再重复扣费
            log.info("全文审查：章节 {} 正在被处理，本条跳过", chapterId);
            return;
        }

        if (meta == null) {
            log.info("全文审查：章节 {} 已不存在", chapterId);
            markChapterFailed(row, CHAPTER_GONE_MSG);
            taskMapper.accumulate(taskId, 1, 1, 0, 0, 0, 0);
            finishIfComplete(taskId);
            return;
        }

        ChapterReviewResult result;
        try {
            result = chapterReviewService.reviewChapterForTask(chapterId, task.getUserId(), task.getNovelTitle());
        } catch (BusinessException e) {
            boolean stopAll = e.getErrorCode() == ErrorCode.AI_QUOTA_EXHAUSTED
                    || e.getErrorCode() == ErrorCode.AI_PLATFORM_BUSY;
            markChapterFailed(row, CHAPTER_FAILED_MSG);
            taskMapper.accumulate(taskId, 1, 1, 0, 0, 0, 0);
            if (stopAll) {
                // 剩余章节继续执行结果相同，故停止任务并将原因写给作者。
                // 不重试：重试无法解决「今日额度已用完」。
                String message = e.getErrorCode() == ErrorCode.AI_QUOTA_EXHAUSTED
                        ? ABORT_QUOTA_MSG : ABORT_BUSY_MSG;
                taskMapper.finish(taskId, AiReviewTask.STATUS_ABORTED, message);
                log.info("全文审查：任务 {} 因「{}」中止", taskId, message);
            } else {
                finishIfComplete(taskId);
                log.warn("全文审查：章节 {} 处理被拒绝（{}）", chapterId, e.getMessage());
            }
            return;
        }

        if (!result.ok()) {
            markChapterFailed(row, result.message());
            taskMapper.accumulate(taskId, 1, 1, 0, 0, 0, result.refundedUnits());
            finishIfComplete(taskId);
            return;
        }

        saveIssues(taskId, row, result);
        row.setStatus(AiReviewChapter.STATUS_DONE);
        row.setSegments(result.segments());
        row.setReviewedChars(result.reviewedChars());
        row.setIssueCount(result.issues().size());
        row.setDroppedIssues(result.droppedIssues());
        row.setSummary(result.summary());
        chapterMapper.updateById(row);

        taskMapper.accumulate(taskId, 1, 0, result.issues().size(),
                result.reviewedChars(), result.chargedUnits(), 0);
        finishIfComplete(taskId);
    }

    @Override
    public void abandonChapter(Long taskId, Long chapterId, String reason) {
        AiReviewTask task = taskMapper.selectById(taskId);
        if (task == null || !isActive(task.getStatus())) {
            return;
        }
        AiReviewChapter row = chapterMapper.selectOne(new QueryWrapper<AiReviewChapter>()
                .eq("task_id", taskId).eq("chapter_id", chapterId).last("LIMIT 1"));
        if (row == null) {
            // 异常发生在占位行插入之前。需补写一行失败记录，否则「该章已处理」这一状态
            // 仅存在于消息中，重投时会被视为未处理，导致重复执行并重复扣费
            row = newRow(task, chapterId, chapterOf(chapterId));
            row.setStatus(AiReviewChapter.STATUS_FAILED);
            row.setMessage(truncate(reason, 255));
            try {
                chapterMapper.insert(row);
            } catch (DuplicateKeyException e) {
                return;   // 另一个消费者已经写下结果
            }
        } else if (Objects.equals(row.getStatus(), AiReviewChapter.STATUS_RUNNING)) {
            markChapterFailed(row, reason);
        } else {
            return;   // 已经有结论了，不要再累加一次进度
        }
        taskMapper.accumulate(taskId, 1, 1, 0, 0, 0, 0);
        finishIfComplete(taskId);
    }

    // ==================== 内部：派发、收尾、持久化 ====================

    /**
     * 逐章投递。在事务内通过 {@link MqSender#sendAfterCommit} 注册为提交后发送：
     * 若在任务行提交前发送消息，消费者回查时将查不到该任务。
     */
    private void dispatch(AiReviewTask task, List<ChapterVO> chapters) {
        for (ChapterVO chapter : chapters) {
            AiReviewMessage message = new AiReviewMessage();
            message.setTaskId(task.getId());
            message.setChapterId(chapter.getId());
            mqSender.sendAfterCommit(MqConstant.AI_EXCHANGE, MqConstant.AI_REVIEW_ROUTING_KEY, message);
        }
        log.info("全文审查任务 {} 派发 {} 章", task.getId(), chapters.size());
    }

    /**
     * 为「进行中」的任务补派发尚未处理完的章节。
     *
     * <p>仅在「复用已有进行中任务」的路径上调用。存在原因：该任务的进度可能已经停止
     * （创建时 MQ 不可达，或消费者中途退出），而 {@link #start} 判断其为「进行中」后直接返回，
     * 导致用户每次点击「全文审查」都取回同一个永不推进的任务，**无法再创建新任务**。
     * 补派发一次后任务即可自行推进，无需人工清理数据库。
     *
     * <p>幂等性由两层保证：消费端的「该章是否已处理」判定（{@link #alreadyHandled}，
     * 占位行也视为「处理中」，会走 DuplicateKey 分支让位给正在执行的消费者），
     * 以及 {@code uk_task_chapter} 唯一索引。
     */
    private void redispatchPending(AiReviewTask task) {
        List<ChapterVO> chapters = chapterService.listChapterBriefs(task.getNovelId());
        if (chapters.isEmpty()) {
            return;
        }
        Set<Long> handled = handledChapterIds(task.getId());
        List<ChapterVO> pending = chapters.stream()
                .filter(c -> inScope(task, c))
                .filter(c -> !handled.contains(c.getId()))
                .toList();
        if (pending.isEmpty()) {
            // 章节均已处理，仅任务状态未到达终态（例如进程在收尾前被终止）：
            // 补一次收尾判定，避免任务一直停留在「进行中」
            finishIfComplete(task.getId());
            return;
        }
        log.info("全文审查：任务 {} 已在进行中，补派发 {} 章（幂等；用于 MQ 不可达/消费者中断后的自愈）",
                task.getId(), pending.size());
        dispatch(task, pending);
    }

    /**
     * 处理完一章后判定任务是否应收尾。
     *
     * <p>收尾判据为 {@code done >= total}：{@code doneChapters} 记录的是
     * **含失败在内、已处理完的章数**（失败章通过 {@code accumulate(taskId, 1, 1, ...)} 累加，
     * 成功章通过 {@code accumulate(taskId, 1, 0, ...)} 累加）。
     *
     * <p>**注意：此处原先为 {@code done + failed >= total}，会将失败章重复计算一次**：
     * 2 章的任务中第 1 章失败后 done=1、failed=1，两者之和等于 total，任务立即判定为「已完成」，
     * 第 2 章的消息到达时被当作「任务已结束」丢弃，即**一次失败即可导致任务提前收尾、
     * 丢弃剩余全部章节**，且状态显示为「已完成」（降级冒烟测试发现，数据库中仅有 1 章明细）。
     * 章数越多该问题越明显：100 章中开头 50 章失败即会触发。
     */
    private void finishIfComplete(Long taskId) {
        AiReviewTask fresh = taskMapper.selectById(taskId);
        if (fresh == null || !isActive(fresh.getStatus())) {
            return;   // 已收尾或被取消：不覆盖
        }
        int total = nz(fresh.getTotalChapters());
        int failed = nz(fresh.getFailedChapters());
        if (nz(fresh.getDoneChapters()) < total) {
            return;
        }
        boolean allFailed = total > 0 && failed >= total;
        int status = allFailed ? AiReviewTask.STATUS_FAILED : AiReviewTask.STATUS_DONE;
        String message = allFailed ? ALL_FAILED_MSG
                : (failed > 0 ? String.format(DONE_WITH_FAILED_MSG, failed) : String.format(DONE_MSG, total));
        taskMapper.finish(taskId, status, message);
        log.info("全文审查任务 {} 收尾：status={} 已处理 {}/{} 章（失败 {}）问题 {} 条",
                taskId, status, fresh.getDoneChapters(), total, failed, fresh.getIssueCount());
    }

    private AiReviewChapter newRow(AiReviewTask task, Long chapterId, ChapterVO meta) {
        AiReviewChapter row = new AiReviewChapter();
        row.setTaskId(task.getId());
        row.setNovelId(task.getNovelId());
        row.setChapterId(chapterId);
        row.setChapterNo(meta == null || meta.getChapterNo() == null ? 0 : meta.getChapterNo());
        row.setChapterTitle(meta == null ? null : truncate(meta.getTitle(), 128));
        row.setStatus(AiReviewChapter.STATUS_RUNNING);
        row.setSegments(1);
        row.setReviewedChars(0);
        row.setIssueCount(0);
        row.setDroppedIssues(0);
        return row;
    }

    private void markChapterFailed(AiReviewChapter row, String message) {
        row.setStatus(AiReviewChapter.STATUS_FAILED);
        row.setMessage(truncate(message, 255));
        chapterMapper.updateById(row);
    }

    private void saveIssues(Long taskId, AiReviewChapter row, ChapterReviewResult result) {
        for (ChapterReviewResult.Issue issue : result.issues()) {
            String type = truncate(issue.type(), 16);
            String excerpt = truncate(issue.excerpt(), 255);
            AiReviewIssue entity = new AiReviewIssue();
            entity.setTaskId(taskId);
            entity.setChapterId(row.getChapterId());
            entity.setChapterNo(row.getChapterNo());
            entity.setChapterTitle(row.getChapterTitle());
            entity.setSegmentNo(issue.segmentNo());
            entity.setType(type);
            entity.setExcerpt(excerpt);
            entity.setSuggestion(truncate(issue.suggestion(), 512));
            // 去重键按**写入数据库后的值**计算：字段经过截断，按原始文本计算会出现
            // 「库中两行内容完全相同、去重键却不同」的异常情况
            entity.setDedupKey(dedupKey(type, excerpt));
            issueMapper.insert(entity);
        }
    }

    /** 跨章归并：同一个「类型 + 片段」只留第一条，其余记成「还出现在哪几章」 */
    private List<AiReviewIssueVO> reduceByDedupKey(List<AiReviewIssue> all) {
        Map<String, AiReviewIssue> representative = new LinkedHashMap<>();
        Map<String, Set<Integer>> chapters = new LinkedHashMap<>();
        for (AiReviewIssue issue : all) {
            String key = StringUtils.hasText(issue.getDedupKey())
                    ? issue.getDedupKey() : dedupKey(issue.getType(), issue.getExcerpt());
            representative.putIfAbsent(key, issue);
            chapters.computeIfAbsent(key, k -> new TreeSet<>())
                    .add(issue.getChapterNo() == null ? 0 : issue.getChapterNo());
        }

        List<AiReviewIssueVO> out = new ArrayList<>(representative.size());
        for (Map.Entry<String, AiReviewIssue> entry : representative.entrySet()) {
            AiReviewIssue issue = entry.getValue();
            Set<Integer> nos = chapters.get(entry.getKey());
            AiReviewIssueVO vo = new AiReviewIssueVO();
            vo.setId(issue.getId());
            vo.setChapterId(issue.getChapterId());
            vo.setChapterNo(issue.getChapterNo());
            vo.setChapterTitle(issue.getChapterTitle());
            vo.setSegmentNo(issue.getSegmentNo());
            vo.setType(issue.getType());
            vo.setExcerpt(issue.getExcerpt());
            vo.setSuggestion(issue.getSuggestion());
            vo.setChapterCount(nos.size());
            vo.setChapterNos(summarizeChapterNos(nos));
            out.add(vo);
        }
        return out;
    }

    private String summarizeChapterNos(Set<Integer> nos) {
        List<String> limited = nos.stream().limit(CHAPTER_NO_LIMIT).map(String::valueOf).toList();
        String joined = String.join("、", limited);
        return nos.size() > CHAPTER_NO_LIMIT ? joined + " 等" : joined;
    }

    // ==================== 内部：查询与小工具 ====================

    private AiReviewTask requireOwnTask(Long taskId) {
        AiReviewTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "审查任务不存在");
        }
        Long userId = LoginUserUtil.getUserId();
        if (!Objects.equals(task.getUserId(), userId) && !LoginUserUtil.isAdmin()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权限查看该审查任务");
        }
        return task;
    }

    /** 取一条：任务与章节均属「可能存在多条历史记录、只关心最新一条」的场景 */
    private AiReviewTask selectLatest(QueryWrapper<AiReviewTask> wrapper) {
        List<AiReviewTask> list = taskMapper.selectList(wrapper.last("LIMIT 1"));
        return list.isEmpty() ? null : list.get(0);
    }

    private boolean alreadyHandled(Long taskId, Long chapterId) {
        AiReviewChapter row = chapterMapper.selectOne(new QueryWrapper<AiReviewChapter>()
                .eq("task_id", taskId).eq("chapter_id", chapterId).last("LIMIT 1"));
        // 仅「审成」与「判失败」视为已处理；占位行（审查中）说明上次消费中途退出，
        // 本次重投需将该章继续处理完成，否则会一直停留在「审查中」
        return row != null && !Objects.equals(row.getStatus(), AiReviewChapter.STATUS_RUNNING);
    }

    private Set<Long> doneChapterIds(Long taskId) {
        List<AiReviewChapter> done = chapterMapper.selectList(new QueryWrapper<AiReviewChapter>()
                .select("chapter_id")
                .eq("task_id", taskId)
                .eq("status", AiReviewChapter.STATUS_DONE));
        return done.stream().map(AiReviewChapter::getChapterId).collect(Collectors.toSet());
    }

    /**
     * 已处理过的章（审成 + 判失败），**不含**仅占位的「审查中」：
     * 占位行说明上次消费中途退出，该章需重投补完（口径见 {@link #alreadyHandled}）。
     *
     * <p>用于补派发时一次性取出结果：{@code alreadyHandled} 为每章一次查询，
     * 几百章的书上点击一次「全文审查」会产生几百条 SQL。
     */
    private Set<Long> handledChapterIds(Long taskId) {
        List<AiReviewChapter> handled = chapterMapper.selectList(new QueryWrapper<AiReviewChapter>()
                .select("chapter_id")
                .eq("task_id", taskId)
                .ne("status", AiReviewChapter.STATUS_RUNNING));
        return handled.stream().map(AiReviewChapter::getChapterId).collect(Collectors.toSet());
    }

    private ChapterVO chapterOf(Long chapterId) {
        try {
            // 用无可见性判定的变体：本方法运行于 MQ 消费线程，无登录上下文，
            // 读者侧方法会把未公开作品的章节判为不存在，而此处的 null 会被记成「该章未审成」
            return chapterService.getChapterMetaById(chapterId);
        } catch (BusinessException e) {
            return null;   // 章节已删除：非故障，按「该章未审成」记录一条
        }
    }

    private AiReviewTaskVO toVO(AiReviewTask task) {
        if (task == null) {
            return null;
        }
        AiReviewTaskVO vo = new AiReviewTaskVO();
        vo.setTaskId(task.getId());
        vo.setNovelId(task.getNovelId());
        vo.setNovelTitle(task.getNovelTitle());
        vo.setStatus(task.getStatus());
        vo.setStatusText(statusText(task.getStatus()));
        vo.setRunning(isActive(task.getStatus()));
        vo.setTotalChapters(task.getTotalChapters());
        vo.setDoneChapters(task.getDoneChapters());
        vo.setFailedChapters(task.getFailedChapters());
        vo.setIssueCount(task.getIssueCount());
        vo.setChargedUnits(task.getChargedUnits());
        vo.setRefundedUnits(task.getRefundedUnits());
        vo.setReviewedChars(task.getReviewedChars());
        vo.setMessage(task.getMessage());
        vo.setCreateTime(task.getCreateTime());
        vo.setFinishTime(task.getFinishTime());

        int total = nz(task.getTotalChapters());
        int done = nz(task.getDoneChapters());
        vo.setPercent(total <= 0 ? 0 : (int) Math.round(done * 100.0 / total));

        int failed = nz(task.getFailedChapters());
        vo.setCanResume(!isActive(task.getStatus()) && total > 0
                && (failed > 0 || task.getStatus() == AiReviewTask.STATUS_ABORTED
                    || task.getStatus() == AiReviewTask.STATUS_FAILED));

        // 失败章节需明确到具体章号：若仅提示「有 3 章未审成」，作者需通读全书才能确定是哪几章
        List<AiReviewChapter> failures = chapterMapper.selectList(new QueryWrapper<AiReviewChapter>()
                .eq("task_id", task.getId())
                .eq("status", AiReviewChapter.STATUS_FAILED)
                .orderByAsc("chapter_no")
                .last("LIMIT " + (FAILED_LIST_LIMIT + 1)));
        vo.setFailedListTruncated(Math.max(0, failures.size() - FAILED_LIST_LIMIT));
        vo.setFailedList(failures.stream().limit(FAILED_LIST_LIMIT).map(row -> {
            AiReviewTaskVO.FailedChapterVO item = new AiReviewTaskVO.FailedChapterVO();
            item.setChapterId(row.getChapterId());
            item.setChapterNo(row.getChapterNo());
            item.setChapterTitle(row.getChapterTitle());
            item.setMessage(row.getMessage());
            return item;
        }).toList());
        return vo;
    }

    private String statusText(Integer status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case AiReviewTask.STATUS_QUEUED -> "排队中";
            case AiReviewTask.STATUS_RUNNING -> "审查中";
            case AiReviewTask.STATUS_DONE -> "已完成";
            case AiReviewTask.STATUS_ABORTED -> "已中止";
            default -> "未完成";
        };
    }

    private boolean isActive(Integer status) {
        return status != null
                && (status == AiReviewTask.STATUS_QUEUED || status == AiReviewTask.STATUS_RUNNING);
    }

    private String dedupKey(String type, String excerpt) {
        String t = type == null ? "" : type.trim();
        String e = excerpt == null ? "" : excerpt.replaceAll("\\s+", "").replace("\u3000", "");
        return truncate(t + "|" + e, 128);
    }

    /** 超长文本按列宽截断：模型返回的片段偶尔超出列定义，相比之下写入失败影响更大 */
    private String truncate(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, max);
    }

    private int nz(Integer value) {
        return value == null ? 0 : value;
    }
}
