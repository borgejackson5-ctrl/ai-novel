package com.ainovel.module.ai.service;

import com.ainovel.common.code.ErrorCode;
import com.ainovel.common.exception.BusinessException;
import com.ainovel.module.ai.client.AiChatClient;
import com.ainovel.module.ai.domain.PolishMode;
import com.ainovel.module.ai.domain.WritingLength;
import com.ainovel.module.ai.domain.entity.AiConfig;
import com.ainovel.module.ai.service.impl.AiWritingServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 续写与润色的业务规则单测。
 *
 * <p>这里不测试「模型写得好不好」，只测试代码这一侧的规则，且它们都是不会报错、只会逐渐失真的性质：
 * <ol>
 *   <li>送进模型的上文 = 扣费的字数（两处各算一次，作者看到的「本次消耗」就成了错误数据）；</li>
 *   <li>没配 Key 时润色不得给出假结果（作者一按替换就会把自己的正文换掉）；</li>
 *   <li>空输入、过短的选区要在开流之前拒绝（否则前端拿到的是一个 200 的空流）。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class AiWritingServiceImplTest {

    /** 默认上文长度 1200（字段带初始值，无 Spring 上下文时同样成立） */
    private static final int CONTEXT = 1200;

    private static final String SENTENCE = "他推开门，走了进去，看见桌子上放着一封信。";

    @Mock
    private AiChatClient aiClient;

    @Mock
    private AiConfigService aiConfigService;

    private AiWritingService service;

    @BeforeEach
    void init() {
        service = new AiWritingServiceImpl(aiClient, aiConfigService);
    }

    private AiConfig configWithKey() {
        AiConfig config = new AiConfig();
        config.setBaseUrl("http://ai.test/v1");
        config.setApiKey("sk-test");
        config.setModel("deepseek-chat");
        config.setTemperature(0.8);
        return config;
    }

    private AiConfig configWithoutKey() {
        AiConfig config = new AiConfig();
        config.setBaseUrl("http://ai.test/v1");
        config.setApiKey("");
        config.setModel("deepseek-chat");
        return config;
    }

    /** 抓取所有真正送进模型的 userPrompt（按调用顺序） */
    private List<String> sentPrompts() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(aiClient, atLeastOnce()).chatStream(anyString(), anyString(), anyString(), anyDouble(),
                anyString(), captor.capture(), any());
        return captor.getAllValues();
    }

    // ==================== 续写：上文怎么取 ====================

    @Test
    @DisplayName("续写只取本章末尾 1200 字：整章一万字和两千字，扣的额度一样多")
    void continue_usesOnlyTailOfChapter() {
        String content = "甲".repeat(2000) + "乙".repeat(2000);

        service.continueWriting(content, null, WritingLength.MEDIUM, configWithKey(), c -> { });

        String prompt = sentPrompts().get(0);
        long tailChars = prompt.chars().filter(c -> c == '乙').count();
        assertEquals(CONTEXT, tailChars, "送进去的正文字数应当正好是 contextChars");
        assertFalse(prompt.contains("甲"), "正文开头不该进提示词 —— 整章塞进去等于按整章扣费");
    }

    @Test
    @DisplayName("估算与实送同源：估算拿到的第一段就是提示词里那一截正文")
    void continue_estimateMatchesWhatIsSent() {
        String content = "丙".repeat(3000);

        service.estimateContinueUnits(content, "主角发现线索");

        // 该断言约束「扣多少」与「送多少」出自同一处计算。若分两处各写一遍，
        // 修改截取长度时容易只改一处，扣费与实际发送静默错开，界面上的数字也不再可信
        ArgumentCaptor<String> parts = ArgumentCaptor.forClass(String.class);
        verify(aiConfigService).estimateUnits(parts.capture(), anyString());
        assertEquals(CONTEXT, parts.getValue().length());
    }

    @Test
    @DisplayName("续写方向会写进提示词；留空时明确告诉模型「没有特别要求」")
    void continue_directionIsPassedThrough() {
        service.continueWriting(SENTENCE, "主角翻窗逃走了", WritingLength.SHORT, configWithKey(), c -> { });
        assertTrue(sentPrompts().get(0).contains("主角翻窗逃走了"));

        service.continueWriting(SENTENCE, "   ", WritingLength.SHORT, configWithKey(), c -> { });
        // 留空不能变成空的标题行，否则模型会自行猜测方向，续写容易原地重复
        assertTrue(sentPrompts().get(1).contains("没有特别要求"));
    }

    @Test
    @DisplayName("长度档位决定提示词里的目标字数（三档分别是 200 / 400 / 800）")
    void continue_lengthTiers() {
        service.continueWriting(SENTENCE, null, WritingLength.SHORT, configWithKey(), c -> { });
        service.continueWriting(SENTENCE, null, WritingLength.MEDIUM, configWithKey(), c -> { });
        service.continueWriting(SENTENCE, null, WritingLength.LONG, configWithKey(), c -> { });

        List<String> prompts = sentPrompts();
        assertTrue(prompts.get(0).contains("大约 200 字"));
        assertTrue(prompts.get(1).contains("大约 400 字"));
        assertTrue(prompts.get(2).contains("大约 800 字"));
    }

    @Test
    @DisplayName("正文为空 ⇒ 抛出，由控制器在开流之前变成 400（不能变成一个 200 的空流）")
    void continue_blankContent_throws() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.estimateContinueUnits("   ", null));

        assertEquals(ErrorCode.PARAM_ERROR, ex.getErrorCode());
        verify(aiConfigService, never()).estimateUnits(any(String.class));
    }

    // ==================== 续写：没配 Key 的时候 ====================

    @Test
    @DisplayName("没配 Key 但允许演示 ⇒ 续写推演示文字，且不去调模型")
    void continue_mockWhenNoKey() {
        AiConfig config = configWithoutKey();
        when(aiConfigService.isMockAllowed(config)).thenReturn(true);
        StringBuilder got = new StringBuilder();

        service.continueWriting(SENTENCE, null, WritingLength.MEDIUM, config, got::append);

        assertTrue(got.length() > 0, "无 Key 时也要有打字机效果，否则前端没法本地开发");
        assertTrue(got.toString().contains("示例"), "演示文字要自报家门，别让作者以为模型真写了一版");
        verify(aiClient, never()).chatStream(anyString(), anyString(), anyString(), anyDouble(),
                anyString(), anyString(), any());
    }

    @Test
    @DisplayName("没配 Key 又不允许演示 ⇒ 开流前就该被拒（requireContinueReady）")
    void continue_unavailableWhenMockOff() {
        AiConfig config = configWithoutKey();
        when(aiConfigService.isMockAllowed(config)).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.requireContinueReady(config));

        assertEquals(ErrorCode.AI_GENERATE_FAIL, ex.getErrorCode());
    }

    // ==================== 润色 ====================

    @Test
    @DisplayName("润色不给演示文字：没配 Key 时即使允许演示也一律拒绝")
    void polish_refusesMockResult() {
        AiConfig config = configWithoutKey();
        when(aiConfigService.isMockAllowed(config)).thenReturn(true);   // 注意这里给的是 true

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.requirePolishReady(config));

        // 续写使用演示文字没有问题（它只是一段建议，作者仍需自行判断）；
        // 润色结果会被作者通过「替换选中」直接写回正文，给出假结果等于替作者改稿
        assertEquals(ErrorCode.AI_GENERATE_FAIL, ex.getErrorCode());
    }

    @Test
    @DisplayName("润色用固定低温：平台那档（默认 0.8）是给创作调的，用在润色上模型会开始改剧情")
    void polish_usesLowTemperature() {
        AiConfig config = configWithKey();
        config.setTemperature(0.95);

        service.polish(SENTENCE, PolishMode.EXPRESS, config, c -> { });

        ArgumentCaptor<Double> temp = ArgumentCaptor.forClass(Double.class);
        verify(aiClient).chatStream(anyString(), anyString(), anyString(), temp.capture(),
                anyString(), anyString(), any());
        assertTrue(temp.getValue() < 0.5, "润色温度要明显低于创作档，实际 " + temp.getValue());
    }

    @Test
    @DisplayName("三种改法给的是三套不同的约束，不是同一句话换个说法")
    void polish_modesHaveDistinctInstructions() {
        service.polish(SENTENCE, PolishMode.EXPRESS, configWithKey(), c -> { });
        service.polish(SENTENCE, PolishMode.COMPACT, configWithKey(), c -> { });
        service.polish(SENTENCE, PolishMode.VIVID, configWithKey(), c -> { });

        List<String> prompts = sentPrompts();
        assertTrue(prompts.get(0).contains("保持原意"));
        assertTrue(prompts.get(1).contains("字数要明显少于原文"));
        assertTrue(prompts.get(2).contains("画面立起来"));
    }

    @Test
    @DisplayName("润色按选中那一段的字数算，不按整章")
    void polish_chargesSelectedText() {
        service.estimatePolishUnits(SENTENCE);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(aiConfigService).estimateUnits(captor.capture());
        assertEquals(SENTENCE, captor.getValue());
    }

    @Test
    @DisplayName("选区太短 ⇒ 拒绝（改不动，还会被最小计费单位兜成 200 字，作者觉得亏）")
    void polish_tooShort_throws() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.estimatePolishUnits("他走了。"));

        assertEquals(ErrorCode.PARAM_ERROR, ex.getErrorCode());
    }

    @Test
    @DisplayName("没选改法 ⇒ 拒绝，不静默按某个默认档跑")
    void polish_nullMode_throws() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.polish(SENTENCE, null, configWithKey(), c -> { }));

        assertEquals(ErrorCode.PARAM_ERROR, ex.getErrorCode());
    }

    // ==================== 代号解析 ====================

    @Test
    @DisplayName("长度代号解析不出来就按中档；改法代号解析不出来返回 null（由调用方拒绝）")
    void codeParsing() {
        assertEquals(WritingLength.MEDIUM, WritingLength.of(null));
        assertEquals(WritingLength.MEDIUM, WritingLength.of("写错了我"));
        assertEquals(WritingLength.LONG, WritingLength.of("long"));

        assertEquals(PolishMode.COMPACT, PolishMode.of("compact"));
        // 润色不能像长度那样「退回默认」：选着「精简」却按「加画面感」执行，
        // 作者拿到一段更长的文字只会以为功能故障
        assertNull(PolishMode.of("bogus"));
    }
}
