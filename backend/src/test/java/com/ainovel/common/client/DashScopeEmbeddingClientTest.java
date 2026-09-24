package com.ainovel.common.client;

import com.ainovel.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 向量客户端中不需要发起网络请求的部分：批量切分与快速失败。
 *
 * <p>真实调用（端点、鉴权、返回结构）在回填链路中执行一次即可验证，不纳入单测：
 * 单测访问外网会成为「需要 Key、需要网络、且慢」的测试，最终难免被 @Disabled。
 */
class DashScopeEmbeddingClientTest {

    private DashScopeProperties props;

    @BeforeEach
    void init() {
        props = new DashScopeProperties();
        props.setApiKey("sk-test");
        props.setEmbeddingBatchSize(10);
        props.setEmbeddingDimensions(1024);
    }

    /** 覆盖真正发起请求的那一步，只记录「每批传入的条数」 */
    private static class Recorder extends DashScopeEmbeddingClient {
        private final List<Integer> batches = new ArrayList<>();

        Recorder(DashScopeProperties props) {
            super(props);
        }

        @Override
        protected List<float[]> embedBatch(List<String> batch) {
            batches.add(batch.size());
            return batch.stream().map(text -> new float[1024]).toList();
        }
    }

    private List<String> texts(int count) {
        return IntStream.range(0, count).mapToObj(i -> "第 " + i + " 段文本").toList();
    }

    @Test
    @DisplayName("超过 10 条自动分批 —— 服务商一次最多收 10 条，传 25 条直接 400（实测）")
    void splitsByBatchSize() {
        Recorder client = new Recorder(props);

        List<float[]> vectors = client.embed(texts(25));

        assertEquals(List.of(10, 10, 5), client.batches, "必须切成 10/10/5 三批");
        assertEquals(25, vectors.size(), "拼回来的向量条数必须与入参一致，否则向量和文本就错位了");
    }

    @Test
    @DisplayName("不足一批就不分批（别让每次调用都走一遍分批路径）")
    void singleBatchWhenSmall() {
        Recorder client = new Recorder(props);

        client.embed(texts(3));

        assertEquals(List.of(3), client.batches);
    }

    @Test
    @DisplayName("空列表不发请求")
    void emptyInputSendsNothing() {
        Recorder client = new Recorder(props);

        assertTrue(client.embed(List.of()).isEmpty());
        assertTrue(client.embed(null).isEmpty());
        assertTrue(client.batches.isEmpty(), "空输入不该产生任何一次调用");
    }

    @Test
    @DisplayName("没配 Key ⇒ 立刻报错，不打网络（也别悄悄返回空向量）")
    void blankKeyFailsFast() {
        props.setApiKey("");
        DashScopeEmbeddingClient client = new DashScopeEmbeddingClient(props);

        BusinessException e = assertThrows(BusinessException.class, () -> client.embed(texts(1)));

        assertTrue(e.getMessage().contains("Key"), "错误信息要能看出是缺 Key，而不是笼统的「失败」");
    }
}
