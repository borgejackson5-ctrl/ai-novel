package com.ainovel.module.novel.service;

import com.ainovel.common.domain.PageResult;
import com.ainovel.module.novel.domain.entity.NovelAppeal;
import com.ainovel.module.novel.domain.form.AppealHandleForm;
import com.ainovel.module.novel.domain.vo.NovelAppealVO;
import org.springframework.transaction.annotation.Transactional;

/**
 * 作品申请工单：作者提交、管理员处理
 *
 * <p>目前仅有一种类型「解除完结」。实现为独立工单而非复用「意见反馈」，原因见
 * {@link NovelAppeal} 的类注释：反馈的语义是「意见」，管理员动作为采纳/不采纳，
 * 且可能发放奖励币；工单的语义是「业务申请」，动作为批准/驳回，批准后需变更业务状态。
 */
public interface NovelAppealService {

    /**
     * 管理端工单分页。status 为空则查全部。
     */
    public PageResult<NovelAppealVO> adminPage(int pageNum, int pageSize, Integer status);

    /**
     * 处理工单：批准则执行业务动作（恢复连载），驳回则仅修改工单状态。
     *
     * <p>两种结果均需向作者发送站内信：作者提交申请后处于等待状态，
     * 需自行翻查列表方可获知处理结果。
     */
    @Transactional(rollbackFor = Exception.class)
    public void handle(Long id, AppealHandleForm form);
}
