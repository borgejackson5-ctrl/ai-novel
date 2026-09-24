package com.ainovel.module.subscribe.domain.vo;

import lombok.Data;

import java.util.List;

/**
 * 某本书的解锁状态（批量返回，供详情页目录一次性回填，避免逐章 checkUnlocked 的 N+1）
 */
@Data
public class UnlockStatusVO {

    /** 是否已整本解锁（整本解锁时所有章节均视为已解锁） */
    private boolean wholeBook;

    /** 已单独解锁的章节 ID 列表（Long 经 Jackson 全局序列化为字符串，前端直接比对） */
    private List<Long> chapterIds;
}
