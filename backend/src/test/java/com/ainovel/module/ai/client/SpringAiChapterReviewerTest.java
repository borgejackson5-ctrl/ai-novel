package com.ainovel.module.ai.client;

import com.ainovel.common.domain.PageResult;
import com.ainovel.module.ai.config.AiProperties;
import com.ainovel.module.ai.domain.ChapterReviewReport;
import com.ainovel.module.ai.tool.ChapterReviewTools;
import com.ainovel.module.novel.domain.vo.ChapterVO;
import com.ainovel.module.novel.service.ChapterService;
import com.ainovel.module.ai.spi.ChapterRetrievalPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 「工具调用 + 循环 + 结构化输出」这条链路的测试：使用假模型服务，断言真实发出的请求。
 *
 * <p>需要单独测试的原因：这条链路上有三件容易「静默不生效」的事：
 * 工具未注册（模型看不到工具，退化为一次普通问答）、工具调用后未把结果带回模型
 * （第二轮拿不到答案）、结构化输出解析失败（返回 null，被上层当成「没问题」）。
 * 三者都不报错，因此需要用假服务把两轮请求都捕获下来检查。
 */
@ExtendWith(MockitoExtension.class)
class SpringAiChapterReviewerTest {

    @Mock
    private ChapterService chapterService;

    private SpringAiChapterReviewer reviewer;
    private final List<String> requestBodies = new ArrayList<>();

    @Mock
    private ChapterRetrievalPort chapterRetrievalPort;
    private HttpServer server;

    private static final Long NOVEL_ID = 10L;

    private AiProperties props;

    @BeforeEach
    void init() {
        props = new AiProperties();
        props.setMaxTokens(500);
        props.setTimeoutSeconds(5);
        props.setSearchTimeoutSeconds(5);
        // 使用真实的工厂与真实的工具：需要验证的正是「框架是否接上了工具、循环是否正确」
        reviewer = new SpringAiChapterReviewer(new AiChatClientFactory(props, HttpClient.newHttpClient()),
                new ChapterReviewTools(chapterService, chapterRetrievalPort), props);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** 假模型服务：按请求次数依次返回给定响应，并记录每次收到的请求体 */
    private String startFakeOpenAi(List<String> responses) throws Exception {
        return startFakeOpenAi(responses, 0);
    }

    /** 同上，但每次响应前先等待一段时间，用于覆盖「总时长预算」这条路径 */
    private String startFakeOpenAi(List<String> responses, long delayMillis) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            int idx = Math.min(requestBodies.size() - 1, responses.size() - 1);
            byte[] bytes = responses.get(idx).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    /** 模型要求调用 listChapters 的那一轮响应 */
    private static String toolCallResponse() {
        return """
                {"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":null,\
                "tool_calls":[{"id":"call_1","type":"function","function":{"name":"listChapters","arguments":"{}"}}]}}]}
                """;
    }

    /** 模型给出结构化结论的那一轮响应 */
    private static String reportResponse(String summary) throws Exception {
        String inner = "{\"summary\":" + new ObjectMapper().writeValueAsString(summary)
                + ",\"issues\":[{\"type\":\"错别字\",\"excerpt\":\"心情很沉重要\",\"suggestion\":\"改成「沉重」\"}]}";
        return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":"
                + new ObjectMapper().writeValueAsString(inner) + "}}]}";
    }

    /** 目录中放一章带标记的标题：它出现在工具结果里即证明工具确实被执行 */
    private void stubChapterList() {
        ChapterVO vo = new ChapterVO();
        vo.setId(1L);
        vo.setChapterNo(1);
        vo.setTitle("标记-第一章");
        // 工具的读取走「不做归属校验」的那个查询：审查运行在 MQ 线程中，没有登录上下文，
        // 使用作者视角的方法会抛权限异常（见 ChapterReviewToolsTest）
        when(chapterService.pageChapterMetaByNovel(eq(NOVEL_ID), anyLong(), anyLong()))
                .thenReturn(PageResult.of(1, 1, 100, List.of(vo)));
    }

    private ChapterReviewRequest request(String baseUrl) {
        return new ChapterReviewRequest(baseUrl, "sk-test", "deepseek-chat",
                "你是校对编辑", "【正文】她的心情很沉重要。", NOVEL_ID, 3);
    }

    @Test
    @DisplayName("模型要求调工具 → 自己转的循环执行工具、把结果带回 → 第二轮拿到结构化结果")
    void review_executesToolLoop() throws Exception {
        stubChapterList();

        String baseUrl = startFakeOpenAi(List.of(toolCallResponse(),
                reportResponse("发现 1 处问题")));

        ChapterReviewReport report = reviewer.review(request(baseUrl));

        assertEquals(2, requestBodies.size(), "应当发生两轮请求：第一轮模型要工具、第二轮拿结果");
        assertTrue(requestBodies.get(0).contains("listChapters"),
                "第一轮请求里必须带上工具定义，否则模型根本不会要求调用。实际：" + requestBodies.get(0));
        assertTrue(requestBodies.get(1).contains("标记-第一章"),
                "工具的执行结果要拼回对话再发一次，否则第二轮等于空转。实际：" + requestBodies.get(1));
        assertTrue(requestBodies.get(1).contains("你是校对编辑"),
                "转了一轮之后 system 提示词不能丢 —— 丢了模型就失去全部审查要求，"
                        + "而它只会表现得「审得更松」，不报任何错");
        assertEquals("发现 1 处问题", report.getSummary());
        assertEquals(1, report.getIssues().size());
        assertEquals("心情很沉重要", report.getIssues().get(0).getExcerpt());
    }

    @Test
    @DisplayName("模型一直要工具 → 到轮数上限就熔断，收尾那次不带工具，且结果标注「没审全」")
    void review_stopsAtMaxRounds() throws Exception {
        stubChapterList();
        props.setReviewMaxToolRounds(2);
        // 三次都返回「还要调用工具」也不应无限循环：第 3 次为收尾调用，必须给出结论
        String baseUrl = startFakeOpenAi(List.of(toolCallResponse(), toolCallResponse(),
                reportResponse("本章没有发现问题")));

        ChapterReviewReport report = reviewer.review(request(baseUrl));

        assertEquals(3, requestBodies.size(),
                "轮数上限 2 ⇒ 两次带工具的调用 + 一次收尾；实际：" + requestBodies.size());
        assertTrue(requestBodies.get(0).contains("listChapters"), "前两轮要带着工具定义");
        assertFalse(requestBodies.get(2).contains("\"tools\":["),
                "收尾那一次不能再带工具，否则模型可以继续要工具。实际：" + requestBodies.get(2));
        assertTrue(requestBodies.get(2).contains("核对到此为止"), "收尾那一次要带上收尾指令");
        assertTrue(report.getSummary().startsWith("（本次跨章核对提前中止"),
                "熔断出来的结果必须标注「没审全」，不能让作者以为审完了。实际：" + report.getSummary());
        assertEquals("心情很沉重要", report.getIssues().get(0).getExcerpt(), "已经拿到的结论仍要保留");
    }

    @Test
    @DisplayName("单轮都很慢 → 总时长预算到点也熔断（轮数没到也一样）")
    void review_stopsAtBudget() throws Exception {
        // 这里不桩章节目录：熔断发生在「执行工具」之前，工具不会被调用
        // （若桩了，Mockito 严格模式会将其报为无用桩，反向证明这一点）
        props.setReviewBudgetSeconds(1);
        // 第一次响应就花掉 1.2 秒 ⇒ 第二轮开始前预算已经用完
        String baseUrl = startFakeOpenAi(List.of(toolCallResponse(), reportResponse("本章没有发现问题")), 1200);

        ChapterReviewReport report = reviewer.review(request(baseUrl));

        assertEquals(2, requestBodies.size(), "预算用完就直接收尾，不再执行工具、不再多转一轮");
        assertFalse(requestBodies.get(1).contains("\"tools\":["), "收尾那一次不带工具");
        assertTrue(report.getSummary().startsWith("（本次跨章核对提前中止"), "同样要标注「没审全」");
    }

    @Test
    @DisplayName("收尾那一次也拿不到内容 → 抛异常，绝不回一个「没问题」的空结构")
    void review_stopWithoutContent_throws() throws Exception {
        props.setReviewMaxToolRounds(1);
        String emptyContent = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"\"}}]}";
        String baseUrl = startFakeOpenAi(List.of(toolCallResponse(), emptyContent));

        assertThrows(RuntimeException.class, () -> reviewer.review(request(baseUrl)));
    }

    @Test
    @DisplayName("模型直接给结果（不调工具）也要能解析")
    void review_parsesStructuredOutput() throws Exception {
        String baseUrl = startFakeOpenAi(List.of(reportResponse("没有问题")));

        ChapterReviewReport report = reviewer.review(request(baseUrl));

        assertEquals(1, requestBodies.size());
        assertEquals("没有问题", report.getSummary());
        assertTrue(requestBodies.get(0).contains("你是校对编辑"), "system 提示词要发出去");
        assertTrue(requestBodies.get(0).contains("JSON"),
                "结构化输出的格式说明要自己拼在 user 消息后面（框架的 entity() 就是这么做的，"
                        + "换成自己转循环后这一步不能丢）。实际：" + requestBodies.get(0));
        assertFalse(report.getSummary().startsWith("（本次跨章核对提前中止"), "正常路径不该带中止标注");
    }

    /** 模型要求调用 readChapter(N) 的那一轮响应 */
    private static String readChapterToolCallResponse(int chapterNo) {
        return "{\"choices\":[{\"finish_reason\":\"tool_calls\",\"message\":{\"role\":\"assistant\","
                + "\"content\":null,\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\","
                + "\"function\":{\"name\":\"readChapter\",\"arguments\":\"{\\\"chapterNo\\\":"
                + chapterNo + "}\"}}]}}]}";
    }

    @Test
    @DisplayName("工具没取到材料 → 由代码在 summary 前钉上次数，不指望模型自己记得写")
    void review_marksNoMaterialLookups() throws Exception {
        // 目录为空 ⇒ readChapter 找不到那一章，工具会回「没取到材料」
        when(chapterService.pageChapterMetaByNovel(eq(NOVEL_ID), anyLong(), anyLong()))
                .thenReturn(PageResult.of(0, 1, 100, java.util.List.of()));

        String baseUrl = startFakeOpenAi(List.of(readChapterToolCallResponse(999),
                reportResponse("已核对，没有矛盾")));

        ChapterReviewReport report = reviewer.review(request(baseUrl));

        assertTrue(report.getSummary().startsWith("（本次跨章核对有 1 次查询未取到材料"),
                "标记必须由代码钉上去 —— 模型这次写的是「已核对，没有矛盾」，"
                        + "不钉就等于给作者一个假的保证。实际：" + report.getSummary());
        assertTrue(report.getSummary().contains("已核对，没有矛盾"),
                "标记是**加在前面**，不是替换掉模型写的那句话");
    }

    @Test
    @DisplayName("次数只数「这一轮新产生的」失败 —— 不能把前几轮的重复累加")
    void review_countsOnlyNewNoMaterialResponses() throws Exception {
        stubChapterList();
        // 第 1 轮读取不存在的章（失败）→ 第 2 轮列目录（成功）→ 第 3 轮给出结论。
        // 若实现从对话历史起始位置统计，第 1 轮的失败会在第 2 轮被重复计数，次数变为 2。
        String baseUrl = startFakeOpenAi(List.of(readChapterToolCallResponse(999), toolCallResponse(),
                reportResponse("本章没有发现问题")));

        ChapterReviewReport report = reviewer.review(request(baseUrl));

        assertTrue(report.getSummary().contains("有 1 次查询未取到材料"),
                "只有一次失败，次数就该是 1。实际：" + report.getSummary());
    }

    @Test
    @DisplayName("上游报错（401）要抛出去，不能静默返回空结果")
    void review_httpError_throws() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] bytes = "{\"error\":\"invalid api key\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(401, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";

        // 静默返回空清单会被上层当作「审查通过」，因此这里必须抛出异常
        assertThrows(RuntimeException.class, () -> reviewer.review(request(baseUrl)));
    }
}
