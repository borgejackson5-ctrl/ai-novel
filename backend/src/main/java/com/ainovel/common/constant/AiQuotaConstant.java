package com.ainovel.common.constant;

/**
 * AI 配额的 Redis 计数器键前缀。
 *
 * <p>集中定义的原因：这些键既由扣减方写入，也由管理端读取用量。前缀分散在各实现类中时，
 * 改动其中一处不会报错，只会让管理端读到 0 —— 而「用量为 0」与「今天确实没人用」在外观上
 * 完全相同。
 *
 * <p>完整的键为「前缀 + 日期」，其中用户额度还含用户名，日期部分由
 * {@code DailyQuotaLimiter} 统一拼接（按其所在时区取当日），调用方只传前缀。
 */
public final class AiQuotaConstant {

    /** 用户每日可用字数：完整键 {@code ai:user:usage:{userId}:{date}} */
    public static final String USER_USAGE_KEY_PREFIX = "ai:user:usage:";

    /** 平台 Key 每日调用次数（全局上限，按次计） */
    public static final String PLATFORM_USAGE_KEY_PREFIX = "ai:platform:usage:";

    /** 文生图每日张数（按次计，与字数无关） */
    public static final String COVER_USAGE_KEY_PREFIX = "ai:cover:usage:";

    private AiQuotaConstant() {
    }
}
