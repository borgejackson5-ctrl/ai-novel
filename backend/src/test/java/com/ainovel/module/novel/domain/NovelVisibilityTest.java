package com.ainovel.module.novel.domain;

import com.ainovel.common.enums.AuditStatusEnum;
import com.ainovel.common.enums.CommonStatusEnum;
import com.ainovel.module.novel.domain.entity.Novel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 可见性判定的守门测试。
 *
 * <p>需要该测试的原因：{@link NovelVisibility} 是全项目唯一的可见性实现，改动会同时影响
 * 书库分页、榜单、搜索、索引同步、对账（分发口径）与作品详情、章节元数据、章节目录、章节正文
 * （阅读口径）五条以上链路，而其中任一条漏判都**不报错** —— 只表现为「搜得到、点进去 404」
 * 或「已下架作品仍能被匿名读到」。因此把两条口径的完整判定表在此钉住。
 */
class NovelVisibilityTest {

    private static final int ONLINE = CommonStatusEnum.ENABLED.getCode();

    private static final int OFFLINE = CommonStatusEnum.DISABLED.getCode();

    /** 作者 id 固定为 100，判定用的「其他读者」为 200 */
    private static Novel novel(int status, int auditStatus) {
        Novel n = new Novel();
        n.setStatus(status);
        n.setAuditStatus(auditStatus);
        n.setUserId(100L);
        return n;
    }

    // ==================== 分发口径 ====================

    @Test
    @DisplayName("分发：仅「已上架 + 此前已过审」可见")
    void distributable() {
        assertTrue(NovelVisibility.isVisible(novel(ONLINE, AuditStatusEnum.PASS.getCode())));
        assertTrue(NovelVisibility.isVisible(novel(ONLINE, AuditStatusEnum.MODIFY_WAIT.getCode())),
                "变更待审展示的仍是旧内容，应继续分发");

        assertFalse(NovelVisibility.isVisible(novel(ONLINE, AuditStatusEnum.WAIT.getCode())),
                "首次待审不得进入书库");
        assertFalse(NovelVisibility.isVisible(novel(ONLINE, AuditStatusEnum.REJECT.getCode())));
        assertFalse(NovelVisibility.isVisible(novel(OFFLINE, AuditStatusEnum.PASS.getCode())),
                "已下架不得分发");
    }

    @Test
    @DisplayName("分发：重新上架审核中仍不分发（此时 status 仍为下架）")
    void reshelveWaitIsNotDistributable() {
        assertFalse(NovelVisibility.isVisible(novel(OFFLINE, AuditStatusEnum.RESHELVE_WAIT.getCode())),
                "作品尚未恢复分发，不应出现在书库 / 榜单 / 搜索");
    }

    // ==================== 详情与阅读口径 ====================

    @Test
    @DisplayName("阅读：重新上架审核中，详情与正文对读者保持可读")
    void reshelveWaitIsReadable() {
        Novel n = novel(OFFLINE, AuditStatusEnum.RESHELVE_WAIT.getCode());

        assertTrue(NovelVisibility.isDetailReadable(n, 200L, false),
                "作者提交重新上架申请后，已收藏、已解锁的读者不能被挡在详情与正文之外");
        assertTrue(NovelVisibility.isDetailReadable(n, null, false),
                "游客也应能打开详情（正文能否读由章节侧的解锁判定负责）");
    }

    @Test
    @DisplayName("阅读：未过审（首次待审 / 已拒绝）仅作者与管理员可读")
    void notAuditedIsOwnerOnly() {
        Novel waiting = novel(ONLINE, AuditStatusEnum.WAIT.getCode());

        assertFalse(NovelVisibility.isDetailReadable(waiting, 200L, false), "其他读者不可读");
        assertFalse(NovelVisibility.isDetailReadable(waiting, null, false), "游客不可读");
        assertTrue(NovelVisibility.isDetailReadable(waiting, 100L, false), "作者本人可读");
        assertTrue(NovelVisibility.isDetailReadable(waiting, 200L, true), "管理员可读");
    }

    @Test
    @DisplayName("阅读：不含上下架判定 —— 已下架作品的详情仍可打开（只是不再分发）")
    void readableIgnoresOnlineStatus() {
        assertTrue(NovelVisibility.isDetailReadable(
                novel(OFFLINE, AuditStatusEnum.PASS.getCode()), 200L, false));
        assertTrue(NovelVisibility.isDetailReadable(
                novel(OFFLINE, AuditStatusEnum.MODIFY_WAIT.getCode()), 200L, false));
    }

    @Test
    @DisplayName("阅读：作品查不到（null，含逻辑删除）时一律不可读")
    void nullNovelIsNotReadable() {
        assertFalse(NovelVisibility.isDetailReadable(null, 100L, false));
        assertFalse(NovelVisibility.isDetailReadable(null, 100L, true),
                "作品不存在时，管理员身份也不应绕过");
    }
}
