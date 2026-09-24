package com.ainovel.module.novel.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 小说返回视图
 */
@Data
public class NovelVO {

    private Long id;
    private String title;
    /** 搜索命中时的高亮 title（含 <em> 标签），普通列表为 null */
    private String highlightTitle;
    private Long categoryId;
    private String categoryName;
    private String coverUrl;
    private String intro;
    private String tags;
    private String author;
    private Integer totalChapters;
    /** 全书总字数（Novel.word_count 冗余，目录分页后前端不再对全量章节求和） */
    private Long wordCount;
    /** 首章 ID（「开始阅读」入口用，目录分页后前端不再拉全量取首章） */
    private Long firstChapterId;
    private Integer coinPrice;
    private Long readCount;
    private Long likeCount;
    /** 当前登录用户是否已点赞（按请求回填，不进缓存；未登录恒为 false） */
    private Boolean liked;
    /**
     * 发布者用户 ID（管理员录入的公版书为 null）。
     *
     * <p>公开字段：详情页/书卡据此跳转作者主页。判断「是否为当前用户的作品」应使用 {@link #isMine}，
     * 不在前端重复比较 ID。
     */
    private Long userId;
    /**
     * 是否为当前登录用户发布的作品（按请求回填，不进缓存；未登录恒为 false）。
     *
     * <p>由服务端算好后下发，前端各处直接用它打标或换入口，无需自行判断归属。
     */
    private Boolean isMine;
    private Integer status;
    private Integer auditStatus;
    private String auditResult;
    /**
     * 连载状态：0 连载中 / 1 已完结。与 status（上架/下架）、auditStatus（审核）为三项不同状态。
     */
    private Integer serialStatus;
    /** 连载状态文案（「连载中」/「已完结」），服务端下发，前端不再自己维护枚举映射 */
    private String serialStatusText;
    /**
     * 是否有「解除完结」申请正在审核中（按请求回填，不进缓存）。
     *
     * <p>有在途申请时不再重复提交，前端据此把入口换成「申请审核中」。
     */
    private Boolean appealPending;
    /** 不可申请解除完结的原因（如「完结未满 3 天，还需 2 天」）；不可申请时按钮置灰并提示 */
    private String resumeSerialBlockedReason;
    /**
     * 是否已下架（上架过后被下架，按请求回填、不进缓存）。
     *
     * <p>与 status 的区别：status=0 可能是「从未上架」（刚发布待审时，非作者无法获取详情），
     * 而 offline=true 专指「曾经上架、现已停止分发」。前端据此标注「已下架」并收起
     * 点赞 / 加入书架 / 解锁整本等入口；已解锁的章节仍应可读。
     */
    private Boolean offline;
    /**
     * 作者自助操作的可用性：为 null 表示可执行，非 null 为「不可执行的原因」文案。
     *
     * <p>门槛判定涉及时间差与订单统计，由服务端计算后下发：前端无法获取 offline_time，
     * 也不应为每个按钮各实现一套判断。仅 /novel/mine 回填，按请求计算，不进缓存。
     * 前端据此设置 {@code disabled} 与 tooltip。
     */
    /** 不可下架的原因（如「新书保护期内，还需 3 天」）；已下架作品该字段恒为 null，因为按钮不出现 */
    private String offlineBlockedReason;
    /** 不可申请重新上架的原因（如「下架未满 24 小时」） */
    private String reshelveBlockedReason;
    /** 不可删除的原因（未满下架保护期 / 已有读者付费解锁） */
    private String deleteBlockedReason;
    /** 首章正文节选（管理端审核文本预览用，约 500 字截断） */
    private String excerpt;
    private LocalDateTime createTime;
}
