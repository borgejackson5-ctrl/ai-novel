package com.ainovel.module.novel.spi;

import java.util.List;

/**
 * 作品收藏数端口（由 bookshelf 模块实现），供作者数据看板聚合。
 *
 * <p>收藏关系存储于书架模块的库中，novel 不直接读取该表，仅获取结论。
 */
public interface NovelCollectCounter {

    long countByNovel(Long novelId);

    /**
     * 收藏数最多的作品 id（榜单回源用）。
     *
     * <p>「哪几本书被收藏得最多」仅书架侧可提供，因此该能力挂在收藏端口上：
     * 其查询 join 了作品表（需按可见性过滤），但数据源在收藏一侧。
     */
    List<Long> topNovelIdsByCollect(int limit);
}
