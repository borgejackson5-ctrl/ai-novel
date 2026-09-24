package com.ainovel.module.admin.domain.vo;

import com.ainovel.module.novel.domain.vo.NovelVO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 管理端审核用的作品视图。
 *
 * <p>在 {@link NovelVO} 基础上补充影子字段：作品信息变更送审时，管理员需要对照查看
 * 「当前生效值」与「待审新值」。影子字段仅在管理端 VO 上暴露，
 * 不放入 NovelVO，否则公开的书库 / 详情接口会一并返回未过审的新内容。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class NovelAuditVO extends NovelVO {

    /** 待审书名（仅变更待审时有值） */
    private String pendingTitle;
    private String pendingIntro;
    private String pendingCoverUrl;
    private String pendingTags;
    private Long pendingCategoryId;
    private String pendingCategoryName;
    private String pendingAuthor;
}
