package com.ainovel.module.novel.service.impl;

import com.ainovel.common.util.NumberWords;
import com.ainovel.module.novel.dao.NovelFactMapper;
import com.ainovel.module.novel.dao.NovelGlossaryMapper;
import com.ainovel.module.novel.domain.entity.NovelFact;
import com.ainovel.module.novel.domain.entity.NovelGlossary;
import com.ainovel.module.novel.domain.vo.FactConflict;
import com.ainovel.module.novel.domain.vo.GlossaryConflict;
import com.ainovel.module.novel.domain.vo.GlossaryEntry;
import com.ainovel.module.novel.domain.vo.GlossaryFamily;
import com.ainovel.module.novel.service.NovelGlossaryService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 名词表的实现：记录与比对。
 *
 * <p>名字由模型一并给出（读取本章时一并列出成本最低），但候选是否真实出现在正文中、
 * 本章写法与库中写法是否仅差一字，这两项判定均由服务端完成。
 * 模型只提供材料，不作最终结论，跨章一致性因此不依赖模型是否每次执行该动作。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NovelGlossaryServiceImpl implements NovelGlossaryService {

    /** 名字长度范围：1 字冲突率高（"他""你"），7 字以上多为整句 */
    private static final int MIN_NAME_CHARS = 2;
    private static final int MAX_NAME_CHARS = 6;

    /** 单章最多记录的名字数，限制模型单次返回大量条目 */
    private static final int MAX_NAMES_PER_CHAPTER = 12;

    /** 单本书的名字上限，避免长书使该表膨胀 */
    private static final int MAX_NAMES_PER_NOVEL = 2000;

    /**
     * **仅对 3~4 字的名字执行「差一个字」扫描**（2 字不扫，5 字以上也不扫）。
     *
     * <p>该限制来源于 2 字名字过大的碰撞面：库中存在「灯市」时，同一章描写灯笼的
     * 正文里，「灯下」「灯还」「灯芯」「灯笼」「灯纸」均会被判定为与第 4 章的「灯市」
     * 不一致，单章可产生十余条误报。
     * 3 字名字只有在完全同一位置替换一个字时才可能命中，
     * 而「沈青梧 / 沈青悟」这类真实写法错误正落在该范围内。
     *
     * <p>5 字以上不扫：该长度多为功法、法宝名（「九转还魂丹」），差一个字通常是两个不同的名称。
     * 2 字名字仍进表、仍注入提示词供模型核对，仅不参与服务端该硬判定。
     */
    private static final int MIN_VARIANT_NAME_CHARS = 3;
    private static final int MAX_VARIANT_NAME_CHARS = 4;

    /** 单次扫描的字符上限：单章上万字时，比对面积随长度线性增长 */
    private static final int MAX_SCAN_CHARS = 20000;

    /** 片段前后各保留的字符数，使「原句」具备上下文 */
    private static final int EXCERPT_PADDING = 8;

    /** 单章最多记录的设定数字条数（与名字同理，限制模型单次返回大量条目） */
    private static final int MAX_FACTS_PER_CHAPTER = 10;

    /** 单本书的设定数字上限 */
    private static final int MAX_FACTS_PER_NOVEL = 2000;

    /** 数值的长度上限：「二十八岁」为最长情形 */
    private static final int MAX_FACT_VALUE_CHARS = 8;

    /**
     * 名词与数值之间允许的最大字符间隔，超过则不再视为同一实体的数值。
     *
     * <p>「刀身长两尺」间隔 2 字、「七根伞骨」数值在前，10 字足以覆盖正常语序。
     * 间隔更大时不是同一实体（「他今年二十八岁，柳砚秋站在门外」不应被判为「柳砚秋=二十八」）。
     */
    private static final int FACT_GAP = 10;

    /** 事实行的分隔符，支持 = / ＝ / : / ： 四种写法 */
    private static final String FACT_SEPARATORS = "=＝:：";

    /**
     * 服务端扫描数值时，名词与数值之间的最大间隔（含连接字）。
     *
     * <p>该值比模型上报路径（{@link #FACT_GAP} = 10）严格：模型在理解句子之后才给出
     * 「刀 = 三尺」，此处为纯字面扫描，间隔越大越可能将两件事误并为一件
     * （「他握紧那把刀，想起三年前的事」）。中间出现标点时直接放弃。
     */
    private static final int SCAN_VALUE_GAP = 4;

    /** 名词与数值之间允许出现的连接字（「刀**有**三尺长」「**两尺**长**的**刀」） */
    private static final String SCAN_CONNECTORS = "有是为约足共长重高深宽厚直达到近超的之了";

    /**
     * 扫描时名词的长度范围。
     *
     * <p>下限为 **1**，与名字表的 {@link #MIN_NAME_CHARS}（2）不同：名字表拦截 1 字是
     * 因为「他/你/这」这类字冲突率高；而数字核对的名词常为单字
     * （刀、伞、剑、枪、门、井），将其排除后会漏掉「刀有三尺长」这类典型设定句。
     * 误配风险由另外两道判据控制：数值必须紧邻（中间仅允许连接字）且必须带量词。
     */
    private static final int MIN_SCAN_NAME_CHARS = 1;

    /** 扫描时名词的长度上限（比 {@link #MAX_NAME_CHARS} 严格，长词更易引入噪声） */
    private static final int MAX_SCAN_NAME_CHARS = 4;

    /** 单个名词的最大尝试次数（「刀」在武侠文中可出现数十次，无需全部尝试） */
    private static final int MAX_SCAN_ATTEMPTS = 20;

    /**
     * **扫描得到的数值仅接受以下几类量词**：尺寸 / 数量 / 年龄。
     *
     * <p>不含「天/年/月/日/时/分/秒」：该类词最易与名词组合成伪事实，
     * 「沈青梧三天没合眼」会被记为「沈青梧 = 三天」，后续每一章写「五天」时
     * 均会报一次「与前文矛盾」。而设定类数字（刀多长、伞骨几根、几岁）几乎不使用这几个量词。
     * 代价是漏掉「时间跨度」一类，换取误报率下降；本项目中误报的影响大于漏报。
     */
    private static final String SCAN_UNITS =
            "根尺丈寸斤两岁个只条把枚颗块层重步里人位口间座件套匹头尾张片段句字篇回倍成"
                    + "滴粒串束袋箱盒瓶罐枝柄副对双群队伙度级品阶圈环排行组份批笔项";

    /** 数值后缀（「两尺**出头**」「三十**左右**」） */
    private static final String VALUE_SUFFIXES = "出头|左右|有余|来|余|多|许";

    /**
     * 扫描时认可的「数值」形态：**数字 + 量词或后缀**，量词不可省略。
     *
     * <p>量词为必需项：省略后「沈青梧**三**天没合眼」中的「三」可单独匹配，
     * 表中将新增一行「沈青梧 = 三」。要求带量词后，「三天」的「天」不在量词表
     * （见 {@link #SCAN_UNITS}）中，该条不成立。
     */
    private static final Pattern SCAN_VALUE = Pattern.compile(
            "([0-9]+|[零〇一二三四五六七八九十百千万亿两]+)(?:[" + SCAN_UNITS + "]|"
                    + VALUE_SUFFIXES + ")");

    /**
     * 模型上报的数值，整串必须匹配「数字（+量词）（+后缀）」。
     *
     * <p>存在「门板 = 七颗铜钉」这类情况，即数量词之后仍带有名词。该类值进入表后
     * 会被后续每一章作为比对依据，因此在此直接拒绝（原实现仅校验「能否解析出数」，
     * 而 {@link NumberWords#parse} 对「七颗铜钉」同样返回 7）。
     */
    private static final Pattern PURE_VALUE = Pattern.compile(
            "^[0-9零〇一二三四五六七八九十百千万亿两]+[" + SCAN_UNITS + "]?(?:"
                    + VALUE_SUFFIXES + ")?$");

    /** 来源标记：模型审查时通过 `facts` 上报 */
    private static final String SOURCE_MODEL = "model";

    /** 来源标记：服务端以名词在正文中扫描得到 */
    private static final String SOURCE_SCAN = "scan";

    /**
     * 服务端在正文中扫描「名词 = 数值」。
     *
     * <p>「记录」与「比对」两条路径若仅接受模型上报的 `facts`，模型未上报时
     * 该章既不比对、表中也不新增。该路径覆盖度不足：8 章仅积累 2~3 条，
     * 「刀 三尺 / 两尺」在 dev 集漏报的根因即第 1 章未上报「刀 = 三尺」，
     * 第 6 章写「两尺」时无基准可比。
     *
     * <p>扫描较模型上报路径**更保守**，限制由常量约束：间隔不超过 {@link #SCAN_VALUE_GAP} 字
     * 且中间不得有标点（标点是「同一短语内的数值」与「下一句偶然出现的数值」之间最可靠的分界）、
     * 数值必须带量词且量词限定为尺寸/数量/年龄类、名词 2~4 字。
     * 方向为**宁可漏报，不可误报**：每条扫描结果都会成为后续章节的判据。
     *
     * @param candidates 候选名词（表中已有 + 模型上报的本章专名）
     * @return 名词 -> 数值（同一名词仅保留首次出现处）
     */
    private Map<String, ParsedFact> scanFacts(String body, Collection<String> candidates) {
        Map<String, ParsedFact> out = new LinkedHashMap<>();
        if (!StringUtils.hasText(body) || candidates == null || candidates.isEmpty()) {
            return out;
        }
        Set<String> done = new LinkedHashSet<>();
        for (String name : candidates) {
            if (out.size() >= MAX_FACTS_PER_CHAPTER) {
                break;
            }
            if (!StringUtils.hasText(name) || !done.add(name)) {
                continue;
            }
            if (name.length() < MIN_SCAN_NAME_CHARS || name.length() > MAX_SCAN_NAME_CHARS) {
                continue;
            }
            // 需尝试该名词的每一处出现，不能只取首次：
            // c1 的原文中「刀」首次出现在「把刀往背后一送」（其后为「往」），
            // 而带数值的句子在后面（「那把刀有三尺长」），
            // 仅取首次会漏掉该设定（此为提升覆盖度的核心场景）。
            int at = body.indexOf(name);
            int tried = 0;
            while (at >= 0 && tried < MAX_SCAN_ATTEMPTS) {
                String value = valueAfter(body, at + name.length());
                if (value == null) {
                    value = valueBefore(body, at);
                }
                if (value != null) {
                    out.put(name, new ParsedFact(value, at, SOURCE_SCAN));
                    break;
                }
                at = body.indexOf(name, at + 1);
                tried++;
            }
        }
        return out;
    }

    /**
     * 从 {@code pos} 起向后查找**紧邻**的数量短语（「刀有三尺长」「刀身七尺」）。
     *
     * <p>「刀有三尺长」跳过连接字「有」后取「三尺」；
     * 「他握紧那把刀，想起三年前的事」后紧邻逗号，放弃，即所需行为。
     */
    private String valueAfter(String body, int pos) {
        int i = pos;
        while (i < body.length() && i - pos < SCAN_VALUE_GAP
                && SCAN_CONNECTORS.indexOf(body.charAt(i)) >= 0) {
            i++;
        }
        if (i >= body.length() || i - pos >= SCAN_VALUE_GAP) {
            return null;
        }
        Matcher m = SCAN_VALUE.matcher(body).region(i, body.length());
        return m.lookingAt() ? m.group() : null;
    }

    /**
     * 从 {@code pos} 起向前查找紧邻的数量短语（「**两尺**长的刀」这类语序）。
     *
     * <p>中文中两种语序均常见（「刀有三尺长」与「三尺长的刀」），故需双向扫描。
     * dev 集漏报处的原文为后者（「那把两尺长的刀」），仅前向扫描会遗漏。
     */
    private String valueBefore(String body, int pos) {
        int j = pos;
        while (j > 0 && pos - j < SCAN_VALUE_GAP
                && SCAN_CONNECTORS.indexOf(body.charAt(j - 1)) >= 0) {
            j--;
        }
        int from = Math.max(0, j - MAX_FACT_VALUE_CHARS);
        if (from >= j) {
            return null;
        }
        Matcher m = SCAN_VALUE.matcher(body).region(from, j);
        String found = null;
        while (m.find()) {
            if (m.end() == j) {
                found = m.group();   // 循环不 break：要最靠右的那个（紧贴名词）
            }
        }
        return found;
    }

    /**
     * 差异位置上不允许出现的字：均为虚词/介词，专名不会与其「仅差一个字」。
     *
     * <p>去除「本章出现过标准写法即整条跳过」的短路后，为避免「一章内写对五处、
     * 写错一处」的漏报，表中的「旧地图」会与正文中的「搁**在地图**边上」
     * 「**把地图**卷起来」匹配，产生两条误报
     * （原句见 `reports/family1.md`）。
     *
     * <p>真实写法错误的差异字均为实词：沈青梧 / 沈青悟、灯芯 / 灯心、萧 / 肖。
     * 虚词几乎不出现在该位置，故以其为判据，代价是「人名中带虚词字」这类罕见情形会漏报，
     * 相对于大量误报，该代价可接受。
     */
    private static final String FUNCTION_CHARS = "的地得了着过在把被让使向从对与和就也还而之此这那";

    /** 候选仅接受纯文字：含数字/标点/空白均视为噪声 */
    private static final Pattern NAME_OK = Pattern.compile("^[\\p{IsHan}A-Za-z]+$");

    private final NovelGlossaryMapper mapper;

    private final NovelFactMapper factMapper;

    @Override
    public List<GlossaryEntry> listEntries(Long novelId) {
        if (novelId == null) {
            return List.of();
        }
        return selectAll(novelId).stream()
                .map(g -> new GlossaryEntry(g.getName(), g.getFirstChapterNo(), g.getHitCount()))
                .toList();
    }

    @Override
    public int recordNames(Long novelId, Long chapterId, Integer chapterNo, String body,
                           Collection<String> candidates) {
        if (novelId == null || candidates == null || candidates.isEmpty() || !StringUtils.hasText(body)) {
            return 0;
        }
        Set<String> existing = new LinkedHashSet<>();
        selectAll(novelId).forEach(g -> existing.add(g.getName()));
        if (existing.size() >= MAX_NAMES_PER_NOVEL) {
            log.info("名词表：本书已记满 {} 条，不再新增（novelId={}）", MAX_NAMES_PER_NOVEL, novelId);
            return 0;
        }

        int recorded = 0;
        // 预先去重：同一名字在一次调用中出现两次（模型列表重复）不应记为两次命中
        for (String raw : new LinkedHashSet<>(candidates)) {
            if (recorded >= MAX_NAMES_PER_CHAPTER) {
                break;
            }
            String name = clean(raw);
            if (name == null) {
                continue;
            }
            // 模型给出的名字必须原样出现在正文中，与审查结果的反幻觉过滤同一口径；
            // 编造的名字一旦进入表，后续数十章都会以其为依据
            if (!body.contains(name)) {
                continue;
            }
            if (existing.contains(name)) {
                // 已记录：仅累加次数，不修改首次章号（首次章号是作者侧依据，不可覆盖）
                mapper.touch(novelId, name);
                recorded++;
                continue;
            }
            NovelGlossary row = new NovelGlossary();
            row.setNovelId(novelId);
            row.setName(name);
            row.setFirstChapterId(chapterId);
            row.setFirstChapterNo(chapterNo);
            row.setHitCount(1);
            try {
                mapper.insert(row);
            } catch (DuplicateKeyException e) {
                // 并发章节记录同一名字：由唯一索引兜底，此处回退为累加
                mapper.touch(novelId, name);
            }
            existing.add(name);
            recorded++;
        }
        return recorded;
    }

    // ==================== 设定数字（名词 = 数值） ====================

    @Override
    public List<FactConflict> detectFactConflicts(Long novelId, String body, Collection<String> facts) {
        if (novelId == null || !StringUtils.hasText(body)) {
            return List.of();
        }
        List<NovelFact> known = selectFacts(novelId);
        if (known.isEmpty()) {
            // 本书尚未记录任何设定数字，无基准，此时上报即为误报
            return List.of();
        }
        Map<String, ParsedFact> mine = parseFacts(body, facts);
        // 增加一路：以**表中已有的名词**在本章正文中扫描其数值。
        // 该路径不依赖模型本章是否上报：表中记录过「刀 = 三尺」后，后续每一章出现「刀」时
        // 均可自行取出本章数值进行比对（原实现中模型未上报即整章不比对，漏报均源于此）。
        scanFacts(body, known.stream().map(NovelFact::getName).toList()).forEach(mine::putIfAbsent);
        if (mine.isEmpty()) {
            return List.of();
        }
        Map<String, NovelFact> firstBy = firstByName(known);

        List<FactConflict> conflicts = new ArrayList<>();
        for (Map.Entry<String, ParsedFact> e : mine.entrySet()) {
            String name = e.getKey();
            ParsedFact now = e.getValue();
            NovelFact standard = firstBy.get(name);
            if (standard == null) {
                continue;   // 该名词前文未记录，无基准可比
            }
            // 「七根」与「7根」为同一数值：字面不同但归一后相等，不判为矛盾（直接比较字符串会误报）
            if (NumberWords.sameNumber(standard.getFactValue(), now.value())) {
                continue;
            }
            int from = Math.max(0, now.at() - EXCERPT_PADDING);
            int to = Math.min(body.length(), now.at() + name.length() + EXCERPT_PADDING);
            String excerpt = body.substring(from, to).trim();
            conflicts.add(new FactConflict(name, standard.getFactValue(), standard.getFirstChapterNo(),
                    now.value(), excerpt));
            log.info("名词表：本章「{}={}」与前文第 {} 章的「{}={}」不一致 chapter 片段={}",
                    name, now.value(), standard.getFirstChapterNo(), name, standard.getFactValue(), excerpt);
        }
        return conflicts;
    }

    @Override
    public int recordFacts(Long novelId, Long chapterId, Integer chapterNo, String body,
                           Collection<String> facts, Collection<String> names) {
        if (novelId == null || !StringUtils.hasText(body)) {
            return 0;
        }
        List<NovelFact> known = selectFacts(novelId);
        Map<String, NovelFact> firstBy = firstByName(known);
        Map<String, ParsedFact> parsed = parseFacts(body, facts);
        // 兜底路径：以「表中已有的名词 + 本章模型上报的专名」在本章中扫描数值。
        // 需带上本章专名的原因：模型上报 `facts` 的比例低（8 章仅积累 2~3 条），
        // 但上报本章专有名词（`names`）是提示词的硬性要求，覆盖率更高，
        // 因此「伞骨一共七根」这类句子由服务端自行抽取，无需等待模型按格式上报。
        List<String> scanCandidates = new ArrayList<>(names == null ? List.of() : names);
        known.forEach(f -> scanCandidates.add(f.getName()));
        scanFacts(body, scanCandidates).forEach(parsed::putIfAbsent);
        if (parsed.isEmpty()) {
            return 0;
        }

        int recorded = 0;
        for (Map.Entry<String, ParsedFact> e : parsed.entrySet()) {
            String name = e.getKey();
            String value = e.getValue().value();
            NovelFact same = firstBy.get(name);
            if (same == null) {
                if (known.size() + recorded >= MAX_FACTS_PER_NOVEL) {
                    log.info("名词表：本书设定数字已记满 {} 条，不再新增（novelId={}）",
                            MAX_FACTS_PER_NOVEL, novelId);
                    break;
                }
                NovelFact row = new NovelFact();
                row.setNovelId(novelId);
                row.setName(name);
                row.setFactValue(value);
                row.setSource(e.getValue().source());
                row.setFirstChapterId(chapterId);
                row.setFirstChapterNo(chapterNo);
                row.setHitCount(1);
                try {
                    factMapper.insert(row);
                } catch (DuplicateKeyException ex) {
                    // 并发章节记录同一条：由唯一索引兜底
                    factMapper.touch(novelId, name, value);
                }
                firstBy.put(name, row);
                recorded++;
            } else if (NumberWords.sameNumber(same.getFactValue(), value)) {
                factMapper.touch(novelId, name, same.getFactValue());
                recorded++;
            }
            // 同一实体但本章为另一数值：**不修改表中的行**，
            // 首次出现的值继续作为基准（以先出现者为准），矛盾已上报给作者。
        }
        return recorded;
    }

    private List<NovelFact> selectFacts(Long novelId) {
        return factMapper.selectList(new QueryWrapper<NovelFact>()
                .eq("novel_id", novelId)
                .orderByAsc("first_chapter_no", "id"));
    }

    /** 同一实体可能存在多行（两种数值各一行）：取首次出现最早的一行作为基准 */
    private Map<String, NovelFact> firstByName(List<NovelFact> facts) {
        Map<String, NovelFact> first = new LinkedHashMap<>();
        facts.forEach(f -> first.putIfAbsent(f.getName(), f));
        return first;
    }

    /**
     * 解析并校验模型给出的设定数字。
     *
     * <p>以下三道校验均为必需：**表中的错误数据比没有数据影响更大**，
     * 编造的数值进入表后，后续每一章都会以其为依据上报作者的「错误」。
     * <ol>
     *   <li>名词必须真实出现在正文中（与名字入库同一口径）；</li>
     *   <li>数值也必须真实出现在正文中，且**与名词距离不超过** {@link #FACT_GAP}，
     *       不校验距离时「他今年二十八岁，柳砚秋站在门外」会被读作「柳砚秋=二十八」；</li>
     *   <li>数值本身必须可解析出数（拦截「伞骨=铜的」这类模型笔误）。</li>
     * </ol>
     *
     * @return 名词 ->（数值, 名词在正文中的位置），同一名词仅保留第一条
     */
    private Map<String, ParsedFact> parseFacts(String body, Collection<String> facts) {
        Map<String, ParsedFact> out = new LinkedHashMap<>();
        if (facts == null || facts.isEmpty()) {
            return out;
        }
        for (String raw : facts) {
            if (out.size() >= MAX_FACTS_PER_CHAPTER) {
                break;
            }
            if (!StringUtils.hasText(raw)) {
                continue;
            }
            int sep = firstSeparator(raw);
            if (sep <= 0 || sep == raw.length() - 1) {
                continue;   // 没有分隔符 / 左边为空 / 右边为空
            }
            String name = clean(raw.substring(0, sep));
            String value = raw.substring(sep + 1).trim();
            if (name == null || value.isEmpty() || value.length() > MAX_FACT_VALUE_CHARS) {
                continue;
            }
            if (NumberWords.parse(value) == null || !PURE_VALUE.matcher(value).matches()) {
                continue;   // 无法解析出数、或「七颗铜钉」这类带名词的，均不接收
            }
            int nameAt = body.indexOf(name);
            if (nameAt < 0) {
                continue;
            }
            int valueAt = body.indexOf(value);
            if (valueAt < 0 || Math.abs(valueAt - nameAt) > FACT_GAP) {
                continue;
            }
            out.putIfAbsent(name, new ParsedFact(value, nameAt, SOURCE_MODEL));
        }
        return out;
    }

    private int firstSeparator(String raw) {
        for (int i = 0; i < raw.length(); i++) {
            if (FACT_SEPARATORS.indexOf(raw.charAt(i)) >= 0) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 解析得到的一条设定数字。
     *
     * @param value  正文中原样出现的数值（含量词）
     * @param at     名词在正文中的位置（用于截取报告片段）
     * @param source 来源：{@code model}=模型上报，{@code scan}=服务端扫描
     */
    private record ParsedFact(String value, int at, String source) {
    }

    @Override
    public List<GlossaryFamily> listFamilies(Long novelId) {
        if (novelId == null) {
            return List.of();
        }
        return buildFamilies(listEntries(novelId));
    }

    @Override
    public List<GlossaryConflict> detectConflicts(Long novelId, String body) {
        if (novelId == null || !StringUtils.hasText(body)) {
            return List.of();
        }
        List<GlossaryEntry> entries = listEntries(novelId);
        if (entries.isEmpty()) {
            // 本书尚未审查任何一章，无可比对基准，此时上报即为误报
            return List.of();
        }
        Set<String> known = new LinkedHashSet<>();
        entries.forEach(e -> known.add(e.name()));

        String text = body.length() > MAX_SCAN_CHARS ? body.substring(0, MAX_SCAN_CHARS) : body;
        // 变体 -> 命中信息，保持发现顺序，同一变体只留一条
        Map<String, Hit> hits = new LinkedHashMap<>();

        for (GlossaryFamily family : buildFamilies(entries)) {
            GlossaryEntry preferred = family.preferred();

            // ① 本书两种写法均已使用，本章使用了**非首选**写法。
            //    原实现中该路径不可达：两种写法都在表中时，「表中已有该写法」的跳过条件会将其全部拦截，
            //    导致书内存在两种写法却不报（漏报）。现归入族中由此处处理。
            for (GlossaryEntry member : family.members()) {
                if (member == preferred) {
                    continue;
                }
                int at = text.indexOf(member.name());
                if (at < 0) {
                    continue;
                }
                hits.putIfAbsent(member.name(),
                        new Hit(preferred, at, countOccurrences(text, member.name()), family));
            }

            // ② 本章出现与首选仅差一个字的写法（表中无该写法）⇒ 疑似写错。
            //
            //    变体与名字为「同一位置仅差一字」，即除该位外完全相同，
            //    因此：差异在**首位**时第 2 个字必然相同，差异**不在首位**时首字必然相同。
            //    以这两个位置为锚点索引可保证覆盖，成本远低于逐字滑窗
            //    （单本书名字可达上千条，逐字滑动一次为「章长 × 名字数」量级）。
            //    **不能仅以首字为锚点**：「萧战天 / 肖战天」这类首字即写错的情况依赖此方法检出，
            //    该场景曾由单测拦截。
            //
            //    此处**不可**再以「本章出现过首选写法即整条跳过」简化：
            //    否则一章内写对五处、写错一处时，该处无法上报。
            String name = preferred.name();
            int len = name.length();
            for (int seed = 0; seed < 2; seed++) {
                char anchor = name.charAt(seed);
                for (int at = text.indexOf(anchor); at >= 0; at = text.indexOf(anchor, at + 1)) {
                    int start = at - seed;   // 锚点是第 seed 个字符，回退到名字的起点
                    if (start < 0 || start + len > text.length()) {
                        continue;
                    }
                    int diff = diffPosition(name, text, start);
                    if (diff < 0) {
                        continue;
                    }
                    // 差异位置为虚词 ⇒ 非「同一专名写错」，而是两个不同的普通词（见 FUNCTION_CHARS）
                    if (isFunctionChar(name.charAt(diff)) || isFunctionChar(text.charAt(start + diff))) {
                        continue;
                    }
                    String variant = text.substring(start, start + len);
                    // 表中也存在该写法 ⇒ 由路径 ① 处理，此处跳过（否则同一变体会被上报两次）
                    if (known.contains(variant)) {
                        continue;
                    }
                    hits.putIfAbsent(variant, new Hit(preferred, start, countOccurrences(text, variant), family));
                }
            }
        }
        if (hits.isEmpty()) {
            return List.of();
        }

        List<GlossaryConflict> conflicts = new ArrayList<>();
        for (Map.Entry<String, Hit> e : hits.entrySet()) {
            String variant = e.getKey();
            Hit hit = e.getValue();
            int from = Math.max(0, hit.at() - EXCERPT_PADDING);
            int to = Math.min(body.length(), hit.at() + variant.length() + EXCERPT_PADDING);
            String excerpt = body.substring(from, to).trim();
            GlossaryEntry preferred = hit.entry();
            List<String> siblings = hit.family().otherSpellings();
            conflicts.add(new GlossaryConflict(preferred.name(), preferred.firstChapterNo(), variant,
                    excerpt, hit.occurrences(), preferred.hitCount() == null ? 0 : preferred.hitCount(),
                    siblings));
            if (hit.family().disputed()) {
                log.info("名词表：本章「{}」与本书另一种写法「{}」不一致（本章 {} 处）chapter 片段={}",
                        variant, String.join("／", siblings), hit.occurrences(), excerpt);
            } else {
                log.info("名词表：本章「{}」与第 {} 章的「{}」写法不一致（本章 {} 处）chapter 片段={}",
                        variant, preferred.firstChapterNo(), preferred.name(), hit.occurrences(), excerpt);
            }
        }
        return conflicts;
    }

    /**
     * 按「长度相同、同一位置仅差一字」将条目并成族（并查集，允许链式传递：A~B、B~C 归为一族）。
     *
     * <p>仅处理 3~4 字的名字，与扫描范围一致：2 字词的族无意义，
     * 「灯市」会与「灯下」「灯还」「灯芯」「灯笼」连成一大族，其性质并非「两种写法」而是同音字集合。
     */
    private List<GlossaryFamily> buildFamilies(List<GlossaryEntry> entries) {
        List<GlossaryEntry> candidates = entries.stream()
                .filter(e -> e.name() != null
                        && e.name().length() >= MIN_VARIANT_NAME_CHARS
                        && e.name().length() <= MAX_VARIANT_NAME_CHARS)
                .toList();
        int n = candidates.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
        }
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (sameLengthOneCharApart(candidates.get(i).name(), candidates.get(j).name())) {
                    union(parent, i, j);
                }
            }
        }
        // 使用 LinkedHashMap 保序：族的顺序等于族内首条在表中的顺序（即首次章号升序）
        Map<Integer, List<GlossaryEntry>> groups = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            groups.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(candidates.get(i));
        }
        return groups.values().stream().map(GlossaryFamily::new).toList();
    }

    private int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];   // 路径减半：压缩链，减少后续查找深度
            i = parent[i];
        }
        return i;
    }

    private void union(int[] parent, int i, int j) {
        int ri = find(parent, i);
        int rj = find(parent, j);
        if (ri != rj) {
            parent[rj] = ri;
        }
    }

    /** 判断两个写法是否「长度相同、同一位置仅差一字」（且替换前后均为文字） */
    private boolean sameLengthOneCharApart(String a, String b) {
        return a.length() == b.length() && diffPosition(a, b, 0) >= 0;
    }

    /** 单次查询读取全表（单本书名字最多数千条，优于逐条查询） */
    private List<NovelGlossary> selectAll(Long novelId) {
        return mapper.selectList(new QueryWrapper<NovelGlossary>()
                .eq("novel_id", novelId)
                .orderByAsc("first_chapter_no", "id"));
    }

    /**
     * 判断两个写法是否「长度相同、同一位置仅差一字」（且替换前后均为文字）。
     *
     * <p>不处理增删（增删导致长度不同，更可能是另一个名字），
     * 且要求替换前后两个字均为文字，否则「沈青，梧」这类被标点隔开的写法也会被判为变体。
     *
     * @return 差异位置；不满足（长度不同 / 差异为零个或多于一个 / 差异处非文字）返回 -1
     */
    private int diffPosition(String name, String text, int at) {
        int diff = -1;
        for (int k = 0; k < name.length(); k++) {
            char a = name.charAt(k);
            char b = text.charAt(at + k);
            if (a == b) {
                continue;
            }
            if (!Character.isLetter(a) || !Character.isLetter(b) || diff >= 0) {
                return -1;
            }
            diff = k;
        }
        return diff;
    }

    private boolean isFunctionChar(char c) {
        return FUNCTION_CHARS.indexOf(c) >= 0;
    }

    private int countOccurrences(String text, String word) {
        int count = 0;
        for (int i = text.indexOf(word); i >= 0; i = text.indexOf(word, i + word.length())) {
            count++;
        }
        return count;
    }

    /** 候选清洗：去除空白后必须为 2~6 个纯文字。不合格返回 null（模型输入噪声较多，不作异常处理） */
    private String clean(String raw) {
        if (raw == null) {
            return null;
        }
        String name = raw.replaceAll("\\s+", "");
        if (name.length() < MIN_NAME_CHARS || name.length() > MAX_NAME_CHARS || !NAME_OK.matcher(name).matches()) {
            return null;
        }
        return name;
    }

    /** 单次命中的中间结果（含族：生成报告时用于判断「两种写法并存」或「本章写错」） */
    private record Hit(GlossaryEntry entry, int at, int occurrences, GlossaryFamily family) {
    }
}
