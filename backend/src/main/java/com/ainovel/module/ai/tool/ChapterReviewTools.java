package com.ainovel.module.ai.tool;

import com.ainovel.common.domain.PageParam;
import com.ainovel.common.domain.PageResult;
import com.ainovel.module.novel.domain.entity.Chapter;
import com.ainovel.module.novel.domain.vo.ChapterVO;
import com.ainovel.module.novel.service.ChapterService;
import com.ainovel.module.ai.spi.ChapterRetrievalPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 章节审查时提供给模型的**只读工具**。
 *
 * <p>审查采用工具调用而非将全部材料写入 prompt：单章三千字直接放入 prompt 可行，但
 * 「这句话与前文是否矛盾」「该人名在前几章如何书写」这类判断需要**更多材料**，
 * 而需要哪些材料只有模型自身明确（即学习清单 4.3 的落点）。
 * 提供若干只读工具由模型自行获取，比在 prompt 中预设所需材料更准确，也避免为求稳妥
 * 将整本书写入 prompt（那才是真正的成本失控）。
 *
 * <p>**数据范围由调用方显式限定**：所有工具均要求 {@link #CTX_NOVEL_ID} 上下文，
 * 且只能读取**该本作品**的章节；模型即使被诱导传入其他章号，也取不到其他作品的内容。
 * 上下文中没有作品 id 时直接抛错，而非「未指定则查询全部」。
 *
 * <p>工具为单例无状态 bean，请求级数据一律通过 {@link ToolContext} 传递，不存放在字段上
 * （存放在字段上时，两个用户同时审查会发生数据串扰）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChapterReviewTools {

    /** 工具上下文键：正在审查的作品与章节（由审查服务写入 prompt 的 toolContext） */
    public static final String CTX_NOVEL_ID = "novelId";
    public static final String CTX_CHAPTER_NO = "chapterNo";

    /** 单次工具返回的字符上限：工具结果会写入 prompt，不设上限会同时放大成本与上下文占用 */
    private static final int MAX_RESULT_CHARS = 6000;

    /**
     * 语义检索返回的条数。
     *
     * <p>取 5 条的依据：此处用途为「核对一个具体设定」，真正相关的片段通常排在前列；
     * 返回过多会占满上下文并分散模型注意力。且下方会剔除当前章的片段，
     * 实际写入 prompt 的通常只有 3~4 条。
     */
    private static final int TOP_K = 5;

    /**
     * 列目录/按章号查找时每页取多少章。
     *
     * <p>**必须使用服务端实际的分页上限（{@code PageParam.MAX_PAGE_SIZE}），不可取更大的值**：
     * 服务端会将其钳制到上限，而「本页不满 ⇒ 后面没有更多章节」的判断仍按原值比较，
     * 导致每本书都表现为"只有一页"，第 100 章之后无法找到（工具仅返回「本书没有第 N 章」而不报错）。
     * 此处曾取值为 300。
     */
    private static final long CHAPTER_PAGE_SIZE = PageParam.MAX_PAGE_SIZE;

    /** 按章号查找时最多翻多少页（100 × 10 = 1000 章，超出则明确返回「找不到」，不做无限分页） */
    private static final int MAX_PAGES = 10;

    /** 邻近检索范围：当前章前后各 10 章 */
    private static final int NEARBY_RANGE = 10;

    /**
     * 「本次查询未取到材料」的统一前缀，**由代码拼接**，是所有失败文案的唯一事实来源。
     *
     * <p>跨章一致性结论仅在前文材料实际可取时成立。该判定原由提示词承担（约定模型识别以
     * 「错误」「未登录」「没有找到」「请给」开头的返回），但清单与工具实际文案分散在两个文件中，
     * 8 条失败文案中仅 1 条匹配（高频的「本书没有第 N 章。」完全不匹配），规则实际未生效，
     * 模型是否如实记录无法保证，可能将未取到材料的情况表述为「已核对，没有矛盾」。
     *
     * <p>改为单一来源后：失败文案一律由 {@link #noMaterial} 拼接本前缀，审查循环通过
     * {@link #isNoMaterial} 判定。提示词中无需再列清单（列出后迟早产生偏差），后续新增失败文案也不会遗漏。
     */
    public static final String NO_MATERIAL_PREFIX = "【本次查询未取到材料】";

    /**
     * 判断该次工具返回是否属于「未取到材料」。
     *
     * <p>两项判据要点：① **仅匹配前缀**，不做关键词匹配：「没有找到」这类词可能出现在正文引文中
     * （前文写有「他没有找到那把刀」），按关键词判定会将正常材料误判为失败；
     * ② 前缀前可能存在**一层双引号**，见方法内的说明。
     */
    public static boolean isNoMaterial(String response) {
        if (response == null) {
            return false;
        }
        // 取得的 responseData() 是工具返回值的 **JSON 序列化形式**，字符串会被再包一层双引号。
        // 例：工具返回「【本次查询未取到材料】本书没有第 999 章。」，进入 ToolResponse 后
        // 变为 "\"【本次查询未取到材料】本书没有第 999 章。\""，前面多出引号，
        // 直接匹配前缀将恒为 false，因此先剥除前置引号。
        int i = 0;
        while (i < response.length() && response.charAt(i) == '"') {
            i++;
        }
        return response.startsWith(NO_MATERIAL_PREFIX, i);
    }

    private static String noMaterial(String text) {
        return NO_MATERIAL_PREFIX + text;
    }

    private final ChapterService chapterService;

    private final ChapterRetrievalPort chapterRetrievalPort;

    /**
     * 列出本书的章号与标题。
     *
     * <p>用途：检查标题重复、编号跳号、标题风格是否统一。这些「书级」问题
     * 仅查看当前章无法发现。
     */
    @Tool(description = "列出本书的章号与章节标题，用于检查标题是否重复、编号是否跳号、标题风格是否统一")
    public String listChapters(ToolContext context) {
        Long novelId = novelIdOf(context);
        PageResult<ChapterVO> page = chapterService.pageChapterMetaByNovel(novelId, 1, CHAPTER_PAGE_SIZE);
        List<ChapterVO> list = page.getList() == null ? List.of() : page.getList();
        if (list.isEmpty()) {
            return noMaterial("本书还没有章节。");
        }

        StringBuilder sb = new StringBuilder();
        for (ChapterVO vo : list) {
            sb.append("第").append(vo.getChapterNo()).append("章 ")
                    .append(StringUtils.hasText(vo.getTitle()) ? vo.getTitle() : "（无标题）")
                    .append('\n');
        }
        long total = page.getTotal() == null ? list.size() : page.getTotal();
        if (total > list.size()) {
            sb.append("（本书共 ").append(total).append(" 章，这里只列了前 ").append(list.size()).append(" 章）");
        }
        log.info("审查工具：列目录 novelId={} 返回 {} 章", novelId, list.size());
        return truncate(sb.toString());
    }

    /**
     * 读取本书指定章号的正文。
     *
     * <p>有变更的待审章读取的是**影子正文**（pending_content）：审查发生在发布前，
     * 需审查的正是作者刚修改的版本。
     */
    @Tool(description = "读取本书指定章号的正文，用于核对前文写法、判断情节衔接。chapterNo 是章号，从 1 开始")
    public String readChapter(@ToolParam(description = "章号，从 1 开始") int chapterNo, ToolContext context) {
        Long novelId = novelIdOf(context);
        Long id = findChapterId(novelId, chapterNo);
        if (id == null) {
            return noMaterial("本书没有第 " + chapterNo + " 章。");
        }
        Chapter chapter = chapterService.getById(id);
        if (chapter == null) {
            return noMaterial("本书没有第 " + chapterNo + " 章。");
        }
        String body = bodyOf(chapter);
        if (!StringUtils.hasText(body)) {
            return noMaterial("第 " + chapterNo + " 章还没有正文。");
        }
        log.info("审查工具：读正文 novelId={} chapterNo={} 长度={}", novelId, chapterNo, body.length());
        return "【第 " + chapterNo + " 章 " + (StringUtils.hasText(chapter.getTitle()) ? chapter.getTitle() : "") + "】\n"
                + truncate(body);
    }

    /**
     * 在邻近章节中查找某个词或短语。
     *
     * <p>两种用途均需在工具描述中写明：描述是模型选择工具的唯一依据，若只写其一，
     * 模型仅会在核对人名时想到该工具。① 核对人名、地名、专有名词的写法前后是否一致
     * （同一角色在第 3 章写作「萧战天」、第 8 章写作「肖战天」）；
     * ② 核对数量、尺寸、时长这类数字（同一把刀前文写三尺、本章写两尺）。
     * 第 ① 种情形若为「本章写 A、其他章写 B」，仅查 A 无法发现问题，因此提示词要求
     * **先查完整词、未命中再只查前两个字**（查「沈青」可同时命中「沈青梧」与「沈青悟」）。
     *
     * <p>**范围仅取当前章 ± 10 章**，不做全书检索：上千章的书按章读取需上千次查询，
     * 单次审查的工具调用耗时会由秒级变为分钟级。邻近范围可覆盖「刚写完这几章」的绝大多数
     * 一致性问题；跨全书核对由阶段 5 的全文审查承担（其本就需按章遍历）。
     */
    @Tool(description = "在本书当前章前后各 10 章范围内查找某个词或短语。用于核对人名、地名、"
            + "专有名词的写法是否前后一致，也可用来核对数量、尺寸、时长这类数字（查它修饰的那个名词）")
    public String searchWordNearby(@ToolParam(description = "要查找的词或短语，1~20 个字，例如某个人名") String word,
                                   ToolContext context) {
        Long novelId = novelIdOf(context);
        if (!StringUtils.hasText(word) || word.length() > 20) {
            // 该早退分支也需保留一行日志：模型会给出空串或整句话，若不记录日志则无法排查，
            // 界面仅表现为「模型称该项未能核对」，日志中也无对应工具记录
            log.info("审查工具：邻近检索的参数不合规（word 长度 {}），已拒绝",
                    word == null ? -1 : word.length());
            return noMaterial("要查找的词请给 1~20 个字。");
        }
        int currentNo = chapterNoOf(context);
        int from = Math.max(1, currentNo - NEARBY_RANGE);
        int to = currentNo + NEARBY_RANGE;

        // 范围内的章号**一次性取好**。原实现对 20 个章号各调用一次 findChapterId，
        // 而该方法每次都从第 1 页开始翻目录（每页附带一次分页插件的 COUNT）：
        // 400 章的书、前后各 10 章，单次工具调用即为 80 次分页查询；
        // 而单次审查中模型会多次调用该工具，均消耗在重复翻同一份目录上。
        Map<Integer, Long> idsInRange = chapterIdsInRange(novelId, from, to);

        StringBuilder sb = new StringBuilder();
        int hits = 0;
        for (int no = from; no <= to; no++) {
            if (no == currentNo) {
                continue;   // 当前章正文已在 prompt 中
            }
            Long id = idsInRange.get(no);
            if (id == null) {
                continue;
            }
            Chapter chapter = chapterService.getById(id);
            String body = bodyOf(chapter);
            if (!StringUtils.hasText(body)) {
                continue;
            }
            int idx = body.indexOf(word);
            if (idx < 0) {
                continue;
            }
            hits++;
            int start = Math.max(0, idx - 12);
            int end = Math.min(body.length(), idx + word.length() + 12);
            sb.append("第").append(no).append("章：…").append(body, start, end).append("…\n");
            if (sb.length() > MAX_RESULT_CHARS) {
                break;
            }
        }
        log.info("审查工具：邻近检索 novelId={} current={} word={} 命中 {} 章", novelId, currentNo, word, hits);
        if (hits == 0) {
            return noMaterial("在当前章前后各 " + NEARBY_RANGE + " 章里没有找到「" + word + "」（当前章请直接看正文）。");
        }
        return "「" + word + "」在第 " + from + "~" + to + " 章范围内出现在：\n" + truncate(sb.toString());
    }

    /** 正文取值口径与审查一致：存在待审影子正文时取其内容（审查发生在发布前） */
    /**
     * 按语义在本书全部章节中检索相关片段。
     *
     * <p>与 {@link #searchWordNearby} 的分工：后者按词检索，快且准，但要求能想到该词；
     * 本方法按语义检索，想不起具体用词时同样可命中，例如查询「这把伞的骨架是几根」，
     * 而前文写作「伞骨一共九根」，无任何词重合。**这是本方法可覆盖相邻章节之外内容的原因**：
     * 邻近检索仅查看当前章 ±10 章，向量检索可命中任意一章。
     *
     * <p>检索不到时**需如实说明**，不返回空字符串：模型看到空结果会认为「前文未写过」，
     * 因而不上报跨章问题，即把「未查成」表现为「无问题」。
     */
    @Tool(description = "按语义在本书全部章节里检索相关片段。核对「前文对某个东西的设定」时用它"
            + "（尺寸、数量、人名写法、之前发生过什么），尤其是想不起该用哪个词去查的时候。"
            + "结果按相关度从高到低排列，每条都带章号。")
    public String searchRelevantContext(
            @ToolParam(description = "要核对的问题，用自然语言写，例如「那把刀有多长」「伞骨一共几根」")
            String query,
            ToolContext context) {
        Long novelId = novelIdOf(context);
        if (!StringUtils.hasText(query)) {
            return noMaterial("请给出要核对的问题。");
        }
        List<ChapterRetrievalPort.RetrievedChunk> hits;
        try {
            hits = chapterRetrievalPort.searchRelevant(novelId, query, TOP_K);
        } catch (Exception e) {
            // search 内部已将异常兜底为空列表，此处再兜一层以应对将来更换实现
            log.warn("审查工具：语义检索失败 novelId={}", novelId, e);
            hits = List.of();
        }
        if (hits.isEmpty()) {
            return noMaterial("按语义检索没有找到相关片段（这本书可能还没建立检索索引，或者确实没有相关内容）。"
                    + "可以改用 searchWordNearby 按具体的词去查。");
        }
        int currentNo = chapterNoOf(context);
        StringBuilder sb = new StringBuilder("【语义检索结果】按相关度从高到低：\n");
        int shown = 0;
        for (ChapterRetrievalPort.RetrievedChunk hit : hits) {
            if (hit.chapterNo() != null && hit.chapterNo() == currentNo) {
                continue;   // 当前章正文已在 prompt 中，检索结果仅重复占用上下文
            }
            shown++;
            sb.append(shown).append(". 第 ").append(hit.chapterNo()).append(" 章：")
                    .append(hit.text()).append('\n');
            if (sb.length() > MAX_RESULT_CHARS) {
                break;
            }
        }
        if (shown == 0) {
            return noMaterial("检索到的片段都出自本章（本章正文已经在上面了），别处没有找到相关内容。");
        }
        log.info("审查工具：语义检索 novelId={} 命中 {} 条，采用 {} 条", novelId, hits.size(), shown);
        return sb.toString();
    }

    private String bodyOf(Chapter chapter) {
        // 口径统一在 Chapter#currentBody（待审优先），此处不重复判断，索引侧使用同一口径
        return chapter == null ? null : chapter.currentBody();
    }

    /**
     * 作品 id 由调用方写入上下文；不存在时抛出异常。
     *
     * <p>不采用「取不到则视为全书」：工具的数据范围必须显式限定，否则上下文传参一旦出现回归，
     * 模型即会读到不属于该作品的内容。
     */
    private Long novelIdOf(ToolContext context) {
        Object value = context == null || context.getContext() == null
                ? null : context.getContext().get(CTX_NOVEL_ID);
        if (value instanceof Long id) {
            return id;
        }
        throw new IllegalStateException("审查工具缺少作品上下文");
    }

    private int chapterNoOf(ToolContext context) {
        Object value = context == null || context.getContext() == null
                ? null : context.getContext().get(CTX_CHAPTER_NO);
        return value instanceof Integer no ? no : 0;
    }

    /**
     * 一次性取出 {@code [from, to]} 范围内的「章号 → 章节 id」。
     *
     * <p>目录按 {@code chapter_no} 升序返回（{@code ChapterServiceImpl.pageChapterMetaByNovel}），
     * 因此翻到「出现 ≥ to 的章号」即可停止，无需对 400 章的书从头翻到尾。
     *
     * <p>「本页不满即最后一页」在此比较的是**服务端实际返回的条数**，与 findChapterId 口径一致：
     * 服务端会将页大小钳制到 {@link PageParam#MAX_PAGE_SIZE}，若与请求数比较则该条件恒成立。
     */
    private Map<Integer, Long> chapterIdsInRange(Long novelId, int from, int to) {
        Map<Integer, Long> map = new HashMap<>();
        for (int pageNo = 1; pageNo <= MAX_PAGES; pageNo++) {
            PageResult<ChapterVO> result = chapterService.pageChapterMetaByNovel(novelId, pageNo, CHAPTER_PAGE_SIZE);
            List<ChapterVO> list = result.getList();
            if (list == null || list.isEmpty()) {
                break;
            }
            boolean reachedRightEnd = false;
            for (ChapterVO vo : list) {
                Integer no = vo.getChapterNo();
                if (no == null) {
                    continue;
                }
                if (no >= from && no <= to) {
                    map.put(no, vo.getId());
                }
                if (no >= to) {
                    reachedRightEnd = true;
                }
            }
            if (reachedRightEnd || list.size() < CHAPTER_PAGE_SIZE) {
                break;
            }
        }
        return map;
    }

    /** 按章号查找章节 id：目录为分页返回，因此最多翻 MAX_PAGES 页 */
    private Long findChapterId(Long novelId, int chapterNo) {
        for (int page = 1; page <= MAX_PAGES; page++) {
            PageResult<ChapterVO> result = chapterService.pageChapterMetaByNovel(novelId, page, CHAPTER_PAGE_SIZE);
            List<ChapterVO> list = result.getList();
            if (list == null || list.isEmpty()) {
                return null;
            }
            for (ChapterVO vo : list) {
                if (vo.getChapterNo() != null && vo.getChapterNo() == chapterNo) {
                    return vo.getId();
                }
            }
            if (list.size() < CHAPTER_PAGE_SIZE) {
                return null;   // 本页不满即无更多章节
            }
            // 此处比较的是「服务端实际返回的条数」而非请求条数：服务端会将页大小钳制到上限，
            // 若与请求数比较则「本页不满」恒成立（历史上曾出现该缺陷）
        }
        return null;
    }

    private String truncate(String text) {
        if (text == null || text.length() <= MAX_RESULT_CHARS) {
            return text;
        }
        return text.substring(0, MAX_RESULT_CHARS) + "\n（内容过长，已截断）";
    }
}
