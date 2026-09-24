package com.ainovel.module.feedback.domain.entity;

import com.ainovel.common.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 用户反馈实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_feedback")
public class Feedback extends BaseEntity {

    private Long userId;

    /** 反馈类型（FEELING / SUGGESTION / BUG） */
    private String type;

    private String content;

    /** 选填联系方式 */
    private String contact;

    /** 是否匿名提交（0 实名 / 1 匿名） */
    private Integer anonymous;

    /** 处理状态（0 待处理 / 1 已采纳 / 2 未采纳） */
    private Integer status;

    /** 奖励虚拟币数（采纳时发放） */
    private Integer rewardCoin;

    private String reply;

    private LocalDateTime replyTime;
}
