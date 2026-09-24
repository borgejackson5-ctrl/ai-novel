package com.ainovel.module.novel.domain.entity;

import com.ainovel.common.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 作品申请工单：作者 → 管理员的业务申请
 *
 * <p>与「意见反馈」（{@code t_feedback}）刻意分开，两者语义不同：
 * <ul>
 *   <li>反馈是<b>意见</b>，管理员的动作是「采纳 / 不采纳」，还可能发奖励币；</li>
 *   <li>工单是<b>业务申请</b>，管理员的动作是「批准 / 驳回」，且批准后要<b>改业务状态</b>
 *       （如把作品从「已完结」恢复为「连载中」）。</li>
 * </ul>
 * 混在一张表里会使奖励逻辑被误触发，状态语义也无法对应。
 *
 * <p>独立工单表的另一好处：后续「申请删除作品」「申请恢复下架」均可纳入，
 * 无需每新增一个申请就修改一次反馈表。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_novel_appeal")
public class NovelAppeal extends BaseEntity {

    /** 申请类型：解除完结 */
    public static final String TYPE_RESUME_SERIAL = "RESUME_SERIAL";

    private Long novelId;

    /** 申请人用户 ID */
    private Long userId;

    /** 申请类型，见 {@link #TYPE_RESUME_SERIAL} */
    private String type;

    private String reason;

    /** 0 待处理 / 1 已通过 / 2 已驳回 */
    private Integer status;

    private String adminReply;

    /** 处理人（管理员）用户 ID */
    private Long handleUserId;

    private LocalDateTime handleTime;
}
