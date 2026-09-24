package com.ainovel.module.ai.domain;

/**
 * 本次 AI 写作会走哪条路径。
 *
 * <p>存在的理由是**检查必须在开流之前完成**：SSE 一旦开始，HTTP 状态码已发出，
 * 此时才发现「无可用 Key」，前端得到的是一个 200 的空流，页面只能持续等待，
 * 无任何错误可显示。因此「是否可写」需在建立流之前确定。
 *
 * <p>采用三态而非 `boolean useMock`：续写与润色对 {@link #MOCK} 的处理不同：
 * 续写的演示文字仅为一段建议（作者不会直接采纳），润色的演示文字会被当作真实改写结果采纳。
 */
public enum WritingAvailability {

    /** 存在可用的 Key，走真实调用 */
    REAL,

    /** 未配置 Key 但允许演示：仅续写可走该路径（见类注释） */
    MOCK,

    /** 未配置 Key 且不允许演示：AI 功能不可用 */
    UNAVAILABLE
}
