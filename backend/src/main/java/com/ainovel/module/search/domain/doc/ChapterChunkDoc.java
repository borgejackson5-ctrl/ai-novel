package com.ainovel.module.search.domain.doc;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;
import org.springframework.data.elasticsearch.annotations.KnnSimilarity;
import org.springframework.data.elasticsearch.annotations.Setting;

/**
 * 章节正文切块的向量文档（索引 `chapter_chunk`）。
 *
 * <p>与 {@link NovelDoc}（小说元数据）为两个独立索引，而非在同一索引上增加字段：
 * 前者为「一本书一条」，后者为「一章 N 条」，合并会使现有搜索查询全部失效。
 *
 * <p>同样需显式调用 createWithMapping()：save/saveAll 不会按注解建索引，
 * 动态映射会丢弃 analyzer 与 dense_vector 的 knn 设置。
 *
 * <p>dims 与 knnSimilarity 写入 mapping 后无法修改：更换 embedding 模型（维度变化）
 * 只能删除索引后重建。因此这两个值需与 `dashscope.embedding-dimensions` 对齐，
 * 而人工维护不可靠，故由 {@code ChapterVectorService#ensureIndex} 在启动时校验。
 */
@Data
@Document(indexName = "chapter_chunk")
@Setting(shards = 1, replicas = 0)
public class ChapterChunkDoc {

    /**
     * 主键：`novelId-chapterId-seq`。
     *
     * <p>使用业务键而非雪花 id：重新索引同一章时同 key 直接覆盖，天然幂等。
     * 若使用每次新生成的 id，重新索引会保留上一版分块，其向量对应旧文本，
     * 检索时会被返回，而正文中已无法找到对应内容。
     */
    @Id
    private String id;

    /** 作品 id：kNN 检索时按它过滤（限定在本书的分块内检索） */
    @Field(type = FieldType.Long)
    private Long novelId;

    @Field(type = FieldType.Long)
    private Long chapterId;

    /** 章号：报告中的「前文第 N 章作 X」依赖该字段 */
    @Field(type = FieldType.Integer)
    private Integer chapterNo;

    /** 章内块序号（从 0 起） */
    @Field(type = FieldType.Integer)
    private Integer seq;

    /**
     * 块正文。
     *
     * <p>必须可检索（默认 `index = true` + IK 分词）：混合检索的关键词一路
     * 以该字段为输入。原实现为 `index = false`（检索走向量，无需再建倒排索引），
     * 引入混合检索时修改。
     *
     * <p>注意：analyzer 与 index 写入 mapping 后均无法修改（与 {@link #vector} 的 dims 同类）。
     * 在历史索引上运行新代码时，关键词一路会返回 0 条且不报错，混合检索将静默退化为纯向量。
     * 因此 {@code ChapterVectorService#ensureIndex} 增加了该项校验，不一致时启动直接失败。
     */
    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String text;

    /** 向量：{@code index = true} 才建立 HNSW 图（缺少该设置时 knn 查询直接报错） */
    @Field(type = FieldType.Dense_Vector, dims = 1024, index = true,
            knnSimilarity = KnnSimilarity.COSINE)
    private float[] vector;
}
