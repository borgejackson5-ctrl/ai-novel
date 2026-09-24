package com.ainovel.module.feedback.service;

import com.ainovel.common.domain.PageResult;
import com.ainovel.module.feedback.domain.form.FeedbackForm;
import com.ainovel.module.feedback.domain.form.FeedbackHandleForm;
import com.ainovel.module.feedback.domain.vo.FeedbackVO;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用户反馈服务：提交 + 我的分页 + 管理员分页 + 处理（采纳奖励虚拟币）
 *
 * <p>匿名反馈仅对管理员隐藏展示身份，user_id 始终写入数据库（用于奖励定位与防刷）。
 */
public interface FeedbackService {

    /** 提交反馈 */
    public void submit(FeedbackForm form);

    /** 我的反馈分页（新→旧） */
    public PageResult<FeedbackVO> pageMine(int pageNum, int pageSize);

    /** 管理员：反馈分页（可按状态/类型过滤），实名回填用户身份 */
    public PageResult<FeedbackVO> adminPage(int pageNum, int pageSize, Integer status, String type);

    /**
     * 管理员处理反馈：置状态 + 回复 +（采纳时）奖励虚拟币 + 站内信通知。
     *
     * <p>仅待处理态可处理（幂等防重复奖励）；加币与写入数据库在同一事务中，奖励失败则整体回滚。
     */
    @Transactional(rollbackFor = Exception.class)
    public void handle(Long id, FeedbackHandleForm form);

    /**
     * 待处理反馈数（管理端侧栏角标用）。
     *
     * <p>与站内信是两条独立通道：站内信提醒「有新反馈了」，这个数字反映「还有几条没处理」，
     * 读过消息不会让它归零。
     */
    public long pendingCount();
}
