package com.ainovel.module.oss.service;


/**
 * OSS 上传服务（封面图）
 *
 * <p>桶整体保持私有，仅对单个对象设「公共读」ACL，使封面图可被前端 &lt;img&gt; 直接加载，
 * 其余对象仍不可匿名访问。上传成功后返回永久公开 URL 供持久化。
 */
public interface OssService {

    /**
     * 上传字节流到 OSS，返回公开访问 URL
     *
     * @param data        图片字节
     * @param dir         key 目录前缀（如 covers）
     * @param extension   扩展名（不含点，如 png/jpg）
     * @param contentType MIME 类型
     */
    public String upload(byte[] data, String dir, String extension, String contentType);

    /**
     * 按公开 URL 删除对象（幂等：OSS 对不存在的 key 同样返回成功）
     *
     * <p>供封面删除消息消费端调用：url → key → deleteObject。OSS 未配置时静默跳过，
     * 避免消费端对永久性错误反复重试后进入死信。
     */
    public void deleteByUrl(String url);
}
