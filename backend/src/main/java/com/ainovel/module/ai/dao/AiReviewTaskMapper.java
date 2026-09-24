package com.ainovel.module.ai.dao;

import com.ainovel.module.ai.domain.entity.AiReviewTask;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AiReviewTaskMapper extends BaseMapper<AiReviewTask> {

    /**
     * 原子累加任务进度。
     *
     * <p>不采用「查询 + 修改字段 + updateById」：多条章节消息为并发消费，
     * 读-改-写之间会互相覆盖，表现为「进度条跳变、最终停留在总数之前」，
     * 且日志无异常、不报错。交由 DB 执行 {@code col = col + n} 累加更为可靠。
     *
     * <p>手写 SQL **不受逻辑删除自动注入保护**：数据库列名为 {@code is_deleted}
     * （实体字段名为 {@code deleted}），此处必须自行带上该条件。
     * {@code update_time} 同样不会被自动填充，需显式写入。
     */
    @Update("""
            UPDATE t_ai_review_task
               SET done_chapters   = done_chapters + #{done},
                   failed_chapters = failed_chapters + #{failed},
                   issue_count     = issue_count + #{issues},
                   reviewed_chars  = reviewed_chars + #{chars},
                   charged_units   = charged_units + #{charged},
                   refunded_units  = refunded_units + #{refunded},
                   status          = CASE WHEN status = 0 THEN 1 ELSE status END,
                   update_time     = NOW()
             WHERE id = #{taskId} AND is_deleted = 0
            """)
    int accumulate(@Param("taskId") Long taskId,
                   @Param("done") int done,
                   @Param("failed") int failed,
                   @Param("issues") int issues,
                   @Param("chars") int chars,
                   @Param("charged") int charged,
                   @Param("refunded") int refunded);

    /**
     * 收尾：写入最终状态与面向作者的说明。
     *
     * <p>条件中的 {@code status IN (0, 1)} 是**幂等闸门**：收尾可能被多个消费者同时判定，
     * 也会遇到「任务已被作者取消」的情况，此时不应将「已中止」改回「已完成」。
     * 改为条件更新后，重复收尾仅影响 0 行，不会覆盖既有状态。
     */
    @Update("""
            UPDATE t_ai_review_task
               SET status      = #{status},
                   message     = #{message},
                   finish_time = NOW(),
                   update_time = NOW()
             WHERE id = #{taskId} AND is_deleted = 0 AND status IN (0, 1)
            """)
    int finish(@Param("taskId") Long taskId,
               @Param("status") int status,
               @Param("message") String message);
}
