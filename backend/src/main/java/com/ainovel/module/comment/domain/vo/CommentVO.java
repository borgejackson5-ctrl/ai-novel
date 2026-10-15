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

    /**
     * 当前登录用户是否已点赞（读者侧列表回填，游客恒为 false）。
     *
     * <p>列表必须带上该字段：点赞接口是切换语义，前端若只按「点一次加一」渲染，
     * 第二次点击（服务端已取消）界面仍在递增，刷新后自己点过的赞又消失。
     *
     * <p>管理端分页不回填（管理端关心的是点赞数，而非管理员本人赞没赞过）。
     */
    private Boolean liked;

    /** 所属作品标题（仅管理端分页回填，读者侧为 null） */
    private String novelTitle;

    /** 所属章节章号（仅管理端分页回填；书评为 null） */
    private Integer chapterNo;
}
