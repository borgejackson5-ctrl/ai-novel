package com.ainovel.module.search.domain.doc;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;
import org.springframework.data.elasticsearch.annotations.Setting;

/**
 * 小说搜索文档（Elasticsearch 索引）
 *
 * <p>与 MySQL t_novel 解耦，专门为全文搜索优化：
 * <ul>
 *   <li>title/author/intro/tags 用 IK 分词（ik_max_word 索引细切 / ik_smart 查询粗切）</li>
 *   <li>categoryId/readCount/status 用精确类型，用于过滤与排序</li>
 * </ul>
 *
 * <p>注意：这些注解仅在索引创建时生效。Spring Data 的 save/saveAll
 * 不会按本类建索引，必须由 SearchService#ensureIndex 显式调用 createWithMapping()。
 * 否则索引将由 ES 动态映射创建，上述 analyzer 全部丢失。
 *
 * <p>replicas=0：本项目为单节点部署，副本分片无处分配会使集群持续处于 yellow 状态，
 * 因此显式设为 0。若将来扩展为多节点，此处需改回 1。
 */
@Data
@Document(indexName = "novel")
@Setting(shards = 1, replicas = 0)
public class NovelDoc {

    @Id
    private Long id;

    /** 书名：搜索权重最高 */
    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String title;

    /**
     * 作者名：搜索权重仅次于书名。
     *
     * <p>搜索框的占位文案是「搜索书名 / 作者」，索引中缺少该字段时按作者搜索必然返回 0 条，
     * 且不报错（表现为「搜作者搜不到」）。中文人名需 IK 分词，
     * 否则搜索「曹」会命中大量无关作品。
     */
    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String author;

    /** 简介 */
    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String intro;

    /** 标签（逗号分隔，IK 分词后可命中） */
    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String tags;

    @Field(type = FieldType.Long)
    private Long categoryId;

    /** 阅读量：列表展示用（当前排序完全交给 BM25 相关度，未参与排序） */
    @Field(type = FieldType.Long)
    private Long readCount;

    /** 点赞量：列表展示用 */
    @Field(type = FieldType.Long)
    private Long likeCount;

    /** 总章节数：列表展示用 */
    @Field(type = FieldType.Integer)
    private Integer totalChapters;

    /** 作品字数：供书库按「字数区间」筛选（与 Novel#wordCount 同为 Long，免去装箱转换） */
    @Field(type = FieldType.Long)
    private Long wordCount;

    /** 状态：1 上架 0 下架，搜索时只返回上架 */
    @Field(type = FieldType.Integer)
    private Integer status;

    /** 连载状态：0 连载中 / 1 已完结（书卡要展示，也供将来按状态筛选） */
    @Field(type = FieldType.Integer)
    private Integer serialStatus;
}
