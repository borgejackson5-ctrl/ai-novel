package com.ainovel.common.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SSE 帧中内容的编码。
 *
 * <p><b>每段内容需先编码为 JSON 字符串字面量的原因</b>：SSE 的一帧以空行分隔，
 * 帧内 {@code data:} 之后**不能出现实际换行符**。而小说正文天然包含段落换行，
 * 直接写入会将一帧拆为两帧，后半部分被当作未知字段丢弃。
 * 表现为「生成的文字缺少若干换行」：不报错、日志正常、单测通过，
 * 需人工通读生成的段落才能发现排版异常。
 *
 * <p>编码后换行变为 {@code \n} 两个字符，一帧始终为单行；由前端 {@code JSON.parse} 还原。
 * 同时规避了另一处问题：不少现成的 SSE 解析实现会使用 {@code ev.slice(5).trim()}，
 * 该 trim 会直接移除单独一个换行字符。
 *
 * <p>置于 {@code common/util} 而非某个控制器内：全项目的流式接口均需遵守该约定，
 * 多处各写一份会逐渐分叉（一侧编码一侧不编码，且症状不一致）。
 */
public final class SsePayload {

    private static final Logger LOG = LoggerFactory.getLogger(SsePayload.class);

    private SsePayload() {
    }

    /** 把一段文本编码成 JSON 字符串字面量：换行变成 {@code \n} 两个字符，帧始终是单行 */
    public static String encode(ObjectMapper objectMapper, String chunk) {
        try {
            return objectMapper.writeValueAsString(chunk);
        } catch (JsonProcessingException e) {
            // 对 String 而言不应失败；若失败也不能破坏该帧格式，
            // 退化为丢弃该段（优于输出非法 JSON）
            LOG.error("流式内容编码失败，已跳过这一段", e);
            return "\"\"";
        }
    }
}
