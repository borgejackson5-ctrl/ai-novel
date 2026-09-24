package com.ainovel.module.novel.spi;

/**
 * 逐章阅读人数的一行，{@link ChapterReaderStats#chapterReaders} 的返回元素。
 *
 * <p>原实现跨模块返回 MyBatis 的 {@code List<Map<String,Object>>}，调用方需依赖字段名推断结构；
 * 此处将结构固化，列名变更时可由编译器发现。
 */
public record ChapterReaderRow(Integer chapterNo, String chapterTitle, Long readers) {
}
