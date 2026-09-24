package com.ainovel.module.ai.dao;

import com.ainovel.module.ai.domain.entity.AiReviewChapter;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AiReviewChapterMapper extends BaseMapper<AiReviewChapter> {

    /**
     * 删除本任务中「审失败」的章节行：**物理删除**，非逻辑删除。
     *
     * <p>唯一索引 {@code uk_task_chapter(task_id, chapter_id)} 不识别逻辑删除：
     * 若保留一行 {@code is_deleted=1} 的失败记录，重新审查该章时插入会命中唯一键，
     * 导致「继续审查」永远无法补上这几章，且整个过程不报错，仅表现为进度不再推进。
     *
     * <p>仅删除 {@code status=2} 的行：审成的章（{@code status=1}）是「继续审查」时
     * 需跳过的对象，保留它们才不会使作者为同一章重复付费。
     */
    @Delete("DELETE FROM t_ai_review_chapter WHERE task_id = #{taskId} AND status = "
            + AiReviewChapter.STATUS_FAILED)
    int deleteFailed(@Param("taskId") Long taskId);
}
