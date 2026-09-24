package com.ainovel.module.ai.service.impl;

import com.ainovel.common.audit.SensitiveWordFilter;
import com.ainovel.common.code.ErrorCode;
import com.ainovel.common.constant.MessageTypeConstant;
import com.ainovel.common.enums.AuditStatusEnum;
import com.ainovel.common.enums.ChapterAuditStatusEnum;
import com.ainovel.common.exception.BusinessException;
import com.ainovel.module.ai.client.AiChatClient;
import com.ainovel.module.ai.domain.entity.AiConfig;
import com.ainovel.module.novel.dao.ChapterMapper;
import com.ainovel.module.novel.dao.NovelMapper;
import com.ainovel.module.novel.domain.entity.Chapter;
import com.ainovel.module.novel.domain.entity.Novel;
import com.ainovel.module.novel.service.ChapterService;
import com.ainovel.module.novel.service.NovelService;
import com.ainovel.module.message.service.MessageService;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import com.ainovel.module.ai.service.AiAuditService;
import com.ainovel.module.ai.service.AiConfigService;

/**
 * AI 内容审核服务（敏感词 + AI 双重校验）
 *
 * <p>定位为「预审」：命中违规直接拒绝并通知作者；
 * 预审通过后保持待审核状态，转人工终审（管理员可在后台通过/拒绝并修正分类）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiAuditServiceImpl implements AiAuditService {

    private final NovelMapper novelMapper;

    private final ChapterMapper chapterMapper;

    private final ChapterService chapterService;

    private final AiChatClient aiClient;

    private final AiConfigService aiConfigService;

    private final MessageService messageService;

    private final NovelService novelService;

    /** 敏感词过滤：词表在配置里（app.audit.sensitive-words），与评论发表共用同一份 */
    private final SensitiveWordFilter sensitiveWordFilter;

    /** 人工终审待办提示 */
    private static final String PRE_PASS_RESULT = "AI 预审通过，等待人工终审";

    /**
     * 执行预审：违规返回 false（已置拒绝），合规返回 true（已提醒管理员终审）
     */
    public boolean audit(Long novelId) {
        Novel novel = novelService.getNovel(novelId);
        if (novel == null) {
            throw new BusinessException(ErrorCode.NOVEL_NOT_FOUND);
        }

        // 变更待审(3)：需审核影子值（即将生效的新内容），而非前台正在展示的旧值
        boolean isModify = novel.getAuditStatus() != null
                && novel.getAuditStatus() == AuditStatusEnum.MODIFY_WAIT.getCode();
        String title = isModify ? novel.getPendingTitle() : novel.getTitle();
        String intro = isModify ? novel.getPendingIntro() : novel.getIntro();
        String content = (title == null ? "" : title) + " " + (intro == null ? "" : intro);

        // 1. 敏感词快速过滤：直接拒绝并通知作者
        String hitWord = sensitiveWordFilter.match(content);
        if (hitWord != null) {
            reject(novel, "包含敏感词：" + hitWord, isModify);
            return false;
        }

        // 2. AI 二次审核（配置了 key 时）：违规直接拒绝
        AiConfig config = aiConfigService.getActiveConfig();
        // 平台 Key 达全局日上限时跳过 AI 审核，仅敏感词过滤 + 转人工终审（不抛错触发 MQ 重试）
        if (StringUtils.hasText(config.getApiKey()) && aiConfigService.tryAcquirePlatformQuota()) {
            String result = aiClient.chat(config.getBaseUrl(), config.getApiKey(), config.getModel(),
                    config.getTemperature() == null ? 0.3 : config.getTemperature(),
                    "你是内容审核员，判断以下小说内容是否合规。只回复\"通过\"或\"拒绝:原因\"。",
                    content);
            if (result != null && result.startsWith("拒绝")) {
                reject(novel, result, isModify);
                return false;
            }
        }

        // 3. 预审通过：保持待审核（或变更待审），转人工终审
        Novel update = new Novel();
        update.setId(novelId);
        update.setAuditResult(PRE_PASS_RESULT);
        novelMapper.updateById(update);
        novelService.evictDetailCache(novelId);

        messageService.sendToAdmins(MessageTypeConstant.AUDIT_SUBMIT,
                isModify ? "作品信息变更待审核" : "新作品待审核",
                isModify
                        ? "用户「" + novel.getAuthor() + "」申请修改《" + title + "》的作品信息，请审核"
                        : "用户「" + novel.getAuthor() + "」发布了《" + title + "》，请审核",
                novelId);
        log.info("AI 预审通过，已转人工终审: novelId={}, modify={}", novelId, isModify);
        return true;
    }

    /**
     * 单章预审（连载/改章）：审章节标题 + 正文（变更待审章取 pending_content）的敏感词 + LLM。
     * 违规直接拒绝章节并通知作者；通过保持待审并提醒管理员终审。
     */
    public boolean auditChapter(Long chapterId) {
        Chapter chapter = chapterService.getById(chapterId);
        if (chapter == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "章节不存在");
        }
        Novel novel = novelService.getNovel(chapter.getNovelId());
        if (novel == null) {
            throw new BusinessException(ErrorCode.NOVEL_NOT_FOUND);
        }

        String body = StringUtils.hasText(chapter.getPendingContent())
                ? chapter.getPendingContent() : chapter.getContent();
        String content = (chapter.getTitle() == null ? "" : chapter.getTitle())
                + " " + (body == null ? "" : body);

        // 1. 敏感词快速过滤
        String hitWord = sensitiveWordFilter.match(content);
        if (hitWord != null) {
            rejectChapter(chapter, novel, "包含敏感词：" + hitWord);
            return false;
        }

        // 2. AI 二次审核（配置了 key 时）
        AiConfig config = aiConfigService.getActiveConfig();
        // 平台 Key 达全局日上限时跳过 AI 审核，仅敏感词过滤 + 转人工终审（不抛错触发 MQ 重试）
        if (StringUtils.hasText(config.getApiKey()) && aiConfigService.tryAcquirePlatformQuota()) {
            String result = aiClient.chat(config.getBaseUrl(), config.getApiKey(), config.getModel(),
                    config.getTemperature() == null ? 0.3 : config.getTemperature(),
                    "你是内容审核员，判断以下章节内容是否合规。只回复\"通过\"或\"拒绝:原因\"。",
                    content);
            if (result != null && result.startsWith("拒绝")) {
                rejectChapter(chapter, novel, result);
                return false;
            }
        }

        // 3. 预审通过：保持待审，转人工终审
        messageService.sendToAdmins(MessageTypeConstant.AUDIT_SUBMIT,
                "新章节待审核",
                "用户「" + novel.getAuthor() + "」更新了《" + novel.getTitle() + "》第"
                        + chapter.getChapterNo() + "章，请审核",
                chapterId);
        log.info("AI 章节预审通过，已转人工终审: chapterId={}", chapterId);
        return true;
    }

    /**
     * 单章预审拒绝：新增章置拒绝；变更待审章回退为通过（清影子正文，读者继续见旧版）。均通知作者。
     */
    private void rejectChapter(Chapter chapter, Novel novel, String reason) {
        boolean revertShadow = chapter.getAuditStatus() != null
                && chapter.getAuditStatus() == ChapterAuditStatusEnum.MODIFY_WAIT.getCode();

        Chapter update = new Chapter();
        update.setId(chapter.getId());
        update.setAuditResult(reason);
        if (revertShadow) {
            update.setAuditStatus(ChapterAuditStatusEnum.PASS.getCode());
            update.setPendingContent(null);
        } else {
            update.setAuditStatus(ChapterAuditStatusEnum.REJECT.getCode());
        }
        chapterMapper.updateById(update);

        if (revertShadow) {
            // 与人工驳回语义一致：影子正文被丢弃、正文退回旧版，currentBody() 发生变化，
            // 章节向量块必须重建，否则索引里留着那份被驳回的稿子
            chapterService.requestChunkReindex(chapter.getNovelId(), chapter.getId());
        }

        if (novel.getUserId() != null) {
            messageService.send(novel.getUserId(), MessageTypeConstant.AUDIT_REJECT,
                    "章节未通过审核",
                    "您的小说《" + novel.getTitle() + "》第" + chapter.getChapterNo() + "章未通过审核：" + reason,
                    chapter.getId());
        }
        log.info("AI 章节预审拒绝: chapterId={}, reason={}", chapter.getId(), reason);
    }

    /**
     * 预审拒绝。
     *
     * <p>变更待审(3) 被拒时**不置为拒绝态**：丢弃影子值、作品回落为「审核通过」，
     * 前台继续展示旧内容。否则一次改稿被 AI 拦截会导致已上架作品直接下架。
     * 与 {@code AdminService.auditReject} 的人工拒绝保持同一套语义。
     */
    private void reject(Novel novel, String reason, boolean isModify) {
        if (isModify) {
            // 影子字段需置回 null，updateById 会忽略 null，故使用 UpdateWrapper
            novelMapper.update(null, new LambdaUpdateWrapper<Novel>()
                    .eq(Novel::getId, novel.getId())
                    .set(Novel::getAuditStatus, AuditStatusEnum.PASS.getCode())
                    .set(Novel::getAuditResult, reason)
                    .set(Novel::getPendingTitle, null)
                    .set(Novel::getPendingIntro, null)
                    .set(Novel::getPendingCoverUrl, null)
                    .set(Novel::getPendingTags, null)
                    .set(Novel::getPendingCategoryId, null)
                    .set(Novel::getPendingAuthor, null));
            novelService.evictDetailCache(novel.getId());

            if (novel.getUserId() != null) {
                messageService.send(novel.getUserId(), MessageTypeConstant.AUDIT_REJECT,
                        "作品信息变更未通过审核",
                        "您的作品《" + novel.getTitle() + "》信息变更未通过审核，已保留原内容："
                                + reason + "（可修改后重新提交）",
                        novel.getId());
            }
            log.info("AI 预审拒绝作品变更: novelId={}, reason={}", novel.getId(), reason);
            return;
        }

        Novel update = new Novel();
        update.setId(novel.getId());
        update.setAuditStatus(AuditStatusEnum.REJECT.getCode());
        update.setAuditResult(reason);
        novelMapper.updateById(update);
        novelService.evictDetailCache(novel.getId());

        if (novel.getUserId() != null) {
            messageService.send(novel.getUserId(), MessageTypeConstant.AUDIT_REJECT,
                    "作品未通过审核",
                    "您的作品《" + novel.getTitle() + "》未通过审核：" + reason,
                    novel.getId());
        }
        log.info("AI 预审拒绝: novelId={}, reason={}", novel.getId(), reason);
    }
}
