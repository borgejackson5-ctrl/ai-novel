package com.ainovel.common.client;

import com.ainovel.common.code.ErrorCode;
import com.ainovel.common.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文本向量化：百炼（DashScope）的 OpenAI 兼容端点，模型 `text-embedding-v3`。
 *
 * <p>服务商接口的约束如下：
 * <ul>
 *   <li>端点 `/compatible-mode/v1/embeddings` 可用，与 `/chat/completions` 使用同一套 Bearer 鉴权，
 *       单次 3 条文本约 0.4 秒；</li>
 *   <li>**单次最多 10 条**，传 25 条返回 400（`batch size is invalid...`）；</li>
 *   <li>维度可指定 1024 / 768 / 512 / 256（均可调通），默认取 1024。</li>
 * </ul>
 *
 * <p><b>不使用 Spring AI 的 `EmbeddingModel` 而自行实现 RestClient</b>：
 * 该链路仅有一个 POST，请求体与响应结构固定；引入框架需额外配置四项
 * （apiKey / baseUrl / model / options），出错时还需判断「框架问题还是服务商问题」。
 * 手写实现约三十行，日志可直接定位失败步骤。后续接入 Ollama 时新增实现类即可。
 */
@Slf4j
@Component
public class DashScopeEmbeddingClient implements EmbeddingClient {

    /** OpenAI 兼容端点路径（与文生图那条原生 API 不是一个路径） */
    private static final String EMBEDDINGS_PATH = "/compatible-mode/v1/embeddings";

    private final DashScopeProperties props;

    /** RestClient 线程安全，作为单例复用 */
    private final RestClient restClient;

    public DashScopeEmbeddingClient(DashScopeProperties props) {
        this.props = props;
        this.restClient = RestClient.builder().baseUrl(props.getBaseUrl()).build();
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        int batchSize = Math.max(1, props.getEmbeddingBatchSize());
        if (texts.size() <= batchSize) {
            return embedBatch(texts);
        }
        // 服务商限制「单次最多 10 条」，此处自行切分后再合并。
        // 若将该限制交由调用方处理，调用方传入 25 条时会收到 400
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (int from = 0; from < texts.size(); from += batchSize) {
            vectors.addAll(embedBatch(texts.subList(from, Math.min(texts.size(), from + batchSize))));
        }
        log.info("文本向量化：{} 条文本，按每批 {} 条切成 {} 批完成",
                texts.size(), batchSize, (texts.size() + batchSize - 1) / batchSize);
        return vectors;
    }

    /**
     * 单批调用。
     *
     * <p>声明为 {@code protected} 而非 {@code private}：单测需覆盖「超过 10 条自动分批」的逻辑，
     * 而测试不应请求外部网络（耗时、依赖 Key、且不稳定）。
     */
    protected List<float[]> embedBatch(List<String> batch) {
        if (!StringUtils.hasText(props.getApiKey())) {
            throw new BusinessException(ErrorCode.AI_GENERATE_FAIL, "未配置文本向量模型的 Key");
        }
        Map<String, Object> body = new HashMap<>();
        body.put("model", props.getEmbeddingModel());
        body.put("input", batch);
        body.put("dimensions", props.getEmbeddingDimensions());
        body.put("encoding_format", "float");

        long start = System.currentTimeMillis();
        JsonNode response;
        try {
            response = restClient.post()
                    .uri(EMBEDDINGS_PATH)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + props.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (Exception e) {
            // 「调用失败」与「无相关结果」在界面上表现一致，仅能通过该日志区分，
            // 因此必须记录失败，不能静默吞掉并返回空向量
            log.warn("文本向量化失败：{} 条，耗时 {}ms", batch.size(), System.currentTimeMillis() - start, e);
            throw new BusinessException(ErrorCode.AI_GENERATE_FAIL, "文本向量化失败，请稍后重试");
        }
        List<float[]> vectors = parse(response, batch.size());
        log.info("文本向量化：{} 条，维度 {}，耗时 {}ms", vectors.size(),
                vectors.get(0).length, System.currentTimeMillis() - start);
        return vectors;
    }

    /**
     * 解析兼容端点的返回。
     *
     * <p>按返回中的 {@code index} 排序，而非按「接收顺序」：接口返回的数组理论上有序，
     * 但若出现乱序而按接收顺序取值，向量将与文本错位。该错位**不会报错**，
     * 只会使检索结果变为随机命中，属于最难排查的一类问题。
     */
    private List<float[]> parse(JsonNode response, int expected) {
        JsonNode data = response == null ? null : response.get("data");
        if (data == null || !data.isArray() || data.size() != expected) {
            int actual = data == null || !data.isArray() ? -1 : data.size();
            log.warn("文本向量化返回条数不对：期望 {}，实际 {}", expected, actual);
            throw new BusinessException(ErrorCode.AI_GENERATE_FAIL, "文本向量化返回的数据不完整");
        }
        List<JsonNode> items = new ArrayList<>();
        data.forEach(items::add);
        items.sort(Comparator.comparingInt(node -> node.path("index").asInt()));

        List<float[]> vectors = new ArrayList<>(expected);
        for (JsonNode item : items) {
            JsonNode values = item.get("embedding");
            if (values == null || !values.isArray() || values.isEmpty()) {
                throw new BusinessException(ErrorCode.AI_GENERATE_FAIL, "文本向量化返回的数据不完整");
            }
            float[] vector = new float[values.size()];
            for (int i = 0; i < values.size(); i++) {
                vector[i] = (float) values.get(i).asDouble();
            }
            vectors.add(vector);
        }
        return vectors;
    }
}
