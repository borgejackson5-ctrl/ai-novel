package com.ainovel.module.novel.domain.form;

import com.ainovel.common.domain.PageParam;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 小说分页查询（书库对外入口）
 *
 * <p>刻意不提供 status / auditStatus 参数：可见性由服务端固定
 * （仅「已上架且审核通过或变更待审」的作品对外可见），调用方无法通过传参绕过。
 * 需要查看未过审 / 已下架作品请走管理端接口。
 *
 * <p>此处筛选维度均为「用户明确选择的」，在 ES 侧作为 filter 硬过滤，
 * 与智能搜索中由模型推断出的分类（仅能加分）语义不同，不可混用。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class NovelQueryForm extends PageParam {

    /** 排序取值：hot=最热（默认）/ latest=最新上架 */
    public static final String SORT_HOT = "hot";
    public static final String SORT_LATEST = "latest";

    private String keyword;

    private Long categoryId;

    /** 连载状态：null=全部 / 0=连载中 / 1=已完结 */
    private Integer serialStatus;

    /** 字数下限（字），null=不限 */
    private Integer minWords;

    /** 字数上限（字），null=不限 */
    private Integer maxWords;

    /** 排序：hot 最热（按阅读量）/ latest 最新（按入库先后），默认 hot */
    private String sort;

    public boolean isLatest() {
        return SORT_LATEST.equalsIgnoreCase(sort);
    }
}
