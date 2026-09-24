package com.ainovel.module.search.service;

import com.ainovel.module.search.domain.form.SmartSearchForm;
import com.ainovel.module.search.domain.vo.SmartSearchVO;

/**
 * AI 智能搜索服务：自然语言 → LLM 意图解析 → 结构化 ES 查询
 *
 * <p>可靠性设计（LLM 输出不可信，全链路防御）：
 * <ul>
 *   <li>fail-fast：独立 5s 短超时，超时或异常时静默降级为关键词搜索，搜索主链路不因 AI 受阻</li>
 *   <li>防御解析：剥离 markdown 围栏、Jackson 宽松解析、字段白名单校验（数量/长度/分类 ID 合法性）</li>
 *   <li>注入防护：用户输入仅作为 LLM prompt 与 ES 查询的参数值，查询结构由代码构建，无 DSL 字符串拼接</li>
 *   <li>无 Key 时自动降级为 mock 启发式解析，演示环境同样可体验智能搜索</li>
 * </ul>
 */
public interface AiSearchService {

    /**
     * 智能搜索入口：AI 解析意图 → 结构化 ES 查询；任何一步失败静默降级关键词搜索
     *
     * <p>前端翻页时带回首次解析出的意图（keywords/tags/categoryId），跳过 LLM 调用以节省 token 与降低延迟
     */
    public SmartSearchVO smartSearch(SmartSearchForm form);
}
