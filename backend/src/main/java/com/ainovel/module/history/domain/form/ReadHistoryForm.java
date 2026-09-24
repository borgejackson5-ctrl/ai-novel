package com.ainovel.module.history.domain.form;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 记录阅读历史请求（书名/章名冗余，雪花 ID 字符串由 Jackson 自动转 Long）
 */
@Data
public class ReadHistoryForm {

    @NotNull(message = "小说ID不能为空")
    private Long novelId;

    @NotNull(message = "章节ID不能为空")
    private Long chapterId;

    private Integer chapterNo;

    private String novelTitle;

    private String chapterTitle;
}
