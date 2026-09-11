package com.maou.apptemplateapi.module.rageval.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RagMetricCalculatorTest {

    private final RagMetricCalculator calculator = new RagMetricCalculator();

    @Test
    void shouldCountHitAndReciprocalRankForDocumentLevelRelevance() {
        List<RagMetricCalculator.RetrievedItem> retrieved = List.of(
                item(10L, "无关片段"),
                item(11L, "目标片段"),
                item(12L, "另一片段"));

        RagMetricCalculator.CaseMetric metric = calculator.evaluate(retrieved, Set.of(), Set.of(11L), List.of(), 5);

        assertThat(metric.hit()).isTrue();
        assertThat(metric.firstRelevantRank()).isEqualTo(2);
        assertThat(metric.reciprocalRank()).isEqualTo(0.5);
        assertThat(metric.recall()).isEqualTo(1.0);
        assertThat(metric.precision()).isCloseTo(1.0 / 3, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(metric.ndcg()).isCloseTo(0.6309, org.assertj.core.data.Offset.offset(1e-3));
    }

    @Test
    void shouldComputeRecallForMultipleExpectedDocuments() {
        List<RagMetricCalculator.RetrievedItem> retrieved = List.of(
                item(10L, "a"), item(11L, "b"), item(12L, "c"));

        RagMetricCalculator.CaseMetric metric = calculator.evaluate(retrieved, Set.of(), Set.of(10L, 11L, 12L, 13L), List.of(), 5);

        assertThat(metric.recall()).isEqualTo(0.75);
        assertThat(metric.precision()).isEqualTo(1.0);
        assertThat(metric.ndcg()).isCloseTo(0.8319, org.assertj.core.data.Offset.offset(1e-3));
    }

    @Test
    void shouldMatchByKeywordWhenDocumentIdsAbsent() {
        List<RagMetricCalculator.RetrievedItem> retrieved = List.of(
                item(20L, "空调不制冷时先确认遥控器模式并清洗滤网"),
                item(21L, "水管漏水处理流程"));

        RagMetricCalculator.CaseMetric metric = calculator.evaluate(retrieved, Set.of(), Set.of(), List.of("空调不制冷"), 5);

        assertThat(metric.hit()).isTrue();
        assertThat(metric.firstRelevantRank()).isEqualTo(1);
        assertThat(metric.reciprocalRank()).isEqualTo(1.0);
    }

    @Test
    void shouldMissWhenNothingRelevant() {
        List<RagMetricCalculator.RetrievedItem> retrieved = List.of(item(30L, "无关内容"));

        RagMetricCalculator.CaseMetric metric = calculator.evaluate(retrieved, Set.of(), Set.of(31L), List.of("不存在的关键词"), 5);

        assertThat(metric.hit()).isFalse();
        assertThat(metric.firstRelevantRank()).isZero();
        assertThat(metric.recall()).isZero();
        assertThat(metric.ndcg()).isZero();
    }

    @Test
    void shouldReturnZeroMetricsForEmptyRetrieval() {
        RagMetricCalculator.CaseMetric metric = calculator.evaluate(List.of(), Set.of(), Set.of(40L), List.of(), 5);

        assertThat(metric.hit()).isFalse();
        assertThat(metric.precision()).isZero();
        assertThat(metric.recall()).isZero();
    }

    @Test
    void shouldOnlyConsiderTopKResults() {
        List<RagMetricCalculator.RetrievedItem> retrieved = List.of(
                item(50L, "a"), item(51L, "b"), item(52L, "目标"));

        RagMetricCalculator.CaseMetric metric = calculator.evaluate(retrieved, Set.of(), Set.of(52L), List.of(), 2);

        assertThat(metric.hit()).isFalse();
        assertThat(metric.recall()).isZero();
    }

    @Test
    void shouldIgnoreNullDocumentIdWhenDocumentIdsExpected() {
        List<RagMetricCalculator.RetrievedItem> retrieved = List.of(
                new RagMetricCalculator.RetrievedItem(null, null, "无归属片段"),
                item(60L, "目标片段"));

        RagMetricCalculator.CaseMetric metric = calculator.evaluate(retrieved, Set.of(), Set.of(60L), List.of(), 5);

        assertThat(metric.firstRelevantRank()).isEqualTo(2);
    }

    @Test
    void shouldJudgeByChunkIdsWithHigherPriorityThanDocumentsAndKeywords() {
        // 期望切片是 9001；检索命中了同文档的 9002（含期望关键词）也不能算相关
        List<RagMetricCalculator.RetrievedItem> retrieved = List.of(
                new RagMetricCalculator.RetrievedItem(9002L, 70L, "水龙头漏水处理：含角阀与密封圈"),
                new RagMetricCalculator.RetrievedItem(9001L, 70L, "术语化表述：出水口持续滴漏"));

        RagMetricCalculator.CaseMetric metric = calculator.evaluate(retrieved, Set.of(9001L), Set.of(70L),
                List.of("角阀"), 5);

        assertThat(metric.hit()).isTrue();
        assertThat(metric.firstRelevantRank()).as("只有目标切片算相关").isEqualTo(2);
        assertThat(metric.recall()).isEqualTo(1.0);
        assertThat(metric.reciprocalRank()).isEqualTo(0.5);
    }

    @Test
    void shouldMissWhenTargetChunkNotRetrievedEvenIfSameDocumentReturned() {
        List<RagMetricCalculator.RetrievedItem> retrieved = List.of(
                new RagMetricCalculator.RetrievedItem(9002L, 70L, "同文档的其它切片"),
                new RagMetricCalculator.RetrievedItem(9003L, 70L, "同文档的第三个切片"));

        RagMetricCalculator.CaseMetric metric = calculator.evaluate(retrieved, Set.of(9001L), Set.of(70L),
                List.of(), 5);

        assertThat(metric.hit()).as("切片级判据下，命中同文档但非目标切片不算命中").isFalse();
        assertThat(metric.firstRelevantRank()).isZero();
    }

    private RagMetricCalculator.RetrievedItem item(Long documentId, String content) {
        return calculator.item(null, documentId, content);
    }
}

