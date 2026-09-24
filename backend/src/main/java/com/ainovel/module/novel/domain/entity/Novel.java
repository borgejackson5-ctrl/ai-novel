package com.ainovel.module.novel.domain.entity;

import com.ainovel.common.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 小说实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_novel")
public class Novel extends BaseEntity {

    private String title;
    private Long categoryId;
    private String coverUrl;
    private String intro;
    private String tags;
    private String author;

    /** 发布者用户 ID（管理员录入的小说为 null） */
    private Long userId;

    private Integer totalChapters;

    /** 总字数 */
    private Long wordCount;

    private Integer coinPrice;
    private Long readCount;
    private Long likeCount;
    private Integer status;
    private Integer auditStatus;
    private String auditResult;

    /**
     * 连载状态：0 连载中 / 1 已完结（默认连载中）。
     *
     * <p>与 {@link #status}（上架/下架）、{@link #auditStatus}（审核）是三件不同的事：
     * 完结是「创作侧」的状态，上架是「分发侧」，审核是「合规侧」。
     */
    private Integer serialStatus;

    /**
     * 转为完结的时间。
     *
     * <p>用于「解除完结的冷静期」判定：转完结后 N 天内作者不能申请恢复连载。
     * 同样不能复用 update_time（任何一次更新都会刷新它）。
     */
    private LocalDateTime finishTime;

    /**
     * 下架时间：作者自助下架或管理员强制下架都会记录。
     *
     * <p>用于两个门槛判定：「下架后 M 小时内不可申请重新上架」与「需已下架满 P 天才能删除」。
     * 不能复用 update_time：任何一次更新（改简介、调价、审核）都会刷新它，时间点就丢了。
     */
    private LocalDateTime offlineTime;

    /*
     * 作品信息的「影子字段」：作者修改已通过作品时，改动先写在这里并置 auditStatus=变更待审(3)，
     * 前台继续读正式字段（旧值）；审核通过后整体覆盖正式字段，拒绝则连同影子值一起丢弃。
     * 与章节表的 pending_content 是同一套做法。
     */
    private String pendingTitle;
    private String pendingIntro;
    private String pendingCoverUrl;
    private String pendingTags;
    private Long pendingCategoryId;
    private String pendingAuthor;
}
