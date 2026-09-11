package com.maou.apptemplateapi.module.ai.service;

import com.maou.apptemplateapi.common.config.ai.AiProperties;
import com.maou.apptemplateapi.module.ai.dto.DegradeDecision;
import com.maou.apptemplateapi.module.ai.enums.DegradeLevel;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiTokenMonitorTest {

    private static final long BUDGET = 100_000L;

    @Test
    void shouldStayNormalWhenBudgetAndPromptAreFine() {
        DegradeDecision decision = monitor(token(true, BUDGET, 0.8, 1.0, 6000, 2))
                .decide(0L, 1_500L, 0, 5);

        assertThat(decision.level()).isEqualTo(DegradeLevel.NORMAL);
        assertThat(decision.degraded()).isFalse();
        assertThat(decision.dailyBudget()).isEqualTo(BUDGET);
        assertThat(decision.usedTokensToday()).isZero();
    }

    @Test
    void shouldDropImagesWhenPromptTooLong() {
        DegradeDecision decision = monitor(token(true, BUDGET, 0.8, 1.0, 1000, 2))
                .decide(0L, 8_000L, 2, 1);

        assertThat(decision.level()).isEqualTo(DegradeLevel.DROP_IMAGES);
        assertThat(decision.degraded()).isTrue();
        assertThat(decision.reasonText()).contains("舍弃 2 张图片");
    }

    @Test
    void shouldCompressContextWhenPromptTooLongWithoutImages() {
        DegradeDecision decision = monitor(token(true, BUDGET, 0.8, 1.0, 1000, 2))
                .decide(0L, 8_000L, 0, 5);

        assertThat(decision.level()).isEqualTo(DegradeLevel.MINIMAL_CONTEXT);
        assertThat(decision.reasonText()).contains("知识片段压缩到 2 条");
    }

    @Test
    void shouldCompressContextWhenUsageReachesDegradeRatio() {
        DegradeDecision decision = monitor(token(true, BUDGET, 0.8, 1.0, 6000, 2))
                .decide(85_000L, 500L, 0, 5);

        assertThat(decision.level()).isEqualTo(DegradeLevel.MINIMAL_CONTEXT);
        assertThat(decision.reasonText()).contains("提前压缩上下文");
    }

    @Test
    void shouldSkipModelWhenUsageReachesBudget() {
        DegradeDecision decision = monitor(token(true, BUDGET, 0.8, 1.0, 6000, 2))
                .decide(100_500L, 500L, 0, 5);

        assertThat(decision.level()).isEqualTo(DegradeLevel.RULE_ONLY);
        assertThat(decision.level().isRuleOnly()).isTrue();
        assertThat(decision.reasonText()).contains("跳过模型调用").contains("规则评分");
    }

    @Test
    void shouldStayNormalWhenMonitorDisabled() {
        DegradeDecision decision = monitor(token(false, BUDGET, 0.8, 1.0, 10, 1))
                .decide(BUDGET * 2, 50_000L, 3, 8);

        assertThat(decision.level()).isEqualTo(DegradeLevel.NORMAL);
        assertThat(decision.reasons()).isEmpty();
    }

    @Test
    void shouldUseDefaultsWhenTokenConfigMissing() {
        AiTokenMonitor monitor = monitor(null);
        DegradeDecision decision = monitor.decide(0L, 100L, 0, 1);

        assertThat(monitor.dailyBudget()).isEqualTo(200_000L);
        assertThat(monitor.minimalContextChunks()).isEqualTo(2);
        assertThat(decision.level()).isEqualTo(DegradeLevel.NORMAL);
    }

    private AiTokenMonitor monitor(AiProperties.Token token) {
        AiProperties properties = new AiProperties(true, false, "https://example.com", "key", "model",
                60, 0.7, 300L, 12000, 3, 1_048_576L, 2_097_152L, "URL", token);
        return new AiTokenMonitor(properties, new AiTokenEstimator(), null);
    }

    private AiProperties.Token token(boolean monitorEnabled,
                                     Long dailyBudget,
                                     Double degradeRatio,
                                     Double ruleOnlyRatio,
                                     Integer maxPromptTokens,
                                     Integer minimalContextChunks) {
        return new AiProperties.Token(monitorEnabled, dailyBudget, degradeRatio, ruleOnlyRatio,
                maxPromptTokens, minimalContextChunks);
    }
}
