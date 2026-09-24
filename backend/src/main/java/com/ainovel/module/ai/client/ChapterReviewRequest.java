package com.ainovel.module.ai.client;

/**
 * 一次章节审查调用的入参。
 *
 * <p>打包为一个对象而非六个参数，是因为它需跨过 {@link ChapterReviewer} 这道边界：
 * 一侧是业务规则（权限、额度、结果过滤），另一侧是「使用哪个框架发起本次调用」。
 * 更换实现时只需实现该接口。
 *
 * @param baseUrl      生效配置中的服务地址（BYOK：每个用户可能不同）
 * @param apiKey       生效配置中的 Key
 * @param model        模型名
 * @param systemPrompt 系统提示词（角色 + 检查项 + 反幻觉约束）
 * @param userPrompt   用户提示词（作品信息 + 正文 + 调用工具的提示）
 * @param novelId      审查的作品，会作为工具上下文传下去，**限定工具能读到的数据范围**
 * @param chapterNo    当前章号，供「邻近检索」这类工具定位
 */
public record ChapterReviewRequest(String baseUrl, String apiKey, String model,
                                   String systemPrompt, String userPrompt,
                                   Long novelId, Integer chapterNo) {
}
