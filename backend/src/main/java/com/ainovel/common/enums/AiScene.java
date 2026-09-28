package com.ainovel.common.enums;

/**
 * AI 调用场景：调用量与耗时的分组维度。
 *
 * <p>取值即指标的 {@code scene} 标签，因此**只增不改名**。改动已有枚举名会让面板上的历史
 * 数据分裂成两个场景，而新数据与旧数据都「看起来正常」。
 *
 * <p>{@link #code()} 与 {@link #label()} 分开：前者进指标与接口字段，后者只用于管理端展示。
 * 合并成一个会让「改展示名」牵动指标标签。
 *
 * <p>场景由调用方在发起调用时传入，而非由客户端按方法名推断。原先的实现按
 * {@code chat} / {@code chatStream} 区分，导致两件事分不开：搜索意图解析与小说起名
 * 均为非流式调用、记在同一个标签下，看板上无法分别统计。
 */
public enum AiScene {

    /** 作品/章节审核之外的创作类生成 */
    TITLE("小说起名"),
    INTRO("小说简介"),
    CONTINUE("正文续写"),
    POLISH("文字润色"),

    /** 章节一致性审查：单次调用量最大，且内部含工具调用循环 */
    REVIEW("章节审查"),
    AUDIT_NOVEL("作品预审"),
    AUDIT_CHAPTER("章节预审"),
    SEARCH("搜索意图解析"),
    EMBEDDING("文本向量化"),
    COVER("封面生成");

    private final String label;

    AiScene(String label) {
        this.label = label;
    }

    /** 指标标签值，与枚举名一致，避免维护两套命名 */
    public String code() {
        return name();
    }

    /** 管理端展示名 */
    public String label() {
        return label;
    }

    /**
     * 由生成接口的 {@code type} 转场景。
     *
     * <p>该 {@code type} 来自请求参数，不受枚举约束。未知取值抛出异常而不回落到某个默认场景：
     * 静默归类会把未知类型的调用记到其他场景名下，数字看着正常、含义已经错了。
     * 入参在 {@code AiServiceImpl.checkType} 处已校验，此处为第二道。
     */
    public static AiScene ofGenerateType(String type) {
        if (type == null) {
            throw new IllegalArgumentException("生成类型不能为空");
        }
        try {
            return valueOf(type);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("未知的生成类型：" + type);
        }
    }

    /** 由指标标签值还原场景；未知值返回 {@code null}（读指标时可能遇到已下线的旧场景） */
    public static AiScene fromCode(String code) {
        for (AiScene scene : values()) {
            if (scene.name().equals(code)) {
                return scene;
            }
        }
        return null;
    }
}
