package com.ainovel.module.comment.domain.form;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 发表评论 / 回复请求（雪花 ID 字符串由 Jackson 自动转 Long）
 */
@Data
public class CommentForm {

    @NotNull(message = "小说ID不能为空")
    private Long novelId;

    /**
     * 章节 ID：null（或不传）= 书评；非空 = 该章的章评。
     *
     * <p>回复别人的评论时沿用父评论所在的维度，前端不必重复指定。
     */
    private Long chapterId;

    /** 回复的评论 ID；null 表示顶层评论 */
    private Long parentId;

    @NotBlank(message = "评论内容不能为空")
    @Size(max = 500, message = "评论最长 500 字")
    private String content;
}
