package com.ainovel.module.comment.dao;

import com.ainovel.module.comment.domain.entity.Comment;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface CommentMapper extends BaseMapper<Comment> {

    /**
     * 点赞数原子自增
     */
    @Update("UPDATE t_comment SET like_count = like_count + 1 WHERE id = #{id} AND is_deleted = 0")
    int incrLikeCount(@Param("id") Long id);

    /**
     * 点赞数原子自减。
     *
     * <p>带 {@code like_count > 0} 兜底：任何异常路径（重复取消、状态寄存器丢失）
     * 都不会把计数减为负数；点赞数为负属于无法解释的状态。
     */
    @Update("UPDATE t_comment SET like_count = like_count - 1 "
            + "WHERE id = #{id} AND like_count > 0 AND is_deleted = 0")
    int decrLikeCount(@Param("id") Long id);
}
