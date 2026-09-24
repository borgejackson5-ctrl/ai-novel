package com.ainovel.module.bookshelf.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 书架条目视图（小说信息回填后返回，novelId/readCount 经 Jackson 序列化为字符串）
 */
@Data
public class BookshelfVO {

    private Long novelId;

    private String novelTitle;

    private String coverUrl;

    private String author;

    private Long readCount;

    private Integer totalChapters;

    /**
     * 已下架（上架过后被下架）。书架条目仍保留并置灰标注，已解锁章节仍可读，
     * 作品下架不应导致条目消失。
     */
    private Boolean offline;

    /**
     * 作品已被删除（逻辑删除）。此时小说信息查不到，条目降级为占位，
     * 前端只展示「作品已删除」提示而不是一张空卡片。
     */
    private Boolean deleted;

    /** 收藏时间 */
    private LocalDateTime createTime;
}
