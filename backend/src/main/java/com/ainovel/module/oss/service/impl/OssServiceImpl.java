package com.ainovel.module.oss.service.impl;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.CannedAccessControlList;
import com.aliyun.oss.model.ObjectMetadata;
import com.ainovel.common.code.ErrorCode;
import com.ainovel.common.exception.BusinessException;
import com.ainovel.module.oss.config.OssProperties;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import java.io.ByteArrayInputStream;
import java.util.UUID;
import com.ainovel.module.oss.service.OssService;

/**
 * OSS 上传服务（封面图）
 *
 * <p>桶整体保持私有，仅对单个对象设「公共读」ACL，使封面图可被前端 &lt;img&gt; 直接加载，
 * 其余对象仍不可匿名访问。上传成功后返回永久公开 URL 供持久化。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OssServiceImpl implements OssService {

    private final OssProperties props;

    private volatile OSS client;

    /**
     * 上传字节流到 OSS，返回公开访问 URL
     *
     * @param data        图片字节
     * @param dir         key 目录前缀（如 covers）
     * @param extension   扩展名（不含点，如 png/jpg）
     * @param contentType MIME 类型
     */
    public String upload(byte[] data, String dir, String extension, String contentType) {
        String key = dir + "/" + UUID.randomUUID().toString().replace("-", "") + "." + extension;
        OSS oss = client();

        ObjectMetadata meta = new ObjectMetadata();
        meta.setContentType(contentType);
        meta.setContentLength(data.length);

        try {
            oss.putObject(props.getBucket(), key, new ByteArrayInputStream(data), meta);
            // 对象级公共读：桶私有、仅此图公开
            oss.setObjectAcl(props.getBucket(), key, CannedAccessControlList.PublicRead);
        } catch (Exception e) {
            log.error("OSS 上传失败: key={}", key, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "封面上传失败，请稍后重试", e);
        }
        return publicUrl(key);
    }

    /**
     * 按公开 URL 删除对象（幂等：OSS 对不存在的 key 同样返回成功）
     *
     * <p>供封面删除消息消费端调用：url → key → deleteObject。OSS 未配置时静默跳过，
     * 避免消费端对永久性错误反复重试后进入死信。
     */
    public void deleteByUrl(String url) {
        if (!StringUtils.hasText(url)) {
            return;
        }
        if (!isConfigured()) {
            log.warn("OSS 未配置，跳过对象删除: url={}", url);
            return;
        }
        String key = extractKey(url);
        try {
            client().deleteObject(props.getBucket(), key);
            log.info("OSS 删除对象成功: key={}", key);
        } catch (Exception e) {
            log.error("OSS 删除对象失败: key={}", key, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "封面删除失败，请稍后重试", e);
        }
    }

    /** 懒加载 OSS 客户端；未配置时给出明确报错而非启动失败 */
    private OSS client() {
        if (client == null) {
            synchronized (this) {
                if (client == null) {
                    if (!isConfigured()) {
                        // 用户可见文案中不包含配置文件名（属实现细节）；
                        // 具体检查项写入日志，供运维查看
                        log.warn("OSS 未配置：缺少 oss.access-key-id / access-key-secret / bucket 之一");
                        throw new BusinessException(ErrorCode.SYSTEM_ERROR, "封面服务暂不可用，请稍后再试");
                    }
                    client = new OSSClientBuilder().build(
                            props.getEndpoint(), props.getAccessKeyId(), props.getAccessKeySecret());
                }
            }
        }
        return client;
    }

    private boolean isConfigured() {
        return StringUtils.hasText(props.getAccessKeyId())
                && StringUtils.hasText(props.getAccessKeySecret())
                && StringUtils.hasText(props.getBucket());
    }

    private String publicUrl(String key) {
        return publicUrlBase() + "/" + key;
    }

    /** 公开访问 base（自定义域名优先，否则 {bucket}.{endpoint}） */
    private String publicUrlBase() {
        return StringUtils.hasText(props.getPublicBaseUrl())
                ? props.getPublicBaseUrl().replaceAll("/+$", "")
                : "https://" + props.getBucket() + "." + props.getEndpoint();
    }

    /** 从公开 URL 反推对象 key（去掉 base 前缀；前缀不匹配时回退取 URL path） */
    private String extractKey(String url) {
        String base = publicUrlBase();
        if (url.startsWith(base + "/")) {
            return url.substring(base.length() + 1);
        }
        try {
            String path = new java.net.URI(url).getPath();
            return path.startsWith("/") ? path.substring(1) : path;
        } catch (Exception e) {
            // URL 不进用户可见文案（实现细节），只进日志
            log.warn("封面地址无法解析: url={}", url);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "封面地址无效", e);
        }
    }

    @PreDestroy
    void shutdown() {
        if (client != null) {
            client.shutdown();
        }
    }
}
