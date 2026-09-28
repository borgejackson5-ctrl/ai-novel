package com.ainovel.common.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("AI 调用场景")
class AiSceneTest {

    @Test
    @DisplayName("生成类型转场景：受控取值可转，未知取值抛异常而不落到某个默认场景")
    void ofGenerateType() {
        assertThat(AiScene.ofGenerateType("TITLE")).isEqualTo(AiScene.TITLE);
        assertThat(AiScene.ofGenerateType("INTRO")).isEqualTo(AiScene.INTRO);

        // 静默归类会把未知类型的调用记到其他场景名下：数字看着正常，含义已经错了
        assertThatThrownBy(() -> AiScene.ofGenerateType("OUTLINE"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("OUTLINE");
        assertThatThrownBy(() -> AiScene.ofGenerateType(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("场景码可还原为枚举，展示名齐备；未知码返回 null 交由调用方回落")
    void codeAndLabel() {
        for (AiScene scene : AiScene.values()) {
            assertThat(AiScene.fromCode(scene.code())).isEqualTo(scene);
            assertThat(scene.label()).as("每个场景都要有展示名，否则页面上会出现英文枚举名").isNotBlank();
        }
        assertThat(AiScene.fromCode("NOT_A_SCENE")).isNull();
        assertThat(AiScene.fromCode(null)).isNull();
    }
}
