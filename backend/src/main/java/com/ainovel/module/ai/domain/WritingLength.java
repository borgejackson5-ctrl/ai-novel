package com.ainovel.module.ai.domain;

import java.util.Arrays;

/**
 * 续写的长度档位。
 *
 * <p>采用三档而非由作者填写字数：填写数字需要作者先确定「需要多少字」，
 * 而写作时所需的是「再来一小段」或「多写点」。档位将该问题简化为一次点击。
 * 实际字数由**服务端**决定：界面仅传档位代号，修改目标字数无需改动前端。
 */
public enum WritingLength {

    /** 一小段：接一句对话、补一个动作 */
    SHORT(200, "短"),

    /** 默认：正常推进一段情节 */
    MEDIUM(400, "中"),

    /** 多写点：展开一个场景 */
    LONG(800, "长");

    private final int targetChars;

    private final String label;

    WritingLength(int targetChars, String label) {
        this.targetChars = targetChars;
        this.label = label;
    }

    public int targetChars() {
        return targetChars;
    }

    public String label() {
        return label;
    }

    /**
     * 按代号解析。
     *
     * <p>不由 Spring 直接将请求体映射为枚举：映射失败会抛出 `HttpMessageNotReadableException`，
     * 全局处理器只能返回笼统的 400，作者看到的是「请求格式错误」，
     * 而真实原因往往只是前端某个默认值拼写错误。
     *
     * @return 无法解析时返回 {@link #MEDIUM}（默认中档，不因一个参数未传而无法写作）
     */
    public static WritingLength of(String code) {
        if (code == null || code.isBlank()) {
            return MEDIUM;
        }
        String upper = code.trim().toUpperCase();
        return Arrays.stream(values())
                .filter(v -> v.name().equals(upper))
                .findFirst()
                .orElse(MEDIUM);
    }
}
