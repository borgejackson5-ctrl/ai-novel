package com.ainovel.module.novel.spi;

import java.util.List;

/**
 * 逐章阅读统计端口（由 history 模块实现），供作者数据看板聚合。
 *
 * <p>口径为去重 UV（同一用户反复阅读同一章仅计一个读者），与 history 模块内部保持一致。
 */
public interface ChapterReaderStats {

    /** 整本书的去重读者数。 */
    long distinctReaders(Long novelId);

    /** 逐章阅读人数（按章节序号升序）。 */
    List<ChapterReaderRow> chapterReaders(Long novelId);
}
