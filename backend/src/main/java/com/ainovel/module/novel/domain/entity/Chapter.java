package com.ainovel.module.novel.domain.entity;

import com.ainovel.common.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import org.springframework.util.StringUtils;
import lombok.EqualsAndHashCode;

/**
 * 章节实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_chapter")
public class Chapter extends BaseEntity {

    private Long novelId;
    private Integer chapterNo;
    private String title;
    private String content;

    /** 本章字数 */
    private Integer wordCount;

    private Integer unlockCoin;

    /** 审核状态：0 待审 / 1 通过 / 2 拒绝 / 3 变更待审（见 ChapterAuditStatusEnum） */
    private Integer auditStatus;

    /** 审核意见/拒绝原因 */
    private String auditResult;

    /** 待审正文（影子正文）：变更已发布章时暂存新版，审核通过后覆盖 content */
    private String pendingContent;

    private Integer sort;

    /**
     * 「待审优先」的当前正文：存在「变更待审」内容时使用它，否则使用已发布正文。
     *
     * <p>这是全站唯一的口径（原仅在审查工具中存在一份私有实现）。**不应各自实现**：
     * 审查读取待审版而检索索引已发布版时，模型会遇到「前文写的是九根，
     * 检索结果为七根」这类矛盾，而两侧单独查看均无误。
     *
     * <p><b>该方法仅服务于「审查 / 索引」链路，阅读链路不使用</b>
     * （名字中的「当前」容易让人误以为阅读器也读取此值）：
     * 读者取正文走 {@code ChapterServiceImpl#loadContentVo}，而 {@code ChapterContentVO}
     * 中**不存在 pendingContent 字段**，「变更待审期间读者看旧版」由 VO 的字段集实现。
     * 使用方为：{@code ChapterReviewTools}（审查读取待审稿）与 {@code ChapterVectorServiceImpl}
     * （向量块索引待审稿）。因此该类的字段集合具有语义，新增字段前需明确其使用方。
     */
    public String currentBody() {
        return StringUtils.hasText(pendingContent) ? pendingContent : content;
    }
}
