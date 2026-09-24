package com.ainovel.module.subscribe.domain.form;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 解锁请求
 */
@Data
public class SubscribeForm {

    @NotNull(message = "小说ID不能为空")
    private Long novelId;

    /** 章节ID，为空表示解锁整本 */
    private Long chapterId;
}
