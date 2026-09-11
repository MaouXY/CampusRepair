package com.maou.apptemplateapi.module.ai.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiTokenEstimatorTest {

    private final AiTokenEstimator estimator = new AiTokenEstimator();

    @Test
    void shouldEstimateCjkCharactersAsOneTokenEach() {
        assertThat(estimator.estimate("水管漏水")).isEqualTo(4);
        assertThat(estimator.estimate("空调不制冷，请检查滤网。")).isEqualTo(12);
    }

    @Test
    void shouldEstimateNonCjkCharactersAsQuarterTokenEach() {
        assertThat(estimator.estimate("worksheet")).isEqualTo(3);
        assertThat(estimator.estimate("ab")).isEqualTo(1);
    }

    @Test
    void shouldEstimateMixedText() {
        assertThat(estimator.estimate("水管漏水 repair")).isEqualTo(6);
    }

    @Test
    void shouldReturnZeroForBlankText() {
        assertThat(estimator.estimate(null)).isZero();
        assertThat(estimator.estimate("")).isZero();
        assertThat(estimator.estimate("   ")).isZero();
    }

    @Test
    void shouldEstimateImageTokens() {
        assertThat(estimator.estimateImages(0)).isZero();
        assertThat(estimator.estimateImages(2)).isEqualTo(1600);
        assertThat(estimator.estimateImages(-1)).isZero();
    }
}
