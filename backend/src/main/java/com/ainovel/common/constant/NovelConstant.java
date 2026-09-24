package com.ainovel.common.constant;

/**
 * 小说领域常量：定价与解锁相关
 *
 * <p>付费墙相关的常量统一集中于此，避免「付费章单价 5 币」「六折打包」分散在
 * {@code NovelService} / {@code NovelImportService} 中各自维护、后续调价时遗漏修改。
 *
 * <p>定价口径：首章免费试读，其余付费章单价 {@link #CHAPTER_PRICE}；
 * 整本打包价 = 付费章解锁币之和 × {@link #BUNDLE_DISCOUNT}（必须 &gt; 0，
 * 否则「整本解锁」可以 0 币买断，绕过单章付费墙）。
 */
public final class NovelConstant {

    /** 付费章默认单价（虚拟币）。用户投稿未指定时默认免费，公版书导入按此定价 */
    public static final int CHAPTER_PRICE = 5;

    /** 整本打包折扣：付费章解锁币之和 × 该系数 = 整本价 */
    public static final double BUNDLE_DISCOUNT = 0.6;

    /*
     * ===== 作者自助上下架 / 删除的「冷静期」参数 =====
     *
     * 共同目的：作品上架后会被读者加入书架、付费解锁并产生阅读记录，
     * 作者单方面的状态变更会影响他人，因此每个不可逆操作都需设置代价与缓冲。
     */

    /** 新书保护期：发布后 N 天内作者不可自助下架（防止发布开头后即下架以获取收藏） */
    public static final int UNSHELVE_PROTECT_DAYS = 7;

    /** 重新上架冷却：下架后 M 小时内不可申请重新上架（避免误操作，同时防止通过上下架刷榜单） */
    public static final int RESHELVE_COOLDOWN_HOURS = 24;

    /** 删除门槛：需已下架满 P 天。为作者留出撤回窗口，也为已收藏的读者留出缓冲 */
    public static final int DELETE_REQUIRE_OFFLINE_DAYS = 7;

    /**
     * 解除完结的冷静期：转为已完结后 N 天内不可申请恢复连载。
     *
     * <p>与上述几个门槛思路一致：完结状态对读者可见（书库与详情页均会标注「已完结」），
     * 短期内反复切换会使该状态失去意义；设置明确的等待期可提高操作的前置成本。
     */
    public static final int RESUME_SERIAL_COOLDOWN_DAYS = 3;

    private NovelConstant() {
    }
}
