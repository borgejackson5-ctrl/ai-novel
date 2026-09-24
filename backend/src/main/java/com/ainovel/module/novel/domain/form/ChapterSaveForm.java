package com.ainovel.module.novel.domain.form;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 章节新增/编辑表单（连载 + 章节管理）
 *
 * <p>新增章节（POST /chapter）时 novelId 必传；编辑（PUT /chapter/{id}）时
 * novelId 忽略（从章节本身定位）。
 */
@Data
public class ChapterSaveForm {

    /** 新增章节时的所属小说 ID */
    private Long novelId;

    @Size(max = 100, message = "章节标题过长")
    private String title;

    @NotBlank(message = "章节正文不能为空")
    private String content;

    /** 本章解锁所需虚拟币，null/0 为免费章；限制 0~10 万防负数与溢出 */
    @Min(value = 0, message = "解锁币不能为负")
    @Max(value = 100000, message = "解锁币超出上限")
    private Integer unlockCoin;
}
