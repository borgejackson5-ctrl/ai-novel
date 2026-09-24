package com.ainovel.module.ai.service.impl;

import com.ainovel.common.code.ErrorCode;
import com.ainovel.common.exception.BusinessException;
import com.ainovel.common.util.NumberWords;
import com.ainovel.common.util.LoginUserUtil;
import com.ainovel.module.ai.client.ChapterReviewRequest;
import com.ainovel.module.ai.client.ChapterReviewer;
import com.ainovel.module.ai.domain.ChapterReviewReport;
import com.ainovel.module.ai.domain.ChapterReviewResult;
import com.ainovel.module.ai.domain.entity.AiConfig;
import com.ainovel.module.ai.domain.vo.ChapterReviewVO;
import com.ainovel.module.ai.service.AiConfigService;
import com.ainovel.module.ai.service.ChapterReviewPrompt;
import com.ainovel.module.ai.service.ChapterReviewService;
import com.ainovel.module.ai.service.ChapterSegmenter;
import com.ainovel.module.ai.spi.ChapterRetrievalPort;
import com.ainovel.module.novel.domain.entity.Chapter;
import com.ainovel.module.novel.domain.entity.Novel;
import com.ainovel.module.novel.domain.vo.FactConflict;
import com.ainovel.module.novel.domain.vo.GlossaryConflict;
import com.ainovel.module.novel.domain.vo.GlossaryFamily;
import com.ainovel.module.novel.service.ChapterService;
import com.ainovel.module.novel.service.NovelGlossaryService;
import com.ainovel.module.novel.service.NovelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 章节审查实现：**工具调用 + 结构化输出**（阶段 4a），阶段 5 增加切段与归并。
 *
 * <p>与「将正文交给模型并等待一次回复」相比有三点区别：
 * <ol>
 *   <li>向模型暴露若干只读工具（{@code ChapterReviewTools}），由其自行查询需要核对的前文，
 *       「需要什么材料」由模型判断，不在 prompt 中预设；</li>
 *   <li>要求模型按结构返回（{@link ChapterReviewReport}），而非返回自然语言后再解析；</li>
 *   <li>结果做**反幻觉过滤**：excerpt 必须能在正文中找到，找不到即丢弃并计数。
 *       结构化输出不保证内容可信，编造的「原句」对信任的损害大于漏报。</li>
 * </ol>
 *
 * <p>阶段 5 增加的**切段与归并**（清单 1.3 的 map-reduce）仅对超长章生效：
 * 短章仍为一次调用，切段的目的不是提高粒度，而是避免单次调用的输入无限增长。
 * 段结果合并时按「类型 + 片段」去重，避免同一处错别字在相邻段中重复上报。
 *
 * <p><b>任何一段失败即整章判定为「未审成」</b>，并退回本章已扣的全部额度。
 * 半章结论的误导性大于没有结论：作者只看到前半段无误，会认为整章已审。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChapterReviewServiceImpl implements ChapterReviewService {

    /**
     * 提示词中最多列出的已确立写法数量。
     *
     * <p>表规模变大后全部写入会挤占正文篇幅（长书可达几千个名字），因此截断并注明「还有 N 个未列出」。
     * 服务端的比对不受该上限影响，其使用的是整张表。
     */
    private static final int MAX_GLOSSARY_IN_PROMPT = 120;

    /**
     * 自动注入的前文片段数量上限。
     *
     * <p>该上限由 3 调整为 5，依据分三层：
     *
     * <p>① **检索侧直接测出缺陷**（`eval/retrieval_eval.py`，不调模型）：检查「应作为依据的
     * 那一块」是否进入前几名。三个样本集共 16 处跨章对照，**两路各自均为 16/16**，
     * 但**仅取前 3 条时融合结果只有 14/16**：两路都已命中，融合后丢失。依据块排名在 4~5 位，
     * 被「两路都排中游」的块挤下；并非融合计算错误，而是**最终取值过少**。取前 5 条恢复 16/16。
     *
     * <p>② **端到端有正向收益**：12 章留出集取 3 条时跨章召回**六轮恒定为 3/5**，
     * 取 5 条的四轮中有两轮达到 4/5；多出的那条属「称谓」类，取 3 条时从未报出。
     *
     * <p>③ **章内召回无可靠损失**：两档四轮均值 85% → 80%，表面下降 5 个百分点，
     * 但该结果**不单调**：注入 8 条的两轮均值为 80.3%，并不劣于 5 条。若「注入越多、章内越差」
     * 成立，8 条应比 5 条更差。故该 5 个百分点按噪声处理。误报均值两档完全相同（2.25）。
     *
     * <p>①②③ 共同支持一条判据：**「检索指标更好」不等于「端到端更好」**，
     * 最终判据须落在产物上。本次改动是在**两端均有度量**的前提下做出的：检索侧 14/16 → 16/16，
     * 端到端仅换来跨章 3/5 → 4/5（四轮中两轮），且章内一侧未能证明存在损失。
     * 若仅有检索侧的数值，该改动的依据不充分。
     *
     * <p>**注意：此处原记录为「3 是量出来的，5 试过、退回来了」，该结论源于读表错误。**
     * 原始表（REPORT 第六轮）中「混合 + 3 条」与「混合 + 5 条」**均为 17/20**；
     * 被用于对比的是「纯向量 + 3 条（旧索引）19/20」与「混合 + 5 条 17/20」，
     * 两者之间多了「索引重建」与「混合开关」两个变量。同表内相同配置更换一次索引即从 19/20 变为 18/20，
     * 该表自身的分辨率仅为 ±1~2 处，因此该结论并非「被新数据推翻」，而是**当时的归因有误**。
     *
     * <p>不应继续上调：注入 8~10 条的两轮中，两章专用于检测误报的干净章**出现误报**（1 条、4 条），
     * 而 3 条与 5 条时基本为 0。
     *
     * <p>做成配置项是为了后续可再调整（`app.rag.max-context-chunks`，该配置项也已写入
     * `application.yaml`；在此之前仅有此处默认值，yaml 中检索不到，也无法按环境修改）。
     * 写成带初始值的字段（而非「为 null 就跳过」）是因为单测没有 Spring 上下文，
     * 没有初始值的 @Value 字段会为 0，等同于该上限在测试中失效。
     */
    @Value("${app.rag.max-context-chunks:5}")
    private int maxContextChunks = 5;

    /** 每条前文片段在提示词里最多保留多少字（原始块是 400 字） */
    private static final int MAX_CHUNK_CHARS_IN_PROMPT = 300;

    /** 兜底查询（整段正文）最多取多少字：过长会稀释语义，embedding 也无需处理这么多 */
    private static final int CONTEXT_QUERY_CHARS = 500;

    /** 每章最多挑选多少句「疑似设定句」用于检索 */
    private static final int MAX_SETTING_QUERIES = 3;

    /** 疑似设定句的长度区间（太短没有语义，太长不是一句话） */
    private static final int MIN_SETTING_SENTENCE = 8;
    private static final int MAX_SETTING_SENTENCE = 80;

    /** 句末标点（与切块器同一套） */
    private static final String SENTENCE_ENDS = "。！？!?…\n";

    /** 提示词中的换行符（定义为常量，避免在字符串字面量中写入转义符） */
    private static final String NEW_LINE = "\n";

    private final ChapterService chapterService;

    private final NovelService novelService;

    private final AiConfigService aiConfigService;

    private final ChapterReviewer chapterReviewer;

    private final NovelGlossaryService glossaryService;

    private final ChapterSegmenter chapterSegmenter;

    /** 前文检索端口（实现位于 search 模块）：审查时由服务端主动查询，不依赖模型决定 */
    private final ChapterRetrievalPort chapterRetrievalPort;

    /**
     * 审查用的系统提示词见 {@link com.ainovel.module.ai.service.ChapterReviewPrompt}。
     *
     * <p>独立成类的原因：该文本属于产品文案，变更频率高于代码（每轮评测均需调整），
     * 且需与工具名保持一致，独立成类后才可测试。
     * 审核用的提示词「只回复通过或拒绝:原因」只能得到二值结论，因为其仅需判断
     * 能否上架；而自查需要的是**可逐条定位、可逐条修改**的清单，
     * 因此提示词、输出结构、反幻觉约束三者需一并给出（见学习清单 4.2）。
     */
    private static final String SYSTEM_PROMPT = ChapterReviewPrompt.SYSTEM_PROMPT;

    /**
     * 失败时对用户展示的文案。
     *
     * <p>写明「不消耗免费字数」是因为额度确已退回，作者需要确认这一点才会重试；
     * 同时不出现任何接口名/模型名等实现细节。
     */
    private static final String FAILED_MSG = "这次审查没能完成，请稍后再试（本次不消耗免费字数）";

    @Override
    public ChapterReviewVO reviewChapter(Long chapterId) {
        Chapter chapter = chapterService.getById(chapterId);
        if (chapter == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "章节不存在");
        }
        // 归属校验置于最前：工具的读取范围由此处确定的作品决定，不能等到模型开始调用工具时才拦截
        Novel novel = novelService.requireOwnerNovel(chapter.getNovelId());
        String body = requireBody(chapter);
        // 执行前确认模型可用。平台未配置 Key 时应**直接返回「暂未开放」**，
        // 不应实际请求上游后返回「这次审查没能完成，请稍后再试」：
        // 后者会使作者反复重试，而重试不会成功。四条创作链路均已做该判断，此前仅审查遗漏
        // （降级冒烟测试发现：平台无 Key 时单章审查返回 200 + 「稍后再试」）。
        //
        // 位置在「正文校验之后」：顺序为**权限 → 数据 → 功能可用性**，
        // 越具体、越本地的错误越先返回，否则「这一章没有正文」会被「AI 暂未开放」掩盖，
        // 作者需先补完正文再重试一次才能发现 AI 未开放。
        aiConfigService.requireModelAvailable(LoginUserUtil.getUserId());

        ChapterReviewResult result = doReview(chapter, novel.getTitle(), body, LoginUserUtil.getUserId());
        return toVO(result);
    }

    @Override
    public ChapterReviewResult reviewChapterForTask(Long chapterId, Long userId, String novelTitle) {
        Chapter chapter = chapterService.getById(chapterId);
        if (chapter == null) {
            // 作者在任务执行期间删除了该章：这属于正常情况，按「未审成」记录一条即可，
            // 不应抛异常，否则整条消息会重试，而重试结果相同。
            return ChapterReviewResult.failed("这一章已经不存在了，可能已被删除");
        }
        String body = bodyOf(chapter);
        if (!StringUtils.hasText(body)) {
            return ChapterReviewResult.failed("这一章没有正文，已跳过");
        }
        // 权限不在此处判定：任务创建时已确认作者身份；MQ 线程中也没有登录上下文
        return doReview(chapter, novelTitle, body, userId);
    }

    // ==================== 主流程：切段 → 逐段审 → 归并 ====================

    /**
     * 提交一章进行审查（内部可能切分为多段）。
     *
     * <p>额度不足（{@link ErrorCode#AI_QUOTA_EXHAUSTED}）与平台忙（{@link ErrorCode#AI_PLATFORM_BUSY}）
     * **原样抛出**，不在此处转为「未审成」，因为这两种情况调用方的处理方式不同：
     * 单章审查向作者提示即可，全文审查则应**中止整个任务**（继续执行会导致每一章失败，
     * 仅将同一错误重复 N 次，并使失败章数计入进度造成误判）。
     */
    private ChapterReviewResult doReview(Chapter chapter, String novelTitle, String body, Long userId) {
        List<ChapterSegmenter.Segment> segments = chapterSegmenter.split(body);
        String normalizedBody = normalize(body);

        // 审查**前**取表：本章自身的写法需在审查完成后才记录，否则本章的错写会污染本次核对
        List<GlossaryFamily> glossary = glossaryService.listFamilies(chapter.getNovelId());
        // 模型一并返回的本章专有名词，段间合并（同一名字在相邻段各出现一次属常见情况）
        Set<String> names = new LinkedHashSet<>();
        // 模型一并返回的设定数字（「伞骨=七根」），同样段间合并
        Set<String> facts = new LinkedHashSet<>();

        // 本章已扣费、失败时需原样退回的配置（按段保存：每段均为一次独立扣费）
        List<AiConfig> charged = new ArrayList<>();
        // 使用 LinkedHashMap 去重：同一处问题被相邻段各报一次时仅保留一条，同时保持模型返回的顺序
        Map<String, ChapterReviewResult.Issue> kept = new LinkedHashMap<>();
        List<String> summaries = new ArrayList<>();
        int dropped = 0;
        int chargedUnits = 0;

        for (ChapterSegmenter.Segment segment : segments) {
            int units = aiConfigService.estimateUnits(segment.text());
            AiConfig config;
            try {
                config = aiConfigService.getActiveConfigForUser(userId, units);
            } catch (BusinessException e) {
                // 额度不足 / 平台忙：原样抛给调用方（全文审查据此中止整个任务），
                // 但前面各段已扣的额度需先行退回
                int refunded = refundAll(charged, userId);
                log.warn("章节审查：第 {} 段失去额度，本章已退回 {} 字 chapterId={}",
                        segment.no(), refunded, chapter.getId());
                throw e;
            } catch (Exception e) {
                int refunded = refundAll(charged, userId);
                log.error("章节审查：准备调用失败 chapterId={}", chapter.getId(), e);
                return ChapterReviewResult.failedAfterRefund(FAILED_MSG, refunded);
            }
            if (config.getQuotaChargedUnits() != null && config.getQuotaChargedUnits() > 0) {
                charged.add(config);
                chargedUnits += config.getQuotaChargedUnits();
            }

            // 服务端主动检索前文（不依赖模型是否愿意调用工具），将最相关的几段直接写入提示词
            String related = retrieveRelatedContext(chapter, segment.text());

            long start = System.currentTimeMillis();
            ChapterReviewReport report;
            try {
                report = chapterReviewer.review(new ChapterReviewRequest(
                        config.getBaseUrl(), config.getApiKey(), config.getModel(),
                        SYSTEM_PROMPT,
                        buildUserPrompt(chapter, novelTitle, segment, segments.size(), body.length(), glossary, related),
                        chapter.getNovelId(), chapter.getChapterNo()));
            } catch (Exception e) {
                // 未取得结果时退回额度：作者不应为失败的调用付费
                int refunded = refundAll(charged, userId);
                log.error("章节审查失败 chapterId={} 第 {} 段/共 {} 段",
                        chapter.getId(), segment.no(), segments.size(), e);
                return ChapterReviewResult.failedAfterRefund(FAILED_MSG, refunded);
            }

            // 模型返回空结构即「未审成」，而非「无问题」。两者混同属于最危险的静默失败：
            // 作者会认为稿件已被审查
            if (report == null) {
                int refunded = refundAll(charged, userId);
                log.warn("章节审查返回空结构，按未完成处理 chapterId={}", chapter.getId());
                return ChapterReviewResult.failedAfterRefund(FAILED_MSG, refunded);
            }

            collect(report, segment.no(), normalizedBody, kept);
            dropped += countDropped(report, normalizedBody);
            if (report.getNames() != null) {
                names.addAll(report.getNames());
            }
            if (report.getFacts() != null) {
                facts.addAll(report.getFacts());
            }
            if (StringUtils.hasText(report.getSummary())) {
                summaries.add(report.getSummary().trim());
            }
            // 记录「模型在每一段返回的名字/数字条数」：跨章核对的覆盖度完全取决于该返回值，
            // 而该信息在界面上不可见（表现为「这一章未报跨章问题」，与「无问题」外观相同）
            log.info("章节审查：第 {} 段完成 chapterId={} 本段={}字 名字{}个/数字{}条 耗时={}ms",
                    segment.no(), chapter.getId(), segment.text().length(),
                    report.getNames() == null ? 0 : report.getNames().size(),
                    report.getFacts() == null ? 0 : report.getFacts().size(),
                    System.currentTimeMillis() - start);
        }

        // 服务端自行核对跨章写法与设定数字（不依赖模型是否查询前文），并与模型上报的结果合并去重
        collectGlossaryConflicts(chapter, body, glossary, kept);
        collectFactConflicts(chapter, body, facts, kept);
        // 记录本章的名字与设定数字：后续审查其他章节时，这些即为「已确立的」写法。
        // 均放在核对**之后**，避免本章自身的错写成为后续章节的「标准」
        try {
            int nameCount = glossaryService.recordNames(chapter.getNovelId(), chapter.getId(),
                    chapter.getChapterNo(), body, names);
            int factCount = glossaryService.recordFacts(chapter.getNovelId(), chapter.getId(),
                    chapter.getChapterNo(), body, facts, names);
            if (nameCount > 0 || factCount > 0) {
                log.info("名词表：本章记入/更新 {} 个专有名词、{} 条设定数字 chapterId={}",
                        nameCount, factCount, chapter.getId());
            }
        } catch (Exception e) {
            // 记录失败仅使下一次核对缺少部分依据，不应使本章的审查结果作废
            log.warn("名词表：记录本章的专有名词/设定数字失败 chapterId={}", chapter.getId(), e);
        }

        return new ChapterReviewResult(true, null, String.join("；", summaries),
                new ArrayList<>(kept.values()), body.length(), segments.size(), dropped,
                chargedUnits, 0);
    }

    /**
     * 将服务端核对出的「写法不一致」并入结果。
     *
     * <p>与模型上报的结果使用同一套去重键（类型 + 归一化片段），因此同一处矛盾被双方各报一次时仅保留一条。
     * 服务端条目的 excerpt 直接取自原文窗口，天然可通过反幻觉核对，其并非模型生成的内容。
     *
     * <p>失败时仅记录警告不抛出：该项为附加能力，比对失败不应影响已取得的审查结果。
     */
    private void collectGlossaryConflicts(Chapter chapter, String body, List<GlossaryFamily> families,
                                          Map<String, ChapterReviewResult.Issue> kept) {
        if (families == null || families.isEmpty()) {
            return;
        }
        List<GlossaryConflict> conflicts;
        try {
            conflicts = glossaryService.detectConflicts(chapter.getNovelId(), body);
        } catch (Exception e) {
            log.warn("名词表：比对本章写法失败 chapterId={}", chapter.getId(), e);
            return;
        }
        for (GlossaryConflict conflict : conflicts) {
            String key = "前后不一致|" + normalize(conflict.excerpt());
            kept.putIfAbsent(key, new ChapterReviewResult.Issue(
                    1, "前后不一致", conflict.excerpt(), suggestionOf(conflict)));
        }
    }

    /**
     * 服务端报告的措辞取决于**可用的证据强度**。
     *
     * <p>提示词要求模型「以先出现的为准」，但服务端不能照搬该规则：表中的写法由模型上报，
     * 模型自身也可能报错（曾将「灯心」「糖胡芦」作为专有名词写入表中）。因此分三档：
     * <ol>
     *   <li>该写法在**多个已审章节**中出现过（{@code hitCount >= 2}）⇒ 属于稳定写法，
     *       可直接表述为「此处应一致」；</li>
     *   <li>该族中存在两种写法（本书均使用过）⇒ **不能判定哪一种正确**，仅提示「两处写法不同，请统一」；</li>
     *   <li>表中仅一条且仅出现过一次 ⇒ 该次出现本身也可能有误，使用中性措辞
     *       「前文第 N 章作 X，本章作 Y，请确认统一为哪一种」。</li>
     * </ol>
     *
     * <p>第 3 档原表述为「此处应一致」，会导致作者朝错误方向修改：表中存有错写「灯心」，
     * 本章正确的「灯芯」因此被报为「应改为灯心」。措辞上的差异直接影响作者改对或改错。
     */
    private String suggestionOf(GlossaryConflict conflict) {
        String where = conflict.firstChapterNo() == null
                ? "本书另一处" : "前文第 " + conflict.firstChapterNo() + " 章";
        String base;
        if (!conflict.siblings().isEmpty()) {
            base = "本书两种写法并存：" + where + "作「" + conflict.name() + "」，另一处作「"
                    + String.join("／", conflict.siblings()) + "」，请统一为其中一种";
        } else if (conflict.hitCount() >= 2) {
            base = where + "作「" + conflict.name() + "」，此处应一致";
        } else {
            base = where + "作「" + conflict.name() + "」，本章作「" + conflict.variant()
                    + "」，请确认统一为哪一种";
        }
        if (conflict.occurrences() > 1) {
            base = base + "（本章另有 " + (conflict.occurrences() - 1) + " 处同样写法）";
        }
        return base;
    }

    /**
     * 将服务端核对出的「数字不一致」并入结果。
     *
     * <p>与写法不一致共用同一个去重键（类型 + 归一化片段），因此同一处被模型与服务端各报一次时仅保留一条。
     *
     * <p>**措辞保持中性，不给修改方向**：数值以哪个为准取决于作者的设定，当前仅有一个「先写下的值」，
     * 无法判断其是否为笔误（「刀身长两尺」与「刀身长三尺」都可能是作者的原始设定）。
     */
    private void collectFactConflicts(Chapter chapter, String body, Set<String> facts,
                                      Map<String, ChapterReviewResult.Issue> kept) {
        if (facts == null || facts.isEmpty()) {
            return;
        }
        List<FactConflict> conflicts;
        try {
            conflicts = glossaryService.detectFactConflicts(chapter.getNovelId(), body, facts);
        } catch (Exception e) {
            log.warn("名词表：比对本章设定数字失败 chapterId={}", chapter.getId(), e);
            return;
        }
        for (FactConflict conflict : conflicts) {
            String where = conflict.firstChapterNo() == null
                    ? "本书另一处" : "前文第 " + conflict.firstChapterNo() + " 章";
            String suggestion = where + "写「" + conflict.name() + conflict.expected() + "」，"
                    + "本章写「" + conflict.name() + conflict.actual() + "」，请确认以哪个为准";
            String key = "前后不一致|" + normalize(conflict.excerpt());
            kept.putIfAbsent(key, new ChapterReviewResult.Issue(
                    1, "前后不一致", conflict.excerpt(), suggestion));
        }
    }

    /** 收集通过核对的条目，按「类型 + 归一化片段」去重 */
    private void collect(ChapterReviewReport report, int segmentNo, String normalizedBody,
                         Map<String, ChapterReviewResult.Issue> kept) {
        if (report.getIssues() == null) {
            return;
        }
        for (ChapterReviewReport.Issue issue : report.getIssues()) {
            if (!verifiable(issue, normalizedBody)) {
                continue;
            }
            String type = normalizeType(issue.getType());
            String key = type + "|" + normalize(issue.getExcerpt());
            kept.putIfAbsent(key, new ChapterReviewResult.Issue(
                    segmentNo, type, issue.getExcerpt(), issue.getSuggestion()));
        }
    }

    /**
     * 将模型返回的类型收敛到四类之一。
     *
     * <p>**不能直接信任模型返回的类型字符串**：提示词已约定「只能填这四类之一」，但实际返回常
     * 带括号补充（「标点错误（中英文标点混用）」「语病（成分赘余）」）。直接作为类型使用会导致两个问题：
     * <ol>
     *   <li>同一处问题在两段中各报一次，一处写作「标点错误」、一处写作「标点错误（中英文标点混用）」，
     *       去重键不同，合并后作者会看到两条完全相同的条目；</li>
     *   <li>界面按类型着色，无法匹配的类型落入兜底色，标点错误会显示为"无问题"的颜色。</li>
     * </ol>
     * 收紧提示词只是第一道约束，代码层必须自行兜住：提示词是否合规不由本项目保证。
     */
    private String normalizeType(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "其他";
        }
        String t = raw.replaceAll("\\s+", "");
        // 判断顺序有决定性：「前后不一致（整段内容重复）」中同时含「不一致」与「重复」，
        // 先判定「不一致」才不会误归为语病
        if (t.contains("不一致") || t.contains("前后") || t.contains("矛盾")) {
            return "前后不一致";
        }
        if (t.contains("标点")) {
            return "标点";
        }
        if (t.contains("语病") || t.contains("赘余") || t.contains("啰嗦") || t.contains("搭配")
                || t.contains("成分") || t.contains("语序")) {
            return "语病";
        }
        if (t.contains("错别字") || t.contains("别字") || t.contains("用词") || t.contains("字形")) {
            return "错别字";
        }
        return "其他";
    }

    /** 统计被丢弃的条数（反幻觉可观测指标：正常应为 0） */
    private int countDropped(ChapterReviewReport report, String normalizedBody) {
        if (report.getIssues() == null) {
            return 0;
        }
        int dropped = 0;
        for (ChapterReviewReport.Issue issue : report.getIssues()) {
            if (!verifiable(issue, normalizedBody)) {
                dropped++;
            }
        }
        return dropped;
    }

    private boolean verifiable(ChapterReviewReport.Issue issue, String normalizedBody) {
        return issue != null && StringUtils.hasText(issue.getExcerpt())
                && normalizedBody.contains(normalize(issue.getExcerpt()));
    }

    /**
     * 归还本章已扣的全部额度。
     *
     * <p>按 {@link AiConfig} 逐条退回，而非「按总字数额退回一次」：每次扣费对应一个独立的
     * {@code AiConfig} 载体（{@code quotaChargedUnits} 记录该次扣费的字数），
     * 自带 Key / 管理员本身扣费为 0，该路径不会进入此列表。
     *
     * @return 实际退回的字数合计（自带 Key / 管理员为 0，任务账面需区分）
     */
    private int refundAll(List<AiConfig> charged, Long userId) {
        int refunded = 0;
        for (AiConfig config : charged) {
            if (config.getQuotaChargedUnits() != null && config.getQuotaChargedUnits() > 0) {
                refunded += config.getQuotaChargedUnits();
            }
            aiConfigService.refundQuotaIfCharged(config, userId);
        }
        charged.clear();
        return refunded;
    }

    // ==================== 正文与提示词 ====================

    private String requireBody(Chapter chapter) {
        String body = bodyOf(chapter);
        if (!StringUtils.hasText(body)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "这一章还没有正文，先写点内容再审查");
        }
        return body;
    }

    /**
     * 拼接用户提示词。
     *
     * <p>切段时需写明「当前为第几段、全章总长度」：否则模型会把该段视为完整一章，
     * 进而将「开头缺少铺垫」「结尾戛然而止」报为结构问题，产生误报。
     */
    private String buildUserPrompt(Chapter chapter, String novelTitle, ChapterSegmenter.Segment segment,
                                   int totalSegments, int totalChars, List<GlossaryFamily> glossary,
                                   String relatedContext) {
        StringBuilder sb = new StringBuilder();
        sb.append("【作品】《").append(StringUtils.hasText(novelTitle) ? novelTitle : "").append("》\n");
        sb.append("【章节】第 ").append(chapter.getChapterNo()).append(" 章 ")
                .append(StringUtils.hasText(chapter.getTitle()) ? chapter.getTitle() : "（无标题）").append('\n');
        appendGlossary(sb, glossary);
        if (totalSegments > 1) {
            sb.append("【说明】这一章较长（全章约 ").append(totalChars).append(" 字），已分成 ")
                    .append(totalSegments).append(" 段逐段审查，本次给的是第 ").append(segment.no())
                    .append(" 段。只审查这一段；需要看别段或前文时用提供的工具去读。\n");
        }
        sb.append("【正文】\n").append(segment.text()).append("\n\n");
        if (StringUtils.hasText(relatedContext)) {
            sb.append(relatedContext);
        }
        sb.append("请审查上面的正文。人名/专有名词的写法照着上面那张表对；"
                + "**本章出现的数量与尺寸（几根、几尺、多少岁）要查前文核对**，"
                + "想不起该用哪个词查时，用 searchRelevantContext 按语义查一次（它能翻到很久以前的章节）。");
        return sb.toString();
    }

    /**
     * 将「本书已确立的写法」写入提示词。
     *
     * <p>该段此前不存在，依赖模型自行查询前文：同一章两次运行结果不一致，一次查出跨章人名矛盾，
     * 一次遗漏。将表直接写入提示词后，核对由「想起时才做」变为「逐项对照」。
     *
     * <p>**仅差一个字的写法需相邻列出**：表中出现两条仅差一个字的写法，说明本书两种写法均使用过
     * （其中一条可能是模型上报的错写）。分开列出会使模型将先列出的那条视为唯一标准，
     * 从而将实际正确的写法报为错误（曾出现：表中存有「灯心」，模型反向报出「灯芯」应修改）。
     * 相邻列出并注明「两种写法均使用过、不要断言哪种正确」，模型即不会单取一条下结论。
     *
     * <p>表为空（本书尚未审查任何一章）时整段不出现：给出空表会使模型认为
     * 「本书没有专有名词」。
     */
    /**
     * 服务端主动检索前文，将最相关的几段直接写入提示词。
     *
     * <p>不由模型自行调用工具检索：两次尝试结果均不理想。第一次模型未调用任何工具；
     * 将「尺寸类必须查」写成硬要求后，其在总评中写「已尝试用检索工具核对，但工具未能返回有效结果」，
     * 而日志中为 0 次工具调用，即**模型声称已查而实际未查**。
     * 与其继续调整提示词，不如将这一步移到服务端：是否检索由服务端决定，
     * 模型仅负责判断「取得的前文与本章是否一致」。该思路与名词表一致。
     *
     * <p>**仅取前文**（章号小于本章）：后文不作为核对依据，写入还会将「后续才会出现的内容」
     * 提前暴露给模型，使其报出无依据的矛盾。
     *
     * <p>查询分两路：先以「疑似设定句」（含数量/尺寸的句子）查询，该类内容名词表无法覆盖
     * 且最易出错；再以整段文本兜底，情节类核对经常整句中不含数字。
     *
     * @return 拼接好的提示词片段；检索不可用、或未查到任何前文时返回空串（调用方原样跳过）
     */
    private String retrieveRelatedContext(Chapter chapter, String segmentText) {
        Integer currentNo = chapter.getChapterNo();
        if (!StringUtils.hasText(segmentText) || chapter.getNovelId() == null || currentNo == null) {
            return "";
        }
        List<String> queries = new ArrayList<>(settingSentences(segmentText));
        queries.add(limit(segmentText.strip(), CONTEXT_QUERY_CHARS));

        Map<String, ChapterRetrievalPort.RetrievedChunk> picked = new LinkedHashMap<>();
        for (String query : queries) {
            if (picked.size() >= maxContextChunks) {
                break;
            }
            List<ChapterRetrievalPort.RetrievedChunk> hits;
            try {
                hits = chapterRetrievalPort.searchRelevant(chapter.getNovelId(), query, maxContextChunks);
            } catch (Exception e) {
                // 检索为辅助能力：其失败不应导致整章审查失败，也不应使作者看到错误提示
                log.warn("章节审查：自动检索前文失败，本次不注入前文片段 chapterId={}", chapter.getId(), e);
                return "";
            }
            for (ChapterRetrievalPort.RetrievedChunk hit : hits) {
                if (hit.chapterNo() == null || hit.chapterNo() >= currentNo
                        || !StringUtils.hasText(hit.text())) {
                    continue;
                }
                picked.putIfAbsent(hit.chapterNo() + "-" + hit.seq(), hit);
                if (picked.size() >= maxContextChunks) {
                    break;
                }
            }
        }
        if (picked.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder("【系统自动找出的相关前文】");
        sb.append("下面是按内容检索出来的、与本章最相关的前文片段（标了章号）。").append(NEW_LINE);
        sb.append("逐条对照本章：**对不上就按「前后不一致」报出来**，并在建议里写明依据来自第几章。").append(NEW_LINE);
        int chars = 0;
        StringBuilder chapters = new StringBuilder();
        for (ChapterRetrievalPort.RetrievedChunk hit : picked.values()) {
            String piece = limit(hit.text().strip(), MAX_CHUNK_CHARS_IN_PROMPT);
            sb.append("· 第 ").append(hit.chapterNo()).append(" 章：").append(piece).append(NEW_LINE);
            chars += piece.length();
            if (chapters.length() > 0) {
                chapters.append('、');
            }
            chapters.append(hit.chapterNo());
        }
        sb.append("这里没有你要对照的东西时，可以用工具再去查。").append(NEW_LINE).append(NEW_LINE);
        log.info("章节审查：自动注入前文 {} 条（来自第 {} 章），共 {} 字 chapterId={}",
                picked.size(), chapters, chars, chapter.getId());
        return sb.toString();
    }

    /**
     * 挑出「疑似设定句」：含数量/尺寸/年龄的句子。
     *
     * <p>仅用 {@link NumberWords} 判断「句中是否含数字」，不判断数字的归属，
     * 后者无法实现（「那件兵器」的指代需阅读前文才能确定）。
     * 因此此处刻意多选若干句，由检索决定哪句能命中。
     */
    private List<String> settingSentences(String text) {
        List<String> out = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length() && out.size() < MAX_SETTING_QUERIES; i++) {
            if (SENTENCE_ENDS.indexOf(text.charAt(i)) < 0) {
                continue;
            }
            addIfSetting(out, text.substring(start, i + 1));
            start = i + 1;
        }
        if (out.size() < MAX_SETTING_QUERIES) {
            addIfSetting(out, text.substring(Math.min(start, text.length())));
        }
        return out;
    }

    private void addIfSetting(List<String> out, String sentence) {
        String trimmed = sentence.strip();
        if (trimmed.length() < MIN_SETTING_SENTENCE || trimmed.length() > MAX_SETTING_SENTENCE) {
            return;
        }
        if (NumberWords.parse(trimmed) != null) {
            out.add(trimmed);
        }
    }

    /** 超长时截断：前文片段仅用于对照，无需整段写入 */
    private String limit(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    private void appendGlossary(StringBuilder sb, List<GlossaryFamily> families) {
        if (families == null || families.isEmpty()) {
            return;
        }
        sb.append("【本书已确立的写法】前面章节出现过的人物、地名、门派、功法、道具（括号里是首次出现的章号）：\n");
        int limit = Math.min(families.size(), MAX_GLOSSARY_IN_PROMPT);
        for (int i = 0; i < limit; i++) {
            GlossaryFamily family = families.get(i);
            sb.append(family.preferred().display());
            for (String other : family.otherSpellings()) {
                sb.append('／').append(other);
            }
            if (i < limit - 1) {
                sb.append('、');
            }
        }
        if (families.size() > limit) {
            sb.append("……还有 ").append(families.size() - limit).append(" 个未列出");
        }
        sb.append("\n本章出现与上面写法不一致的地方，一律按「前后不一致」报出来。\n");
        sb.append("用「／」连着的那是**本书两种写法都用过**（我们也定不了哪个对）：本章用了其中一种就报出来，\n");
        sb.append("并说明两种写法各出现在第几章，**不要断言哪一种正确**。\n");
    }

    /** 正文取值：有变更的待审章取影子正文（审查发生在发布前，需审查作者刚修改的版本） */
    private String bodyOf(Chapter chapter) {
        return StringUtils.hasText(chapter.getPendingContent())
                ? chapter.getPendingContent() : chapter.getContent();
    }

    private ChapterReviewVO toVO(ChapterReviewResult result) {
        ChapterReviewVO vo = new ChapterReviewVO();
        vo.setOk(result.ok());
        if (!result.ok()) {
            vo.setMessage(result.message());
            vo.setIssues(List.of());
            return vo;
        }
        vo.setSummary(result.summary());
        vo.setReviewedChars(result.reviewedChars());
        vo.setSegments(result.segments());
        vo.setDroppedIssues(result.droppedIssues());
        List<ChapterReviewVO.IssueVO> list = new ArrayList<>();
        for (ChapterReviewResult.Issue issue : result.issues()) {
            ChapterReviewVO.IssueVO item = new ChapterReviewVO.IssueVO();
            item.setType(issue.type());
            item.setExcerpt(issue.excerpt());
            item.setSuggestion(issue.suggestion());
            list.add(item);
        }
        vo.setIssues(list);
        if (result.droppedIssues() > 0) {
            log.warn("章节审查：{} 条结果因在正文里核对不上而丢弃", result.droppedIssues());
        }
        return vo;
    }

    /** 核对用的归一化：去除所有空白（含全角空格）。模型引用时经常改动换行/空格 */
    private String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("\\s+", "").replace("\u3000", "");
    }
}
