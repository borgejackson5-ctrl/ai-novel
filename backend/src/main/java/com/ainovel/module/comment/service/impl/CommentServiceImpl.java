package com.ainovel.module.comment.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.ainovel.common.audit.SensitiveWordFilter;
import com.ainovel.common.code.ErrorCode;
import com.ainovel.common.domain.PageResult;
import com.ainovel.common.enums.ChapterAuditStatusEnum;
import com.ainovel.common.exception.BusinessException;
import com.ainovel.common.util.LoginUserUtil;
import com.ainovel.module.comment.dao.CommentMapper;
import com.ainovel.module.comment.domain.entity.Comment;
import com.ainovel.module.comment.domain.form.CommentForm;
import com.ainovel.module.comment.domain.vo.CommentVO;
import com.ainovel.module.novel.domain.entity.Chapter;
import com.ainovel.common.domain.PageParam;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import com.ainovel.module.comment.service.CommentService;
import com.ainovel.module.novel.service.ChapterService;
import com.ainovel.module.user.service.UserService;

/**
 * 评论服务：顶层评论分页 + 回复列表 + 发评论 + 删自己的 + 点赞（一人一赞）
 *
 * <p>作者昵称 / 回复数均一次批量回填，避免 N+1；点赞走 Redis 永久去重，与小说点赞一致。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommentServiceImpl implements CommentService {

    private final CommentMapper commentMapper;

    private final UserService userService;

    private final ChapterService chapterService;

    private final StringRedisTemplate stringRedisTemplate;

    private final SensitiveWordFilter sensitiveWordFilter;

    /** 评论点赞状态寄存器：key 按评论聚合（field = 点赞用户 id），删评论时一次 DEL 清干净 */
    private static final String COMMENT_LIKE_KEY_PREFIX = "comment:like:";

    /**
     * 单条评论最多返回多少条回复。
     *
     * <p>回复列表是全站唯一一个「取全部」的评论查询（分页与计数都是另一套口径），
     * 而一条热评的回复数无天然上界；不设限会一次性将该父评论下的所有行加载进内存。
     */
    private static final int MAX_REPLIES = 200;

    /** 书评分页（新→旧）：chapter_id IS NULL，与章评严格分开，避免两边内容互串 */
    public PageResult<CommentVO> page(int pageNum, int pageSize, Long novelId) {
        return pageInternal(pageNum, pageSize, new LambdaQueryWrapper<Comment>()
                .eq(Comment::getNovelId, novelId)
                .isNull(Comment::getChapterId));
    }

    /**
     * 章评分页（「本章说」）：按章节维度取顶层评论。
     *
     * <p>只按 chapterId 过滤即可：章节 ID 全局唯一，天然限定在某一本作品中，
     * 不必再叠加 novelId 条件。
     */
    public PageResult<CommentVO> chapterPage(int pageNum, int pageSize, Long chapterId) {
        return pageInternal(pageNum, pageSize, new LambdaQueryWrapper<Comment>()
                .eq(Comment::getChapterId, chapterId));
    }

    private PageResult<CommentVO> pageInternal(int pageNum, int pageSize,
                                               LambdaQueryWrapper<Comment> wrapper) {
        Page<Comment> page = commentMapper.selectPage(
                new Page<>(PageParam.clampPage(pageNum), PageParam.clampSize(pageSize)),
                wrapper.isNull(Comment::getParentId).orderByDesc(Comment::getId));
        List<CommentVO> vos = page.getRecords().stream()
                .map(c -> BeanUtil.copyProperties(c, CommentVO.class))
                .toList();
        fillAuthorNames(vos);
        fillReplyCounts(vos);
        return PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(), vos);
    }

    /**
     * 某评论的回复列表（旧→新）
     *
     * <p>带上限，理由见 {@link #MAX_REPLIES}。
     */
    public List<CommentVO> replies(Long commentId) {
        List<CommentVO> vos = commentMapper.selectList(
                        new LambdaQueryWrapper<Comment>()
                                .eq(Comment::getParentId, commentId)
                                .orderByAsc(Comment::getId)
                                .last("LIMIT " + MAX_REPLIES))
                .stream().map(c -> BeanUtil.copyProperties(c, CommentVO.class)).toList();
        fillAuthorNames(vos);
        return vos;
    }

    /** 发表评论 / 回复（书评或章评，由 chapterId 决定） */
    public CommentVO add(CommentForm form) {
        Long userId = LoginUserUtil.getUserId();
        Long chapterId = form.getChapterId();

        // 回复时沿用父评论所在的维度：前端只传 parentId 也能落到正确的一侧。
        // 否则回复一条章评却不带 chapterId 时，该回复会归入「书评」维度；
        // 它不会跨维度显示（回复按 parentId 取），但数据归属错误。
        if (form.getParentId() != null) {
            Comment parent = commentMapper.selectById(form.getParentId());
            if (parent == null || !parent.getNovelId().equals(form.getNovelId())) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "要回复的评论不存在");
            }
            if (chapterId == null) {
                chapterId = parent.getChapterId();
            }
        }
        if (chapterId != null) {
            requireChapterCommentable(chapterId, form.getNovelId());
        }

        // 内容安全：评论此前不做过滤（敏感词仅用于作品/章节预审），
        // 但评论区同为公开分发面，此处接入与预审相同的词表。
        // 提示中不包含命中词，避免向试探者暴露哪些词被拦截。
        if (sensitiveWordFilter.contains(form.getContent())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "评论包含不适宜的内容，请修改后再试");
        }

        Comment c = new Comment();
        c.setUserId(userId);
        c.setNovelId(form.getNovelId());
        c.setChapterId(chapterId);
        c.setParentId(form.getParentId());
        c.setContent(form.getContent());
        c.setLikeCount(0);
        commentMapper.insert(c);
        CommentVO vo = BeanUtil.copyProperties(c, CommentVO.class);
        vo.setAuthorName(nicknameMap(List.of(userId)).getOrDefault(userId, ""));
        return vo;
    }

    /**
     * 章评只对「读者可见的章节」开放。
     *
     * <p>在服务端拦截而非依赖前端隐藏入口：未过审章节的正文仍在影子字段中，
     * 若允许评论，等同于确认「该章节存在且已完稿」，也会使审核中的内容提前出现讨论。
     */
    private void requireChapterCommentable(Long chapterId, Long novelId) {
        Chapter chapter = chapterService.getById(chapterId);
        if (chapter == null || !chapter.getNovelId().equals(novelId)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "章节不存在");
        }
        if (!ChapterAuditStatusEnum.isVisible(chapter.getAuditStatus())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "该章节尚未发布，暂不可评论");
        }
    }

    /** 删除自己的评论（顶层评论级联删回复，防止孤儿） */
    public void delete(Long id) {
        Long userId = LoginUserUtil.getUserId();
        Comment c = commentMapper.selectById(id);
        if (c == null || !c.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "评论不存在");
        }
        commentMapper.deleteById(id);
        clearLikeState(id);
        if (c.getParentId() == null) {
            // 先取回复 id 再删除：删除后无法获知带走了哪些 id，点赞状态寄存器将永久残留在 Redis 中
            List<Long> replyIds = commentMapper.selectList(
                            new LambdaQueryWrapper<Comment>().eq(Comment::getParentId, id))
                    .stream().map(Comment::getId).toList();
            commentMapper.delete(new LambdaQueryWrapper<Comment>().eq(Comment::getParentId, id));
            replyIds.forEach(this::clearLikeState);
        }
    }

    /** 清除某条评论的点赞状态寄存器（评论已删除时该状态无保留意义） */
    private void clearLikeState(Long commentId) {
        stringRedisTemplate.delete(COMMENT_LIKE_KEY_PREFIX + commentId);
    }

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
    public boolean like(Long id) {
        Long userId = LoginUserUtil.getUserId();
        if (commentMapper.selectById(id) == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "评论不存在");
        }
        String key = COMMENT_LIKE_KEY_PREFIX + id;
        String field = String.valueOf(userId);
        Boolean added = stringRedisTemplate.opsForHash().putIfAbsent(key, field, "1");
        if (Boolean.TRUE.equals(added)) {
            commentMapper.incrLikeCount(id);
            return true;
        }
        stringRedisTemplate.opsForHash().delete(key, field);
        commentMapper.decrLikeCount(id);
        return false;
    }

    /** 批量回填作者昵称（一次查询，避免 N+1） */
    private void fillAuthorNames(List<CommentVO> vos) {
        Set<Long> userIds = vos.stream().map(CommentVO::getUserId).collect(Collectors.toSet());
        Map<Long, String> names = nicknameMap(userIds);
        vos.forEach(v -> v.setAuthorName(names.get(v.getUserId())));
    }

    /** 批量统计顶层评论的回复数（一次查询，按 parentId 分组） */
    private void fillReplyCounts(List<CommentVO> vos) {
        if (vos.isEmpty()) {
            return;
        }
        List<Long> ids = vos.stream().map(CommentVO::getId).toList();
        Map<Long, Long> counts = commentMapper.selectList(
                        new LambdaQueryWrapper<Comment>().in(Comment::getParentId, ids))
                .stream().collect(Collectors.groupingBy(Comment::getParentId, Collectors.counting()));
        vos.forEach(v -> v.setReplyCount(counts.getOrDefault(v.getId(), 0L)));
    }

    /** 用户 ID → 昵称（昵称优先，回退用户名） */
    private Map<Long, String> nicknameMap(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return userService.getNicknameMap(userIds);
    }
}
