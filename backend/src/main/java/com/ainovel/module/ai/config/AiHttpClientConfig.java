package com.ainovel.module.ai.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * AI 调用共用的 {@link HttpClient}。
 *
 * <p>**共享单个 bean 而非各处分别创建**：本站支持 BYOK，客户端按「地址 + 模型 + Key 指纹 + 超时档」
 * 缓存（见 {@code AiChatClientFactory}），该缓存上限为 200 个配置。若每个配置各自
 * {@code new JdkClientHttpRequestFactory()}，每个 factory 都会持有自己的
 * {@code HttpClient}（JDK 默认构造器即 {@code HttpClient.newHttpClient()}），
 * 而 {@code HttpClientImpl} 在**首次请求时**会启动自己的 selector 线程，
 * 于是线程数会随「配置过自有 Key 的用户数」增长：该数值与业务量无关，
 * 属于资源随用户数线性上涨。
 *
 * <p>共享一个实例后：连接池按 host 分开（不同服务商不会互相污染），
 * 线程只有一份。readTimeout 仍保留在各自的 {@code JdkClientHttpRequestFactory} 上，
 * 两条超时档的隔离不受影响。
 *
 * <p>**连接超时在此显式设置**：JDK 默认**不设**连接超时（即无限等待，
 * 实际由操作系统的 TCP 重传超时兜底：Windows 约 21 秒、Linux 约 130 秒）。
 * 两个 AI 客户端设置的 readTimeout 会映射为 {@code HttpRequest.timeout}
 * （该时限覆盖整个请求、包含建连阶段，因此不会永久阻塞），但其含义是
 * 「标准档（默认 60 秒）遇到不可达地址时需等满 60 秒才报错」。
 * 建连握手本身仅需几百毫秒，单独设置一个短上限更为合理，
 * 错误类型也更明确（{@code HttpConnectTimeoutException} 而非笼统的超时）。
 */
@Configuration
public class AiHttpClientConfig {

    /** 配置缺省或为空时的兜底建连超时（秒） */
    private static final int DEFAULT_CONNECT_TIMEOUT_SECONDS = 5;

    /**
     * 全应用共用的 AI HTTP 客户端。
     *
     * <p>**刻意不设置 {@code followRedirects}**：原先未设置（JDK 默认 {@code NEVER}），
     * 保持行为一致。服务商地址应为最终地址，跟随跳转会把该 Key 发送到重定向目标。
     */
    @Bean
    HttpClient aiHttpClient(AiProperties props) {
        Integer configured = props.getConnectTimeoutSeconds();
        int seconds = configured == null || configured <= 0 ? DEFAULT_CONNECT_TIMEOUT_SECONDS : configured;
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(seconds))
                .build();
    }
}
