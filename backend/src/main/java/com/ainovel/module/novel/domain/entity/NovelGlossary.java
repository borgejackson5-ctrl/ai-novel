package com.ainovel.module.novel.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 作品级专有名词表的一行：本书中某个「名字」的标准写法。
 *
 * <p><b>此处不继承 {@code BaseEntity}</b>（全项目唯一一处）：
 * {@code BaseEntity} 带 {@code is_deleted} + {@code @TableLogic}，而该表为**派生数据**：
 * 作品删除后该行无用途，保留孤儿行不影响任何查询；反之，带逻辑删除会触发
 * 「唯一索引不识别逻辑删除」问题：已删除的行仍占用 {@code (novel_id, name)}，
 * 重新累积同一名字时会产生唯一键冲突，且**不报错**，表现为「该名字无法再进入表」
 * （本项目在 {@code t_ai_review_chapter} 上曾出现该问题，见 memory/AI-NOTES.md）。
 *
 * <p>因此：审计字段照旧（由 {@code MetaObjectHandler} 填充），但不含逻辑删除列。
 */
@Data
@TableName("t_novel_glossary")
public class NovelGlossary {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long novelId;

    /** 标准写法本身（人名/地名/门派/功法/道具），2~6 个字 */
    private String name;

    /** 首次出现的章节：上报给作者的依据「前文第 N 章作 X」依赖该字段 */
    private Long firstChapterId;

    private Integer firstChapterNo;

    /** 被审查到的次数：数值越高表示该名字在书中的使用越稳定 */
    private Integer hitCount;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
