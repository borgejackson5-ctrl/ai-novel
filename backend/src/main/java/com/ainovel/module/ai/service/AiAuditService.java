package com.ainovel.module.ai.service;

/**
 * AI 内容审核服务（敏感词 + AI 双重校验）
 *
 * <p>定位为「预审」：命中违规直接拒绝并通知作者；
 * 预审通过后保持待审核状态，转人工终审（管理员可在后台通过/拒绝并修正分类）。
 */
public interface AiAuditService {

    /**
     * 执行预审：违规返回 false（已置拒绝），合规返回 true（已提醒管理员终审）
     */
    public boolean audit(Long novelId);

    /**
     * 单章预审（连载/改章）：审章节标题 + 正文（变更待审章取 pending_content）的敏感词 + LLM。
     * 违规直接拒绝章节并通知作者；通过保持待审并提醒管理员终审。
     */
    public boolean auditChapter(Long chapterId);
}
