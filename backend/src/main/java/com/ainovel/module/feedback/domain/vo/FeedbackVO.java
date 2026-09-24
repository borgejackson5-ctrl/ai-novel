package com.ainovel.module.feedback.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 反馈视图对象
 */
@Data
public class FeedbackVO {

    private Long id;
    private Long userId;
    private String type;
    private String content;
    private String contact;
    private Integer anonymous;
    private Integer status;
    private Integer rewardCoin;
    private String reply;
    private LocalDateTime replyTime;
    private LocalDateTime createTime;

    /** 实名反馈回填的用户身份（匿名时为空，保护隐私） */
    private String username;
    private String nickname;
}
