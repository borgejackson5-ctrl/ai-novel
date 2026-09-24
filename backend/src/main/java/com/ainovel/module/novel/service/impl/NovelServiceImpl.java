package com.ainovel.module.novel.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.ainovel.common.code.ErrorCode;
import com.ainovel.common.constant.MessageTypeConstant;
import com.ainovel.common.constant.MqConstant;
import com.ainovel.common.constant.NovelConstant;
import com.ainovel.common.domain.PageParam;
import com.ainovel.common.domain.PageResult;
import com.ainovel.common.enums.AuditStatusEnum;
import com.ainovel.common.enums.ChapterAuditStatusEnum;
import com.ainovel.common.enums.CommonStatusEnum;
import com.ainovel.common.enums.NovelAppealStatusEnum;
import com.ainovel.common.enums.OrderStatusEnum;
import com.ainovel.common.enums.SerialStatusEnum;
import com.ainovel.common.exception.BusinessException;
import com.ainovel.common.message.NovelReadMessage;
import com.ainovel.common.mq.MqSender;
import com.ainovel.common.util.CacheHelper;
import com.ainovel.common.util.LoginUserUtil;
import com.ainovel.common.message.AiAuditMessage;
import com.ainovel.module.category.service.CategoryService;
import com.ainovel.module.message.service.MessageService;
import com.ainovel.common.message.OssDeleteMessage;
import com.ainovel.module.novel.dao.NovelAppealMapper;
import com.ainovel.module.novel.dao.NovelMapper;
import com.ainovel.module.novel.dao.ChapterMapper;
import com.ainovel.module.novel.domain.NovelVisibility;
import com.ainovel.module.novel.domain.entity.Novel;
import com.ainovel.module.novel.domain.entity.NovelAppeal;
import com.ainovel.module.novel.domain.entity.Chapter;
import com.ainovel.module.novel.domain.form.ChapterForm;
import com.ainovel.module.novel.domain.form.NovelEditForm;
import com.ainovel.module.novel.domain.form.NovelForm;
import com.ainovel.module.novel.domain.form.NovelPublishForm;
import com.ainovel.module.novel.domain.form.NovelQueryForm;
import com.ainovel.module.novel.domain.vo.LikeVO;
import com.ainovel.module.novel.domain.vo.NovelEditVO;
import com.ainovel.module.novel.domain.vo.NovelVO;
import com.ainovel.module.novel.spi.NovelPurchaseProbe;
import com.ainovel.common.message.ChapterChunkSyncMessage;
import com.ainovel.common.message.SearchSyncMessage;
import com.ainovel.module.user.domain.entity.User;
import com.ainovel.module.user.service.UserService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import com.ainovel.module.novel.service.NovelService;

/**
 * 小说服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NovelServiceImpl implements NovelService {

    /** 阅读去重的 key 前缀与窗口（UV 语义：同一用户 24h 内对同一本书只计一次） */
    private static final String READ_DEDUP_KEY_PREFIX = "novel:read:dedup:";
    private static final long READ_DEDUP_HOURS = 24;

    /** 详情缓存 key 前缀与 TTL：详情页属读多写少的访问热点，缓存 30 分钟 */
    private static final String DETAIL_KEY_PREFIX = "novel:detail:";
    private static final long DETAIL_TTL_MINUTES = 30;

    /** 详情缓存反序列化目标类型（泛型用 TypeReference 表达，见 CacheHelper） */
    private static final TypeReference<NovelVO> NOVEL_VO_TYPE = new TypeReference<>() {
    };

    private final NovelMapper novelMapper;

    private final NovelAppealMapper novelAppealMapper;

    private final CacheHelper cacheHelper;

    private final ChapterMapper chapterMapper;

    private final CategoryService categoryService;

    private final UserService userService;

    private final MqSender mqSender;

    private final StringRedisTemplate stringRedisTemplate;

    private final MessageService messageService;

    /** 判断「是否有读者为作品花过币」，为删除门槛的关键依据 */
    private final NovelPurchaseProbe novelPurchaseProbe;

    public PageResult<NovelVO> page(NovelQueryForm form) {
        LambdaQueryWrapper<Novel> wrapper = new LambdaQueryWrapper<>();
        // 可见性条件固定于服务端，不接受调用方覆盖：书库是对外分发入口，
        // 未过审(0)/审核拒绝(2) 的内容不得出现，已下架(status=0) 也不再分发。
        // 变更待审(3) 属于「已上架、改动待审」，前台仍显示旧内容，故保留可见。
        wrapper.like(StringUtils.hasText(form.getKeyword()), Novel::getTitle, form.getKeyword())
                .eq(form.getCategoryId() != null, Novel::getCategoryId, form.getCategoryId())
                .eq(form.getSerialStatus() != null, Novel::getSerialStatus, form.getSerialStatus())
                // 字数区间：上下界都可单独缺省，便于「50 万字以上」这类单边筛选
                .ge(form.getMinWords() != null, Novel::getWordCount, form.getMinWords())
                .le(form.getMaxWords() != null, Novel::getWordCount, form.getMaxWords());
        // 可见性口径统一取自 NovelVisibility（唯一实现），不在各调用点重复实现
        NovelVisibility.appendTo(wrapper);
        // 排序走白名单二选一，不接受字符串直拼（防注入）。
        // 「最新」用 id 倒序：雪花 ID 单调递增，等价于入库先后，且走主键索引。
        // 若按阅读量排序，新导入的书会长期排在末尾（首页无变化，易被误判为导入失败）。
        if (form.isLatest()) {
            wrapper.orderByDesc(Novel::getId);
        } else {
            wrapper.orderByDesc(Novel::getReadCount).orderByDesc(Novel::getId);
        }

        Page<Novel> page = novelMapper.selectPage(
                new Page<>(form.getPageNum(), form.getPageSize()), wrapper);

        Map<Long, String> categoryNames = categoryService.getNameMap();
        Long currentUserId = LoginUserUtil.getUserIdOrNull();
        List<NovelVO> voList = page.getRecords().stream().map(d -> {
            NovelVO vo = BeanUtil.copyProperties(d, NovelVO.class);
            vo.setCategoryName(categoryNames.get(d.getCategoryId()));
            // 归属标记：供前台在书库/榜单里把自己的作品标出来
            vo.setIsMine(currentUserId != null && currentUserId.equals(d.getUserId()));
            vo.setSerialStatusText(SerialStatusEnum.textOf(d.getSerialStatus()));
            return vo;
        }).toList();

        return PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(), voList);
    }

    public Novel getNovel(Long id) {
        return id == null ? null : novelMapper.selectById(id);
    }

    public List<Novel> listNovels(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return novelMapper.selectBatchIds(ids);
    }

    public boolean recordRead(Long novelId) {
        // 用户级去重：同一用户 24h 内对同一本书仅计一次（UV 语义），避免脚本重复刷高阅读量与榜单
        Long userId = LoginUserUtil.getUserId();
        String dedupKey = READ_DEDUP_KEY_PREFIX + userId + ":" + novelId;
        Boolean first = stringRedisTemplate.opsForValue()
                .setIfAbsent(dedupKey, "1", Duration.ofHours(READ_DEDUP_HOURS));
        if (!Boolean.TRUE.equals(first)) {
            return false;
        }
        // 更新本模块表：t_novel 归属 novel 模块，阅读量在此累加
        novelMapper.incrReadCount(novelId);
        // 异步通知榜单累加热度：热度为派生指标，其延迟或失败不影响读者请求
        NovelReadMessage msg = new NovelReadMessage();
        msg.setNovelId(novelId);
        mqSender.sendAfterCommit(MqConstant.RANK_READ_EXCHANGE, MqConstant.RANK_READ_ROUTING_KEY, msg);
        return true;
    }

    public List<Novel> listVisibleByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        LambdaQueryWrapper<Novel> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(Novel::getId, ids);
        NovelVisibility.appendTo(wrapper);
        return novelMapper.selectList(wrapper);
    }

    public List<Novel> listVisibleLatest(int limit) {
        LambdaQueryWrapper<Novel> wrapper = new LambdaQueryWrapper<>();
        NovelVisibility.appendTo(wrapper);
        wrapper.orderByDesc(Novel::getId);
        return novelMapper.selectList(wrapper.last("LIMIT " + limit));
    }

    public List<Novel> listVisibleHottest(int limit) {
        LambdaQueryWrapper<Novel> wrapper = new LambdaQueryWrapper<>();
        NovelVisibility.appendTo(wrapper);
        wrapper.orderByDesc(Novel::getReadCount);
        return novelMapper.selectList(wrapper.last("LIMIT " + limit));
    }

    public List<Novel> listVisibleFinishedHottest(int limit) {
        LambdaQueryWrapper<Novel> wrapper = new LambdaQueryWrapper<>();
        NovelVisibility.appendTo(wrapper);
        wrapper.eq(Novel::getSerialStatus, SerialStatusEnum.FINISHED.getCode());
        wrapper.orderByDesc(Novel::getReadCount);
        return novelMapper.selectList(wrapper.last("LIMIT " + limit));
    }

    public List<Novel> pageVisibleForIndex(int pageNo, int pageSize) {
        LambdaQueryWrapper<Novel> wrapper = new LambdaQueryWrapper<>();
        NovelVisibility.appendTo(wrapper);
        wrapper.orderByAsc(Novel::getId);
        // 使用 LIMIT 偏移而非分页插件：插件会在拦截器层把 size 截断到全局上限，
        // 导致调用方「本页不满即取完」的判据失真（首页即被误判为取完，索引仅写入一部分，
        // 且不产生任何报错）。偏移量由本方法计算，size 保持调用方传入值。
        wrapper.last("LIMIT " + Math.max(pageNo - 1, 0) * pageSize + ", " + pageSize);
        return novelMapper.selectList(wrapper);
    }

    public List<Long> pageVisibleIdsForIndex(int pageNo, int pageSize) {
        // 字符串列投影（配合 QueryWrapper）：只取 id 一列，且不依赖 TableInfo 解析
        QueryWrapper<Novel> wrapper = new QueryWrapper<Novel>().select("id");
        NovelVisibility.appendTo(wrapper);
        wrapper.orderByAsc("id");
        wrapper.last("LIMIT " + Math.max(pageNo - 1, 0) * pageSize + ", " + pageSize);
        return novelMapper.selectList(wrapper).stream().map(Novel::getId).toList();
    }

    public PageResult<Novel> searchByKeyword(String keyword, NovelQueryForm filter, int page, int size) {
        String kw = keyword == null ? "" : keyword.trim();
        LambdaQueryWrapper<Novel> wrapper = new LambdaQueryWrapper<>();
        wrapper.and(w -> w.like(Novel::getTitle, kw)
                .or().like(Novel::getIntro, kw)
                .or().like(Novel::getTags, kw));
        NovelVisibility.appendTo(wrapper);
        if (filter != null) {
            wrapper.eq(filter.getCategoryId() != null, Novel::getCategoryId, filter.getCategoryId())
                    .eq(filter.getSerialStatus() != null, Novel::getSerialStatus, filter.getSerialStatus())
                    .ge(filter.getMinWords() != null, Novel::getWordCount, filter.getMinWords())
                    .le(filter.getMaxWords() != null, Novel::getWordCount, filter.getMaxWords());
        }
        // 降级路径无相关度可用于排序，以阅读量倒序替代（与搜索主路径的差异主要体现在此）
        wrapper.orderByDesc(Novel::getReadCount);
        Page<Novel> result = novelMapper.selectPage(new Page<>(page, size), wrapper);
        return PageResult.of(result.getTotal(), result.getCurrent(), result.getSize(), result.getRecords());
    }

    public NovelVO detail(Long id) {
        NovelVO vo = cacheHelper.get(DETAIL_KEY_PREFIX + id, NOVEL_VO_TYPE,
                () -> buildDetail(id), DETAIL_TTL_MINUTES, TimeUnit.MINUTES);
        if (vo == null) {
            throw new BusinessException(ErrorCode.NOVEL_NOT_FOUND);
        }
        // 高频计数（阅读量/点赞量）实时回查，不进缓存，避免读到 30 分钟前的脏计数。
        // 同一次回查一并取出 status/audit_status/user_id，用于存在性、可见性、归属三项判定。
        Novel row = fillCounts(vo, id);
        assertDetailReadable(row);
        return vo;
    }

    /**
     * 详情可见性门：作品详情本身也是分发入口，未过审内容不能凭 id 直接访问。
     *
     * <ul>
     *   <li>查不到（含逻辑删除）→ 404，不必等 30 分钟详情缓存过期；</li>
     *   <li>审核通过 / 变更待审 → 公开可见（变更待审对外仍展示旧内容）；</li>
     *   <li>待审 / 已拒绝 → 仅作者本人与管理员可见，其余一律 404
     *       （返回 404 而非 403，避免把「这个 id 存在一本未过审的书」这一信息暴露出去）。</li>
     * </ul>
     */
    private void assertDetailReadable(Novel row) {
        if (row == null) {
            throw new BusinessException(ErrorCode.NOVEL_NOT_FOUND);
        }
        if (NovelVisibility.isAuditVisible(row.getAuditStatus()) || LoginUserUtil.isAdmin()) {
            return;
        }
        Long userId = LoginUserUtil.getUserIdOrNull();
        if (userId != null && userId.equals(row.getUserId())) {
            return;
        }
        throw new BusinessException(ErrorCode.NOVEL_NOT_FOUND);
    }

    /**
     * 回源组装详情静态字段（读多写少的元信息），供缓存存储。
     *
     * <p>计数字段 readCount/likeCount 在此置空，由 {@link #fillCounts} 实时回填。
     */
    private NovelVO buildDetail(Long id) {
        Novel novel = novelMapper.selectById(id);
        if (novel == null) {
            return null; // 交给 CacheHelper 空值缓存，防穿透
        }
        NovelVO vo = BeanUtil.copyProperties(novel, NovelVO.class);
        Map<Long, String> categoryNames = categoryService.getNameMap();
        vo.setCategoryName(categoryNames.get(novel.getCategoryId()));
        vo.setSerialStatusText(SerialStatusEnum.textOf(novel.getSerialStatus()));
        vo.setReadCount(null);
        vo.setLikeCount(null);
        // 首章 ID：供详情页「开始阅读」入口直接跳第一章（目录分页后前端不再拉全量取首章）。
        // 用字符串投影而非 lambda 投影：单测无 Spring 上下文时 lambda 依赖 TableInfo 会失败
        Chapter first = chapterMapper.selectOne(new QueryWrapper<Chapter>()
                .select("id")
                .eq("novel_id", id)
                .orderByAsc("chapter_no")
                .last("LIMIT 1"));
        vo.setFirstChapterId(first == null ? null : first.getId());
        return vo;
    }

    /**
     * 实时回填「按请求而变」的字段：计数、点赞状态、作品归属、是否已下架。这些不进缓存
     * （缓存存的是元信息旧值，变更待审期间前台也正是靠读旧值保持稳定）。
     *
     * @return 本次回查到的行（含 user_id / status / audit_status），供调用方做可见性判定，
     *         查不到（已逻辑删除）返回 null
     */
    private Novel fillCounts(NovelVO vo, Long id) {
        Novel row = novelMapper.selectCounts(id);
        if (row != null) {
            vo.setReadCount(row.getReadCount());
            vo.setLikeCount(row.getLikeCount());
        }
        Long userId = LoginUserUtil.getUserIdOrNull();
        vo.setLiked(userId != null && Boolean.TRUE.equals(
                stringRedisTemplate.hasKey("novel:like:" + userId + ":" + id)));
        vo.setIsMine(userId != null && row != null && userId.equals(row.getUserId()));
        // 已下架：曾上架后被下架（审核维度仍通过），区别于「从未上架」。该类作品详情仍可打开，
        // 已购读者的权益不被单方面收回，仅不再出现在书库/榜单/搜索中。
        vo.setOffline(row != null
                && row.getStatus() != null && row.getStatus() == CommonStatusEnum.DISABLED.getCode()
                && NovelVisibility.isAuditVisible(row.getAuditStatus()));
        return row;
    }

    /**
     * 失效详情缓存：写操作（编辑/删除/上下架）先更 DB 后立即删缓存，Cache-Aside 一致性。
     */
    public void evictDetailCache(Long id) {
        // 排到事务提交之后再删：写操作多在事务内，事务内删缓存会让并发读把旧值回填进缓存
        // （Cache-Aside 竞态，脏数据要等 TTL 才消失）。无事务时立即删，语义不变。
        cacheHelper.evictAfterCommit(DETAIL_KEY_PREFIX + id);
    }

    /**
     * 用户发布作品：创建小说（待审核、下架）+ 循环建章 + 发 MQ 进入 AI 预审/人工终审链路
     */
    @Transactional(rollbackFor = Exception.class)
    public NovelVO publish(NovelPublishForm form) {
        Long userId = LoginUserUtil.getUserId();

        Map<Long, String> categoryNames = categoryService.getNameMap();
        if (!categoryNames.containsKey(form.getCategoryId())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "分类不存在，请重新选择");
        }
        User user = userService.getUser(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }

        List<ChapterForm> chapters = form.getChapters();
        long wordCount = 0L;
        long paidCoinSum = 0L; // 付费章解锁币之和（首章强制免费，不计入）
        for (int i = 0; i < chapters.size(); i++) {
            ChapterForm c = chapters.get(i);
            wordCount += c.getContent() == null ? 0 : c.getContent().length();
            // 首章(i=0)强制免费试读，只累加后续付费章的解锁币
            if (i > 0) {
                Integer coin = c.getUnlockCoin();
                if (coin != null && coin > 0) {
                    paidCoinSum += coin;
                }
            }
        }
        // 整本打包价 = 付费章解锁币之和 × 六折；有付费章时必须 > 0，否则「整本解锁」可 0 币买断、绕过单章付费墙
        int coinPrice = paidCoinSum == 0 ? 0 : (int) Math.max(1, Math.round(paidCoinSum * NovelConstant.BUNDLE_DISCOUNT));

        Novel novel = new Novel();
        novel.setTitle(form.getTitle());
        novel.setCategoryId(form.getCategoryId());
        novel.setCoverUrl(form.getCoverUrl());
        novel.setIntro(form.getIntro());
        novel.setTags(form.getTags());
        novel.setAuthor(StringUtils.hasText(form.getAuthor())
                ? form.getAuthor()
                : (StringUtils.hasText(user.getNickname()) ? user.getNickname() : user.getUsername()));
        novel.setUserId(userId);
        novel.setTotalChapters(chapters.size());
        novel.setWordCount(wordCount);
        novel.setCoinPrice(coinPrice);
        novel.setReadCount(0L);
        novel.setLikeCount(0L);
        // 未上架 + 待审核：审核通过时自动上架
        novel.setStatus(CommonStatusEnum.DISABLED.getCode());
        novel.setAuditStatus(AuditStatusEnum.WAIT.getCode());
        novelMapper.insert(novel);

        // 循环建章：首章强制免费试读，其余按用户设定价格（默认免费）；章节随作品一起待审
        //
        // 此处直接调用 chapterMapper.insert，**未经过 ChapterService 的 afterChapterChange funnel**。
        // 该 funnel 会额外执行三项操作，逐条说明发布路径为何无需执行：
        //   ① 重算作品 total_chapters / word_count：上文已按整个表单计算完成后 insert（389/390 行）；
        //   ② 清除分页目录缓存：作品刚创建，缓存中不可能存在该条目；
        //   ③ 投递「章节正文变了」消息：下文每章显式投递 sendChunkSyncMessage，
        //      作品级 ES 同步在末尾投递 sendSyncMessage。
        // 不走 funnel 的原因见 sendChunkSyncMessage 的注释（bean 环）。
        // 上述「无需执行」的结论有前提：**作品初始为下架状态、且整批章节一次建完**。
        // 若将发布改为「直接上架」或「分批追加章节」，② 与 ① 的假设不再成立，
        // 需同步补充对应处理。
        int no = 1;
        for (ChapterForm c : chapters) {
            String content = c.getContent() == null ? "" : c.getContent();
            Chapter chapter = new Chapter();
            chapter.setNovelId(novel.getId());
            chapter.setChapterNo(no);
            chapter.setTitle(StringUtils.hasText(c.getTitle()) ? c.getTitle() : ("第" + no + "章"));
            chapter.setContent(content);
            chapter.setWordCount(content.length());
            chapter.setUnlockCoin(no == 1 ? 0 : (c.getUnlockCoin() == null ? 0 : c.getUnlockCoin()));
            chapter.setSort(no);
            chapter.setAuditStatus(ChapterAuditStatusEnum.WAIT.getCode());
            chapter.setAuditResult("待审核");
            chapterMapper.insert(chapter);
            // 逐章请求重建向量块（投递的是「本章正文已变更」这一事实，与保存/审核路径共用同一消费者）。
            // 不走 ChapterService 的原因见 sendChunkSyncMessage 的注释（bean 环）。
            // 量级说明：一次发布携带几章即产生几条消息；表单无章数上限，若后续支持数百章，
            // 此处需改为投递一条「整本重建」，避免单次发布产生数百次向量调用。
            sendChunkSyncMessage(novel.getId(), chapter.getId());
            no++;
        }

        // 同步 ES（最终一致），并发 MQ 进入 AI 预审（敏感词 + LLM）→ 合规则转人工终审
        sendSyncMessage(novel.getId(), "UPSERT");
        AiAuditMessage message = new AiAuditMessage();
        message.setNovelId(novel.getId());
        // AI 审核在事务内触发，afterCommit 后投递，避免消费者提交前回查 null 而进死信
        mqSender.sendAfterCommit(MqConstant.AI_EXCHANGE, MqConstant.AI_AUDIT_ROUTING_KEY, message);
        log.info("用户发布作品: novelId={}, userId={}, chapters={}", novel.getId(), userId, chapters.size());

        return detail(novel.getId());
    }

    /**
     * 当前用户的作品分页（含审核状态与拒绝原因）
     */
    public PageResult<NovelVO> mine(long pageNum, long pageSize) {
        Long userId = LoginUserUtil.getUserId();
        // 分页参数钳位：防止 pageSize 传入过大值造成查询压力（与 PageParam.getPageSize 同口径）
        long safePageNum = Math.max(pageNum, 1);
        long safePageSize = Math.min(Math.max(pageSize, 1), PageParam.MAX_PAGE_SIZE);
        Page<Novel> page = novelMapper.selectPage(
                new Page<>(safePageNum, safePageSize),
                new LambdaQueryWrapper<Novel>()
                        .eq(Novel::getUserId, userId)
                        .orderByDesc(Novel::getId));

        Map<Long, String> categoryNames = categoryService.getNameMap();
        List<Novel> rows = page.getRecords();
        // 一次批量查询判断哪些作品有付费读者，供删除门槛展示（避免逐本回查订单表）
        Set<Long> paidNovelIds = paidNovelIds(rows.stream().map(Novel::getId).toList());
        // 同理，批量查「有在途申请」的作品，避免逐本查工单表
        Set<Long> appealNovelIds = pendingAppealNovelIds(rows.stream().map(Novel::getId).toList());
        List<NovelVO> voList = rows.stream().map(d -> {
            NovelVO vo = BeanUtil.copyProperties(d, NovelVO.class);
            vo.setCategoryName(categoryNames.get(d.getCategoryId()));
            // 查询本身已按 user_id 过滤，这里恒为 true，统一字段语义让前端无需分情况处理
            vo.setIsMine(true);
            fillAuthorActions(vo, d, paidNovelIds.contains(d.getId()), appealNovelIds.contains(d.getId()));
            return vo;
        }).toList();
        return PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(), voList);
    }

    /** 一次 IN 查询取回「有待处理申请」的作品 ID 集合 */
    private Set<Long> pendingAppealNovelIds(List<Long> novelIds) {
        if (novelIds.isEmpty()) {
            return Set.of();
        }
        return novelAppealMapper.selectList(new LambdaQueryWrapper<NovelAppeal>()
                        .select(NovelAppeal::getNovelId)
                        .in(NovelAppeal::getNovelId, novelIds)
                        .eq(NovelAppeal::getStatus, NovelAppealStatusEnum.PENDING.getCode()))
                .stream().map(NovelAppeal::getNovelId).collect(Collectors.toSet());
    }

    /**
     * 某位作者的公开发布作品（作者主页用）：只返回读者可见的作品。
     *
     * <p>可见性 = 审核通过 或 变更待审，且未下架；否则作者主页会暴露尚未过审的作品。
     */
    public PageResult<NovelVO> byAuthor(Long authorId, long pageNum, long pageSize) {
        long safePageNum = Math.max(pageNum, 1);
        long safePageSize = Math.min(Math.max(pageSize, 1), PageParam.MAX_PAGE_SIZE);
        LambdaQueryWrapper<Novel> authorWrapper = new LambdaQueryWrapper<Novel>()
                .eq(Novel::getUserId, authorId);
        // 可见性条件统一走 NovelVisibility（唯一实现），不在调用点自行拼接 eq/in
        NovelVisibility.appendTo(authorWrapper);
        authorWrapper.orderByDesc(Novel::getReadCount);
        Page<Novel> page = novelMapper.selectPage(new Page<>(safePageNum, safePageSize), authorWrapper);

        Map<Long, String> categoryNames = categoryService.getNameMap();
        Long currentUserId = LoginUserUtil.getUserIdOrNull();
        List<NovelVO> voList = page.getRecords().stream().map(d -> {
            NovelVO vo = BeanUtil.copyProperties(d, NovelVO.class);
            vo.setCategoryName(categoryNames.get(d.getCategoryId()));
            vo.setIsMine(currentUserId != null && currentUserId.equals(d.getUserId()));
            vo.setSerialStatusText(SerialStatusEnum.textOf(d.getSerialStatus()));
            return vo;
        }).toList();
        return PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(), voList);
    }

    /**
     * 作品写操作的统一权限门：当前用户为本书作者或管理员，否则拒绝。
     * 与 {@code ChapterService.requireOwnerNovel} 语义一致，避免两处判定口径不一致。
     */
    public Novel requireOwnerNovel(Long novelId) {
        Novel novel = novelMapper.selectById(novelId);
        if (novel == null) {
            throw new BusinessException(ErrorCode.NOVEL_NOT_FOUND);
        }
        Long userId = LoginUserUtil.getUserId();
        boolean owner = novel.getUserId() != null && novel.getUserId().equals(userId);
        if (!owner && !LoginUserUtil.isAdmin()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权限操作该作品");
        }
        return novel;
    }

    // ==================== 作者自助：下架 / 重新上架 / 删除 ====================
    //
    // 三者构成「由轻到重」的处置链：下架 = 停止分发（可逆）；重新上架 = 回到审核队列；
    // 删除 = 终结（不可逆）。动作越重门槛越高，且每一档都需考虑「正在阅读本书的读者」。
    //
    // 管理员强制下架走 changeStatus()，不受新书保护期约束，以保证违规内容可立即处置。

    /**
     * 作者自助下架：立即停止对外分发（书库/榜单/搜索不再出现），
     * 但详情页仍可打开、已解锁章节仍可读，已付费读者的权益不因作者单方面操作被收回。
     */
    public void offlineByAuthor(Long novelId) {
        Novel novel = requireOwnerNovel(novelId);
        if (novel.getStatus() == null || novel.getStatus() != CommonStatusEnum.ENABLED.getCode()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "作品当前不是上架状态");
        }
        String blocked = unshelveBlockedReason(novel);
        if (blocked != null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, blocked);
        }

        Novel update = new Novel();
        update.setId(novelId);
        update.setStatus(CommonStatusEnum.DISABLED.getCode());
        update.setOfflineTime(LocalDateTime.now());
        novelMapper.updateById(update);

        evictDetailCache(novelId);
        // 状态改为下架后同步 ES：消费者回查发现不可见，会将文档从索引中移除
        sendSyncMessage(novelId, "UPSERT");
    }

    /**
     * 申请重新上架：进入待审核队列，由管理员复核通过后才恢复分发。
     *
     * <p>需经审核而非直接恢复：下架期间内容可能已被大幅修改，
     * 「先下架」本身也可能是规避审核的手段，直接恢复即绕过审核环节；审核同时构成一道冷却期。
     *
     * <p>期间 status 保持下架（不分发），但 auditStatus 置「重新上架待审」使详情仍可访问：
     * 已收藏的读者不应因作者提交申请而失去入口。
     */
    public void requestReshelve(Long novelId) {
        Novel novel = requireOwnerNovel(novelId);
        requireOfflinePassed(novel, "申请重新上架");
        String blocked = reshelveBlockedReason(novel);
        if (blocked != null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, blocked);
        }

        Novel update = new Novel();
        update.setId(novelId);
        update.setAuditStatus(AuditStatusEnum.RESHELVE_WAIT.getCode());
        update.setAuditResult("作者申请重新上架，等待审核");
        novelMapper.updateById(update);

        evictDetailCache(novelId);
        messageService.sendToAdmins(MessageTypeConstant.AUDIT_SUBMIT,
                "重新上架申请待审核",
                "用户「" + novel.getAuthor() + "」申请将《" + novel.getTitle() + "》重新上架，请审核",
                novelId);
    }

    /**
     * 作者删除作品（逻辑删除，不可逆）。
     *
     * <p>前置三道：已下架 → 下架满 {@link NovelConstant#DELETE_REQUIRE_OFFLINE_DAYS} 天 →
     * <b>没有读者为它花过币</b>。最后一条为关键条件：删除会使已购读者的书架条目变为「已删除」、
     * 已解锁章节不可读，等同于单方面收回已付费权益。作品存在问题可予下架，
     * 但不应连同他人的付费记录一并删除。
     */
    public void deleteByAuthor(Long novelId) {
        Novel novel = requireOwnerNovel(novelId);
        requireOfflinePassed(novel, "删除");
        String blocked = deleteBlockedReason(novel, hasPaidReader(novelId));
        if (blocked != null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, blocked);
        }

        doDelete(novel);
    }

    // ==================== 作者自助：连载 / 完结 ====================
    //
    // 完结是「创作侧」状态，与上架（分发侧）、审核（合规侧）相互独立。
    // 动因：读者需要「本书是否已写完」的信号，作者也需要一个正式的收尾动作。
    // 代价是完结后内容需锁定（否则可借完结状态反复改动已发布内容），
    // 因此提供「申请恢复」出口：存在恢复路径后才可实施内容锁定。

    /**
     * 作者把自己的作品标记为「已完结」。
     *
     * <p>标记后：章节增删改、简介 / 标签 / 分类 / 笔名的修改都会被拒绝，只放行书名与封面。
     * 记录 {@code finishTime} 作为冷静期起算点。
     */
    public void finishByAuthor(Long novelId) {
        Novel novel = requireOwnerNovel(novelId);
        if (SerialStatusEnum.isFinished(novel.getSerialStatus())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "作品已经是已完结状态");
        }
        // 仅「读者可见」的作品才可标记完结，否则等同于给未上架作品添加完结标签
        if (novel.getStatus() == null || novel.getStatus() != CommonStatusEnum.ENABLED.getCode()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "作品未上架，无需标记完结");
        }

        Novel update = new Novel();
        update.setId(novelId);
        update.setSerialStatus(SerialStatusEnum.FINISHED.getCode());
        update.setFinishTime(LocalDateTime.now());
        novelMapper.updateById(update);

        evictDetailCache(novelId);
        // 该状态会进入 VO 与索引，同步一次以使搜索/书库卡片更新
        sendSyncMessage(novelId, "UPSERT");
        log.info("作者标记作品完结: novelId={}, userId={}", novelId, novel.getUserId());
    }

    /**
     * 作者提交「解除完结」申请：进入管理员工单队列，批准后才恢复为连载中。
     *
     * <p>不由作者直接改回的原因：完结是读者可见的状态（书库与详情页均标注「已完结」），
     * 频繁变更会使该状态失去意义。将恢复入口交由管理员，使作者的完结动作具有约束力。
     * 此外提交受限：转完结后有冷静期，且同一作品不允许存在多个在途申请。
     */
    @Transactional(rollbackFor = Exception.class)
    public void requestResumeSerial(Long novelId, String reason) {
        Novel novel = requireOwnerNovel(novelId);
        if (!SerialStatusEnum.isFinished(novel.getSerialStatus())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "作品当前不是已完结状态，无需申请");
        }
        boolean pending = hasPendingAppeal(novelId);
        String blocked = resumeSerialBlockedReason(novel, pending);
        if (blocked != null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, blocked);
        }

        NovelAppeal appeal = new NovelAppeal();
        appeal.setNovelId(novelId);
        appeal.setUserId(novel.getUserId());
        appeal.setType(NovelAppeal.TYPE_RESUME_SERIAL);
        appeal.setReason(StringUtils.hasText(reason) ? reason.trim() : null);
        appeal.setStatus(NovelAppealStatusEnum.PENDING.getCode());
        novelAppealMapper.insert(appeal);

        messageService.sendToAdmins(MessageTypeConstant.AUDIT_SUBMIT,
                "解除完结申请待处理",
                "用户「" + novel.getAuthor() + "」申请将《" + novel.getTitle()
                        + "》恢复为连载中，请处理",
                novelId);
        log.info("作者申请解除完结: novelId={}, appealId={}", novelId, appeal.getId());
    }

    /**
     * 管理员批准解除完结：作品恢复为连载中，章节重新可编辑。
     *
     * <p>清空 {@code finishTime}：冷静期仅约束「刚被作者标记完结」的那一次，
     * 恢复后再次完结会重新写入时间并重新起算。
     */
    public void resumeSerialByAdmin(Long novelId) {
        Novel novel = novelMapper.selectById(novelId);
        if (novel == null) {
            throw new BusinessException(ErrorCode.NOVEL_NOT_FOUND);
        }
        // 注意：updateById 默认忽略 null 字段，置空 finish_time 必须用 UpdateWrapper 显式 set
        novelMapper.update(null, new LambdaUpdateWrapper<Novel>()
                .set(Novel::getSerialStatus, SerialStatusEnum.SERIALIZING.getCode())
                .set(Novel::getFinishTime, null)
                .eq(Novel::getId, novelId));

        evictDetailCache(novelId);
        sendSyncMessage(novelId, "UPSERT");
        log.info("管理员批准解除完结: novelId={}", novelId);
    }

    /**
     * 是否有在途（待处理）的申请工单。
     */
    private boolean hasPendingAppeal(Long novelId) {
        return novelAppealMapper.selectCount(new LambdaQueryWrapper<NovelAppeal>()
                .eq(NovelAppeal::getNovelId, novelId)
                .eq(NovelAppeal::getStatus, NovelAppealStatusEnum.PENDING.getCode())) > 0;
    }

    /** 解除完结的可用性：已经在途、或在冷静期内都不可申请。返回 null 表示可申请 */
    private static String resumeSerialBlockedReason(Novel novel, boolean pendingAppeal) {
        if (pendingAppeal) {
            return "已提交申请，等待管理员处理";
        }
        if (novel.getFinishTime() == null) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime canAt = novel.getFinishTime()
                .plusDays(NovelConstant.RESUME_SERIAL_COOLDOWN_DAYS);
        return now.isBefore(canAt)
                ? "完结未满 " + NovelConstant.RESUME_SERIAL_COOLDOWN_DAYS + " 天，"
                        + untilText(now, canAt, "天") + "后可申请恢复连载"
                : null;
    }

    /**
     * 已完结作品的内容锁：仅放行书名与封面，其余字段必须保持原值。
     *
     * <p>在后端拦截的原因：前端的置灰仅为提示，构造请求即可绕过。
     *
     * <p>采用「与当前生效值比对」而非「忽略这些字段」，以避免静默丢弃：
     * 作者实际修改了简介却提示保存成功，其影响大于直接拒绝。
     */
    private static void assertFinishUnlocked(Novel novel, NovelEditForm form) {
        if (!SerialStatusEnum.isFinished(novel.getSerialStatus())) {
            return;
        }
        boolean introChanged = !Objects.equals(nvl(form.getIntro()), nvl(novel.getIntro()));
        boolean tagsChanged = !Objects.equals(nvl(form.getTags()), nvl(novel.getTags()));
        boolean categoryChanged = !Objects.equals(form.getCategoryId(), novel.getCategoryId());
        boolean authorChanged = StringUtils.hasText(form.getAuthor())
                && !Objects.equals(form.getAuthor().trim(), nvl(novel.getAuthor()));
        if (introChanged || tagsChanged || categoryChanged || authorChanged) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "作品已完结，只能修改书名和封面；如需修改其他内容，请先申请恢复连载");
        }
    }

    private static String nvl(String s) {
        return s == null ? "" : s.trim();
    }

    /** 重新上架 / 删除的共同前置条件：作品须为「已下架且原本已过审」 */
    private void requireOfflinePassed(Novel novel, String action) {        if (novel.getStatus() == null || novel.getStatus() != CommonStatusEnum.DISABLED.getCode()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "请先下架作品，再执行" + action);
        }
        if (novel.getAuditStatus() == null || novel.getAuditStatus() != AuditStatusEnum.PASS.getCode()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "作品当前状态不支持" + action + "（未过审，或已有申请在处理中）");
        }
    }

    /** 逻辑删除 + 缓存与索引失效 + 异步清理封面对象 */
    private void doDelete(Novel novel) {
        novelMapper.deleteById(novel.getId());
        evictDetailCache(novel.getId());
        sendSyncMessage(novel.getId(), "DELETE");
        deleteCoverObject(novel.getCoverUrl());
    }

    /**
     * 异步删除封面对象：先删 DB（真源）再发 MQ 删 OSS（派生资源），最坏情况留下孤儿对象，不会出现破图。
     */
    private void deleteCoverObject(String coverUrl) {
        if (!StringUtils.hasText(coverUrl)) {
            return;
        }
        OssDeleteMessage msg = new OssDeleteMessage();
        msg.setUrl(coverUrl);
        mqSender.sendAfterCommit(MqConstant.OSS_EXCHANGE, MqConstant.OSS_DELETE_ROUTING_KEY, msg);
    }

    private boolean hasPaidReader(Long novelId) {
        return novelPurchaseProbe.hasPaidReader(novelId);
    }

    /** 批量判断哪些作品有付费读者（一次 IN 查询，避免逐本回查订单表） */
    private Set<Long> paidNovelIds(List<Long> novelIds) {
        if (novelIds.isEmpty()) {
            return Set.of();
        }
        return novelPurchaseProbe.paidNovelIds(novelIds);
    }

    /**
     * 将三个自助操作的门槛结果回填到 VO（供列表将按钮置灰并说明原因）。
     *
     * <p>仅为「按钮可能出现的情形」计算原因：下架按钮仅在上架时出现；
     * 重新上架与删除仅在下架且无在途申请时出现。前端 {@code :disabled + tooltip} 直接使用这两个字段。
     */
    private void fillAuthorActions(NovelVO vo, Novel novel, boolean hasPaidReader, boolean appealPending) {
        vo.setSerialStatusText(SerialStatusEnum.textOf(novel.getSerialStatus()));
        if (SerialStatusEnum.isFinished(novel.getSerialStatus())) {
            vo.setAppealPending(appealPending);
            vo.setResumeSerialBlockedReason(resumeSerialBlockedReason(novel, appealPending));
        }
        boolean online = novel.getStatus() != null && novel.getStatus() == CommonStatusEnum.ENABLED.getCode();
        boolean offlinePassed = !online
                && novel.getAuditStatus() != null && novel.getAuditStatus() == AuditStatusEnum.PASS.getCode();
        if (online) {
            vo.setOfflineBlockedReason(unshelveBlockedReason(novel));
        }
        if (offlinePassed) {
            vo.setReshelveBlockedReason(reshelveBlockedReason(novel));
            vo.setDeleteBlockedReason(deleteBlockedReason(novel, hasPaidReader));
        }
    }

    /** 新书保护期：发布后 N 天内不可自助下架。返回 null 表示可下架 */
    private static String unshelveBlockedReason(Novel novel) {
        if (novel.getCreateTime() == null) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime canAt = novel.getCreateTime().plusDays(NovelConstant.UNSHELVE_PROTECT_DAYS);
        return now.isBefore(canAt)
                ? "新书保护期内，" + untilText(now, canAt, "天") + "后可下架"
                : null;
    }

    /** 重新上架冷却：下架后 M 小时内不可申请。返回 null 表示可申请 */
    private static String reshelveBlockedReason(Novel novel) {
        if (novel.getOfflineTime() == null) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime canAt = novel.getOfflineTime().plusHours(NovelConstant.RESHELVE_COOLDOWN_HOURS);
        return now.isBefore(canAt)
                ? "下架未满 " + NovelConstant.RESHELVE_COOLDOWN_HOURS + " 小时，"
                        + untilText(now, canAt, "小时") + "后可申请重新上架"
                : null;
    }

    /** 删除门槛：已下架满 P 天、且无付费读者。返回 null 表示可删除 */
    private static String deleteBlockedReason(Novel novel, boolean hasPaidReader) {
        if (novel.getOfflineTime() == null) {
            return "请先下架作品";
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime canAt = novel.getOfflineTime().plusDays(NovelConstant.DELETE_REQUIRE_OFFLINE_DAYS);
        if (now.isBefore(canAt)) {
            return "下架未满 " + NovelConstant.DELETE_REQUIRE_OFFLINE_DAYS + " 天，"
                    + untilText(now, canAt, "天") + "后可删除";
        }
        if (hasPaidReader) {
            return "已有读者付费解锁，不能删除（可保持下架）";
        }
        return null;
    }

    /** 「还需 3 天」这类剩余时间文案：向上取整，避免出现「还需 0 天」的无效提示 */
    private static String untilText(LocalDateTime from, LocalDateTime to, String unit) {
        long minutes = Math.max(Duration.between(from, to).toMinutes(), 1);
        long value = "天".equals(unit)
                ? (long) Math.ceil(minutes / (60.0 * 24))
                : (long) Math.ceil(minutes / 60.0);
        return "还需 " + Math.max(value, 1) + " " + unit;
    }

    /**
     * 作者编辑页数据：当前生效值 + 待审影子值（仅作品所有者可取）。
     */
    public NovelEditVO editDetail(Long novelId) {
        Novel novel = requireOwnerNovel(novelId);
        NovelEditVO vo = BeanUtil.copyProperties(novel, NovelEditVO.class);
        vo.setCategoryName(categoryService.getNameMap().get(novel.getCategoryId()));
        return vo;
    }

    /**
     * 作者提交作品信息变更。
     *
     * <p>不修改正式字段，仅写入影子字段并将 auditStatus 置为「变更待审(3)」：
     * 前台继续显示旧值，管理端审核通过后由 {@code AdminService.auditPass} 整体覆盖，
     * 拒绝则丢弃影子值并回落为「审核通过」。该设计在开放自助编辑的同时，
     * 消除「先以合规内容过审、再改为违规内容」的绕过路径。
     *
     * <p>若作品当前为待审(0)或已拒绝(2)（尚未上架、无人可见），则直接覆盖正式字段并
     * 回到待审，无需经过影子字段，此时不存在「读者已看到旧版」的问题。
     */
    @Transactional(rollbackFor = Exception.class)
    public NovelEditVO submitEdit(Long novelId, NovelEditForm form) {
        Novel novel = requireOwnerNovel(novelId);

        // 处于「重新上架待审」时不允许再次提交变更：此时作品正在复核，若进入下面的
        // 「未过审则直接改正式字段」分支，状态会被覆盖为首次待审(0)，而 status 仍为下架，
        // 读者可见性随即失效，已收藏的读者将无法打开详情页。
        if (novel.getAuditStatus() != null
                && novel.getAuditStatus() == AuditStatusEnum.RESHELVE_WAIT.getCode()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "重新上架申请审核中，请等待结果后再修改作品信息");
        }

        Map<Long, String> categoryNames = categoryService.getNameMap();
        if (!categoryNames.containsKey(form.getCategoryId())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "分类不存在，请重新选择");
        }
        // 已完结作品内容锁定：仅放行书名与封面
        assertFinishUnlocked(novel, form);
        boolean finished = SerialStatusEnum.isFinished(novel.getSerialStatus());
        // 笔名留空则沿用当前值，避免清空后前台作者名为空
        String author = StringUtils.hasText(form.getAuthor())
                ? form.getAuthor().trim() : novel.getAuthor();

        Novel update = new Novel();
        update.setId(novelId);

        if (novel.getAuditStatus() != null
                && novel.getAuditStatus() == AuditStatusEnum.PASS.getCode()) {
            // 已通过（含变更待审后再次修改）：全部写入影子字段，等待审核。
            // 已完结时仅写书名/封面两项，其余影子值保持原样，否则审核通过时会连同
            // 简介、标签、分类一并覆盖，内容锁将失效。
            update.setPendingTitle(form.getTitle());
            update.setPendingCoverUrl(form.getCoverUrl());
            if (!finished) {
                update.setPendingIntro(form.getIntro());
                update.setPendingTags(form.getTags());
                update.setPendingCategoryId(form.getCategoryId());
                update.setPendingAuthor(author);
            }
            update.setAuditStatus(AuditStatusEnum.MODIFY_WAIT.getCode());
            update.setAuditResult("修改审核中");
        } else {
            // 未过审 / 已拒绝：直接改正式字段并回到待审
            update.setTitle(form.getTitle());
            update.setCoverUrl(form.getCoverUrl());
            if (!finished) {
                update.setIntro(form.getIntro());
                update.setTags(form.getTags());
                update.setCategoryId(form.getCategoryId());
                update.setAuthor(author);
            }
            update.setAuditStatus(AuditStatusEnum.WAIT.getCode());
            update.setAuditResult("待审核");
        }
        novelMapper.updateById(update);
        evictDetailCache(novelId);
        sendSyncMessage(novelId, "UPSERT");

        // 作品信息变更一并走 AI 预审 → 人工终审链路（与首次发布同一条队列）
        AiAuditMessage message = new AiAuditMessage();
        message.setNovelId(novelId);
        mqSender.sendAfterCommit(MqConstant.AI_EXCHANGE, MqConstant.AI_AUDIT_ROUTING_KEY, message);

        log.info("作者提交作品信息变更: novelId={}, userId={}, auditStatus={}",
                novelId, novel.getUserId(), update.getAuditStatus());
        return editDetail(novelId);
    }

    public NovelVO save(NovelForm form) {
        Novel novel = BeanUtil.copyProperties(form, Novel.class);
        String oldCoverUrl = null;
        if (form.getId() == null) {
            novel.setReadCount(0L);
            novel.setLikeCount(0L);
            novel.setAuditStatus(AuditStatusEnum.PASS.getCode());
            novelMapper.insert(novel);
        } else {
            // 编辑：先取出旧封面地址，换封面时异步删旧对象（避免孤儿）
            Novel existing = novelMapper.selectById(form.getId());
            oldCoverUrl = existing == null ? null : existing.getCoverUrl();
            novelMapper.updateById(novel);
            // 先更 DB 后删缓存（Cache-Aside），避免编辑后详情仍返回旧数据
            evictDetailCache(novel.getId());
        }
        // 异步同步到 ES（MQ 解耦，最终一致）
        sendSyncMessage(novel.getId(), "UPSERT");
        // 换封面：新封面与旧不同且旧非空时，异步删旧对象
        if (StringUtils.hasText(oldCoverUrl) && !oldCoverUrl.equals(novel.getCoverUrl())) {
            OssDeleteMessage msg = new OssDeleteMessage();
            msg.setUrl(oldCoverUrl);
            mqSender.sendAfterCommit(MqConstant.OSS_EXCHANGE, MqConstant.OSS_DELETE_ROUTING_KEY, msg);
        }
        return detail(novel.getId());
    }

    /** 管理员删除（不受作者的冷静期约束，以保证违规内容可立即处置） */
    public void delete(Long id) {
        Novel novel = novelMapper.selectById(id);
        novelMapper.deleteById(id);
        // 先更 DB 后删缓存：删除后详情缓存立即失效，下次访问 404（不会返回已删数据）
        evictDetailCache(id);
        // 逻辑删除后同步移除 ES 文档
        sendSyncMessage(id, "DELETE");
        deleteCoverObject(novel == null ? null : novel.getCoverUrl());
    }

    public void changeStatus(Long id, Integer status) {
        if (!CommonStatusEnum.isValid(status)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "状态值非法，仅允许 0(下架)/1(上架)");
        }
        Novel novel = new Novel();
        novel.setId(id);
        novel.setStatus(status);
        novelMapper.updateById(novel);
        // 先更 DB 后删缓存：上下架后详情缓存立即失效，状态实时可见
        evictDetailCache(id);
        // 上下架同步 ES（下架 status=0，搜索时被 term 过滤）
        sendSyncMessage(id, "UPSERT");
    }

    public LikeVO like(Long id) {
        Long userId = LoginUserUtil.getUserId();
        String key = "novel:like:" + userId + ":" + id;
        // toggle 语义：setIfAbsent 原子判定「此前是否已赞」；true=此前未赞，本次点赞；false=此前已赞，本次取消
        boolean liked;
        if (Boolean.TRUE.equals(stringRedisTemplate.opsForValue().setIfAbsent(key, "1"))) {
            novelMapper.incrLikeCount(id);
            liked = true;
        } else {
            stringRedisTemplate.delete(key);
            novelMapper.decrLikeCount(id);
            liked = false;
        }
        LikeVO vo = new LikeVO();
        vo.setLiked(liked);
        Novel row = novelMapper.selectCounts(id);
        vo.setLikeCount(row == null ? 0L : row.getLikeCount());
        return vo;
    }

    @Override
    public Map<Long, Long> countByCategory() {
        // 字符串列投影 + 分组：MP 照常注入 is_deleted 条件。
        // 别名用无下划线的小写，避免任何列名转换带来的 key 不确定性。
        QueryWrapper<Novel> wrapper = new QueryWrapper<Novel>()
                .select("category_id AS cid", "COUNT(*) AS cnt")
                .isNotNull("category_id")
                .groupBy("category_id");
        List<Map<String, Object>> rows = novelMapper.selectMaps(wrapper);
        Map<Long, Long> result = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Object cid = row.get("cid");
            Object cnt = row.get("cnt");
            if (cid != null && cnt != null) {
                result.put(((Number) cid).longValue(), ((Number) cnt).longValue());
            }
        }
        return result;
    }

    /**
     * 发送搜索同步消息（DB 变更 -> ES）
     *
     * @param novelId   小说 ID
     * @param operation UPSERT 覆盖写入 / DELETE 移除
     */
    private void sendSyncMessage(Long novelId, String operation) {
        SearchSyncMessage msg = new SearchSyncMessage();
        msg.setNovelId(novelId);
        msg.setOperation(operation);
        // publish() 在事务内调用走 afterCommit 延迟投递；save/delete/changeStatus 无事务立即投递
        mqSender.sendAfterCommit(MqConstant.SEARCH_EXCHANGE, MqConstant.SEARCH_SYNC_ROUTING_KEY, msg);
    }

    /**
     * 请求重建某一章的向量块。
     *
     * <p>此处自行投递消息而不调用 {@code ChapterService.requestChunkReindex} 的原因：
     * {@code ChapterServiceImpl} 已依赖 {@code NovelService}（失效详情缓存、重算聚合），
     * 反向注入构成 bean 环，在构造器注入下会导致启动失败。
     *
     * <p>代价是消息组装在此处与 {@code ChapterServiceImpl} 中重复了一次。
     * 修改 {@code ChapterChunkSyncMessage} 的字段时，需同步修改这两处。
     */
    private void sendChunkSyncMessage(Long novelId, Long chapterId) {
        ChapterChunkSyncMessage msg = new ChapterChunkSyncMessage();
        msg.setNovelId(novelId);
        msg.setChapterId(chapterId);
        mqSender.sendAfterCommit(MqConstant.SEARCH_EXCHANGE,
                MqConstant.SEARCH_CHUNK_ROUTING_KEY, msg);
    }
}
