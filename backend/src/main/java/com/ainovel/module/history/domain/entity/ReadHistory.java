package com.ainovel.module.history.domain.entity;

import com.ainovel.common.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 阅读历史实体（追加写，每读一章记一条；书名/章名冗余供列表展示免二次查询）
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_read_history")
public class ReadHistory extends BaseEntity {

    private Long userId;

    private Long novelId;

    private Long chapterId;

    private Integer chapterNo;

    private String novelTitle;

    private String chapterTitle;
}
