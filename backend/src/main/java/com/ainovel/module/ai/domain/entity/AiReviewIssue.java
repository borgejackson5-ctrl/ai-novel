package com.ainovel.module.ai.domain.entity;

import com.ainovel.common.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 全文审查发现的单条问题（可定位到章、到段）。
 *
 * <p>{@code chapterNo / chapterTitle} 为快照：章节可能被删除或重新编号，
 * 而审查报告描述的是「审查当时」的情况，随快照记录才不会指向其他章节。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_ai_review_issue")
public class AiReviewIssue extends BaseEntity {

    private Long taskId;

    private Long chapterId;

    private Integer chapterNo;

    private String chapterTitle;

    /** 问题所在的段号（长章切段后 >1；单段章恒为 1） */
    private Integer segmentNo;

    /** 错别字 / 语病 / 标点 / 前后不一致 */
    private String type;

    private String excerpt;

    private String suggestion;

    /** 归一化去重键（类型 + 去除空白后的片段）：跨章归并时同一处问题只上报一次 */
    private String dedupKey;
}
