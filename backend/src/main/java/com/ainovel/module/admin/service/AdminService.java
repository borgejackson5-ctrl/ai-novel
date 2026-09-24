package com.ainovel.module.admin.service;

import com.ainovel.common.domain.PageResult;
import com.ainovel.module.admin.domain.vo.DashboardVO;
import com.ainovel.module.coin.domain.vo.RechargeOrderVO;
import com.ainovel.module.novel.domain.vo.ChapterAuditVO;
import com.ainovel.module.novel.domain.vo.NovelVO;
import com.ainovel.module.admin.domain.vo.NovelAuditVO;
import com.ainovel.module.subscribe.domain.vo.SubscribeOrderVO;
import com.ainovel.module.user.domain.vo.UserVO;
import org.springframework.transaction.annotation.Transactional;

/**
 * 管理后台服务：数据看板 + 用户管理 + 订单管理
 */
public interface AdminService {

    /**
     * 数据看板聚合：各类计数 + 充值/解锁汇总 + 热度榜 + 近期订单
     */
    public DashboardVO dashboard();

    /**
     * 作品审核分页（auditStatus 不传则查全部）
     */
    public PageResult<NovelAuditVO> auditPage(int pageNum, int pageSize, Integer auditStatus);

    /**
     * 管理端作品列表：与对外书库不同，此处必须能查看全部作品。
     *
     * <p>对外书库 {@code /novel/page} 已固定可见性（仅上架且审核通过 / 变更待审），
     * 管理端若沿用该过滤将无法查看完整数据：已下架作品需要支持重新上架，被驳回作品需要展示原因，
     * 管理员必须能访问库内全部状态。这是本接口独立存在的原因。
     */
    public PageResult<NovelVO> novelPage(int pageNum, int pageSize, String keyword,
                                         Long categoryId, Integer status, Integer auditStatus);

    /**
     * 待人工处理数量（侧边栏红点 / 顶栏待办 chip）。
     *
     * <p>含三类：首次提交待审(0)、已通过作品的变更待审(3)、已下架作品的重新上架申请(4)，
     * 三者均需管理员处理。
     */
    public long pendingAuditCount();

    /**
     * 审核通过。
     *
     * <p>按两种情形分别处理：
     * <ul>
     *   <li>首次待审(0)：置为通过并自动上架，同时将随作品一起提交的待审章节批量置为通过</li>
     *   <li>变更待审(3)：将影子字段整体覆盖到正式字段并清空影子，作品保持上架，不修改章节状态</li>
     * </ul>
     */
    @Transactional(rollbackFor = Exception.class)
    public void auditPass(Long novelId, Long categoryId);

    /**
     * 审核拒绝。
     *
     * <p>变更待审被拒时不置为拒绝态：丢弃影子值、作品回落为「审核通过」，
     * 前台继续展示原有旧值。否则一次改稿被拒会使已上架的作品直接下线。
     * 与章节影子正文的拒绝处理语义一致。
     */
    @Transactional(rollbackFor = Exception.class)
    public void auditReject(Long novelId, String reason);

    // ==================== 章节级审核 ====================

    /**
     * 章节审核分页：默认查「待审(0) + 变更待审(3)」，可传 auditStatus 精确过滤。
     * 返回 VO 含书名/作者/正文节选（变更章取 pending_content，并附旧版节选供对比）。
     */
    public PageResult<ChapterAuditVO> auditChapterPage(int pageNum, int pageSize, Integer auditStatus);

    /**
     * 章节审核通过：新增章(0)→通过(1) + 重算聚合；变更待审章(3)→影子正文原子替换为通过(1) + 重算。
     */
    @Transactional(rollbackFor = Exception.class)
    public void auditChapterPass(Long chapterId);

    /**
     * 章节审核拒绝：新增章(0)→拒绝(2)；变更待审章(3)→回退通过(1)（清影子正文，读者继续见旧版）。
     * 均不影响读者可见集合，无需重算聚合/失效缓存。
     */
    @Transactional(rollbackFor = Exception.class)
    public void auditChapterReject(Long chapterId, String reason);

    public PageResult<UserVO> userPage(int pageNum, int pageSize, String keyword);

    public void updateUserStatus(Long userId, Integer status);

    public PageResult<RechargeOrderVO> rechargeOrderPage(int pageNum, int pageSize, Integer status, String keyword);

    public PageResult<SubscribeOrderVO> subscribeOrderPage(int pageNum, int pageSize, Integer status, String keyword);
}
