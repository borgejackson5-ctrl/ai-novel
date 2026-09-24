package com.ainovel.common.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实调用探针：需要真实 Key，默认不执行。
 *
 * <p>执行方式：`EMBEDDING_PROBE=true mvn test -Dtest=EmbeddingLiveProbeTest`
 * （Key 从本地配置读取，见下：刻意不从命令行传入，命令行会进入 shell 历史与进程列表）
 *
 * <p>需要它的原因：端点是否可用、维度是否正确、换一种说法还能否召回，
 * 这三件事单测都无法回答（单测访问外网会成为「需要 Key、需要网络、且慢」的测试，
 * 最终难免被 @Disabled）。而它们是选型的前提，需要有一条可随时重跑的验证路径。
 */
@EnabledIfEnvironmentVariable(named = "EMBEDDING_PROBE", matches = "true",
        disabledReason = "真实调用探针：需要网络的场景手动跑（EMBEDDING_PROBE=true）")
class EmbeddingLiveProbeTest {

    private static final int DIMENSIONS = 1024;

    @Test
    @DisplayName("端点可用 + 维度正确 + 批量 25 条自动分批")
    void endpointWorksAndSplitsBatches() {
        DashScopeEmbeddingClient client = client();

        List<String> texts = IntStream.range(0, 25).mapToObj(i -> "第 " + i + " 句测试文本。").toList();
        List<float[]> vectors = client.embed(texts);

        assertEquals(25, vectors.size());
        assertEquals(DIMENSIONS, vectors.get(0).length, "维度必须与配置一致 —— 它写进 ES mapping 就改不了");
    }

    @Test
    @DisplayName("换个说法也能召回（RAG 相对关键词检索的价值所在）")
    void semanticRecallBeatsKeyword() {
        DashScopeEmbeddingClient client = client();
        List<String> docs = List.of(
                "他把那把伞拿起来，伞骨一共九根。",       // 目标句
                "柳砚秋站在门外，肩上落了一层雪。",
                "屋里只有一张桌子，桌上摆着一只粗瓷碗。",
                "灯油快见了底，火苗矮下去。");
        // 注意该查询中没有「伞骨」二字：关键词检索（现有 IK 分词方案）在这里无法命中
        String query = "这把伞的骨架是几根？";

        List<String> inputs = new ArrayList<>(docs);
        inputs.add(query);
        List<float[]> all = client.embed(inputs);
        float[] queryVector = all.get(all.size() - 1);

        int best = 0;
        double bestScore = -1;
        double secondScore = -1;
        for (int i = 0; i < docs.size(); i++) {
            double score = cosine(queryVector, all.get(i));
            if (score > bestScore) {
                secondScore = bestScore;
                bestScore = score;
                best = i;
            } else if (score > secondScore) {
                secondScore = score;
            }
        }
        System.out.printf("PROBE 语义召回：Top1=%.4f「%s」　Top2=%.4f（区分度 %.4f）%n",
                bestScore, docs.get(best), secondScore, bestScore - secondScore);

        assertEquals(0, best, "目标句应该排第一，实际第一是：" + docs.get(best));
        assertTrue(bestScore > 0.7, "相似度太低（" + bestScore + "），这个模型的语义区分能力不足以做检索");
        assertTrue(bestScore - secondScore > 0.15,
                "第一名与第二名贴太近（" + bestScore + " / " + secondScore + "），检索结果会不稳定");
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        return na == 0 || nb == 0 ? 0 : dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private static DashScopeEmbeddingClient client() {
        DashScopeProperties props = new DashScopeProperties();
        props.setApiKey(localApiKey());
        props.setEmbeddingDimensions(DIMENSIONS);
        return new DashScopeEmbeddingClient(props);
    }

    /**
     * 从本地配置读取 Key。
     *
     * <p>刻意不从命令行 / 环境变量传入：那两处都会留下痕迹（shell 历史、进程列表、CI 日志）。
     * 该文件本身被 gitignore，读取它没有额外风险。
     */
    private static String localApiKey() {
        Path path = Path.of("src/main/resources/application-local.yaml");
        try {
            String text = Files.readString(path);
            Matcher block = Pattern.compile("dashscope:(.*?)(?=\\n\\S|\\z)", Pattern.DOTALL).matcher(text);
            if (block.find()) {
                Matcher key = Pattern.compile("api-key:\\s*\"?([^\"\\n]+)\"?").matcher(block.group(1));
                if (key.find()) {
                    return key.group(1).trim();
                }
            }
            throw new IllegalStateException("application-local.yaml 里找不到 dashscope.api-key");
        } catch (IOException e) {
            throw new IllegalStateException("读不到 " + path.toAbsolutePath(), e);
        }
    }
}
