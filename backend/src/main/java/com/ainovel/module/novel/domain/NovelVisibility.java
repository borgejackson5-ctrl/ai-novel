package com.ainovel.module.novel.domain;

import com.ainovel.common.enums.AuditStatusEnum;
import com.ainovel.common.enums.CommonStatusEnum;
import com.ainovel.module.novel.domain.entity.Novel;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import java.util.List;

/**
 * 作品可见性判定，为全项目唯一实现。含两条相互独立的口径：
 *
 * <ul>
 *   <li><b>对外分发</b>（{@link #isVisible(Novel)}、{@link #appendTo}）：已上架（status=1）
 *       且审核通过（1）或变更待审（3）。变更待审(3) 属于「已上架、改动待审」，
 *       前台展示的仍是旧内容，因此保持可见。</li>
 *   <li><b>详情与阅读</b>（{@link #isDetailReadable}）：不含上下架判定；未过审仅作者与管理员可读。</li>
 * </ul>
 *
 * <p><b>收敛的必要性</b>：书库分页、搜索全量重建、索引同步、定时对账依赖分发口径；
 * 作品详情、章节元数据、章节目录、章节正文依赖阅读口径。若各自实现一份，修改时遗漏其中一处
 * 即会产生「搜得到、点进去 404」或「已下架作品仍能被匿名读到」这类难以排查的问题，
 * 而每段代码表面均无异常。
 */
public final class NovelVisibility {

    /** 对外可见的审核状态：通过 / 变更待审 */
    private static final List<Integer> VISIBLE_AUDIT_STATUSES =
            List.of(AuditStatusEnum.PASS.getCode(), AuditStatusEnum.MODIFY_WAIT.getCode());

    private NovelVisibility() {
    }

    /** 对外可见的审核状态集合 */
    public static List<Integer> visibleAuditStatuses() {
        return VISIBLE_AUDIT_STATUSES;
    }

    /**
     * 仅判定「审核维度」：通过(1) / 变更待审(3) 视为可见。
     *
     * <p>与 {@link #isVisible(Novel)} 的区别：**不含上下架判定**。
     * 「已下架」的判定需先确定审核维度是否通过（通过则称下架，未通过则称未过审），
     * 因此该维度需可单独查询，但实现同样只能有一份。
     */
    public static boolean isAuditVisible(Integer auditStatus) {
        return auditStatus != null && VISIBLE_AUDIT_STATUSES.contains(auditStatus);
    }

    /** 对象判定：这部作品当前是否可以对外分发 */
    public static boolean isVisible(Novel novel) {
        return novel != null
                && novel.getStatus() != null
                && novel.getStatus() == CommonStatusEnum.ENABLED.getCode()
                && novel.getAuditStatus() != null
                && VISIBLE_AUDIT_STATUSES.contains(novel.getAuditStatus());
    }

    /**
     * 详情与阅读路径的可读性判定：作品详情、章节元数据、章节目录、章节正文共用。
     *
     * <p>与 {@link #isVisible(Novel)}（对外分发）的区别是**不含上下架判定**：下架停止的是
     * 书库、榜单、搜索这类分发入口，已解锁的读者仍可打开详情继续阅读。两条口径服务不同问题，
     * 因此各自独立。
     *
     * <p>未过审（首次待审 0、已拒绝 2）时仅作者本人与管理员可读。
     *
     * <p>登录态由三个参数显式传入而非在方法内读取，使本类保持纯判定、不依赖请求上下文，
     * 从而可在无登录态的线程（如 MQ 消费者）中安全复用。
     *
     * @param novel  作品实体；{@code null} 表示查不到（含逻辑删除）
     * @param userId 当前登录用户 id；未登录传 {@code null}
     * @param admin  当前用户是否管理员
     * @return true = 可读
     */
    public static boolean isDetailReadable(Novel novel, Long userId, boolean admin) {
        if (novel == null) {
            return false;
        }
        if (admin || isAuditVisible(novel.getAuditStatus())) {
            return true;
        }
        return userId != null && userId.equals(novel.getUserId());
    }

    /** 给 Lambda 条件构造器追加可见性条件（书库分页、全量重建这类实体查询用） */
    public static void appendTo(LambdaQueryWrapper<Novel> wrapper) {
        wrapper.eq(Novel::getStatus, CommonStatusEnum.ENABLED.getCode())
                .in(Novel::getAuditStatus, VISIBLE_AUDIT_STATUSES);
    }

    /** 给字符串列名条件构造器追加可见性条件（对账这类需要列投影的场景用） */
    public static void appendTo(QueryWrapper<Novel> wrapper) {
        wrapper.eq("status", CommonStatusEnum.ENABLED.getCode())
                .in("audit_status", VISIBLE_AUDIT_STATUSES);
    }
}
