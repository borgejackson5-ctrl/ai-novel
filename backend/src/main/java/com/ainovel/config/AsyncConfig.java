package com.ainovel.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 线程池配置：SSE 流式生成等长任务使用独立线程池，
 * 避免占用 ForkJoinPool.commonPool / Tomcat 工作线程
 *
 * <p>容量与背压的实际语义：同时执行的流式生成数为 core 4（满载后进入队列，最多 100 个），
 * 队列满后由 {@code CallerRunsPolicy} 交由调用线程执行，即对应的 HTTP 请求线程。
 * 即先排队、队列满后转为同步执行，不会丢弃任务；代价是高并发时占用 Tomcat 线程。
 * 当前生成一次仅调用一轮模型（秒级），该容量足够；若后续需要长任务（如整本审查），
 * 应改用 MQ + 进度查询，而非继续扩大该线程池。
 */
@Configuration
public class AsyncConfig {

    @Bean("sseExecutor")
    public Executor sseExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("sse-");
        // 队列满时由调用线程执行：退化为同步执行，不静默丢弃任务（见类注释）
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
