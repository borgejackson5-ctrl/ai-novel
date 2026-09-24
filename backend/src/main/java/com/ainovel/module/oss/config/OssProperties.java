package com.ainovel.module.oss.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 阿里云 OSS 配置
 *
 * <p>密钥不持久化、不提交至 git：仅由 gitignored 的 application-local.yaml 注入。
 * 未配置（accessKeyId 为空）时 {@code OssService} 会在调用时给出明确报错，而非启动失败。
 */
@Data
@Component
@ConfigurationProperties(prefix = "oss")
public class OssProperties {

    /** 地域 endpoint，如 oss-cn-beijing.aliyuncs.com */
    private String endpoint = "";

    private String accessKeyId = "";

    private String accessKeySecret = "";

    /** 存储桶名 */
    private String bucket = "";

    /** 公开访问域名（可为空，空则按 {bucket}.{endpoint} 拼接） */
    private String publicBaseUrl = "";
}
