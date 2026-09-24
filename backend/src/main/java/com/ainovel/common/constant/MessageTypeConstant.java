package com.ainovel.common.constant;

/**
 * 站内信类型
 */
public final class MessageTypeConstant {

    /** 用户发布了新作品，提醒管理员审核 */
    public static final String AUDIT_SUBMIT = "AUDIT_SUBMIT";

    /** 审核通过，通知作者 */
    public static final String AUDIT_PASS = "AUDIT_PASS";

    /** 审核拒绝，通知作者（含原因） */
    public static final String AUDIT_REJECT = "AUDIT_REJECT";

    /** 申请工单处理结果（如「解除完结」通过 / 驳回），通知作者 */
    public static final String APPEAL_RESULT = "APPEAL_RESULT";

    /** 用户提交了反馈，提醒管理员处理 */
    public static final String FEEDBACK_SUBMIT = "FEEDBACK_SUBMIT";

    /**
     * 反馈处理结果（采纳 / 未采纳），通知提交者。
     *
     * <p>替代原先硬编码的 {@code "feedback_reward"}：后者仅在「采纳且有奖励」时发送，
     * 语义不符（当前三种结果均发送）。历史消息仍存于数据库中，前端映射表保留了旧名称。
     */
    public static final String FEEDBACK_RESULT = "FEEDBACK_RESULT";

    private MessageTypeConstant() {
    }
}
