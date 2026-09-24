package com.ainovel.module.ai.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 结构化输出「抗噪」的守门测试。
 *
 * <p>约束的对象：模型返回的 JSON 并不总是干净的，它会把同一个字段写两遍
 * （{@code "facts":[...],"facts":[...]}）。若目标类型是 record，Jackson 会在收集完所有属性后
 * 才构造对象，因此最后一个字段遇到重复键时对象已经创建完成，它会去调用 setter，
 * 而 record 没有 setter，直接抛出 {@code Should never call `set()` on setterless property}，
 * 导致整章审查作废。
 *
 * <p>实际代价：一次评测中用例 c6 因此废掉一章（该次记录 ok=false，
 * 界面上显示「这次审查没能完成，请稍后再试」）。当时 {@code facts} 位于最后一个字段的位置，
 * 而在它之前 {@code names} 一直占着该位置，也就是说该问题一直存在，只是此前未被触发。
 *
 * <p>因此这两个类型从 record 改为带 setter 的类：重复键退化为「以后一个为准」。
 * 该测试用于约束这一行为：若将其改回 record，此处会失败。
 */
class ChapterReviewReportTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("最后一个字段（facts）写了两遍也要能解析，以后一个为准")
    void duplicateLastFieldIsTolerated() throws Exception {
        String json = "{\"summary\":\"总评\",\"issues\":[],\"names\":[\"沈青梧\"],"
                + "\"facts\":[\"伞骨=七根\"],\"facts\":[\"伞骨=九根\"]}";

        ChapterReviewReport report = mapper.readValue(json, ChapterReviewReport.class);

        assertEquals(List.of("伞骨=九根"), report.getFacts());
        assertEquals(List.of("沈青梧"), report.getNames());
    }

    @Test
    @DisplayName("issues 里一条问题的最后一个字段写了两遍也要能解析")
    void duplicateFieldInsideIssueIsTolerated() throws Exception {
        String json = "{\"summary\":\"总评\",\"issues\":[{\"type\":\"错别字\",\"excerpt\":\"甲甲甲甲甲甲甲甲\","
                + "\"suggestion\":\"改成乙\",\"suggestion\":\"改成丙\"}],\"names\":[],\"facts\":[]}";

        ChapterReviewReport report = mapper.readValue(json, ChapterReviewReport.class);

        assertEquals(1, report.getIssues().size());
        assertEquals("改成丙", report.getIssues().get(0).getSuggestion());
    }

    @Test
    @DisplayName("字段缺省 / 为 null 时按空处理，不能抛异常（模型偶尔会漏给 facts）")
    void missingFieldsAreTolerated() throws Exception {
        ChapterReviewReport report = mapper.readValue(
                "{\"summary\":\"s\",\"issues\":[]}", ChapterReviewReport.class);

        assertEquals("s", report.getSummary());
        assertTrue(report.getFacts() == null, "缺省就是 null，调用方按空处理");
    }
}
