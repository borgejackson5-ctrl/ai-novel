package com.ainovel.module.ai.domain.form;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * AI 润色的入参。
 *
 * <p>{@code content} 是作者**选中**的那一段，而非整章。整章一次性润色存在两个问题：
 * 一次几千字既慢且成本高，且模型会把作者的语气整体抹平成其自身腔调。
 * 「选中一段、改一段」既降低成本又能保留作者的语气。
 */
@Data
public class AiPolishForm {

    @NotBlank(message = "先选中要润色的文字")
    @Size(max = 2000, message = "选中的内容太长了，一次最多 2000 字，先分段润色")
    private String content;

    /** 改法 EXPRESS / COMPACT / VIVID，必填且必须可解析（见 {@code PolishMode} 的说明） */
    private String mode;
}
