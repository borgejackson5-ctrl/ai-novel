package com.ainovel.common.client;

import com.ainovel.common.code.ErrorCode;
import com.ainovel.common.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;

/**
 * 阿里云百炼（DashScope）文生图客户端
 *
 * <p>链路：提交异步任务 → 轮询任务状态 → 下载图片字节。DashScope 返回的是临时 URL（约 24h），
 * 调用方需尽快转存到自有 OSS 以获取永久地址。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DashScopeClient {

    private static final String SUBMIT_PATH = "/api/v1/services/aigc/text2image/image-synthesis";
    private static final String TASK_PATH = "/api/v1/tasks/";
    private static final int POLL_INTERVAL_MS = 3000;
    private static final int MAX_POLLS = 40; // 最多约 120s

    private final DashScopeProperties props;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private RestClient restClient;

    @PostConstruct
    void init() {
        this.restClient = RestClient.builder()
                .requestFactory(clientRequestFactory(60))
                .build();
    }

    /** 文生图：提交 → 轮询 → 下载，返回图片字节 */
    public byte[] textToImage(String prompt) {
        String apiKey = requireKey();
        String taskId = submit(apiKey, prompt);
        String url = poll(apiKey, taskId);
        return download(url);
    }

    private String requireKey() {
        if (!StringUtils.hasText(props.getApiKey())) {
            // 文案中不出现配置项名称（对用户无意义），需检查的内容仅写入日志
            log.warn("文生图未配置：dashscope.api-key 为空");
            throw new BusinessException(ErrorCode.AI_GENERATE_FAIL, "文生图功能暂不可用，请稍后再试");
        }
        return props.getApiKey();
    }

    private String submit(String apiKey, String prompt) {
        Map<String, Object> body = Map.of(
                "model", props.getModel(),
                "input", Map.of("prompt", prompt),
                "parameters", Map.of("size", props.getSize(), "n", 1)
        );
        try {
            String resp = restClient.post()
                    .uri(props.getBaseUrl() + SUBMIT_PATH)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("X-DashScope-Async", "enable")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(objectMapper.writeValueAsString(body))
                    .retrieve()
                    .body(String.class);
            JsonNode root = objectMapper.readTree(resp);
            String taskId = root.path("output").path("task_id").asText("");
            if (taskId.isEmpty()) {
                String msg = root.path("message").asText("未返回任务 ID");
                // 上游返回原文仅记入日志，不进入用户可见文案
                log.warn("文生图提交未拿到 task_id: {}", msg);
                throw new BusinessException(ErrorCode.AI_GENERATE_FAIL, "文生图提交失败，请稍后重试");
            }
            return taskId;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("文生图提交失败", e);
            throw new BusinessException(ErrorCode.AI_GENERATE_FAIL, "文生图提交失败，请稍后重试", e);
        }
    }

    private String poll(String apiKey, String taskId) {
        for (int i = 0; i < MAX_POLLS; i++) {
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BusinessException(ErrorCode.AI_GENERATE_FAIL, "文生图被中断", e);
            }
            try {
                String resp = restClient.get()
                        .uri(props.getBaseUrl() + TASK_PATH + taskId)
                        .header("Authorization", "Bearer " + apiKey)
                        .retrieve()
                        .body(String.class);
                JsonNode output = objectMapper.readTree(resp).path("output");
                String status = output.path("task_status").asText("");
                if ("SUCCEEDED".equals(status)) {
                    String url = output.path("results").path(0).path("url").asText("");
                    if (!url.isEmpty()) {
                        return url;
                    }
                    throw new BusinessException(ErrorCode.AI_GENERATE_FAIL, "文生图结果为空");
                }
                if ("FAILED".equals(status) || "CANCELED".equals(status)) {
                    String msg = output.path("message").asText("文生图任务失败");
                    log.warn("文生图任务失败: taskId={}, status={}, msg={}", taskId, status, msg);
                    throw new BusinessException(ErrorCode.AI_GENERATE_FAIL, "文生图生成失败，请稍后重试");
                }
                // PENDING / RUNNING：继续轮询
            } catch (BusinessException e) {
                throw e;
            } catch (Exception e) {
                log.error("查询文生图任务失败: taskId={}", taskId, e);
                throw new BusinessException(ErrorCode.AI_GENERATE_FAIL, "查询文生图任务失败", e);
            }
        }
        throw new BusinessException(ErrorCode.AI_GENERATE_FAIL, "文生图超时，请稍后重试");
    }

    private byte[] download(String url) {
        try {
            // 以 URI 原样构造：DashScope 返回的签名 URL 中 Signature 含 +/=（%2B/%3D），
            // 若使用 uri(String) 会被 RestClient 二次编解码，导致 OSS 报 SignatureDoesNotMatch
            return restClient.get().uri(java.net.URI.create(url)).retrieve().body(byte[].class);
        } catch (Exception e) {
            log.error("下载文生图失败: url={}", url, e);
            throw new BusinessException(ErrorCode.AI_GENERATE_FAIL, "下载生成图失败", e);
        }
    }

    private org.springframework.http.client.ClientHttpRequestFactory clientRequestFactory(int timeoutSeconds) {
        org.springframework.http.client.JdkClientHttpRequestFactory factory =
                new org.springframework.http.client.JdkClientHttpRequestFactory();
        factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        return factory;
    }
}
