package com.ainovel.module.comment.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 评论返回视图（userId/novelId/chapterId/parentId 经 Jackson 序列化为字符串）
 */
@Data
public class CommentVO {

    private Long id;

    private Long userId;

    private Long novelId;

    /** 章节 ID：null = 书评；非空 = 章评（前端据此区分展示位置） */
    private Long chapterId;

    private Long parentId;

    private String content;

    private Integer likeCount;

    private LocalDateTime createTime;

    /** 评论人昵称（批量回填，防 N+1） */
    private String authorName;

    /** 顶层评论的回复数（仅顶层评论返回） */
    private Long replyCount;
}
