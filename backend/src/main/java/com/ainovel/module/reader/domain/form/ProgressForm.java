package com.ainovel.module.reader.domain.form;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 保存阅读进度请求（前端发的是雪花 ID 的字符串，Jackson 自动转 Long）
 */
@Data
public class ProgressForm {

    @NotNull(message = "小说ID不能为空")
    private Long novelId;

    @NotNull(message = "章节ID不能为空")
    private Long chapterId;

    private String novelTitle;

    private Integer chapterNo;

    private String chapterTitle;

    private String mode;

    private Integer scrollTop;

    private Integer pageNo;

    /** 客户端最后写入时间戳（ms），LWW 冲突合并：云端更新则忽略本次推送 */
    private Long clientTime;
}
