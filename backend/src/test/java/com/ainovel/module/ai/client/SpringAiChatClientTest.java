package com.ainovel.module.ai.client;

import com.ainovel.common.metrics.BusinessMetrics;
import com.ainovel.module.ai.config.AiProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;

/**
 * Spring AI 实现的契约测试：断言与手写版完全相同（见 {@link AiChatClientContract}）。
 *
 * <p>两个实现运行同一组用例，是「迁移是否真的等价」唯一有说服力的证据。
 * 下面有一处已确认的差异，用覆盖 + {@link Disabled} 显式记录，而不是放宽断言。
 */
class SpringAiChatClientTest extends AiChatClientContract {

    @Override
    AiChatClient createClient(AiProperties props) {
        // 不需要 init()：Spring AI 的实例在 builder().build() 时即已建好，没有 @PostConstruct 这一步。
        // 客户端工厂承担「按配置构建 + 缓存」，由普通对话与章节审查共用
        return new SpringAiChatClient(props, new AiChatClientFactory(props, HttpClient.newHttpClient()),
                new BusinessMetrics(new SimpleMeterRegistry()));
    }

    /**
     * 已知差异：Spring AI 这条流无法取消上游请求。
     *
     * <p>验证数据（假模型服务每 40ms 推一段、共 400 段）：消费方在第 2 段抛出取消异常后，
     * 服务端一路写下去（6 秒后已写到第 280 段，且没有任何一次写失败），
     * 说明客户端到上游的连接一直存活。手写版在同样场景下，服务端会在数秒内写失败
     * （连接确实断开），因此这一条在手写版上通过。
     *
     * <p>四种做法均已验证无效：① 让异常从 {@code doOnNext} 抛出；② 在消费方中
     * {@code Disposable.dispose()}；③ 退出前再 dispose 一次；④ 把工厂的传输从 RestClient 换成
     * WebClient + {@code JdkClientHttpConnector}。结论是框架那条流的读取循环只认 EOF，
     * 取消信号到不了该层。
     *
     * <p>另一条结论（无需重复验证）：为获取 {@code Disposable} 而改写成
     * 「自行 subscribe」反而更慢：同一场景下线程要 1439ms 才释放，
     * 而 {@code .blockLast()} + 从 {@code doOnNext} 抛出只需 118ms。
     * Reactor 本就会把 {@code onNext} 抛出的异常当作错误信号去取消上游订阅，
     * 手工订阅除更难理解外也更慢。因此实现中刻意保留 {@code .blockLast()}。
     *
     * <p>影响：应用层该做的都已做到（不再回调、线程很快释放、不退额度、日志记「客户端已断开」），
     * 但在默认实现下，用户点击停止后模型仍会把这个字生成完，消耗的是一段输出 token 与带宽。
     * 彻底解决只能绕开框架的流式读取（自行读取 SSE），等于放弃这处迁移的收益，暂不实施。
     * 需要真正取消时，把 {@code app.ai-client} 置为 {@code handwritten} 即可。
     */
    @Test
    @Disabled("已实测：Spring AI 的流只认 EOF，取消传不到上游读取循环（见方法注释里的四种尝试）")
    @DisplayName("chatStream → 取消要断掉上游（Spring AI 实现目前做不到，用默认实现时模型仍会生成完）")
    @Override
    void chatStream_cancelledByConsumer_closesUpstream() throws Exception {
        super.chatStream_cancelledByConsumer_closesUpstream();
    }
}
