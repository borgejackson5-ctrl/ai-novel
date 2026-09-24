package com.ainovel.module.novel.dao;

import com.ainovel.module.novel.domain.entity.NovelFact;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface NovelFactMapper extends BaseMapper<NovelFact> {

    /**
     * 同一「名词 = 数值」再次出现：仅累加次数，**不修改首次出现的章号**。
     *
     * <p>理由与名词表的 touch 一致：首次章号是上报给作者的依据，被后续章节覆盖后，
     * 建议中会写「前文第 80 章作 X」，而该章实际只是再次出现。
     *
     * <p>使用 {@code hit_count + 1} 而非先查后写：全文审查为并发消费。
     */
    @Update("""
            UPDATE t_novel_fact
               SET hit_count   = hit_count + 1,
                   update_time = NOW()
             WHERE novel_id = #{novelId} AND name = #{name} AND fact_value = #{factValue}
            """)
    int touch(@Param("novelId") Long novelId, @Param("name") String name,
              @Param("factValue") String factValue);
}
