package com.ainovel.module.search.service.impl;

import com.ainovel.common.code.ErrorCode;
import com.ainovel.common.constant.AiConstant;
import com.ainovel.common.domain.PageResult;
import com.ainovel.common.exception.BusinessException;
import com.ainovel.common.metrics.BusinessMetrics;
import com.ainovel.common.util.LoginUserUtil;
import com.ainovel.module.ai.client.AiChatClient;
import com.ainovel.module.ai.domain.entity.AiConfig;
import com.ainovel.module.ai.service.AiConfigService;
import com.ainovel.module.category.service.CategoryService;
import com.ainovel.module.novel.domain.vo.NovelVO;
import com.ainovel.module.search.domain.dto.SearchIntent;
import com.ainovel.module.search.domain.form.SmartSearchForm;
import com.ainovel.module.search.domain.vo.SmartSearchVO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import com.ainovel.module.search.service.AiSearchService;
import com.ainovel.module.search.service.SearchService;

/**
 * AI 智能搜索服务：自然语言 → LLM 意图解析 → 结构化 ES 查询
 *
 * <p>可靠性设计（LLM 输出不可信，全链路防御）：
 * <ul>
 *   <li>fail-fast：独立 5s 短超时，超时或异常时静默降级为关键词搜索，搜索主链路不因 AI 受阻</li>
 *   <li>防御解析：剥离 markdown 围栏、Jackson 宽松解析、字段白名单校验（数量/长度/分类 ID 合法性）</li>
 *   <li>注入防护：用户输入仅作为 LLM prompt 与 ES 查询的参数值，查询结构由代码构建，无 DSL 字符串拼接</li>
 *   <li>无 Key 时自动降级为 mock 启发式解析，演示环境同样可体验智能搜索</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiSearchServiceImpl implements AiSearchService {

    /** LLM 输出防御上限：防止模型返回超长或超量词条导致 ES 查询过大 */
    private static final int MAX_KEYWORDS = 5;
    private static final int MAX_TAGS = 4;
    private static final int MAX_TOKEN_LENGTH = 20;

    /** 降级为「分类浏览」时最多返回的入口数（库中当前 7 个分类，该上限足够） */
    private static final int MAX_CATEGORY_SUGGESTIONS = 8;

    /** 意图解析使用低温：需要确定性输出，不需要创造性 */
    private static final double PARSE_TEMPERATURE = 0.2;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** mock 解析用题材词表 */
    private static final List<String> TAG_WORDS = List.of(
            "修仙", "玄幻", "武侠", "江湖", "志怪", "神魔", "历史", "演义",
            "言情", "古风", "穿越", "重生", "逆袭", "权谋", "宫斗", "奇幻", "名著", "仙侠");

    /**
     * mock 解析用：题材词 → 分类名（运行时反查真实分类 ID，不与 DB 主键耦合）
     *
     * <p>映射按库中的实际归类编写，不按题材直觉：聊斋志异 / 封神演义在题材上属于神怪，
     * 但库中已划入「志怪神魔」；「古典名著」只保留最知名的几部。映射错误会使推断出的分类
     * 与数据不一致。分类仅为加分项，判断错误不会导致零召回，但准确更优。
     *
     * <p>顺序敏感：命中第一个即 break（见 mockParse）。因此同类词需相邻放置，
     * 词义更具体的放在前面（如「儿女英雄」需在「英雄」类词之前）。
     * 新增分类必须在此处补充词条，否则该分类无法被推断到，且静默降级、不报错。
     */
    private static final Map<String, String> CATEGORY_HINTS;

    static {
        Map<String, String> hints = new LinkedHashMap<>();
        hints.put("名著", "古典名著");
        hints.put("经典", "古典名著");
        hints.put("四大名著", "古典名著");
        hints.put("西游", "古典名著");
        hints.put("三国", "古典名著");
        hints.put("水浒", "古典名著");
        hints.put("红楼", "古典名著");
        hints.put("志怪", "志怪神魔");
        hints.put("神魔", "志怪神魔");
        hints.put("聊斋", "志怪神魔");
        hints.put("封神", "志怪神魔");
        hints.put("鬼怪", "志怪神魔");
        hints.put("济公", "志怪神魔");
        hints.put("山海经", "志怪神魔");
        hints.put("玄幻", "仙侠修真");
        hints.put("奇幻", "仙侠修真");
        hints.put("修真", "仙侠修真");
        hints.put("修仙", "仙侠修真");
        hints.put("魔法", "仙侠修真");
        hints.put("仙侠", "仙侠修真");
        hints.put("武侠", "侠义公案");
        hints.put("江湖", "侠义公案");
        hints.put("侠客", "侠义公案");
        hints.put("侠义", "侠义公案");
        hints.put("公案", "侠义公案");
        hints.put("断案", "侠义公案");
        hints.put("七侠五义", "侠义公案");
        hints.put("历史", "历史演义");
        hints.put("演义", "历史演义");
        hints.put("列国", "历史演义");
        hints.put("隋唐", "历史演义");
        hints.put("杨家将", "历史演义");
        hints.put("言情", "世情讽喻");
        hints.put("古风", "世情讽喻");
        hints.put("世情", "世情讽喻");
        hints.put("市井", "世情讽喻");
        hints.put("官场", "世情讽喻");
        hints.put("谴责", "世情讽喻");
        hints.put("三言二拍", "世情讽喻");
        hints.put("才子佳人", "儿女英雄");
        hints.put("儿女英雄", "儿女英雄");
        hints.put("巾帼", "儿女英雄");
        hints.put("木兰", "儿女英雄");
        CATEGORY_HINTS = Collections.unmodifiableMap(hints);
    }

    private final AiChatClient aiClient;

    private final AiConfigService aiConfigService;

    private final SearchService searchService;

    private final CategoryService categoryService;

    /** 降级率指标：智能搜索的降级是静默的（用户仅感知为结果质量下降），需要重点监控 */
    private final BusinessMetrics businessMetrics;

    /**
     * 智能搜索入口：AI 解析意图 → 结构化 ES 查询；任何一步失败静默降级关键词搜索
     *
     * <p>前端翻页时带回首次解析出的意图（keywords/tags/categoryId），跳过 LLM 调用以节省 token 与降低延迟
     */
    public SmartSearchVO smartSearch(SmartSearchForm form) {
        String query = form.getQuery() == null ? "" : form.getQuery().trim();
        if (query.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "搜索内容不能为空");
        }
        int page = form.getPage() == null || form.getPage() < 1 ? 1 : form.getPage();
        int size = form.getSize() == null || form.getSize() < 1 ? 10 : form.getSize();

        long start = System.currentTimeMillis();
        // 自带 Key → 不限次；否则平台 Key（仅受全局日上限，不扣个人额度）
        AiConfig config = aiConfigService.getActiveConfigForSearch(LoginUserUtil.getUserIdOrNull());
        Map<Long, String> categoryNames = categoryService.getNameMap();

        SearchIntent intent;
        String source;
        String model;
        // 缓存意图同样执行白名单校验：前端传回的数据与 LLM 输出同样不可信
        SearchIntent cached = fromForm(form, categoryNames.keySet());
        if (cached != null) {
            intent = cached;
            source = "cached";
            model = null;
        } else {
            // 无 Key 时：mock 开关启用才做启发式解析，关闭则直接放弃解析。
            // 抛出的异常由下方的 catch 捕获，静默降级为关键词搜索。
            // 搜索是主链路，不应因 AI 不可用而失败。
            boolean useMock = !StringUtils.hasText(config.getApiKey());
            if (useMock && !aiConfigService.isMockAllowed(config)) {
                throw new BusinessException(ErrorCode.AI_GENERATE_FAIL, AiConstant.AI_NOT_OPEN_MSG);
            }
            intent = null;
            source = "ai";
            model = config.getModel();
            try {
                if (useMock) {
                    intent = mockParse(query, categoryNames);
                    source = "mock";
                    model = "mock";
                } else {
                    String llmOutput = aiClient.chatFast(config.getBaseUrl(), config.getApiKey(), config.getModel(),
                            PARSE_TEMPERATURE, buildSystemPrompt(categoryNames), query);
                    intent = parseIntent(llmOutput, categoryNames.keySet());
                }
            } catch (Exception e) {
                // 静默降级：解析失败不报错，用户无感知地回退到关键词搜索
                log.warn("AI 意图解析失败，降级关键词搜索: query={}, cause={}", query, e.getMessage());
            }
        }

        if (intent == null || (intent.getKeywords().isEmpty() && intent.getTags().isEmpty())) {
            // 降级不等于放弃：整句查询直接交由检索几乎必然零命中（「找一本古典名著」中
            // 没有任何词能在正文中命中），因此先用本地启发式再提取一次。
            // 该方式为纯规则、零成本、不依赖任何外部服务，正是 AI 不可用时的保底手段。
            SearchIntent guessed = mockParse(query, categoryNames);
            boolean hasGuess = !guessed.getKeywords().isEmpty()
                    || !guessed.getTags().isEmpty()
                    || guessed.getCategoryId() != null;

            SmartSearchVO vo = new SmartSearchVO();
            vo.setDegraded(true);
            vo.setSource("keyword");
            if (hasGuess) {
                vo.setKeywords(guessed.getKeywords());
                vo.setTags(guessed.getTags());
                vo.setCategoryId(guessed.getCategoryId());
                // 明确标注降级来源：用户看到「AI 暂不可用」才能理解结果较为粗略的原因
                vo.setAiUnderstanding("AI 暂不可用，已按本地规则提取：" + buildUnderstanding(guessed, categoryNames));
                vo.setPage(searchService.smartSearch(guessed, page, size));
                // 本地规则同样可能提取错误（「随便看看」会被整句当作关键词，仍为零命中），
                // 因此无任何命中时再降级提供分类入口，避免用户面对空白页
                if (isEmptyPage(vo.getPage())) {
                    vo.setCategories(categoryOptions(categoryNames));
                }
            } else {
                // 启发式也无法提取实词时（如「asdfgh」「推荐一下」）：仅提供分类入口
                vo.setKeywords(List.of());
                vo.setTags(List.of());
                vo.setCategories(categoryOptions(categoryNames));
                vo.setPage(searchService.search(query, page, size));
            }
            if (!"cached".equals(source)) {
                saveLog("fallback", true, query.length(), start);
            }
            return vo;
        }

        SmartSearchVO vo = new SmartSearchVO();
        vo.setDegraded(false);
        vo.setSource(source);
        vo.setKeywords(intent.getKeywords());
        vo.setTags(intent.getTags());
        vo.setCategoryId(intent.getCategoryId());
        vo.setAiUnderstanding(buildUnderstanding(intent, categoryNames));
        vo.setPage(searchService.smartSearch(intent, page, size));
        if (!"cached".equals(source)) {
            saveLog(model, false, query.length(), start);
        }
        return vo;
    }

    /**
     * 从表单提取前端带回的缓存意图（翻页场景）；未携带返回 null 走 AI 解析
     */
    private SearchIntent fromForm(SmartSearchForm form, Set<Long> validCategoryIds) {
        if (form.getKeywords() == null && form.getTags() == null) {
            return null;
        }
        SearchIntent intent = new SearchIntent();
        intent.setKeywords(sanitizeTokens(form.getKeywords(), MAX_KEYWORDS));
        intent.setTags(sanitizeTokens(form.getTags(), MAX_TAGS));
        Long categoryId = form.getCategoryId();
        intent.setCategoryId(categoryId != null && validCategoryIds.contains(categoryId) ? categoryId : null);
        return intent;
    }

    /**
     * 意图解析提示词：携带运行时分类列表，要求只输出 JSON
     */
    private String buildSystemPrompt(Map<Long, String> categoryNames) {
        String categoryList = categoryNames.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("，"));
        return """
                你是小说平台的搜索意图解析器，把用户的自然语言搜索解析成结构化查询。只输出一个 JSON 对象，不要任何解释、前后缀或 markdown 代码块。
                输出格式：{"keywords": ["关键词"], "tags": ["标签"], "categoryId": 数字或null}
                解析规则：
                1. keywords：从用户输入提取适合全文检索的实词（题材、人物、场景），最多 %d 个，每个不超过 6 个汉字
                2. tags：提取小说题材标签，如 玄幻、修仙、武侠、历史、志怪、言情、穿越、重生、权谋、宫斗，最多 %d 个
                3. categoryId：用户意图匹配下列分类时填对应数字 ID，无法判断填 null。可选分类：%s
                4. 只基于用户输入的语义提取，禁止编造输入中不存在的实体
                """.formatted(MAX_KEYWORDS, MAX_TAGS, categoryList);
    }

    /**
     * 防御性解析 LLM 输出：任何一步不合法都返回 null（由调用方降级）
     */
    private SearchIntent parseIntent(String llmOutput, Set<Long> validCategoryIds) {
        if (!StringUtils.hasText(llmOutput)) {
            return null;
        }
        // 模型可能夹带 markdown 围栏或说明文字，只截取首个 { 到末个 } 之间的 JSON 主体
        int start = llmOutput.indexOf('{');
        int end = llmOutput.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            JsonNode node = OBJECT_MAPPER.readTree(llmOutput.substring(start, end + 1));
            SearchIntent intent = new SearchIntent();
            intent.setKeywords(readTokenList(node.path("keywords"), MAX_KEYWORDS));
            intent.setTags(readTokenList(node.path("tags"), MAX_TAGS));
            // categoryId 白名单校验：模型幻觉出的非法 ID 直接忽略
            JsonNode categoryId = node.path("categoryId");
            if (categoryId.isNumber() && validCategoryIds.contains(categoryId.asLong())) {
                intent.setCategoryId(categoryId.asLong());
            }
            return intent;
        } catch (Exception e) {
            log.warn("LLM 输出解析失败: {}", llmOutput);
            return null;
        }
    }

    private List<String> readTokenList(JsonNode node, int max) {
        if (!node.isArray()) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        for (JsonNode item : node) {
            if (item.isTextual()) {
                tokens.add(item.asText());
            }
        }
        return sanitizeTokens(tokens, max);
    }

    /**
     * 词条清洗（LLM 输出与前端缓存共用）：trim、长度过滤防超长串、数量截断防海量词条
     */
    private List<String> sanitizeTokens(List<String> tokens, int max) {
        if (tokens == null || tokens.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String token : tokens) {
            if (token == null) {
                continue;
            }
            String trimmed = token.trim();
            if (trimmed.isEmpty() || trimmed.length() > MAX_TOKEN_LENGTH) {
                continue;
            }
            result.add(trimmed);
            if (result.size() >= max) {
                break;
            }
        }
        return result;
    }

    /**
     * mock 启发式解析：题材词表命中 → tags；去口语虚词后的核心短语 → keywords；题材词映射分类
     */
    private SearchIntent mockParse(String query, Map<Long, String> categoryNames) {
        SearchIntent intent = new SearchIntent();
        intent.setTags(TAG_WORDS.stream().filter(query::contains).limit(MAX_TAGS).toList());

        // 去除口语虚词：长词写在前面（正则交替左侧优先，否则「想看」会先被「想」匹配）
        // 「讲 / 说 / 这种 / 那种 / 类型」等词会混入关键词（如「有没有讲修仙的小说」
        // 会提取出「讲修仙」），去除后才是可用于检索的实词
        String core = query.replaceAll(
                "想看点|想看|想找|想读|求推荐|推荐一下|推荐|有没有|帮找|找一下|几本|一本|两部|一部|一些|"
                        + "好看的|好看|什么|的|小说|书|吗|呢|求|想|帮我|这种|那种|类型|风格|类似|之类|讲|说",
                " ").trim();
        intent.setKeywords(Arrays.stream(core.split("\\s+"))
                .filter(s -> s.length() >= 2 && s.length() <= MAX_TOKEN_LENGTH)
                .limit(MAX_KEYWORDS)
                .toList());

        for (Map.Entry<String, String> hint : CATEGORY_HINTS.entrySet()) {
            if (query.contains(hint.getKey())) {
                categoryNames.entrySet().stream()
                        .filter(e -> e.getValue().equals(hint.getValue()))
                        .map(Map.Entry::getKey)
                        .findFirst()
                        .ifPresent(intent::setCategoryId);
                break;
            }
        }
        return intent;
    }

    private String buildUnderstanding(SearchIntent intent, Map<Long, String> categoryNames) {
        StringBuilder sb = new StringBuilder();
        if (!intent.getKeywords().isEmpty()) {
            sb.append("关键词：").append(String.join("、", intent.getKeywords()));
        }
        if (!intent.getTags().isEmpty()) {
            if (!sb.isEmpty()) {
                sb.append("；");
            }
            sb.append("标签：").append(String.join("、", intent.getTags()));
        }
        String categoryName = intent.getCategoryId() == null ? null : categoryNames.get(intent.getCategoryId());
        if (categoryName != null) {
            if (!sb.isEmpty()) {
                sb.append("；");
            }
            sb.append("分类：").append(categoryName);
        }
        return sb.toString();
    }

    /** 降级时的分类浏览入口：id 与名称即可满足前端渲染可点击标签的需要 */
    private static List<SmartSearchVO.CategoryOption> categoryOptions(Map<Long, String> categoryNames) {
        return categoryNames.entrySet().stream()
                .limit(MAX_CATEGORY_SUGGESTIONS)
                .map(e -> new SmartSearchVO.CategoryOption(e.getKey(), e.getValue()))
                .toList();
    }

    /** 判断检索结果是否为空，降级路径据此判断本地规则提取的词是否也未有命中 */
    private static boolean isEmptyPage(PageResult<NovelVO> page) {
        return page == null || page.getList() == null || page.getList().isEmpty();
    }

    /**
     * 记录一次意图解析结果，用于统计智能搜索的调用量与降级率。
     *
     * <p>仅写入应用日志，不持久化。原实现写入 {@code t_ai_generation_log}（type=SEARCH），
     * 该表已删除：它仅覆盖 6 条 AI 链路中的 2 条，且将用户搜索词与解析结果原文
     * 持久化，与项目对调用记录的口径（只记模型 / 耗时 / 字数，不记正文）冲突。
     * 改为日志后统计口径不变：{@code model} 仍用于区分真实模型 / mock / fallback，
     * {@code degraded=true} 即为降级，可通过日志检索计算比例。
     *
     * <p>不记录用户输入的搜索词，只记录其长度。搜索词属于用户行为数据，
     * 与正文一样不应写入日志。
     */
    private void saveLog(String model, boolean degraded, int queryChars, long start) {
        long costMs = System.currentTimeMillis() - start;
        if (degraded) {
            // model 即降级来源（fallback / mock），直接作为 reason 使用
            businessMetrics.aiDegrade("SEARCH", model);
        }
        log.info("智能搜索：意图解析 model={} degraded={} 耗时={}ms queryChars={}",
                model, degraded, costMs, queryChars);
    }
}
