package com.ainovel.module.bookshelf.domain.entity;

import com.ainovel.common.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 书架（收藏）实体：user_id + novel_id 唯一
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_bookshelf")
public class Bookshelf extends BaseEntity {

    private Long userId;

    private Long novelId;
}
