package com.ainovel.module.comment.service;

import com.ainovel.common.domain.PageResult;
import com.ainovel.module.comment.domain.form.CommentForm;
import com.ainovel.module.comment.domain.vo.CommentVO;
import java.util.List;

/**
 * 评论服务：顶层评论分页 + 回复列表 + 发评论 + 删自己的 + 点赞（一人一赞）
 *
 * <p>作者昵称 / 回复数均一次批量回填，避免 N+1；点赞走 Redis 永久去重，与小说点赞一致。
 */
public interface CommentService {

    /** 书评分页（新→旧）：chapter_id IS NULL，与章评严格分开，避免两边内容互串 */
    public PageResult<CommentVO> page(int pageNum, int pageSize, Long novelId);

    /**
     * 章评分页（「本章说」）：按章节维度取顶层评论。
     *
     * <p>只按 chapterId 过滤即可：章节 ID 全局唯一，天然限定在某一本作品中，
     * 不必再叠加 novelId 条件。
     */
    public PageResult<CommentVO> chapterPage(int pageNum, int pageSize, Long chapterId);

    /**
     * 某评论的回复列表（旧→新）
     *
     * <p>带上限 {@code MAX_REPLIES}（上限值定义在实现类）。
     */
    public List<CommentVO> replies(Long commentId);

    /** 发表评论 / 回复（书评或章评，由 chapterId 决定） */
    public CommentVO add(CommentForm form);

    /** 删除自己的评论（顶层评论级联删回复，防止孤儿） */
    public void delete(Long id);

    /**
     * 点赞 / 取消点赞（切换）。
     *
     * <p>与小说点赞语义保持一致：一个人对一条评论只存在一个赞，再点一次是「取消」。
     * 此前的实现只增不减，误触后无法撤销。
     *
     * <p>去重状态按<b>评论</b>聚合存 Redis Hash（field = userId），而不是「用户:评论」散列 key：
     * 删评论时一次 DEL 即可全部清除，无需按 pattern 扫描键。
     *
     * @return 操作后的状态：true = 已点赞，false = 已取消
     */
    public boolean like(Long id);
}
