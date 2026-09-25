package com.ainovel.module.novel.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.ainovel.common.code.ErrorCode;
import com.ainovel.common.constant.MqConstant;
import com.ainovel.common.constant.NovelConstant;
import com.ainovel.common.domain.PageParam;
import com.ainovel.common.domain.PageResult;
import com.ainovel.common.enums.ChapterAuditStatusEnum;
import com.ainovel.common.enums.SerialStatusEnum;
import com.ainovel.common.exception.BusinessException;
import com.ainovel.common.mq.MqSender;
import com.ainovel.common.util.CacheHelper;
import com.ainovel.common.util.LoginUserUtil;
import com.ainovel.common.message.AiAuditMessage;
import com.ainovel.common.message.ChapterChunkSyncMessage;
import com.ainovel.module.novel.dao.ChapterMapper;
import com.ainovel.module.novel.dao.NovelMapper;
import com.ainovel.module.novel.domain.entity.Chapter;
import com.ainovel.module.novel.domain.entity.Novel;
import com.ainovel.module.novel.domain.NovelVisibility;
import com.ainovel.module.novel.domain.form.ChapterSaveForm;
import com.ainovel.module.novel.domain.vo.ChapterContentVO;
import com.ainovel.module.novel.domain.vo.ChapterVO;
import com.ainovel.common.message.SearchSyncMessage;
import com.ainovel.module.novel.spi.ChapterAccessChecker;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import com.ainovel.module.novel.service.NovelService;
import com.ainovel.module.novel.service.ChapterService;

/**
 * 章节服务：目录列表（不含正文）+ 正文读取（解锁校验）+ 章节增删改（连载/章节级审核）
 *
 * <p>继承 {@link ServiceImpl} 以复用 saveBatch 批量入库（TXT 导入器使用）。
 *
 * <p>章节级审核状态机：读者可见 = 审核通过(1) 或 变更待审(3)；新增章待审(0)、
 * 已发布章修改走影子正文（pending_content 暂存新版，审核通过原子替换、拒绝回退旧版）。
 */
@Service
@RequiredArgsConstructor
public class ChapterServiceImpl extends ServiceImpl<ChapterMapper, Chapter> implements ChapterService {

    /** 分页目录缓存 key 前缀：按「书:页:条数」缓存，避免大书一次性拉全量 */
    private static final String CHAPTER_PAGE_KEY_PREFIX = "novel:chapter:page:";
    private static final long CHAPTER_LIST_TTL_MINUTES = 10;

    /**
     * 章节正文缓存 key 前缀：按「书:章」组织，便于章节变更时按书批量失效。
     *
     * <p>正文是表里最大的字段（单库 3000 万字），而阅读器逐章翻页会把同一章反复读出，
     * 目录这类小字段查询本就走索引，真正值得缓存的是正文。
     */
    private static final String CHAPTER_CONTENT_KEY_PREFIX = "novel:chapter:content:";

    /** 正文缓存 TTL：正文发布后基本不变，且有写时失效兜底，可设置得比目录缓存更长 */
    private static final long CHAPTER_CONTENT_TTL_MINUTES = 30;

    /** 章序号并发重试上限：唯一索引 (novel_id, chapter_no) 兜底，冲突后重读 max 重试 */
    private static final int MAX_CHAPTER_NO_RETRY = 3;

    /** 分页目录缓存反序列化目标类型 */
    private static final TypeReference<PageResult<ChapterVO>> CHAPTER_PAGE_TYPE = new TypeReference<>() {
    };

    /** 章节正文缓存反序列化目标类型 */
    private static final TypeReference<ChapterContentVO> CHAPTER_CONTENT_TYPE = new TypeReference<>() {
    };

    private final ChapterAccessChecker chapterAccessChecker;

    private final CacheHelper cacheHelper;

    private final NovelMapper novelMapper;

    private final MqSender mqSender;

    private final NovelService novelService;

    /**
     * 分页目录（读者视角）：只投影元信息列，按「书:页:条数」缓存。
     * 大书（上千章）不再一次性拉全量，主页目录/阅读器抽屉按页拉取。
     *
     * <p>作品可见性判定在缓存之前：缓存只按「书:页:条数」存值，不含调用方身份，
     * 若把判定放进回源函数，一次作者视角的访问会把结果写进公共缓存，之后匿名请求直接命中。
     */
    public PageResult<ChapterVO> pageVOByNovel(Long novelId, long pageNum, long pageSize) {
        requireReadableNovel(novelId);
        return cacheHelper.get(CHAPTER_PAGE_KEY_PREFIX + novelId + ":" + pageNum + ":" + pageSize,
                CHAPTER_PAGE_TYPE, () -> queryChapterPage(novelId, pageNum, pageSize),
                CHAPTER_LIST_TTL_MINUTES, TimeUnit.MINUTES);
    }

    /**
     * 所属作品可读性门：章节元数据、章节目录、章节正文三条读者侧读路径共用。
     *
     * <p>判据与作品详情一致（{@link NovelVisibility#isDetailReadable}）：未过审仅作者与管理员可读，
     * 查不到（含逻辑删除）对所有人不可读。章节自身状态另有各自的过滤（目录与元数据按章节审核状态、
     * 正文按章节审核状态与解锁情况），两者是「且」的关系。
     *
     * <p>抛 404 而非 403，理由同作品详情：不暴露「这个 id 存在一本未过审的书」。
     *
     * @return 所属作品实体，供调用方复用，避免二次查询
     */
    private Novel requireReadableNovel(Long novelId) {
        Novel novel = novelMapper.selectById(novelId);
        if (!NovelVisibility.isDetailReadable(novel, LoginUserUtil.getUserIdOrNull(), LoginUserUtil.isAdmin())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "章节不存在");
        }
        return novel;
    }

    /** 当前用户是否为该作品作者本人或管理员（未过审章节的放行条件） */
    private static boolean isOwnerOrAdmin(Novel novel) {
        if (LoginUserUtil.isAdmin()) {
            return true;
        }
        Long userId = LoginUserUtil.getUserIdOrNull();
        return userId != null && userId.equals(novel.getUserId());
    }

    private PageResult<ChapterVO> queryChapterPage(Long novelId, long pageNum, long pageSize) {
        Page<Chapter> result = page(new Page<>(PageParam.clampPage(pageNum), PageParam.clampSize(pageSize)),
                new QueryWrapper<Chapter>()
                        .select("id", "chapter_no", "title", "word_count", "unlock_coin")
                        .eq("novel_id", novelId)
                        .in("audit_status", ChapterAuditStatusEnum.PASS.getCode(), ChapterAuditStatusEnum.MODIFY_WAIT.getCode())
                        .orderByAsc("chapter_no"));
        List<ChapterVO> voList = result.getRecords().stream()
                .map(ChapterServiceImpl::toChapterVO).toList();
        return PageResult.of(result.getTotal(), result.getCurrent(), result.getSize(), voList);
    }

    /**
     * 作者/管理员视角目录：返回全部章节（含审核状态与拒绝原因），不过滤、不缓存。
     * 供章节管理页展示，需实时反映审核状态变化。
     *
     * <p>**必须分页**：单本作品上千章较为常见，章节管理页一次拉取全量既慢且结果过长。
     */
    public PageResult<ChapterVO> pageAuthorVOByNovel(Long novelId, long pageNum, long pageSize) {
        requireOwnerNovel(novelId);
        return pageChapterMetaByNovel(novelId, pageNum, pageSize);
    }

    /**
     * 与 {@link #pageAuthorVOByNovel} 为同一查询，但**不做归属校验**。
     *
     * <p>需单独提供该方法的原因：AI 审查工具运行于 MQ 消费线程，无登录上下文，
     * 复用作者视角方法会使**每次工具调用都抛出「无权限操作该作品」**；
     * 工具异常会被框架转换为错误提示交给模型，模型仍照常给出结论，
     * 结果是一份「未核对前文」的自查报告（曾出现表述为「已核对，未发现前后矛盾」的情况）。
     * 整本审查的权限在任务创建时已确认，工具仅需按调用方传入的 novelId 读取。
     */
    public PageResult<ChapterVO> pageChapterMetaByNovel(Long novelId, long pageNum, long pageSize) {
        long safePageNum = Math.max(1, pageNum);
        long safePageSize = Math.min(Math.max(1, pageSize), PageParam.MAX_PAGE_SIZE);
        Page<Chapter> result = page(new Page<>(safePageNum, safePageSize),
                new QueryWrapper<Chapter>()
                        .select("id", "chapter_no", "title", "word_count", "unlock_coin", "audit_status", "audit_result")
                        .eq("novel_id", novelId)
                        .orderByAsc("chapter_no"));
        List<ChapterVO> voList = result.getRecords().stream()
                .map(ChapterServiceImpl::toChapterVO).toList();
        return PageResult.of(result.getTotal(), safePageNum, safePageSize, voList);
    }

    /**
     * 单章元数据（标题/字数/价格），供阅读器锁卡片与当前章展示。
     * 正文接口 {@link #getContent} 对付费章会拒绝返回，故锁章需单独取元数据。
     *
     * <p>返回对象含 {@code auditStatus} 与 {@code auditResult}（驳回理由），因此必须同时判两层：
     * 所属作品可见性，以及章节自身的审核状态。缺少后一层时，未过审章节的审核结论可按 id 递增枚举。
     * 未过审章节对作者本人与管理员放行，与 {@link #getContent} 一致，否则作者无法预览自己的待审章。
     */
    public ChapterVO getChapterVO(Long id) {
        Chapter chapter = getById(id);
        if (chapter == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "章节不存在");
        }
        Novel novel = requireReadableNovel(chapter.getNovelId());
        if (!ChapterAuditStatusEnum.isVisible(chapter.getAuditStatus()) && !isOwnerOrAdmin(novel)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "章节不存在");
        }
        return toChapterVO(chapter);
    }

    /**
     * 单章元数据，不做可见性判定，供内部调用（接口上有与 {@link #getChapterVO} 的差异说明）。
     *
     * <p>写路径也走本方法：作者正在编辑的章可能尚未过审，若复用读者侧方法，
     * 未公开作品下的章节会被判为不存在，而写操作此前已成功，表现为「改完却报错」。
     */
    public ChapterVO getChapterMetaById(Long id) {
        Chapter chapter = getById(id);
        if (chapter == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "章节不存在");
        }
        return toChapterVO(chapter);
    }

    /** 章节实体到 VO 的映射，不做任何可见性判定（判定由各调用入口各自承担） */
    private static ChapterVO toChapterVO(Chapter chapter) {
        return BeanUtil.copyProperties(chapter, ChapterVO.class);
    }

    /**
     * 全书章节的轻量目录：仅投影元信息列、按章号升序、不分页、不校验权限。
     *
     * <p>用于 AI 全文审查的任务派发（见接口上的说明）：
     * 此处刻意**不使用分页插件**，因其会静默截断 size，而调用方依赖「一次取全」生成
     * 每章一条的审查消息，截断的后果是「后半本书未进入任务，界面上也无任何提示」。
     * 以一次 selectList 取回 id / 章号 / 标题 / 字数，量级为几百 KB，较按页翻取更可靠。
     */
    public List<ChapterVO> listChapterBriefs(Long novelId) {
        List<Chapter> list = list(new QueryWrapper<Chapter>()
                .select("id", "chapter_no", "title", "word_count")
                .eq("novel_id", novelId)
                .orderByAsc("chapter_no"));
        return list.stream().map(ChapterServiceImpl::toChapterVO).toList();
    }

    /**
     * 失效分页目录缓存：章节变更（新增/删除/审核状态变化/覆盖导入重建）后立即清除。
     *
     * <p>目录缓存**仅分页一种**（原有一份「整本一次性拉全量」的目录缓存，
     * 随前端不再调用而移除），因此此处只需按模式清除分页缓存。
     * 使用 SCAN 而非 keys，见 {@link CacheHelper#evictByPattern}。
     */
    public void evictListCache(Long novelId) {
        // 排到事务提交之后再清，避免并发读把旧目录回填进缓存（Cache-Aside 竞态）
        cacheHelper.evictByPatternAfterCommit(CHAPTER_PAGE_KEY_PREFIX + novelId + ":*");
    }

    /**
     * 读取章节正文：免费章直接放行；付费章需「本章已解锁」或「整本已解锁」，否则拒绝。
     * 待审/拒绝章节仅作者本人/管理员可见（变更待审章读者仍见旧版 content）。
     *
     * <p>正文走缓存，但**权限判断在缓存之外**：
     * <ol>
     *   <li>先以「元数据投影」查询一行小字段（正文为大字段，判断可见性时无需读出）；</li>
     *   <li>权限通过后再取正文，且正文按「章节」维度缓存：同一章正文对所有用户相同，
     *       可见性属权限问题而非内容差异，因此缓存中只可能是「已允许访问的正文」。</li>
     * </ol>
     * 若将带权限判断的结果整体缓存则会产生串号：付费章的判断依赖用户，缓存命中后会返回其他用户的结果。
     */
    public ChapterContentVO getContent(Long chapterId) {
        // 取「可为空」的登录态：免费章允许游客试读（新用户不注册就能看第一章），
        // 付费章与待审章仍然要登录 / 要作者身份，见下面两个分支
        Long userId = LoginUserUtil.getUserIdOrNull();
        Chapter meta = getOne(new QueryWrapper<Chapter>()
                .select("id", "novel_id", "chapter_no", "title", "unlock_coin", "audit_status")
                .eq("id", chapterId));
        if (meta == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "章节不存在");
        }
        // 所属作品不可读（逻辑删除 / 未过审且非作者）时整本章节都不可读，返回 404。
        // 该判定与目录、元数据接口共用同一实现，避免任一路径成为绕过入口。
        Novel novel = requireReadableNovel(meta.getNovelId());
        // 待审/拒绝章（读者不可见）：仅作者本人/管理员放行，其余拒绝
        if (!ChapterAuditStatusEnum.isVisible(meta.getAuditStatus()) && !isOwnerOrAdmin(novel)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "该章节待审核");
        }
        boolean free = meta.getUnlockCoin() == null || meta.getUnlockCoin() == 0;
        // 付费章：作者本人 / 管理员免付费直读，或本章/整本已解锁
        if (!free) {
            // 游客不存在「已解锁」状态，直接要求登录；401 会使前端跳转登录页，符合预期
            if (userId == null) {
                throw new BusinessException(ErrorCode.UNAUTHORIZED, "登录后可阅读付费章节");
            }
            if (!chapterAccessChecker.canRead(userId, meta.getNovelId(), chapterId)) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "请先解锁本章");
            }
        }
        ChapterContentVO vo = cacheHelper.get(
                CHAPTER_CONTENT_KEY_PREFIX + meta.getNovelId() + ":" + chapterId, CHAPTER_CONTENT_TYPE,
                () -> loadContentVo(chapterId), CHAPTER_CONTENT_TTL_MINUTES, TimeUnit.MINUTES);
        if (vo == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "章节不存在");
        }
        return vo;
    }

    /** 回源组装正文（读多写少，供缓存存储） */
    private ChapterContentVO loadContentVo(Long chapterId) {
        Chapter chapter = getById(chapterId);
        if (chapter == null) {
            return null;
        }
        ChapterContentVO vo = BeanUtil.copyProperties(chapter, ChapterContentVO.class);
        fillNeighbors(vo, chapter);
        return vo;
    }

    /**
     * 失效某本书的章节正文缓存：章节内容/审核状态变更后调用。
     *
     * <p>正文缓存不带用户，所以只能靠「写时失效」保证不返回旧正文；
     * key 里带上 novelId 正是为了让这里能按书一次性清掉。
     */
    public void evictContentCache(Long novelId) {
        cacheHelper.evictByPattern(CHAPTER_CONTENT_KEY_PREFIX + novelId + ":*");
    }

    /**
     * 填充上/下一章 ID：按 chapter_no 相邻查询（LIMIT 1，只投影 id），
     * 阅读器据此做上下章导航，无需为导航拉取全量目录。
     */
    private void fillNeighbors(ChapterContentVO vo, Chapter chapter) {
        Chapter prev = getOne(new QueryWrapper<Chapter>()
                .select("id").eq("novel_id", chapter.getNovelId())
                .in("audit_status", ChapterAuditStatusEnum.PASS.getCode(), ChapterAuditStatusEnum.MODIFY_WAIT.getCode())
                .lt("chapter_no", chapter.getChapterNo())
                .orderByDesc("chapter_no").last("LIMIT 1"));
        Chapter next = getOne(new QueryWrapper<Chapter>()
                .select("id").eq("novel_id", chapter.getNovelId())
                .in("audit_status", ChapterAuditStatusEnum.PASS.getCode(), ChapterAuditStatusEnum.MODIFY_WAIT.getCode())
                .gt("chapter_no", chapter.getChapterNo())
                .orderByAsc("chapter_no").last("LIMIT 1"));
        vo.setPrevChapterId(prev == null ? null : prev.getId());
        vo.setNextChapterId(next == null ? null : next.getId());
    }

    // ==================== 章节增删改（连载 + 章节级审核） ====================

    /**
     * 新增章节（连载）：章序号 = 当前最大 + 1，audit_status=待审，读者不可见，发 AI 预审。
     *
     * <p>并发安全：selectMaxChapterNo + 1 非原子，并发新增同一本书可能读到相同 max、
     * 撞唯一索引 (novel_id, chapter_no)。唯一索引兜底 + 有限乐观重试（重读 max 再插）。
     * MySQL InnoDB 唯一键冲突是语句级错误，不会毒化事务，可在同一事务内重试。
     */
    @Transactional(rollbackFor = Exception.class)
    public ChapterVO addChapter(Long novelId, ChapterSaveForm form) {
        requireChapterEditable(requireOwnerNovel(novelId));
        String content = form.getContent();

        Chapter chapter = null;
        for (int attempt = 0; attempt < MAX_CHAPTER_NO_RETRY; attempt++) {
            int nextNo = baseMapper.selectMaxChapterNo(novelId) + 1;
            Chapter candidate = buildChapter(novelId, nextNo, content, form);
            try {
                save(candidate);
                chapter = candidate;
                break;
            } catch (DataIntegrityViolationException e) {
                if (attempt == MAX_CHAPTER_NO_RETRY - 1) {
                    throw e;
                }
                // 撞唯一索引：并发者已占用该 chapter_no，重读 max 重试
            }
        }

        // 新章待审不可见，聚合不变；但目录/详情缓存保守失效（章节列表接口可能缓存了旧数据）
        afterChapterChange(novelId, chapter.getId(), false);
        sendChapterAudit(chapter.getId());
        return BeanUtil.copyProperties(chapter, ChapterVO.class);
    }

    /**
     * 组装章节实体：首章（chapter_no=1）强制免费试读，其余按表单价格（默认 0）。
     * 抽取成独立方法供 {@link #addChapter} 的并发重试循环复用。
     */
    private Chapter buildChapter(Long novelId, int nextNo, String content, ChapterSaveForm form) {
        Chapter chapter = new Chapter();
        chapter.setNovelId(novelId);
        chapter.setChapterNo(nextNo);
        chapter.setTitle(StringUtils.hasText(form.getTitle()) ? form.getTitle() : ("第" + nextNo + "章"));
        chapter.setContent(content);
        chapter.setWordCount(content.length());
        chapter.setUnlockCoin(nextNo == 1 ? 0 : (form.getUnlockCoin() == null ? 0 : form.getUnlockCoin()));
        chapter.setSort(nextNo);
        chapter.setAuditStatus(ChapterAuditStatusEnum.WAIT.getCode());
        chapter.setAuditResult("待审核");
        return chapter;
    }

    /**
     * 修改章节（影子正文状态机）：
     * <ul>
     *   <li>已发布章(1)：正文存 pending_content，audit_status→变更待审(3)，读者继续见旧版；</li>
     *   <li>待审(0)/拒绝(2)/变更待审(3)：直接改 content，重新待审(0)。</li>
     * </ul>
     * 标题与价格直接生效；首章强制免费。
     */
    @Transactional(rollbackFor = Exception.class)
    public ChapterVO updateChapter(Long id, ChapterSaveForm form) {
        Chapter chapter = getById(id);
        if (chapter == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "章节不存在");
        }
        requireChapterEditable(requireOwnerNovel(chapter.getNovelId()));

        String newContent = form.getContent();
        int coin = chapter.getChapterNo() != null && chapter.getChapterNo() == 1
                ? 0 : (form.getUnlockCoin() == null ? 0 : form.getUnlockCoin());

        Chapter update = new Chapter();
        update.setId(id);
        update.setTitle(StringUtils.hasText(form.getTitle()) ? form.getTitle() : chapter.getTitle());
        update.setUnlockCoin(coin);

        if (chapter.getAuditStatus() != null && chapter.getAuditStatus() == ChapterAuditStatusEnum.PASS.getCode()) {
            // 已发布章：影子正文，旧版 content 不动，待审正文进 pending_content
            update.setPendingContent(newContent);
            update.setAuditStatus(ChapterAuditStatusEnum.MODIFY_WAIT.getCode());
            update.setAuditResult("修改审核中");
        } else {
            // 未发布章（待审/拒绝）或已在变更待审：直接覆盖正文，重新待审
            update.setContent(newContent);
            update.setWordCount(newContent.length());
            update.setPendingContent(null);
            update.setAuditStatus(ChapterAuditStatusEnum.WAIT.getCode());
            update.setAuditResult("待审核");
        }
        updateById(update);

        // 重算聚合：标题与价格直接生效（不走影子字段），若本章可见则整本包价随之变化；
        // 不重算会出现「单章已调价、整本包价仍为旧值」的折扣漏洞。
        afterChapterChange(chapter.getNovelId(), chapter.getId(), true);
        sendChapterAudit(id);
        // 写路径取无判定变体：本章此刻大概率是待审状态，走读者侧方法会自我拒绝
        return getChapterMetaById(id);
    }

    /**
     * 删除章节：读者可见章节（已通过 1 / 变更待审 3）拒绝删除（避免已付费读者退币纠纷），
     * 仅待审(0)/拒绝(2)章可逻辑删除。
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteChapter(Long id) {
        Chapter chapter = getById(id);
        if (chapter == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "章节不存在");
        }
        requireChapterEditable(requireOwnerNovel(chapter.getNovelId()));
        if (ChapterAuditStatusEnum.isVisible(chapter.getAuditStatus())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "已发布章节不可删除，仅可修改重新送审");
        }
        removeById(id);
        afterChapterChange(chapter.getNovelId(), chapter.getId(), true);
    }

    /**
     * 重算小说聚合字段（总章数/总字数/整本打包价），只统计读者可见章节（audit_status IN 1,3）。
     * 新增待审章不计数，审核通过/删除变更待审章后触发。
     */
    public void recountNovel(Long novelId) {
        Map<String, Object> stats = baseMapper.selectVisibleStats(novelId);
        long chapterCount = ((Number) stats.get("chapterCount")).longValue();
        long wordCount = ((Number) stats.get("wordCount")).longValue();
        long paidCoinSum = ((Number) stats.get("paidCoinSum")).longValue();
        // 整本打包价 = 付费章解锁币之和 × 六折；有付费章时必须 > 0
        int coinPrice = paidCoinSum == 0 ? 0 : (int) Math.max(1, Math.round(paidCoinSum * NovelConstant.BUNDLE_DISCOUNT));

        Novel novel = new Novel();
        novel.setId(novelId);
        novel.setTotalChapters((int) chapterCount);
        novel.setWordCount(wordCount);
        novel.setCoinPrice(coinPrice);
        novelMapper.updateById(novel);
    }

    /**
     * 章节写操作后的统一副作用：失效目录/详情缓存 + 同步 ES（章节数/字数可能变化）
     * + 重建这一章的向量块。
     *
     * <p>三个副作用的分工：缓存要求「立即准确」，作品索引处理「目录/字数变化」，
     * 向量块处理「本章正文变化、后续审查需要使用」。三者统一在此执行，以避免出现
     * 「新增入口仅处理前两项」的情况：块索引曾因此遗漏（当时仅有手动重建入口）。
     */
    private void afterChapterChange(Long novelId, Long chapterId, boolean recount) {
        if (recount) {
            recountNovel(novelId);
        }
        evictListCache(novelId);
        evictContentCache(novelId);
        novelService.evictDetailCache(novelId);
        sendSyncMessage(novelId);
        requestChunkReindex(novelId, chapterId);
    }

    /**
     * 投递章节块重建消息。正文可能变化的路径均需调用它，不限于作者保存：
     * 审核把影子正文提升为正文、驳回丢掉影子正文，同样会改变 {@code currentBody()}。
     */
    @Override
    public void requestChunkReindex(Long novelId, Long chapterId) {
        if (novelId == null || chapterId == null) {
            return;
        }
        ChapterChunkSyncMessage message = new ChapterChunkSyncMessage();
        message.setNovelId(novelId);
        message.setChapterId(chapterId);
        mqSender.sendAfterCommit(MqConstant.SEARCH_EXCHANGE,
                MqConstant.SEARCH_CHUNK_ROUTING_KEY, message);
    }

    /**
     * 校验当前用户为本书作者或管理员，否则拒绝（章节写操作的统一权限门）。
     */
    private Novel requireOwnerNovel(Long novelId) {
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

    /**
     * 已完结作品的内容锁：章节不可新增 / 修改 / 删除。
     *
     * <p>仅限制作者，<b>管理员放行</b>，与「下架」遵循同一原则：内容出现问题需能立即处置，
     * 不因作者标记完结而无法操作。作者本人也无法自助解锁，只能通过「申请恢复连载」。
     */
    private void requireChapterEditable(Novel novel) {
        if (SerialStatusEnum.isFinished(novel.getSerialStatus()) && !LoginUserUtil.isAdmin()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "作品已完结，章节不可修改；如需继续更新，请先申请恢复连载");
        }
    }

    /** 发送章节 AI 预审消息（复用整本审核队列，message.chapterId 区分单章） */
    private void sendChapterAudit(Long chapterId) {
        AiAuditMessage message = new AiAuditMessage();
        message.setChapterId(chapterId);
        mqSender.sendAfterCommit(MqConstant.AI_EXCHANGE, MqConstant.AI_AUDIT_ROUTING_KEY, message);
    }

    /** 发送搜索同步消息（章节变更 -> ES 更新 totalChapters/wordCount） */
    private void sendSyncMessage(Long novelId) {
        SearchSyncMessage msg = new SearchSyncMessage();
        msg.setNovelId(novelId);
        msg.setOperation("UPSERT");
        mqSender.sendAfterCommit(MqConstant.SEARCH_EXCHANGE, MqConstant.SEARCH_SYNC_ROUTING_KEY, msg);
    }
}
