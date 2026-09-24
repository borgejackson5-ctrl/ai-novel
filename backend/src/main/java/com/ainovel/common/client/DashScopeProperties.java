package com.ainovel.common.client;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 阿里云百炼（DashScope）配置：文生图 + 文本向量。
 *
 * <p>两项功能共用同一个 Key，因此置于同一前缀下：服务商侧为同一账号，
 * 拆成两份配置会使修改 Key 需要改两处。
 *
 * <p>密钥仅由 gitignored 的 application-local.yaml 注入，默认留空。
 */
@Data
@Component
@ConfigurationProperties(prefix = "dashscope")
public class DashScopeProperties {

    private String apiKey = "";

    private String baseUrl = "https://dashscope.aliyuncs.com";

    /** 文生图模型 */
    private String model = "wanx2.1-t2i-turbo";

    /** 出图尺寸（宽*高） */
    private String size = "1024*1024";

    // ==================== 文本向量（RAG 检索用） ====================

    /**
     * 文本向量模型。
     *
     * <p><b>需使用其它服务商的原因</b>：DeepSeek 官方 API **不提供 Embedding**
     * （仅有 chat/completions，无 embeddings 端点），而本项目的对话均走 DeepSeek。
     * 百炼的 Key 已在本配置类中，无需新开账号。
     */
    private String embeddingModel = "text-embedding-v3";

    /**
     * 向量维度。
     *
     * <p><b>该值确定后不可修改</b>：它写入 ES 的 `dense_vector` mapping，
     * 而 ES 不允许修改已存在字段的维度，切换维度只能删除索引后重建
     * （与「已存在字段的 analyzer 不可修改」属于同一约束）。因此更换 embedding 模型应视为
     * **破坏性变更**，而非修改配置后重启。
     *
     * <p>百炼 v3 支持 1024 / 768 / 512 / 256（均可调通）。此处取 1024 为精度优先；
     * 存储受限时可降至 512，但需重建索引。
     */
    private int embeddingDimensions = 1024;

    /**
     * 单次请求最大文本条数。
     *
     * <p>**该值为服务商的硬性限制**：单次传 25 条直接返回 400
     * （`batch size is invalid, it should not be larger than 10`）。
     * 调用方可传入任意长度的列表，由 {@code EmbeddingClient} 按该值切分后合并，
     * 不应将该限制暴露给上层。
     */
    private int embeddingBatchSize = 10;
}
