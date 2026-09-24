package com.ainovel.module.admin.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.ainovel.common.code.ErrorCode;
import com.ainovel.common.constant.MqConstant;
import com.ainovel.common.constant.RoleConstant;
import com.ainovel.common.domain.PageResult;
import com.ainovel.common.enums.AuditStatusEnum;
import com.ainovel.common.enums.ChapterAuditStatusEnum;
import com.ainovel.common.enums.CommonStatusEnum;
import com.ainovel.common.enums.OrderStatusEnum;
import com.ainovel.common.exception.BusinessException;
import com.ainovel.common.mq.MqSender;
import com.ainovel.common.constant.MessageTypeConstant;
import com.ainovel.module.admin.domain.vo.DashboardVO;
import com.ainovel.module.category.dao.CategoryMapper;
import com.ainovel.module.category.domain.entity.Category;
import com.ainovel.module.coin.dao.RechargeOrderMapper;
import com.ainovel.module.coin.domain.entity.RechargeOrder;
import com.ainovel.module.coin.domain.vo.RechargeOrderVO;
import com.ainovel.module.novel.dao.NovelMapper;
import com.ainovel.module.novel.dao.ChapterMapper;
import com.ainovel.module.novel.domain.entity.Novel;
import com.ainovel.module.novel.domain.entity.Chapter;
import com.ainovel.module.novel.domain.vo.ChapterAuditVO;
import com.ainovel.module.novel.domain.vo.NovelVO;
import com.ainovel.module.novel.service.ChapterService;
import com.ainovel.module.novel.service.NovelService;
import com.ainovel.module.admin.domain.vo.NovelAuditVO;
import com.ainovel.common.message.OssDeleteMessage;
import com.ainovel.module.message.service.MessageService;
import com.ainovel.common.message.SearchSyncMessage;
import com.ainovel.module.subscribe.dao.SubscribeOrderMapper;
import com.ainovel.module.subscribe.domain.entity.SubscribeOrder;
import com.ainovel.module.subscribe.domain.vo.SubscribeOrderVO;
import com.ainovel.module.user.dao.RoleMapper;
import com.ainovel.module.user.dao.UserMapper;
import com.ainovel.module.user.dao.UserRoleMapper;
import com.ainovel.module.user.domain.entity.Role;
import com.ainovel.module.user.domain.entity.User;
import com.ainovel.module.user.domain.entity.UserRole;
import com.ainovel.module.user.domain.vo.UserVO;
import com.ainovel.common.domain.PageParam;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import com.ainovel.module.admin.service.AdminService;

/**
 * 管理后台服务：数据看板 + 用户管理 + 订单管理
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminServiceImpl implements AdminService {

    /** 订单搜索中用户名模糊匹配的用户数上限，见 {@code userIdsByKeyword} */
    private static final int MAX_KEYWORD_USER_IDS = 200;

    private final UserMapper userMapper;

    private final NovelMapper novelMapper;

    private final ChapterMapper chapterMapper;

    private final CategoryMapper categoryMapper;

    private final RechargeOrderMapper rechargeOrderMapper;

    private final SubscribeOrderMapper subscribeOrderMapper;

    private final UserRoleMapper userRoleMapper;

    private final RoleMapper roleMapper;

    private final MessageService messageService;

    private final MqSender mqSender;

    private final NovelService novelService;

    private final ChapterService chapterService;

    /**
     * 数据看板聚合：各类计数 + 充值/解锁汇总 + 热度榜 + 近期订单
     */
    public DashboardVO dashboard() {
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        long userTotal = userMapper.selectCount(null);
        long userToday = userMapper.selectCount(
                new LambdaQueryWrapper<User>().ge(User::getCreateTime, todayStart));
        long novelTotal = novelMapper.selectCount(null);
        long novelOnline = novelMapper.selectCount(
                new LambdaQueryWrapper<Novel>().eq(Novel::getStatus, CommonStatusEnum.ENABLED.getCode()));
        // 待办含首次待审(0)、变更待审(3)、重新上架待审(4)：三者都要管理员动手
        long pendingAudit = novelMapper.selectCount(new LambdaQueryWrapper<Novel>()
                .in(Novel::getAuditStatus, AuditStatusEnum.WAIT.getCode(),
                        AuditStatusEnum.MODIFY_WAIT.getCode(),
                        AuditStatusEnum.RESHELVE_WAIT.getCode()));

        long rechargeCount = rechargeOrderMapper.selectCount(
                new LambdaQueryWrapper<RechargeOrder>()
                        .eq(RechargeOrder::getStatus, OrderStatusEnum.PAID.getCode()));
        BigDecimal rechargeAmount = sumPaidColumn(rechargeOrderMapper,
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<RechargeOrder>()
                        .select("IFNULL(SUM(pay_amount),0)"));
        long subscribeCount = subscribeOrderMapper.selectCount(
                new LambdaQueryWrapper<SubscribeOrder>()
                        .eq(SubscribeOrder::getStatus, OrderStatusEnum.PAID.getCode()));
        BigDecimal subscribeCoin = sumPaidColumn(subscribeOrderMapper,
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<SubscribeOrder>()
                        .select("IFNULL(SUM(coin_amount),0)"));

        return DashboardVO.builder()
                .userTotal(userTotal)
                .userToday(userToday)
                .novelTotal(novelTotal)
                .novelOnline(novelOnline)
                .pendingAudit(pendingAudit)
                .rechargeCount(rechargeCount)
                .rechargeAmount(rechargeAmount)
                .subscribeCount(subscribeCount)
                .subscribeCoin(subscribeCoin == null ? 0 : subscribeCoin.longValue())
                .hotNovels(topNovels())
                .recentOrders(recentOrders())
                .build();
    }

    /**
     * 热度 Top5：按阅读量倒序取前 5，附分类名
     */
    private List<DashboardVO.TopNovel> topNovels() {
        List<Novel> top = novelMapper.selectList(new LambdaQueryWrapper<Novel>()
                .orderByDesc(Novel::getReadCount)
                .last("LIMIT 5"));
        if (top.isEmpty()) {
            return List.of();
        }
        List<Long> categoryIds = top.stream().map(Novel::getCategoryId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        Map<Long, String> categoryNames = categoryIds.isEmpty() ? Map.of()
                : categoryMapper.selectBatchIds(categoryIds).stream()
                        .collect(Collectors.toMap(Category::getId, Category::getName));
        return top.stream().map(d -> DashboardVO.TopNovel.builder()
                .id(d.getId())
                .title(d.getTitle())
                .categoryName(categoryNames.getOrDefault(d.getCategoryId(), "未分类"))
                .readCount(d.getReadCount())
                .likeCount(d.getLikeCount())
                .build()).toList();
    }

    /**
     * 近期订单：充值 + 解锁各取最新 8 条合并，按时间倒序截取 8 条，附用户名与业务描述
     */
    private List<DashboardVO.RecentOrder> recentOrders() {
        List<RechargeOrder> recharges = rechargeOrderMapper.selectList(
                new LambdaQueryWrapper<RechargeOrder>()
                        .orderByDesc(RechargeOrder::getCreateTime).last("LIMIT 8"));
        List<SubscribeOrder> subscribes = subscribeOrderMapper.selectList(
                new LambdaQueryWrapper<SubscribeOrder>()
                        .orderByDesc(SubscribeOrder::getCreateTime).last("LIMIT 8"));

        Map<Long, String> userNames = userNameMap(recharges, subscribes);
        Map<Long, String> novelNames = novelNameMap(subscribes);

        java.util.ArrayList<DashboardVO.RecentOrder> merged = new java.util.ArrayList<>();
        recharges.forEach(o -> merged.add(DashboardVO.RecentOrder.builder()
                .type("RECHARGE")
                .orderNo(o.getOrderNo())
                .username(userNames.getOrDefault(o.getUserId(), "用户" + o.getUserId()))
                .desc("充值 ¥" + (o.getPayAmount() == null ? "0" : o.getPayAmount().stripTrailingZeros().toPlainString())
                        + " · " + o.getCoinAmount() + " 币")
                .amount(o.getPayAmount())
                .status(o.getStatus())
                .createTime(o.getCreateTime())
                .build()));
        subscribes.forEach(o -> merged.add(DashboardVO.RecentOrder.builder()
                .type("SUBSCRIBE")
                .orderNo(o.getOrderNo())
                .username(userNames.getOrDefault(o.getUserId(), "用户" + o.getUserId()))
                .desc("解锁《" + novelNames.getOrDefault(o.getNovelId(), "未知小说") + "》")
                .amount(o.getCoinAmount() == null ? BigDecimal.ZERO : BigDecimal.valueOf(o.getCoinAmount()))
                .status(o.getStatus())
                .createTime(o.getCreateTime())
                .build()));
        merged.sort(Comparator.comparing(DashboardVO.RecentOrder::getCreateTime, Comparator.nullsLast(Comparator.reverseOrder())));
        return merged.stream().limit(8).toList();
    }

    private Map<Long, String> userNameMap(List<RechargeOrder> recharges, List<SubscribeOrder> subscribes) {
        List<Long> userIds = new java.util.ArrayList<>();
        recharges.forEach(o -> userIds.add(o.getUserId()));
        subscribes.forEach(o -> userIds.add(o.getUserId()));
        List<Long> distinct = userIds.stream().distinct().toList();
        if (distinct.isEmpty()) {
            return Map.of();
        }
        return userMapper.selectBatchIds(distinct).stream()
                .collect(Collectors.toMap(User::getId, User::getUsername, (a, b) -> a));
    }

    private Map<Long, String> novelNameMap(List<SubscribeOrder> subscribes) {
        List<Long> novelIds = subscribes.stream().map(SubscribeOrder::getNovelId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        if (novelIds.isEmpty()) {
            return Map.of();
        }
        return novelMapper.selectBatchIds(novelIds).stream()
                .collect(Collectors.toMap(Novel::getId, Novel::getTitle, (a, b) -> a));
    }

    /**
     * 对 mapper 执行带 status=已支付 条件的 SUM 聚合，空表返回 BigDecimal.ZERO
     */
    private <T> BigDecimal sumPaidColumn(com.baomidou.mybatisplus.core.mapper.BaseMapper<T> mapper,
                                         com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<T> wrapper) {
        wrapper.eq("status", OrderStatusEnum.PAID.getCode());
        List<Object> sums = mapper.selectObjs(wrapper);
        if (sums == null || sums.isEmpty() || sums.get(0) == null) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(sums.get(0).toString());
    }

    /**
     * 作品审核分页（auditStatus 不传则查全部）
     */
    public PageResult<NovelAuditVO> auditPage(int pageNum, int pageSize, Integer auditStatus) {
        LambdaQueryWrapper<Novel> wrapper = new LambdaQueryWrapper<Novel>()
                .eq(auditStatus != null, Novel::getAuditStatus, auditStatus)
                .orderByAsc(Novel::getId);
        Page<Novel> page = novelMapper.selectPage(new Page<>(PageParam.clampPage(pageNum), PageParam.clampSize(pageSize)), wrapper);

        List<Long> categoryIds = page.getRecords().stream().map(Novel::getCategoryId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        Map<Long, String> categoryNames = categoryIds.isEmpty() ? Map.of()
                : categoryMapper.selectBatchIds(categoryIds).stream()
                        .collect(Collectors.toMap(Category::getId, Category::getName));
        List<NovelAuditVO> list = page.getRecords().stream().map(d -> {
            NovelAuditVO vo = BeanUtil.copyProperties(d, NovelAuditVO.class);
            vo.setCategoryName(categoryNames.get(d.getCategoryId()));
            // 变更待审时把影子值一并下发，供管理端做新旧对照
            vo.setPendingCategoryName(categoryNames.get(d.getPendingCategoryId()));
            return vo;
        }).toList();
        fillExcerpt(list);
        return PageResult.of(page.getTotal(), pageNum, pageSize, list);
    }

    /**
     * 管理端作品列表：与对外书库不同，此处必须能查看全部作品。
     *
     * <p>对外书库 {@code /novel/page} 已固定可见性（仅上架且审核通过 / 变更待审），
     * 管理端若沿用该过滤将无法查看完整数据：已下架作品需要支持重新上架，被驳回作品需要展示原因，
     * 管理员必须能访问库内全部状态。这是本接口独立存在的原因。
     */
    public PageResult<NovelVO> novelPage(int pageNum, int pageSize, String keyword,
                                         Long categoryId, Integer status, Integer auditStatus) {
        LambdaQueryWrapper<Novel> wrapper = new LambdaQueryWrapper<Novel>()
                .like(StringUtils.hasText(keyword), Novel::getTitle, keyword)
                .eq(categoryId != null, Novel::getCategoryId, categoryId)
                .eq(status != null, Novel::getStatus, status)
                .eq(auditStatus != null, Novel::getAuditStatus, auditStatus)
                .orderByDesc(Novel::getId);
        Page<Novel> page = novelMapper.selectPage(new Page<>(PageParam.clampPage(pageNum), PageParam.clampSize(pageSize)), wrapper);

        List<Long> categoryIds = page.getRecords().stream().map(Novel::getCategoryId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        Map<Long, String> categoryNames = categoryIds.isEmpty() ? Map.of()
                : categoryMapper.selectBatchIds(categoryIds).stream()
                        .collect(Collectors.toMap(Category::getId, Category::getName));
        List<NovelVO> list = page.getRecords().stream().map(d -> {
            NovelVO vo = BeanUtil.copyProperties(d, NovelVO.class);
            vo.setCategoryName(categoryNames.get(d.getCategoryId()));
            return vo;
        }).toList();
        return PageResult.of(page.getTotal(), pageNum, pageSize, list);
    }

    /**
     * 回填审核预览用的首章正文节选（截断 ~500 字），供管理端文本预览，避免逐本再请求章节接口
     *
     * <p>采用一次批量查询（{@code selectFirstChaptersByNovelIds}）：原实现在循环中逐本
     * {@code selectOne}，一页最多 100 本即产生 100 次额外查询，属典型 N+1 问题。
     */
    private void fillExcerpt(List<? extends NovelVO> list) {
        if (list.isEmpty()) {
            return;
        }
        List<Long> novelIds = list.stream().map(NovelVO::getId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        if (novelIds.isEmpty()) {
            return;
        }
        Map<Long, Chapter> firstChapterMap = chapterMapper.selectFirstChaptersByNovelIds(novelIds).stream()
                .filter(c -> c.getNovelId() != null)
                .collect(Collectors.toMap(Chapter::getNovelId, Function.identity(), (a, b) -> a));
        for (NovelVO vo : list) {
            Chapter first = firstChapterMap.get(vo.getId());
            if (first != null && StringUtils.hasText(first.getContent())) {
                String content = first.getContent().trim();
                vo.setExcerpt(content.length() > 500 ? content.substring(0, 500) + "…" : content);
            }
        }
    }

    /**
     * 待人工处理数量（侧边栏红点 / 顶栏待办 chip）。
     *
     * <p>含三类：首次提交待审(0)、已通过作品的变更待审(3)、已下架作品的重新上架申请(4)，
     * 三者均需管理员处理。
     */
    public long pendingAuditCount() {
        return novelMapper.selectCount(new LambdaQueryWrapper<Novel>()
                .in(Novel::getAuditStatus, AuditStatusEnum.WAIT.getCode(),
                        AuditStatusEnum.MODIFY_WAIT.getCode(),
                        AuditStatusEnum.RESHELVE_WAIT.getCode()));
    }

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
    public void auditPass(Long novelId, Long categoryId) {
        Novel novel = requireWaitAudit(novelId);

        if (AuditStatusEnum.MODIFY_WAIT.getCode() == novel.getAuditStatus()) {
            applyPendingEdit(novel, categoryId);
            return;
        }

        Novel update = new Novel();
        update.setId(novelId);
        update.setAuditStatus(AuditStatusEnum.PASS.getCode());
        update.setStatus(CommonStatusEnum.ENABLED.getCode());
        update.setAuditResult("人工审核通过");
        if (categoryId != null && !categoryId.equals(novel.getCategoryId())) {
            if (categoryMapper.selectById(categoryId) == null) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "分类不存在");
            }
            update.setCategoryId(categoryId);
        }
        novelMapper.updateById(update);

        // 作品过审 → 随作品一起发布的章节（发布时置待审）批量置通过，否则读者目录过滤后为空
        Chapter chapterBatch = new Chapter();
        chapterBatch.setAuditStatus(ChapterAuditStatusEnum.PASS.getCode());
        chapterBatch.setAuditResult("人工审核通过");
        chapterMapper.update(chapterBatch, new LambdaUpdateWrapper<Chapter>()
                .eq(Chapter::getNovelId, novelId)
                .eq(Chapter::getAuditStatus, ChapterAuditStatusEnum.WAIT.getCode()));
        chapterService.evictListCache(novelId);

        // 审核改状态绕过 NovelService，需手动失效详情缓存（发布时已缓存待审状态）
        novelService.evictDetailCache(novelId);

        syncSearch(novelId, "UPSERT");
        notifyAuthor(novel, MessageTypeConstant.AUDIT_PASS,
                "作品审核通过",
                "您的作品《" + novel.getTitle() + "》已通过审核并上架，快去分享给小伙伴吧");
    }

    /**
     * 变更送审通过：影子值整体覆盖正式字段并清空影子。
     *
     * <p>使用 LambdaUpdateWrapper 而非 updateById：影子字段需要置回 null，
     * 而 MyBatis-Plus 的 updateById 默认策略会忽略 null 值，无法清空。
     */
    private void applyPendingEdit(Novel novel, Long categoryId) {
        Long novelId = novel.getId();
        // 分类以影子值为准；管理员若在审核时指定了修正分类，则以管理员的为准
        Long finalCategoryId = categoryId != null ? categoryId : novel.getPendingCategoryId();
        if (finalCategoryId != null && categoryMapper.selectById(finalCategoryId) == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "分类不存在");
        }
        String oldCoverUrl = novel.getCoverUrl();

        novelMapper.update(null, new LambdaUpdateWrapper<Novel>()
                .eq(Novel::getId, novelId)
                .set(Novel::getTitle, novel.getPendingTitle())
                .set(Novel::getIntro, novel.getPendingIntro())
                .set(Novel::getCoverUrl, novel.getPendingCoverUrl())
                .set(Novel::getTags, novel.getPendingTags())
                .set(Novel::getAuthor, novel.getPendingAuthor())
                .set(finalCategoryId != null, Novel::getCategoryId, finalCategoryId)
                .set(Novel::getAuditStatus, AuditStatusEnum.PASS.getCode())
                .set(Novel::getAuditResult, "变更审核通过")
                .set(Novel::getPendingTitle, null)
                .set(Novel::getPendingIntro, null)
                .set(Novel::getPendingCoverUrl, null)
                .set(Novel::getPendingTags, null)
                .set(Novel::getPendingCategoryId, null)
                .set(Novel::getPendingAuthor, null));

        novelService.evictDetailCache(novelId);
        syncSearch(novelId, "UPSERT");

        // 更换封面：新封面与旧封面不同且旧值非空时，异步删除旧对象（与 NovelService.save 处理一致）
        if (StringUtils.hasText(oldCoverUrl)
                && !oldCoverUrl.equals(novel.getPendingCoverUrl())) {
            OssDeleteMessage msg = new OssDeleteMessage();
            msg.setUrl(oldCoverUrl);
            mqSender.sendAfterCommit(MqConstant.OSS_EXCHANGE, MqConstant.OSS_DELETE_ROUTING_KEY, msg);
        }

        notifyAuthor(novel, MessageTypeConstant.AUDIT_PASS,
                "作品信息变更通过审核",
                "您的作品《" + novel.getPendingTitle() + "》信息变更已通过审核并生效");
    }

    /**
     * 审核拒绝。
     *
     * <p>变更待审被拒时不置为拒绝态：丢弃影子值、作品回落为「审核通过」，
     * 前台继续展示原有旧值。否则一次改稿被拒会使已上架的作品直接下线。
     * 与章节影子正文的拒绝处理语义一致。
     */
    @Transactional(rollbackFor = Exception.class)
    public void auditReject(Long novelId, String reason) {
        Novel novel = requireWaitAudit(novelId);

        if (AuditStatusEnum.MODIFY_WAIT.getCode() == novel.getAuditStatus()) {
            novelMapper.update(null, new LambdaUpdateWrapper<Novel>()
                    .eq(Novel::getId, novelId)
                    .set(Novel::getAuditStatus, AuditStatusEnum.PASS.getCode())
                    .set(Novel::getAuditResult, reason)
                    .set(Novel::getPendingTitle, null)
                    .set(Novel::getPendingIntro, null)
                    .set(Novel::getPendingCoverUrl, null)
                    .set(Novel::getPendingTags, null)
                    .set(Novel::getPendingCategoryId, null)
                    .set(Novel::getPendingAuthor, null));
            // 正式字段未变，但 auditResult 变了，详情缓存要失效
            novelService.evictDetailCache(novelId);
            notifyAuthor(novel, MessageTypeConstant.AUDIT_REJECT,
                    "作品信息变更未通过审核",
                    "您的作品《" + novel.getTitle() + "》信息变更未通过审核，已保留原内容："
                            + reason + "（可修改后重新提交）");
            return;
        }

        // 重新上架申请被拒：作品本身合规（此前已过审），仅本次申请不予恢复分发。
        // 因此回落为「已下架」态而非「审核拒绝」：若置为 REJECT，作品将从读者可见性中消失，
        // 已收藏的读者将无法打开详情页，已购章节也无法阅读。
        if (AuditStatusEnum.RESHELVE_WAIT.getCode() == novel.getAuditStatus()) {
            novelMapper.update(null, new LambdaUpdateWrapper<Novel>()
                    .eq(Novel::getId, novelId)
                    .set(Novel::getAuditStatus, AuditStatusEnum.PASS.getCode())
                    .set(Novel::getAuditResult, reason));
            novelService.evictDetailCache(novelId);
            notifyAuthor(novel, MessageTypeConstant.AUDIT_REJECT,
                    "重新上架申请未通过",
                    "您的作品《" + novel.getTitle() + "》重新上架申请未通过，作品保持下架："
                            + reason);
            return;
        }

        Novel update = new Novel();
        update.setId(novelId);
        update.setAuditStatus(AuditStatusEnum.REJECT.getCode());
        update.setStatus(CommonStatusEnum.DISABLED.getCode());
        update.setAuditResult(reason);
        novelMapper.updateById(update);

        // 审核改状态绕过 NovelService，需手动失效详情缓存
        novelService.evictDetailCache(novelId);

        notifyAuthor(novel, MessageTypeConstant.AUDIT_REJECT,
                "作品未通过审核",
                "您的作品《" + novel.getTitle() + "》未通过审核：" + reason);
    }

    // ==================== 章节级审核 ====================

    /**
     * 章节审核分页：默认查「待审(0) + 变更待审(3)」，可传 auditStatus 精确过滤。
     * 返回 VO 含书名/作者/正文节选（变更章取 pending_content，并附旧版节选供对比）。
     */
    public PageResult<ChapterAuditVO> auditChapterPage(int pageNum, int pageSize, Integer auditStatus) {
        LambdaQueryWrapper<Chapter> wrapper = new LambdaQueryWrapper<>();
        if (auditStatus != null) {
            wrapper.eq(Chapter::getAuditStatus, auditStatus);
        } else {
            wrapper.in(Chapter::getAuditStatus,
                    ChapterAuditStatusEnum.WAIT.getCode(), ChapterAuditStatusEnum.MODIFY_WAIT.getCode());
        }
        wrapper.orderByAsc(Chapter::getId);
        Page<Chapter> page = chapterMapper.selectPage(new Page<>(PageParam.clampPage(pageNum), PageParam.clampSize(pageSize)), wrapper);

        List<Long> novelIds = page.getRecords().stream().map(Chapter::getNovelId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        Map<Long, Novel> novelMap = novelIds.isEmpty() ? Map.of()
                : novelMapper.selectBatchIds(novelIds).stream()
                        .collect(Collectors.toMap(Novel::getId, Function.identity()));

        List<ChapterAuditVO> list = page.getRecords().stream().map(c -> {
            ChapterAuditVO vo = new ChapterAuditVO();
            vo.setId(c.getId());
            vo.setNovelId(c.getNovelId());
            vo.setChapterNo(c.getChapterNo());
            vo.setTitle(c.getTitle());
            vo.setUnlockCoin(c.getUnlockCoin());
            vo.setAuditStatus(c.getAuditStatus());
            vo.setAuditResult(c.getAuditResult());
            Novel n = novelMap.get(c.getNovelId());
            vo.setNovelTitle(n == null ? null : n.getTitle());
            vo.setAuthor(n == null ? null : n.getAuthor());
            boolean modify = c.getAuditStatus() != null
                    && c.getAuditStatus() == ChapterAuditStatusEnum.MODIFY_WAIT.getCode();
            vo.setExcerpt(truncateExcerpt(modify ? c.getPendingContent() : c.getContent()));
            if (modify) {
                vo.setOldExcerpt(truncateExcerpt(c.getContent()));
            }
            return vo;
        }).toList();
        return PageResult.of(page.getTotal(), pageNum, pageSize, list);
    }

    /**
     * 章节审核通过：新增章(0)→通过(1) + 重算聚合；变更待审章(3)→影子正文原子替换为通过(1) + 重算。
     */
    @Transactional(rollbackFor = Exception.class)
    public void auditChapterPass(Long chapterId) {
        Chapter chapter = requireWaitChapter(chapterId);
        Integer status = chapter.getAuditStatus();

        Chapter update = new Chapter();
        update.setId(chapterId);
        update.setAuditStatus(ChapterAuditStatusEnum.PASS.getCode());
        update.setAuditResult("人工审核通过");
        if (status == ChapterAuditStatusEnum.MODIFY_WAIT.getCode()) {
            String pending = chapter.getPendingContent();
            update.setContent(pending);
            update.setWordCount(pending == null ? 0 : pending.length());
            update.setPendingContent(null);
        }
        chapterMapper.updateById(update);

        chapterService.recountNovel(chapter.getNovelId());
        chapterService.evictListCache(chapter.getNovelId());
        // 变更待审章：正文刚被影子值替换，而审核期间可能已有读者读取过旧正文并留下缓存。
        // 若不清理正文缓存，读者将在 TTL（30 分钟）内继续看到旧版，与「过审即刻生效」不符。
        if (status == ChapterAuditStatusEnum.MODIFY_WAIT.getCode()) {
            chapterService.evictContentCache(chapter.getNovelId());
            // 影子正文被提升为正式正文（pending 为 null 时为「被清空」），两种情况都改变了
            // currentBody()。若不重建向量块，RAG 会持续将审核前的正文作为「前文」引用
            chapterService.requestChunkReindex(chapter.getNovelId(), chapter.getId());
        }
        novelService.evictDetailCache(chapter.getNovelId());
        syncSearch(chapter.getNovelId(), "UPSERT");

        Novel novel = novelMapper.selectById(chapter.getNovelId());
        if (novel != null && novel.getUserId() != null) {
            messageService.send(novel.getUserId(), MessageTypeConstant.AUDIT_PASS,
                    "章节审核通过",
                    "您的小说《" + novel.getTitle() + "》第" + chapter.getChapterNo() + "章已通过审核",
                    chapter.getId());
        }
    }

    /**
     * 章节审核拒绝：新增章(0)→拒绝(2)；变更待审章(3)→回退通过(1)（清影子正文，读者继续见旧版）。
     * 均不影响读者可见集合，无需重算聚合/失效缓存。
     */
    @Transactional(rollbackFor = Exception.class)
    public void auditChapterReject(Long chapterId, String reason) {
        Chapter chapter = requireWaitChapter(chapterId);
        boolean revertShadow = chapter.getAuditStatus() != null
                && chapter.getAuditStatus() == ChapterAuditStatusEnum.MODIFY_WAIT.getCode();

        Chapter update = new Chapter();
        update.setId(chapterId);
        update.setAuditResult(reason);
        if (revertShadow) {
            update.setAuditStatus(ChapterAuditStatusEnum.PASS.getCode());
            update.setPendingContent(null);
        } else {
            update.setAuditStatus(ChapterAuditStatusEnum.REJECT.getCode());
        }
        chapterMapper.updateById(update);

        if (revertShadow) {
            // 影子正文被丢弃、正文退回旧版：currentBody() 已变化。若不重建块，
            // 索引中会保留已被驳回的稿件，RAG 仍会将其作为「前文」引用
            chapterService.requestChunkReindex(chapter.getNovelId(), chapterId);
        }

        Novel novel = novelMapper.selectById(chapter.getNovelId());
        if (novel != null && novel.getUserId() != null) {
            messageService.send(novel.getUserId(), MessageTypeConstant.AUDIT_REJECT,
                    "章节未通过审核",
                    "您的小说《" + novel.getTitle() + "》第" + chapter.getChapterNo() + "章未通过审核：" + reason,
                    chapter.getId());
        }
    }

    /**
     * 校验章节存在且处于待审（0）或变更待审（3）状态。
     */
    private Chapter requireWaitChapter(Long chapterId) {
        Chapter chapter = chapterMapper.selectById(chapterId);
        if (chapter == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "章节不存在");
        }
        Integer status = chapter.getAuditStatus();
        if (status == null || (status != ChapterAuditStatusEnum.WAIT.getCode()
                && status != ChapterAuditStatusEnum.MODIFY_WAIT.getCode())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "该章节不处于待审核状态");
        }
        return chapter;
    }

    /** 正文节选（约 500 字），审核列表预览用，避免拉全量大字段 */
    private String truncateExcerpt(String content) {
        if (!StringUtils.hasText(content)) {
            return null;
        }
        String s = content.trim();
        return s.length() > 500 ? s.substring(0, 500) + "…" : s;
    }

    /**
     * 校验作品存在且处于待人工处理状态：首次待审(0) 或 变更待审(3)。
     * 终审只作用于这两种状态，其余（已通过/已拒绝）不允许重复处理。
     */
    private Novel requireWaitAudit(Long novelId) {
        Novel novel = novelMapper.selectById(novelId);
        if (novel == null) {
            throw new BusinessException(ErrorCode.NOVEL_NOT_FOUND);
        }
        if (!AuditStatusEnum.isPending(novel.getAuditStatus())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "该作品不处于待审核状态");
        }
        return novel;
    }

    private void notifyAuthor(Novel novel, String type, String title, String content) {
        if (novel.getUserId() != null) {
            messageService.send(novel.getUserId(), type, title, content, novel.getId());
        }
    }

    /**
     * 小说变更同步 ES（与 NovelService 同一 MQ 链路）
     *
     * <p>调用方多在 @Transactional 内（审核通过/拒绝），若在事务提交前投递，
     * 消费者回查 MySQL 会读到未提交的旧 status，导致 ES 与 DB 不一致（审核通过的新书无法被搜索到）。
     * 因此注册 afterCommit 回调，事务提交后再投递；无事务时立即投递。
     */
    private void syncSearch(Long novelId, String operation) {
        SearchSyncMessage msg = new SearchSyncMessage();
        msg.setNovelId(novelId);
        msg.setOperation(operation);
        // 审核通过/拒绝在事务内调用，走 afterCommit 延迟投递；无事务立即投递
        mqSender.sendAfterCommit(MqConstant.SEARCH_EXCHANGE, MqConstant.SEARCH_SYNC_ROUTING_KEY, msg);
    }

    public PageResult<UserVO> userPage(int pageNum, int pageSize, String keyword) {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.like(StringUtils.hasText(keyword), User::getUsername, keyword)
                .orderByDesc(User::getCreateTime);
        Page<User> page = userMapper.selectPage(new Page<>(PageParam.clampPage(pageNum), PageParam.clampSize(pageSize)), wrapper);
        List<UserVO> list = page.getRecords().stream()
                .map(u -> BeanUtil.copyProperties(u, UserVO.class)).toList();
        fillUserRole(list);
        return PageResult.of(page.getTotal(), pageNum, pageSize, list);
    }

    /**
     * 批量回填用户列表角色编码（避免逐用户查角色）
     */
    private void fillUserRole(List<UserVO> users) {
        if (users == null || users.isEmpty()) {
            return;
        }
        List<Long> userIds = users.stream().map(UserVO::getId).toList();
        List<UserRole> userRoles = userRoleMapper.selectList(
                new LambdaQueryWrapper<UserRole>().in(UserRole::getUserId, userIds));
        if (userRoles.isEmpty()) {
            return;
        }
        List<Long> roleIds = userRoles.stream().map(UserRole::getRoleId).distinct().toList();
        Map<Long, String> roleCodeById = roleMapper.selectBatchIds(roleIds).stream()
                .collect(Collectors.toMap(Role::getId, Role::getRoleCode));
        Map<Long, Boolean> isAdminByUser = userRoles.stream().collect(Collectors.toMap(
                UserRole::getUserId,
                ur -> RoleConstant.ROLE_CODE_ADMIN.equals(roleCodeById.get(ur.getRoleId())),
                Boolean::logicalOr));
        users.forEach(u -> u.setRole(Boolean.TRUE.equals(isAdminByUser.get(u.getId()))
                ? RoleConstant.ROLE_CODE_ADMIN
                : RoleConstant.ROLE_CODE_USER));
    }

    public void updateUserStatus(Long userId, Integer status) {
        if (!CommonStatusEnum.isValid(status)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "状态值非法，仅允许 0(禁用)/1(启用)");
        }
        User user = new User();
        user.setId(userId);
        user.setStatus(status);
        userMapper.updateById(user);
    }

    public PageResult<RechargeOrderVO> rechargeOrderPage(int pageNum, int pageSize, Integer status, String keyword) {
        LambdaQueryWrapper<RechargeOrder> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(status != null, RechargeOrder::getStatus, status);
        if (StringUtils.hasText(keyword)) {
            String kw = keyword.trim();
            wrapper.and(w -> {
                w.like(RechargeOrder::getOrderNo, kw);
                List<Long> userIds = userIdsByKeyword(kw);
                if (!userIds.isEmpty()) {
                    w.or().in(RechargeOrder::getUserId, userIds);
                }
            });
        }
        wrapper.orderByDesc(RechargeOrder::getCreateTime);
        Page<RechargeOrder> page = rechargeOrderMapper.selectPage(new Page<>(PageParam.clampPage(pageNum), PageParam.clampSize(pageSize)), wrapper);
        List<RechargeOrderVO> list = page.getRecords().stream()
                .map(o -> BeanUtil.copyProperties(o, RechargeOrderVO.class)).toList();
        fillUsername(list.stream().map(RechargeOrderVO::getUserId).toList(),
                name -> list.forEach(vo -> vo.setUsername(name.get(vo.getUserId()))));
        return PageResult.of(page.getTotal(), pageNum, pageSize, list);
    }

    public PageResult<SubscribeOrderVO> subscribeOrderPage(int pageNum, int pageSize, Integer status, String keyword) {
        LambdaQueryWrapper<SubscribeOrder> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(status != null, SubscribeOrder::getStatus, status);
        if (StringUtils.hasText(keyword)) {
            String kw = keyword.trim();
            wrapper.and(w -> {
                w.like(SubscribeOrder::getOrderNo, kw);
                List<Long> userIds = userIdsByKeyword(kw);
                if (!userIds.isEmpty()) {
                    w.or().in(SubscribeOrder::getUserId, userIds);
                }
            });
        }
        wrapper.orderByDesc(SubscribeOrder::getCreateTime);
        Page<SubscribeOrder> page = subscribeOrderMapper.selectPage(new Page<>(PageParam.clampPage(pageNum), PageParam.clampSize(pageSize)), wrapper);
        List<SubscribeOrderVO> list = page.getRecords().stream()
                .map(o -> BeanUtil.copyProperties(o, SubscribeOrderVO.class)).toList();
        fillUsername(list.stream().map(SubscribeOrderVO::getUserId).toList(),
                name -> list.forEach(vo -> vo.setUsername(name.get(vo.getUserId()))));
        return PageResult.of(page.getTotal(), pageNum, pageSize, list);
    }

    /**
     * 按关键字查询匹配的用户 ID（用户名模糊）
     *
     * <p>必须设置上限：返回值将作为订单查询的 `IN` 列表，模糊词命中大量用户时，
     * `IN` 列表过大会严重影响 SQL 性能。因此截断到 {@link #MAX_KEYWORD_USER_IDS} 并在触发时告警：
     * 静默截断会被误认为「搜索无结果」，告警才能表明已触及上限。
     */
    private List<Long> userIdsByKeyword(String keyword) {
        List<Long> ids = userMapper.selectList(
                        new LambdaQueryWrapper<User>().like(User::getUsername, keyword)
                                .last("LIMIT " + MAX_KEYWORD_USER_IDS))
                .stream().map(User::getId).toList();
        if (ids.size() == MAX_KEYWORD_USER_IDS) {
            log.warn("用户名关键字命中用户数达到上限 {}，订单搜索只按前 {} 个用户匹配: keyword={}",
                    MAX_KEYWORD_USER_IDS, MAX_KEYWORD_USER_IDS, keyword);
        }
        return ids;
    }

    /**
     * 批量取 userIds 对应的用户名映射，并回调消费
     */
    private void fillUsername(List<Long> userIds, java.util.function.Consumer<Map<Long, String>> consumer) {
        List<Long> distinct = userIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return;
        }
        Map<Long, String> nameMap = userMapper.selectBatchIds(distinct).stream()
                .collect(Collectors.toMap(User::getId, User::getUsername, (a, b) -> a));
        consumer.accept(nameMap);
    }
}
