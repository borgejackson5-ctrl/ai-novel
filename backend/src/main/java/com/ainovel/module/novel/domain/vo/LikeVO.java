package com.ainovel.module.novel.domain.vo;

import lombok.Data;

/**
 * 点赞结果视图：点赞是 toggle（一人一赞可取消），返回本次操作后的最新状态与计数，
 * 供前端据此高亮按钮、刷新计数，避免再发一次详情请求。
 */
@Data
public class LikeVO {

    /** 本次操作后是否处于「已点赞」状态（true=刚点赞 / false=刚取消） */
    private Boolean liked;

    /** 最新点赞数（Long 全局序列化为字符串，前端直接展示） */
    private Long likeCount;
}
