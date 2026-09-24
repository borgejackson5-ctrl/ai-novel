package com.ainovel.module.search.domain.form;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

/**
 * 智能搜索请求表单
 */
@Data
public class SmartSearchForm {

    /** 自然语言查询，如「想看重生逆袭的爽文」 */
    @NotBlank(message = "搜索内容不能为空")
    private String query;

    @Min(value = 1, message = "页码不能小于 1")
    private Integer page = 1;

    @Min(value = 1, message = "每页条数不能小于 1")
    @Max(value = 50, message = "每页条数不能超过 50")
    private Integer size = 10;

    /** 翻页复用：可选，携带首次解析出的意图则跳过 LLM 调用直接查询（节省 token 与降低延迟） */
    private List<String> keywords;

    private List<String> tags;

    private Long categoryId;
}
