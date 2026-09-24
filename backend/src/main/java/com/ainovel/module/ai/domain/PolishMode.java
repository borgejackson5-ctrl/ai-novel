package com.ainovel.module.ai.domain;

import java.util.Arrays;

/**
 * 润色的三种改法。
 *
 * <p>三种是三个**不同的目标**，而非同一目标的不同强度：目标不同，提示词需写入的约束也完全不同：
 * <ul>
 *   <li>{@link #EXPRESS}：一个字的信息量不变，仅更换说法；</li>
 *   <li>{@link #COMPACT}：信息量不变，字数需减少；</li>
 *   <li>{@link #VIVID}：将概括改为具体，字数通常增加。</li>
 * </ul>
 * 合并为一个「改写强度」滑块不可行：同一方向（具体 vs 抽象）无法既压缩字数又增加画面感。
 *
 * <p>代号来自请求，无法解析时**不静默退回默认值**：润色会直接修改作者已有文字，
 * 若选择「精简」却按「加强画面感」执行，作者会得到一段更长的文字并认为功能异常。
 */
public enum PolishMode {

    /** 保持原意，仅调整语句通顺度 */
    EXPRESS("改通顺"),

    /** 删除冗余与重复表述，信息量不变 */
    COMPACT("精简"),

    /** 将概括表述改为具体描写，增强画面感 */
    VIVID("加画面感");

    private final String label;

    PolishMode(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /**
     * 按代号解析。
     *
     * @return 无法解析时返回 null，由调用方给出明确提示（见类注释）
     */
    public static PolishMode of(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String upper = code.trim().toUpperCase();
        return Arrays.stream(values())
                .filter(v -> v.name().equals(upper))
                .findFirst()
                .orElse(null);
    }
}
