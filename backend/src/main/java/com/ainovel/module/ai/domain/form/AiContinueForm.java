package com.ainovel.module.ai.domain.form;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * AI 续写的入参。
 *
 * <p>{@code content} 是编辑器中的**整章正文**，而非截取好的上文：取末尾多少字由服务端决定
 * （{@code app.ai-write-context-chars}）。放在服务端有两个好处：修改长度无需发布前端，
 * 且「扣除多少字」与「送入多少字」由同一处计算，不会各自计算。
 */
@Data
public class AiContinueForm {

    @NotBlank(message = "先写点内容，再来续写")
    @Size(max = 20000, message = "正文太长了，请分段续写")
    private String content;

    /**
     * 接下来要写的内容（可空）。
     *
     * <p>有无该项差别明显：留空时模型只能顺着上文自行确定方向，容易写成原地打转的环境描写；
     * 给出一句「主角发现了线索」它即可知道应推进什么。因此界面保留该输入框，但**不强制填写**。
     */
    @Size(max = 200, message = "续写方向最多 200 字")
    private String direction;

    /** 长度档位 SHORT / MEDIUM / LONG，留空按中档 */
    private String length;
}
