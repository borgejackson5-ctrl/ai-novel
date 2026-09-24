package com.ainovel.module.reader.domain.entity;

import com.ainovel.common.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 阅读进度实体（每用户一行 = 「继续阅读」书签）
 *
 * <p>只保留最后读到的一章 + 该章位置（scrollTop/pageNo），对应前端 localStorage 的
 * `reader-last`（全局单书签）。书名/章名冗余存储，续读栏展示免二次查询。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_reading_progress")
public class ReadingProgress extends BaseEntity {

    /** 用户 ID（唯一，一行一用户） */
    private Long userId;

    private Long novelId;

    private Long chapterId;

    /** 书名（冗余，供「继续阅读」栏展示） */
    private String novelTitle;

    private Integer chapterNo;

    /** 章标题（冗余） */
    private String chapterTitle;

    /** 阅读模式：scroll（滚动）/ page（仿真翻页） */
    private String mode;

    /** 滚动位置（滚动模式） */
    private Integer scrollTop;

    /** 页码（仿真模式） */
    private Integer pageNo;

    /** 客户端最后写入时间戳（ms，LWW 冲突合并用，null=旧客户端无此字段） */
    private Long clientTime;
}
