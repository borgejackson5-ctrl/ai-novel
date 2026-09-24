package com.ainovel.module.novel.dao;

import com.ainovel.module.novel.domain.entity.NovelGlossary;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface NovelGlossaryMapper extends BaseMapper<NovelGlossary> {

    /**
     * 名字已在表中：仅累加出现次数，**不修改首次出现的章号**。
     *
     * <p>「首次出现在第几章」是该机制唯一的解释依据（建议中需写明「前文第 N 章作 X」），
     * 一旦被后续章节覆盖，作者会看到「第 80 章作 X」，而该章实际只是再次出现。
     *
     * <p>使用 {@code hit_count = hit_count + 1} 而非先查后写：全文审查为并发消费，
     * 读-改-写会相互覆盖（与进度累加问题的形态相同）。
     * 手写 SQL **不受逻辑删除自动注入保护**，该表本身不含 is_deleted 字段。
     */
    @Update("""
            UPDATE t_novel_glossary
               SET hit_count   = hit_count + 1,
                   update_time = NOW()
             WHERE novel_id = #{novelId} AND name = #{name}
            """)
    int touch(@Param("novelId") Long novelId, @Param("name") String name);
}
