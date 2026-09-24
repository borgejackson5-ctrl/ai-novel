package com.ainovel.common.client;

import java.util.List;

/**
 * 文本向量化的入口。
 *
 * <p><b>保留接口层的原因</b>：当前实现为百炼（`DashScopeEmbeddingClient`），
 * 但更换服务商在本项目中**必然会发生**：DeepSeek 无 Embedding API，
 * 而可能在需要控制成本时改为本地 Ollama 的 bge-m3。
 * 届时需修改的只有实现类与一个配置项，而非各处分散的 HTTP 调用。
 *
 * <p><b>契约（实现必须遵守）</b>：
 * <ol>
 *   <li>返回的向量**顺序与入参一一对应**，长度相等。条数不一致时**应抛异常**，
 *       不得返回错位的向量：错位不会报错，只会使检索结果不可预期，
 *       该问题的排查成本远高于一个 500。</li>
 *   <li>调用方可传入任意长度的列表；服务商的批量上限由实现内部处理
 *       （百炼为 10 条，见 {@code DashScopeProperties#embeddingBatchSize}）。</li>
 *   <li>失败时抛 {@code BusinessException}，message 需能识别出失败发生在「向量化」步骤：
 *       检索链路上「调用失败」与「无相关结果」在界面上表现一致，
 *       仅能通过日志与异常消息区分。</li>
 * </ol>
 */
public interface EmbeddingClient {

    /**
     * 把一批文本转成向量。
     *
     * @param texts 文本列表，非空时返回等长的向量列表；空列表返回空列表
     */
    List<float[]> embed(List<String> texts);
}
