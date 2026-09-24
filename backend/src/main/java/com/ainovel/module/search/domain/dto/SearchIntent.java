package com.ainovel.module.search.domain.dto;

import lombok.Data;

import java.util.List;

/**
 * 智能搜索结构化意图（AI 解析自然语言后的产物）
 */
@Data
public class SearchIntent {

    /** 检索关键词：multi_match 全文检索用 */
    private List<String> keywords;

    /** 题材标签：命中 tags 字段，权重更高 */
    private List<String> tags;

    /** 分类 ID：null 表示不限分类 */
    private Long categoryId;
}
