package com.ainovel.module.novel.service.impl;

import com.ainovel.module.novel.dao.NovelFactMapper;
import com.ainovel.module.novel.dao.NovelGlossaryMapper;
import com.ainovel.module.novel.domain.entity.NovelFact;
import com.ainovel.module.novel.domain.entity.NovelGlossary;
import com.ainovel.module.novel.domain.vo.FactConflict;
import com.ainovel.module.novel.domain.vo.GlossaryConflict;
import com.ainovel.module.novel.domain.vo.GlossaryEntry;
import com.ainovel.module.novel.domain.vo.GlossaryFamily;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 名词表的守门测试。
 *
 * <p>该表是「跨章一致性不依赖模型自觉」的基础，因此两件事必须约束：
 * <ol>
 *   <li>入库的候选必须确实出现在正文中：模型编造一个名字进来，后面几十章都会以它为依据；</li>
 *   <li>比对只认「同长度、同位置仅差一个字」，且表中已有的另一种写法不算矛盾。
 *       该判据放宽一点就会产生大量误报，而误报对作者的干扰大于漏报。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class NovelGlossaryServiceImplTest {

    @Mock
    private NovelGlossaryMapper mapper;

    @Mock
    private NovelFactMapper factMapper;

    private NovelGlossaryServiceImpl service;

    @BeforeEach
    void init() {
        service = new NovelGlossaryServiceImpl(mapper, factMapper);
    }

    private NovelGlossary row(String name, Integer firstNo) {
        NovelGlossary g = new NovelGlossary();
        g.setNovelId(9L);
        g.setName(name);
        g.setFirstChapterNo(firstNo);
        g.setHitCount(1);
        return g;
    }

    private void givenTable(NovelGlossary... rows) {
        when(mapper.selectList(any())).thenReturn(List.of(rows));
    }

    // ==================== 记录 ====================

    @Test
    @DisplayName("候选必须在本章正文里原样出现，按长度过滤，名单里重复的只算一次")
    void recordNames_filtersAndInserts() {
        givenTable();
        String body = "沈青梧推开木窗。沈青梧看见灯芯爆了一下。";
        int n = service.recordNames(9L, 100L, 3, body,
                List.of("沈青梧", "沈青梧", "黑风寨", "梧", "沈青，梧", "沈青梧他"));

        assertEquals(1, n, "只有「沈青梧」该被记下：其余要么编造、要么长度/字符不合格");
        ArgumentCaptor<NovelGlossary> captor = ArgumentCaptor.forClass(NovelGlossary.class);
        verify(mapper).insert(captor.capture());
        assertEquals("沈青梧", captor.getValue().getName());
        assertEquals(3, captor.getValue().getFirstChapterNo(), "首次出现的章号要记下来当依据");
        assertEquals(100L, captor.getValue().getFirstChapterId());
    }

    @Test
    @DisplayName("名字已在表里 ⇒ 只累加次数，**不动首次章号**（它是给作者的依据）")
    void recordNames_existingTouchesOnly() {
        givenTable(row("沈青梧", 1));
        int n = service.recordNames(9L, 100L, 8, "沈青梧站在门外。", List.of("沈青梧"));

        assertEquals(1, n);
        verify(mapper, never()).insert(any(NovelGlossary.class));
        verify(mapper).touch(9L, "沈青梧");
    }

    @Test
    @DisplayName("并发记同一个名字撞唯一键 ⇒ 退回累加，不把异常抛给调用方")
    void recordNames_duplicateKeyFallsBackToTouch() {
        givenTable();
        when(mapper.insert(any(NovelGlossary.class))).thenThrow(new DuplicateKeyException("uk_novel_name"));

        int n = service.recordNames(9L, 100L, 2, "柳砚秋到了镇上。", List.of("柳砚秋"));

        assertEquals(1, n);
        verify(mapper).touch(9L, "柳砚秋");
    }

    @Test
    @DisplayName("表已记满 ⇒ 不再新增（防长书把表撑爆），但也不报错")
    void recordNames_stopsAtNovelLimit() {
        NovelGlossary[] many = new NovelGlossary[2000];
        for (int i = 0; i < 2000; i++) {
            many[i] = row("名字" + i, i);
        }
        givenTable(many);

        assertEquals(0, service.recordNames(9L, 100L, 5, "沈青梧站在门外。", List.of("沈青梧")));
        verify(mapper, never()).insert(any(NovelGlossary.class));
    }

    // ==================== 比对 ====================

    @Test
    @DisplayName("本章写成「沈青悟」⇒ 报一条，片段带上下文，出现几处也带上")
    void detectConflicts_hitsVariant() {
        givenTable(row("沈青梧", 1));
        String body = "沈青悟把刀放在桌上。屋里很静。\n\n沈青悟没有说话，把地图卷起来。";

        List<GlossaryConflict> conflicts = service.detectConflicts(9L, body);

        assertEquals(1, conflicts.size());
        GlossaryConflict c = conflicts.get(0);
        assertEquals("沈青梧", c.name());
        assertEquals(1, c.firstChapterNo());
        assertEquals("沈青悟", c.variant());
        assertEquals(2, c.occurrences(), "本章两处都写成沈青悟，只在第一条里说明");
        assertTrue(body.contains(c.excerpt()), "片段必须来自原文，反幻觉核对才过得去：" + c.excerpt());
    }

    @Test
    @DisplayName("本章写的就是标准写法 ⇒ 不报")
    void detectConflicts_standardSpellingIsFine() {
        givenTable(row("沈青梧", 1));
        assertEquals(0, service.detectConflicts(9L, "沈青梧推开木窗。").size());
    }

    @Test
    @DisplayName("两种写法都进了表 ⇒ 报一条「两种写法并存」，但不说哪种对（siblings 非空、语气中性）")
    void detectConflicts_bothKnownReportedAsDisputed() {
        givenTable(row("沈青梧", 1), row("沈青悟", 4));

        List<GlossaryConflict> conflicts = service.detectConflicts(9L, "沈青悟把刀放在桌上。");

        assertEquals(1, conflicts.size(), "两种写法都在表里，这件事本身就该提醒作者 —— "
                + "以前这条会被「表里已有这个写法」全挡掉，书里明明两种写法却永远不报");
        assertEquals("沈青梧", conflicts.get(0).name(), "首选取先出现的那条");
        assertEquals("沈青悟", conflicts.get(0).variant());
        assertFalse(conflicts.get(0).siblings().isEmpty(),
                "siblings 非空 ⇒ 调用方据此用中性语气，不能断言「应改成 X」");
    }

    @Test
    @DisplayName("族里两种写法，本章用的是先出现的那种 ⇒ 不报")
    void detectConflicts_disputedButUsesPreferred() {
        givenTable(row("沈青梧", 1), row("沈青悟", 4));
        assertEquals(0, service.detectConflicts(9L, "沈青梧把刀放在桌上。").size());
    }

    @Test
    @DisplayName("一章里写对五处、写错一处 ⇒ 那一处照样要报（曾经的漏报：出现过标准写法就整条跳过）")
    void detectConflicts_reportsVariantEvenIfStandardAlsoAppears() {
        givenTable(row("沈青梧", 1), row("黑风寨", 2));
        String body = "沈青梧推开木窗。沈青梧看见灯芯爆了一下。沈青梧没有说话，沈青梧在等。"
                + "沈青梧终于回头，沈青悟问他去哪。";

        List<GlossaryConflict> conflicts = service.detectConflicts(9L, body);

        assertEquals(1, conflicts.size(),
                "写对五处、写错一处：写错的那处不能因为「本章出现过标准写法」被跳过");
        assertEquals("沈青悟", conflicts.get(0).variant());
    }

    @Test
    @DisplayName("首字就写错的变体也要捞出来（「萧战天 / 肖战天」—— 只按首字锚定会漏掉）")
    void detectConflicts_firstCharDiffers() {
        givenTable(row("萧战天", 2));
        List<GlossaryConflict> conflicts = service.detectConflicts(9L, "肖战天站在山门外。");
        assertEquals(1, conflicts.size(), "首字锚点只够覆盖「差异不在首位」的情况，这条是另一半");
        assertEquals("肖战天", conflicts.get(0).variant());
    }

    @Test
    @DisplayName("分组：只差一个字的条目归成一族，族里首选是先出现的那条")
    void listFamilies_groupsSpellingsDifferingByOneChar() {
        givenTable(row("沈青梧", 1), row("黑风寨", 2), row("沈青悟", 4), row("沈青吾", 6));

        List<GlossaryFamily> families = service.listFamilies(9L);

        assertEquals(2, families.size(), "沈青梧/沈青悟/沈青吾 一族（链式传递），黑风寨 单独一族");
        GlossaryFamily disputed = families.get(0);
        assertTrue(disputed.disputed());
        assertEquals("沈青梧", disputed.preferred().name(), "首选 = 最早出现的写法");
        assertEquals(2, disputed.otherSpellings().size());
        assertTrue(disputed.otherSpellings().get(0).contains("沈青悟"), "展示串要带首次章号："
                + disputed.otherSpellings());
        assertFalse(families.get(1).disputed(), "黑风寨没有同族的写法");
    }

    @Test
    @DisplayName("分组不碰 2 字名字（「灯市」会跟「灯下/灯还/灯芯/灯笼」连成一大族，那不是两种写法）")
    void listFamilies_ignoresTwoCharNames() {
        givenTable(row("灯市", 1), row("灯笼", 3), row("灯芯", 4));
        assertTrue(service.listFamilies(9L).stream().noneMatch(GlossaryFamily::disputed),
                "2 字词不进族：分出来的「族」只是同音字池");
    }

    @Test
    @DisplayName("差两个字 ⇒ 不报（宁可漏，不可误报）")
    void detectConflicts_twoCharsApart() {
        givenTable(row("沈青梧", 1));
        // 沈木木 与 沈青梧 有两处不同：这更像另一个人，而不是同一处写错
        assertEquals(0, service.detectConflicts(9L, "沈木木把刀放在桌上。").size());
    }

    @Test
    @DisplayName("被标点隔开的写法不算变体（「沈青，梧」不是名字写错）")
    void detectConflicts_punctuationBreaksMatch() {
        givenTable(row("沈青梧", 1));
        assertEquals(0, service.detectConflicts(9L, "沈青，梧推开木窗。").size());
    }

    @Test
    @DisplayName("表为空 ⇒ 不报：这本书还没审过任何一章，没有可比对的基准")
    void detectConflicts_emptyTable() {
        givenTable();
        assertEquals(0, service.detectConflicts(9L, "沈青悟把刀放在桌上。").size());
    }

    @Test
    @DisplayName("2 个字的名字不参与扫描 —— 实测踩过：表里有「灯市」，一章写灯笼的正文里「灯下/灯还/灯芯/灯笼」全被判成不一致，十几条全是误报")
    void detectConflicts_twoCharNameSkipped() {
        givenTable(row("灯市", 4));
        String body = "灯挂在檐下，灯芯爆了一下。灯笼是旧的，灯纸发黄。那年冬天，巷口的灯还没装上。";
        assertEquals(0, service.detectConflicts(9L, body).size());
    }

    @Test
    @DisplayName("3 个字的名字照样能命中（真名基本都在这个长度：沈青梧/沈青悟、萧战天/肖战天）")
    void detectConflicts_threeCharNameStillWorks() {
        givenTable(row("萧战天", 2));
        assertEquals(1, service.detectConflicts(9L, "肖战天站在山门外。").size());
    }

    @Test
    @DisplayName("5 个字以上的名字不参与扫描（长名字差一个字往往是两样东西）")
    void detectConflicts_longNameSkipped() {
        givenTable(row("九转还魂丹", 2));
        assertEquals(0, service.detectConflicts(9L, "他拿出九转还魂单。").size());
    }

    @Test
    @DisplayName("一个变体对多个候选名字时只报一次（否则清单里会出现两条同样的句子）")
    void detectConflicts_oneVariantReportedOnce() {
        givenTable(row("沈青梧", 1), row("沈青悟", 4), row("沈青吾", 6));
        List<GlossaryConflict> conflicts = service.detectConflicts(9L, "沈青悟把刀放在桌上。");
        // 这一族里有三种写法：本章用的「沈青悟」由族内那条路报一次，
        // 不该再被「沈青梧 的变体」和「沈青吾 的变体」各报一次
        assertEquals(1, conflicts.size());
        assertEquals("沈青悟", conflicts.get(0).variant());
    }

    @Test
    @DisplayName("查表时字段映射不丢：名字、首次章号、出现次数都要带出去")
    void listEntries_mapsAllFields() {
        NovelGlossary g = row("张三", 7);
        g.setHitCount(5);
        givenTable(g);

        List<GlossaryEntry> entries = service.listEntries(9L);

        assertEquals(1, entries.size());
        assertEquals("张三", entries.get(0).name());
        assertEquals(7, entries.get(0).firstChapterNo());
        assertEquals(5, entries.get(0).hitCount());
    }

    @Test
    @DisplayName("差异位置是虚词 ⇒ 不报：表里「旧地图」，正文「把地图卷起来」不是把「旧」写错了")
    void detectConflicts_functionWordDifferenceSkipped() {
        givenTable(row("旧地图", 6));
        // 该用例覆盖「一章里五处正确、一处写错」这一漏报修复后暴露出的误报（改名前后各一份原句）
        assertEquals(0, service.detectConflicts(9L, "他把地图卷起来，用一根麻绳捆好。").size());
        assertEquals(0, service.detectConflicts(9L, "把两尺长的刀就搁在地图边上。").size());
    }

    // ==================== 设定数字 ====================

    private NovelFact factRow(String name, String value, Integer firstNo) {
        NovelFact f = new NovelFact();
        f.setNovelId(9L);
        f.setName(name);
        f.setFactValue(value);
        f.setFirstChapterNo(firstNo);
        f.setHitCount(1);
        return f;
    }

    private void givenFacts(NovelFact... rows) {
        when(factMapper.selectList(any())).thenReturn(List.of(rows));
    }

    @Test
    @DisplayName("前文「七根」本章「九根」⇒ 报一条，两个数值都照正文原样带出去")
    void detectFactConflicts_reportsDifferentNumber() {
        givenFacts(factRow("伞骨", "七根", 2));
        String body = "他撑开那把九根伞骨的伞。";

        List<FactConflict> conflicts = service.detectFactConflicts(9L, body, List.of("伞骨=九根"));

        assertEquals(1, conflicts.size());
        FactConflict c = conflicts.get(0);
        assertEquals("伞骨", c.name());
        assertEquals("七根", c.expected());
        assertEquals("九根", c.actual());
        assertEquals(2, c.firstChapterNo());
        assertTrue(body.contains(c.excerpt()), "片段必须来自原文：建议里要照原样写回去");
    }

    @Test
    @DisplayName("「七根」与「7根」是同一个数 ⇒ 不报（直接比字符串就会误报）")
    void detectFactConflicts_sameNumberDifferentSpelling() {
        givenFacts(factRow("伞骨", "七根", 2));
        assertEquals(0, service.detectFactConflicts(9L, "他撑开那把 7根伞骨的伞。", List.of("伞骨=7根")).size());
    }

    @Test
    @DisplayName("名词与数值离得太远 ⇒ 候选丢掉：别把「他今年二十八岁…柳砚秋站在门外」读成「柳砚秋=二十八」")
    void detectFactConflicts_farApartCandidateDropped() {
        // 故意不 stub 表：候选在「校验」这一层就该被丢掉，压根走不到比对
        String body = "他今年二十八岁。风从巷口灌进来，把檐下的灯笼吹得直晃。柳砚秋站在门外。";
        assertEquals(0, service.detectFactConflicts(9L, body, List.of("柳砚秋=二十八")).size());
    }

    @Test
    @DisplayName("数值那侧不是数字 ⇒ 候选丢掉（模型偶尔会写「伞骨=铜的」）")
    void detectFactConflicts_nonNumericValueDropped() {
        assertEquals(0, service.detectFactConflicts(9L, "他撑开那把伞骨是铜的伞。", List.of("伞骨=铜的")).size());
    }

    @Test
    @DisplayName("表里没有就新增，先出现的章号要记下来")
    void recordFacts_insertsNew() {
        givenFacts();
        int n = service.recordFacts(9L, 300L, 5, "他撑开那把九根伞骨的伞。", List.of("伞骨=九根"), List.of());

        assertEquals(1, n);
        ArgumentCaptor<NovelFact> captor = ArgumentCaptor.forClass(NovelFact.class);
        verify(factMapper).insert(captor.capture());
        assertEquals("伞骨", captor.getValue().getName());
        assertEquals("九根", captor.getValue().getFactValue());
        assertEquals(5, captor.getValue().getFirstChapterNo());
    }

    @Test
    @DisplayName("表里已有同一个数（写法不同）⇒ 只累加，不新增")
    void recordFacts_sameNumberTouches() {
        givenFacts(factRow("伞骨", "七根", 2));
        int n = service.recordFacts(9L, 300L, 5, "他撑开那把 7根伞骨的伞。", List.of("伞骨=7根"), List.of());

        assertEquals(1, n);
        verify(factMapper, never()).insert(any(NovelFact.class));
        verify(factMapper).touch(9L, "伞骨", "七根");
    }

    @Test
    @DisplayName("同一个东西、本章写的是另一个数 ⇒ **不动表里的行**（首次出现的继续当依据，矛盾已经报过了）")
    void recordFacts_differentNumberKeepsFirst() {
        givenFacts(factRow("伞骨", "七根", 2));
        int n = service.recordFacts(9L, 300L, 5, "他撑开那把九根伞骨的伞。", List.of("伞骨=九根"), List.of());

        assertEquals(0, n);
        verify(factMapper, never()).insert(any(NovelFact.class));
        verify(factMapper, never()).touch(any(), any(), any());
    }

    @Test
    @DisplayName("novelId 为空 ⇒ 一律返回空，不发查询")
    void nullNovelId_safe() {
        assertTrue(service.listEntries(null).isEmpty());
        assertTrue(service.detectConflicts(null, "正文").isEmpty());
        assertEquals(0, service.recordNames(null, 1L, 1, "正文", List.of("张三")));
        verify(mapper, never()).selectList(any());
        verify(mapper, never()).insert(any(NovelGlossary.class));
    }

    @Test
    @DisplayName("正文为空 ⇒ 不记不查（别拿空正文去比对，那会把整本书的名字都当成缺失）")
    void blankBody_safe() {
        assertEquals(0, service.recordNames(9L, 1L, 1, "   ", List.of("张三")));
        assertTrue(service.detectConflicts(9L, "   ").isEmpty());
        verify(mapper, never()).selectList(any());
    }

    // ==================== 服务端自己扫数字（补覆盖度） ====================

    @Test
    @DisplayName("扫「名词 + 数值」：两种语序都认 ——「刀有三尺长」与「两尺长的刀」")
    void scanFacts_bothWordOrders() {
        givenFacts();   // 表是空的，这两条全靠扫描

        assertEquals(1, service.recordFacts(9L, 300L, 1, "他手里那把刀有三尺长。",
                List.of(), List.of("刀")), "名词在前的语序没扫到");
        assertEquals(1, service.recordFacts(9L, 300L, 1, "他握着那把两尺长的刀。",
                List.of(), List.of("刀")), "数值在前的语序没扫到（dev 集那处漏报就是这一种）");
    }

    @Test
    @DisplayName("名词第一次出现时不带数值、后面才带 ⇒ 也要扫到（c1 的「刀」就是这样：先「把刀往背后一送」，后面才是「那把刀有三尺长」）")
    void scanFacts_triesEveryOccurrence() {
        givenFacts();

        int n = service.recordFacts(9L, 300L, 1,
                "他象往常一样，把刀往背后一送。那把刀有三尺长，刀鞘是旧的。", List.of(), List.of("刀"));

        assertEquals(1, n, "只看第一次出现会把这条设定整个漏掉（实测漏过一次）");
        ArgumentCaptor<NovelFact> captor = ArgumentCaptor.forClass(NovelFact.class);
        verify(factMapper).insert(captor.capture());
        assertEquals("三尺", captor.getValue().getFactValue());
    }

    @Test
    @DisplayName("中间有标点 ⇒ 不扫：别把「他握紧那把刀，三年前的事涌上来」读成「刀 = 三年」")
    void scanFacts_punctuationStopsIt() {
        givenFacts();

        assertEquals(0, service.recordFacts(9L, 300L, 1, "他握紧那把刀，三年前的事涌上来。",
                List.of(), List.of("刀")));
    }

    @Test
    @DisplayName("时间量词不收：「沈青梧三天没合眼」不能记成「沈青梧 = 三天」，否则后面每章都要报一次假矛盾")
    void scanFacts_ignoresTimeUnits() {
        givenFacts();

        assertEquals(0, service.recordFacts(9L, 300L, 1, "沈青梧三天没合眼。",
                List.of(), List.of("沈青梧")));
    }

    @Test
    @DisplayName("核心场景：模型本章什么都没报，服务端也拿表里的名词扫出本章的数值来比对")
    void detectFactConflicts_scansKnownName() {
        givenFacts(factRow("刀", "三尺", 1));

        List<FactConflict> conflicts = service.detectFactConflicts(9L, "他握着那把两尺长的刀。", List.of());

        assertEquals(1, conflicts.size(),
                "表里记过「刀 = 三尺」，本章写「两尺」却没比出来 —— 覆盖度没补上");
        assertEquals("两尺", conflicts.get(0).actual());
        assertEquals("三尺", conflicts.get(0).expected());
    }

    @Test
    @DisplayName("模型报的值必须是纯数量短语：「七颗铜钉」「三年前」一律不收（进表后会污染后面每一章）")
    void recordFacts_rejectsImpureValue() {
        givenFacts();

        assertEquals(0, service.recordFacts(9L, 300L, 3, "门板上钉着七颗铜钉，漆掉了几块。",
                List.of("门板=七颗铜钉"), List.of()), "值里拖着名词，原本会被 NumberWords 解析成 7 收进来");
        assertEquals(0, service.recordFacts(9L, 300L, 1, "那是三年前的事。",
                List.of("雨夜=三年前"), List.of()));
    }

    @Test
    @DisplayName("扫描入库的条目记来源 scan：两条来路可信度不同，出问题时要能分开看")
    void recordFacts_recordsSource() {
        givenFacts();

        int n = service.recordFacts(9L, 300L, 1, "他手里那把刀有三尺长。", List.of(), List.of("刀"));

        assertEquals(1, n);
        ArgumentCaptor<NovelFact> captor = ArgumentCaptor.forClass(NovelFact.class);
        verify(factMapper).insert(captor.capture());
        NovelFact row = captor.getValue();
        assertEquals("scan", row.getSource());
        assertEquals("刀", row.getName());
        assertEquals("三尺", row.getFactValue());
    }
}
