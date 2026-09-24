package com.ainovel.common.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * SSE 帧中内容的编码规则。
 *
 * <p>需要单独测试的原因：该问题不会报错。SSE 的一帧以空行分隔，
 * 帧内 `data:` 之后出现真实换行会把一帧拆成两帧，后半截会被当作未知字段丢弃。
 * 表现为「生成的文字少了几个换行」，单测不会失败、日志干净，
 * 只有人工阅读生成结果才能发现排版异常。而小说正文天然带段落换行，因此该路径必然被执行。
 */
class SsePayloadTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("带换行的内容编码后不含任何真的换行，且解码回来逐字相同")
    void encode_keepsFrameSingleLine() throws Exception {
        String raw = "他推开门。\n\n雨还在下。\r\n\t带「引号」和\\反斜杠的一段。";

        String encoded = SsePayload.encode(objectMapper, raw);

        assertFalse(encoded.contains("\n"), "编码结果里的换行必须是 \\n 两个字符，不能是真的换行");
        assertFalse(encoded.contains("\r"), "回车同理 —— 它也会打断一帧");
        // 仅「不含换行」不够：还必须能还原，否则只是换一种方式丢失内容
        assertEquals(raw, objectMapper.readValue(encoded, String.class));
    }

    @Test
    @DisplayName("普通中文片段原样可还原")
    void encode_plainText() throws Exception {
        String raw = "她抬起头，看着窗外。";

        assertEquals(raw, objectMapper.readValue(SsePayload.encode(objectMapper, raw), String.class));
    }

    @Test
    @DisplayName("控制字符（模型偶尔吐出来的）也不会漏进帧里")
    void encode_controlChars() {
        String encoded = SsePayload.encode(objectMapper, "a\u0000b\u0007c");

        assertFalse(encoded.contains("\u0000"));
        assertFalse(encoded.contains("\u0007"));
    }
}
