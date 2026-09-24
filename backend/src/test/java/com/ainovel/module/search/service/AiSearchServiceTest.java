package com.ainovel.module.search.service;

import com.ainovel.common.domain.PageResult;
import com.ainovel.module.ai.client.AiChatClient;
import com.ainovel.module.ai.domain.entity.AiConfig;
import com.ainovel.module.ai.service.AiConfigService;
import com.ainovel.module.category.service.CategoryService;
import com.ainovel.module.novel.domain.vo.NovelVO;
import com.ainovel.module.search.domain.dto.SearchIntent;
import com.ainovel.module.search.domain.form.SmartSearchForm;
import com.ainovel.module.search.domain.vo.SmartSearchVO;
import com.ainovel.module.search.service.impl.AiSearchServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 智能搜索单测：聚焦 LLM 输出不可信的防御解析与静默降级链路
 */
@ExtendWith(MockitoExtension.class)
class AiSearchServiceTest {

    @Mock
    private AiChatClient aiClient;
    @Mock
    private AiConfigService aiConfigService;
    @Mock
    private SearchService searchService;
    @Mock
    private CategoryService categoryService;

    private AiSearchService aiSearchService;

    @BeforeEach
    void initService() {
        aiSearchService = new AiSearchServiceImpl(aiClient, aiConfigService, searchService, categoryService,
                new com.ainovel.common.metrics.BusinessMetrics(
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
    }

    private void mockConfigWithKey() {
        AiConfig config = new AiConfig();
        config.setBaseUrl("http://ai.test/v1");
        config.setApiKey("sk-test");
        config.setModel("deepseek-chat");
        when(aiConfigService.getActiveConfigForSearch(any())).thenReturn(config);
    }

    private SmartSearchForm form(String query) {
        SmartSearchForm form = new SmartSearchForm();
        form.setQuery(query);
        return form;
    }

    @Test
    @DisplayName("正常 JSON 输出 → 解析成功，意图透传给结构化 ES 查询")
    void parse_normalJson() {
        mockConfigWithKey();
        when(categoryService.getNameMap()).thenReturn(Map.of(1L, "古典名著", 3L, "侠义公案"));
        when(aiClient.chatFast(anyString(), anyString(), anyString(), anyDouble(), anyString(), anyString()))
                .thenReturn("{\"keywords\":[\"重生\"],\"tags\":[\"爽文\"],\"categoryId\":3}");

        aiSearchService.smartSearch(form("想看重生爽文"));

        ArgumentCaptor<SearchIntent> captor = ArgumentCaptor.forClass(SearchIntent.class);
        verify(searchService).smartSearch(captor.capture(), eq(1), eq(10));
        verify(searchService, never()).search(anyString(), anyInt(), anyInt());

        SearchIntent intent = captor.getValue();
        assertEquals(java.util.List.of("重生"), intent.getKeywords());
        assertEquals(java.util.List.of("爽文"), intent.getTags());
        assertEquals(3L, intent.getCategoryId());
    }

    @Test
    @DisplayName("输出被 markdown 围栏包裹 → 剥离围栏后仍可解析")
    void parse_markdownFence() {
        mockConfigWithKey();
        when(categoryService.getNameMap()).thenReturn(Map.of(1L, "古典名著"));
        when(aiClient.chatFast(anyString(), anyString(), anyString(), anyDouble(), anyString(), anyString()))
                .thenReturn("好的，解析结果如下：\n```json\n{\"keywords\":[\"言情\"],\"tags\":[\"世家\"],\"categoryId\":null}\n```\n请查收");

        SmartSearchVO vo = aiSearchService.smartSearch(form("权谋言情小说"));

        assertFalse(vo.getDegraded());
        assertEquals(java.util.List.of("言情"), vo.getKeywords());
        assertEquals(java.util.List.of("世家"), vo.getTags());
        assertNull(vo.getCategoryId());
    }

    @Test
    @DisplayName("输出非 JSON 纯文本 → 降级：改用本地启发式提取的词去查，不再拿整句硬搜")
    void parse_garbage_degrades() {
        mockConfigWithKey();
        when(categoryService.getNameMap()).thenReturn(Map.of(1L, "古典名著"));
        when(aiClient.chatFast(anyString(), anyString(), anyString(), anyDouble(), anyString(), anyString()))
                .thenReturn("抱歉，我无法理解您的搜索意图。");
        when(searchService.smartSearch(any(SearchIntent.class), anyInt(), anyInt()))
                .thenReturn(PageResult.of(1, 1, 10, List.of(new NovelVO())));

        SmartSearchVO vo = aiSearchService.smartSearch(form("随便看看"));

        assertTrue(vo.getDegraded());
        assertEquals("keyword", vo.getSource());
        // 整句直接检索几乎必然零命中，因此降级路径先用本地规则提取实词
        verify(searchService, never()).search(anyString(), anyInt(), anyInt());
        verify(searchService).smartSearch(any(SearchIntent.class), eq(1), eq(10));
        assertTrue(vo.getAiUnderstanding().contains("AI 暂不可用"));
    }

    @Test
    @DisplayName("AI 调用异常（超时/接口失败）→ 降级：本地启发式把实词提出来，仍能搜到东西")
    void parse_llmFailure_degrades() {
        mockConfigWithKey();
        when(categoryService.getNameMap()).thenReturn(Map.of(1L, "古典名著"));
        when(aiClient.chatFast(anyString(), anyString(), anyString(), anyDouble(), anyString(), anyString()))
                .thenThrow(new RuntimeException("read timeout"));
        when(searchService.smartSearch(any(SearchIntent.class), anyInt(), anyInt()))
                .thenReturn(PageResult.of(6, 1, 10, List.of(new NovelVO())));

        SmartSearchVO vo = aiSearchService.smartSearch(form("找一本古典名著"));

        assertTrue(vo.getDegraded());
        // 这正是该兜底的价值：AI 不可用时，「找一本古典名著」也不应变成零结果
        assertTrue(vo.getKeywords().contains("古典名著"), "本地规则应把「找一本」剥掉");
        assertTrue(vo.getTags().contains("名著"));
        verify(searchService, never()).search(anyString(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("categoryId 幻觉非法 ID → 白名单校验忽略该字段，其余意图正常使用")
    void parse_hallucinatedCategoryId_ignored() {
        mockConfigWithKey();
        when(categoryService.getNameMap()).thenReturn(Map.of(1L, "古典名著"));
        when(aiClient.chatFast(anyString(), anyString(), anyString(), anyDouble(), anyString(), anyString()))
                .thenReturn("{\"keywords\":[\"言情\"],\"tags\":[],\"categoryId\":999}");

        SmartSearchVO vo = aiSearchService.smartSearch(form("言情小说"));

        assertFalse(vo.getDegraded());
        assertNull(vo.getCategoryId());
        assertEquals(java.util.List.of("言情"), vo.getKeywords());
    }

    @Test
    @DisplayName("输出超量/超长词条 → 数量截断 + 长度过滤")
    void parse_tokenOverflow_truncated() {
        mockConfigWithKey();
        when(categoryService.getNameMap()).thenReturn(Map.of(1L, "古典名著"));
        when(aiClient.chatFast(anyString(), anyString(), anyString(), anyDouble(), anyString(), anyString()))
                .thenReturn("{\"keywords\":[\"重生\",\"穿越\",\"逆袭\",\"世家\",\"仙侠\",\"第6个应被截断\"],"
                        + "\"tags\":[\"" + "超".repeat(30) + "\",\"爽文\"],\"categoryId\":null}");

        SmartSearchVO vo = aiSearchService.smartSearch(form("重生穿越逆袭"));

        assertFalse(vo.getDegraded());
        assertEquals(5, vo.getKeywords().size());
        // 超长 tag（30 字）被丢弃，只保留合法的「爽文」
        assertEquals(java.util.List.of("爽文"), vo.getTags());
    }

    @Test
    @DisplayName("无 Key → 降级 mock 启发式解析，演示环境可用")
    void noKey_mockParse() {
        AiConfig config = new AiConfig();
        config.setBaseUrl("http://ai.test/v1");
        config.setApiKey("");
        config.setModel("deepseek-chat");
        config.setMockEnabled(1);   // mock 开关开着才允许无 Key 时给演示内容
        when(aiConfigService.getActiveConfigForSearch(any())).thenReturn(config);
        // AiConfigService 是 mock，开关判断要显式打桩（默认 false 会导致「AI 功能暂未开放」）
        when(aiConfigService.isMockAllowed(any())).thenReturn(true);
        when(categoryService.getNameMap()).thenReturn(Map.of(2L, "仙侠修真"));

        SmartSearchVO vo = aiSearchService.smartSearch(form("想看修仙玄幻的小说"));

        assertFalse(vo.getDegraded());
        assertEquals("mock", vo.getSource());
        assertEquals(java.util.List.of("修仙", "玄幻"), vo.getTags());
        assertEquals(2L, vo.getCategoryId());
        verify(searchService).smartSearch(any(SearchIntent.class), eq(1), eq(10));
    }

    @Test
    @DisplayName("解析结果既无关键词也无标签 → 降级关键词搜索")
    void parse_emptyIntent_degrades() {
        mockConfigWithKey();
        when(categoryService.getNameMap()).thenReturn(Map.of(1L, "古典名著"));
        when(aiClient.chatFast(anyString(), anyString(), anyString(), anyDouble(), anyString(), anyString()))
                .thenReturn("{\"keywords\":[],\"tags\":[],\"categoryId\":1}");

        SmartSearchVO vo = aiSearchService.smartSearch(form("推荐一下"));

        assertTrue(vo.getDegraded());
        // 「推荐一下」剥掉口语虚词后无剩余内容，本地规则也无法处理，此时提供分类入口
        verify(searchService).search("推荐一下", 1, 10);
        assertNotNull(vo.getCategories());
        assertFalse(vo.getCategories().isEmpty());
    }

    @Test
    @DisplayName("空查询 → PARAM_ERROR")
    void blankQuery_throws() {
        assertThrows(com.ainovel.common.exception.BusinessException.class,
                () -> aiSearchService.smartSearch(form("  ")));
    }

    @Test
    @DisplayName("翻页带回缓存意图 → 跳过 LLM 调用，意图清洗后直接查询")
    void cachedIntent_skipsLlm() {
        mockConfigWithKey();
        when(categoryService.getNameMap()).thenReturn(Map.of(3L, "侠义公案"));

        SmartSearchForm form = form("想看侠义公案的");
        form.setKeywords(java.util.List.of("侠义公案"));
        form.setTags(java.util.List.of("仙侠"));
        form.setCategoryId(3L);

        SmartSearchVO vo = aiSearchService.smartSearch(form);

        assertFalse(vo.getDegraded());
        assertEquals("cached", vo.getSource());
        verify(aiClient, never()).chatFast(anyString(), anyString(), anyString(), anyDouble(), anyString(), anyString());
        verify(searchService).smartSearch(any(SearchIntent.class), eq(1), eq(10));
    }

    @Test
    @DisplayName("缓存意图携带非法字段 → 同样清洗：超量截断 + 非法 categoryId 忽略")
    void cachedIntent_sanitize() {
        mockConfigWithKey();
        when(categoryService.getNameMap()).thenReturn(Map.of(3L, "侠义公案"));

        SmartSearchForm form = form("想看侠义公案的");
        form.setKeywords(java.util.List.of("古装", "仙侠", "玄幻", "修真", "热血", "多余的第6个"));
        form.setTags(java.util.List.of("仙侠"));
        form.setCategoryId(999L);

        SmartSearchVO vo = aiSearchService.smartSearch(form);

        assertFalse(vo.getDegraded());
        assertEquals(5, vo.getKeywords().size());
        assertNull(vo.getCategoryId());
    }

    @Test
    @DisplayName("缓存意图清洗后为空 → 降级：本地启发式接住，而不是拿整句去搜")
    void cachedIntent_empty_degrades() {
        mockConfigWithKey();
        when(categoryService.getNameMap()).thenReturn(Map.of(3L, "侠义公案"));
        when(searchService.smartSearch(any(SearchIntent.class), anyInt(), anyInt()))
                .thenReturn(PageResult.of(1, 1, 10, List.of(new NovelVO())));

        SmartSearchForm form = form("想看侠义公案的");
        form.setKeywords(List.of("  "));
        form.setTags(List.of(""));

        SmartSearchVO vo = aiSearchService.smartSearch(form);

        assertTrue(vo.getDegraded());
        // 剥掉「想看 / 的」后还剩「侠义公案」，本地规则可处理
        assertTrue(vo.getKeywords().contains("侠义公案"));
        verify(searchService, never()).search(anyString(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("降级：本地规则猜的词也零命中 → 补分类入口，别让用户面对空白页")
    void fallback_noHit_offersCategories() {
        mockConfigWithKey();
        when(categoryService.getNameMap()).thenReturn(Map.of(1L, "古典名著", 3L, "侠义公案"));
        when(aiClient.chatFast(anyString(), anyString(), anyString(), anyDouble(), anyString(), anyString()))
                .thenThrow(new RuntimeException("read timeout"));
        // 「随便看看」会被本地规则整句当作关键词，自然检索不到结果
        when(searchService.smartSearch(any(SearchIntent.class), anyInt(), anyInt()))
                .thenReturn(PageResult.of(0, 1, 10, List.of()));

        SmartSearchVO vo = aiSearchService.smartSearch(form("随便看看"));

        assertTrue(vo.getDegraded());
        assertNotNull(vo.getCategories(), "零命中时必须给退路");
        assertEquals(2, vo.getCategories().size());
    }
}
