package com.ainovel.module.novel.service.impl;

import com.ainovel.common.code.ErrorCode;
import com.ainovel.common.exception.BusinessException;
import com.ainovel.common.ratelimit.DailyQuotaLimiter;
import com.ainovel.common.client.DashScopeClient;
import com.ainovel.module.oss.service.OssService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import com.ainovel.module.novel.service.CoverService;

/**
 * 封面服务：AI 生成（文生图）或用户上传，统一转存 OSS 并返回永久公开 URL。
 *
 * <p>版权约束（任务书）：AI 原创封面为安全来源；用户自行上传需在前端确认拥有版权/使用权，
 * 后端仅做类型与大小校验，内容合规交由既有审核链路兜底。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CoverServiceImpl implements CoverService {

    /** 允许的上传扩展名（小写，含点） */
    private static final Set<String> ALLOWED_EXT = Set.of(".png", ".jpg", ".jpeg", ".webp", ".gif");
    private static final long MAX_SIZE = 5 * 1024 * 1024; // 5MB

    /** 文件头魔数：扩展名可被任意修改而文件头不可，因此以文件头判定真实类型 */
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] JPEG_MAGIC = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] GIF_MAGIC = {0x47, 0x49, 0x46, 0x38};
    private static final byte[] RIFF_MAGIC = {0x52, 0x49, 0x46, 0x46};
    private static final byte[] WEBP_MAGIC = {0x57, 0x45, 0x42, 0x50};

    /** 文生图固定风格后缀：统一出图观感并避免文字水印 */
    private static final String STYLE_SUFFIX = "，竖版小说封面，精美插画风格，画面无文字";

    /** 文生图每日硬上限计数器 key 前缀（独立于文本 Key 的计数器） */
    private static final String COVER_USAGE_KEY_PREFIX = "ai:cover:usage:";

    /**
     * 封面在 OSS 上的目录前缀。
     *
     * <p>抽取为常量是因为它有两个调用点：改名时遗漏一处会出现
     * 「新封面在新目录、旧封面在旧目录」，两侧均不报错，但无法对齐。
     */
    private static final String COVER_DIR = "ai-novel/covers";

    private final DashScopeClient dashScopeClient;

    private final OssService ossService;

    private final DailyQuotaLimiter dailyQuotaLimiter;

    /** 文生图每日硬上限（图比文本贵，独立且更严，可配置） */
    @Value("${app.ai-cover-daily-limit:20}")
    private int coverDailyLimit;

    /** AI 生成封面：文生图 → 下载 → 转存 OSS */
    public String generate(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "请填写封面描述");
        }
        // 文生图使用独立的 DashScope Key，单独设置每日硬上限；超限后提示可手动上传封面
        // 文生图按「次」计（重量 1）：每次生成计一张图，与字数无关
        if (!dailyQuotaLimiter.tryAcquire(COVER_USAGE_KEY_PREFIX, coverDailyLimit, 1)) {
            throw new BusinessException(ErrorCode.AI_COVER_LIMIT);
        }
        String finalPrompt = prompt.trim() + STYLE_SUFFIX;
        byte[] image = dashScopeClient.textToImage(finalPrompt);
        return ossService.upload(image, COVER_DIR, "png", "image/png");
    }

    /** 用户上传封面：大小 → 后缀白名单 → 文件头魔数 → 转存 OSS */
    public String upload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "请选择封面图片");
        }
        if (file.getSize() > MAX_SIZE) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "封面图片不能超过 5MB");
        }
        String ext = extractExt(file.getOriginalFilename());
        if (ext == null || !ALLOWED_EXT.contains(ext)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "仅支持 png/jpg/jpeg/webp/gif 格式图片");
        }
        byte[] data;
        try {
            data = file.getBytes();
        } catch (IOException e) {
            // 若不记录日志且不传递 cause，排查时仅剩「读取图片失败」一句提示，根因完全丢失
            log.error("读取上传图片失败: filename={}", file.getOriginalFilename(), e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "读取图片失败", e);
        }
        // 扩展名由调用方提供、魔数为文件自带：仅信扩展名等同于将校验交由上传者
        //（任意文件改名为 .png 即可通过）。此处再校验一次文件头，
        // 并以魔数结论决定 content-type 与存储扩展名，改过名的图按真实类型存储。
        String realExt = detectImageExt(data);
        if (realExt == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "图片内容无法识别，请上传有效的图片文件");
        }
        return ossService.upload(data, COVER_DIR, realExt, contentType("." + realExt));
    }

    /**
     * 按文件头魔数识别真实图片类型，覆盖白名单内的四种格式。
     *
     * <p>识别不出（含被改名的可执行文件、脚本、压缩包）一律返回 null，由调用方拒绝。
     */
    private String detectImageExt(byte[] data) {
        if (matchMagic(data, 0, PNG_MAGIC)) {
            return "png";
        }
        if (matchMagic(data, 0, JPEG_MAGIC)) {
            return "jpeg";
        }
        if (matchMagic(data, 0, GIF_MAGIC)) {
            return "gif";
        }
        // WEBP 是 RIFF 容器：前 4 字节 "RIFF"，8-11 字节 "WEBP"（中间 4 字节是长度）
        if (matchMagic(data, 0, RIFF_MAGIC) && matchMagic(data, 8, WEBP_MAGIC)) {
            return "webp";
        }
        return null;
    }

    private boolean matchMagic(byte[] data, int offset, byte[] magic) {
        if (data == null || data.length < offset + magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if (data[offset + i] != magic[i]) {
                return false;
            }
        }
        return true;
    }

    private String extractExt(String filename) {
        if (filename == null) {
            return null;
        }
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? null : filename.substring(dot).toLowerCase(Locale.ROOT);
    }

    private String contentType(String ext) {
        return switch (ext) {
            case ".png" -> "image/png";
            case ".jpg", ".jpeg" -> "image/jpeg";
            case ".webp" -> "image/webp";
            case ".gif" -> "image/gif";
            default -> "application/octet-stream";
        };
    }
}
