package com.ainovel.module.comment.domain.entity;

import com.ainovel.common.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 评论实体（评论 / 回复共用一张表，parentId 区分层级；书评 / 章评共用一张表，chapterId 区分）
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_comment")
public class Comment extends BaseEntity {

    /** 评论人用户 ID */
    private Long userId;

    /** 所属小说 ID */
    private Long novelId;

    /**
     * 章节 ID：null = 书评（评论整本书），非空 = 章评（评论某一章）。
     *
     * <p>两类评论共用一张表：章评本质上只是多了章节维度的评论，
     * 单独建表会使点赞、回复、删除、审核这些逻辑重复实现两遍。
     */
    private Long chapterId;

    /** 父评论 ID：null=顶层评论，非空=某评论的回复 */
    private Long parentId;

    private String content;

    /** 点赞数 */
    private Integer likeCount;
}
