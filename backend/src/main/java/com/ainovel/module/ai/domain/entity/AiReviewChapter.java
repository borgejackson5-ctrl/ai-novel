package com.ainovel.module.ai.domain.entity;

import com.ainovel.common.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 全文审查中**单章**的处理结果。
 *
 * <p>该表有两个不可省略的作用：
 * <ol>
 *   <li>**幂等判据**：唯一索引 {@code uk_task_chapter(task_id, chapter_id)}。
 *       消息重投时先查询/先插入，重复的那次直接跳过，不依赖内存记账（进程重启即丢失），
 *       也不依赖「是否存在问题记录」推断（已审且无问题的章不存在问题记录）；</li>
 *   <li>**回答「该章没有结果的原因」**：{@code status=2} 时由 {@code message} 写明原因。
 *       若仅有问题表，「全部已审且无问题」与「全部未审成」会表现为同一个空列表。</li>
 * </ol>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_ai_review_chapter")
public class AiReviewChapter extends BaseEntity {

    /** 审查中（占位行：先插入以占用唯一键，防止同一章被并发消费两次） */
    public static final int STATUS_RUNNING = 0;
    /** 已审完（无论是否发现问题） */
    public static final int STATUS_DONE = 1;
    /** 审查失败（额度不足、模型不可用、超时…） */
    public static final int STATUS_FAILED = 2;

    private Long taskId;

    private Long novelId;

    private Long chapterId;

    private Integer chapterNo;

    private String chapterTitle;

    private Integer status;

    /** 该章切分为几段送审（仅长章时 >1）：与作者看到的「超长章节分段审查」对应 */
    private Integer segments;

    private Integer reviewedChars;

    private Integer issueCount;

    /** 反幻觉指标：模型引用了正文里找不到的片段，被丢弃的条数，正常为 0 */
    private Integer droppedIssues;

    private String summary;

    private String message;
}
