package com.ainovel.module.novel.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 作品级设定事实表的一行：这本书里某个东西的「数量/尺寸/时长」。
 *
 * <p>与 {@link NovelGlossary} 成对：后者管理「名字的写法」，本类管理「数字的取值」。
 * 分表的理由写在 {@code sql/migration_add_novel_fact.sql} 里（唯一键的形态不同）。
 *
 * <p>同样**不继承 {@code BaseEntity}**（不带 {@code is_deleted}），理由见 {@link NovelGlossary}。
 */
@Data
@TableName("t_novel_fact")
public class NovelFact {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long novelId;

    /** 被计量的东西：刀、伞骨、年龄 */
    private String name;

    /** 正文中原样出现的数值（含量词：「七根」「两尺」）；上报给作者时需原样写回，不可改写为「7」 */
    private String factValue;

    /**
     * 该数值的来源：{@code model}=模型审查时上报，{@code scan}=服务端从正文中扫描得到。
     *
     * <p>需区分来源的原因：两条路径的可信度不同。模型上报的值通过三道校验（名词在正文中、
     * 数值在正文中、两者距离不超过 {@code FACT_GAP}）；服务端扫描的值额外受量词白名单约束
     * （排除「天/年/月/日」这类时间词，否则「沈青梧三天没合眼」会被记为「沈青梧=三天」）。
     * 若后续某类值被判定为噪声，可借助该列分别统计，并仅删除其中一类。
     */
    private String source;

    /** 首次出现的章节：上报给作者的依据「前文第 N 章作 X」依赖该字段 */
    private Long firstChapterId;

    private Integer firstChapterNo;

    /** 被审查到的次数 */
    private Integer hitCount;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
