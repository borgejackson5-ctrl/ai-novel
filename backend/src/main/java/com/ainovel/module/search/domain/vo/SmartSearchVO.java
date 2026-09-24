package com.ainovel.module.search.domain.vo;

import com.ainovel.common.domain.PageResult;
import com.ainovel.module.novel.domain.vo.NovelVO;
import lombok.Data;

import java.util.List;

/**
 * 智能搜索结果：携带 AI 理解痕迹，让前端可解释、可降级提示
 */
@Data
public class SmartSearchVO {

    /** 是否已降级为关键词搜索（AI 不可用/解析失败/超时） */
    private Boolean degraded;

    /** 意图来源：ai 真实模型 / mock 无 Key 启发式 / cached 前端带回缓存意图 / keyword 降级 */
    private String source;

    /** 理解摘要，如「关键词：重生、逆袭；标签：爽文；分类：侠义公案」 */
    private String aiUnderstanding;

    private List<String> keywords;

    private List<String> tags;

    private Long categoryId;

    /**
     * 分类浏览入口：AI 与本地启发式均无法从输入中提取实词时返回。
     *
     * <p>其作用是保证降级结果不为空白。用户输入「asdfgh」这类内容时，
     * 任何检索都无法给出结果，此时提供若干可点击的分类，优于返回空列表。
     */
    private List<CategoryOption> categories;

    private PageResult<NovelVO> page;

    /** 分类入口（仅需 id 与名称，可满足前端渲染可点击标签的需要） */
    public record CategoryOption(Long id, String name) {
    }
}
