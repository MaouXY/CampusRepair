package com.maou.apptemplateapi.module.rag.service;

import com.maou.apptemplateapi.module.rag.dto.RagChunkResponse;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RRF 与 rerank 的融合排序：窗口限制、加权融合、量纲归一化、重排缺失时的退化行为。
 */
class RagRankBlenderTest {

    private static final String SCENARIO = "test-rank-blend";

    private final RagFusionService fusionService = new RagFusionService();
    private final RagRankBlender blender = new RagRankBlender();

    @Test
    void shouldKeepRrfOrderWhenRerankNotApplied() {
        List<RagFusionService.RagFusionResult> fused = fused(
                ranked(RagFusionService.SOURCE_KEYWORD, chunk(1L, "A"), chunk(2L, "B"), chunk(3L, "C")),
                Map.of());

        List<RagChunkResponse> ordered = blender.blend(fused, Map.of(), 0.7, 20, 3, SCENARIO);

        assertThat(ordered).extracting(RagChunkResponse::id).containsExactly(1L, 2L, 3L);
    }

    @Test
    void shouldFollowRerankFullyWhenBlendWeightIsOne() {
        List<RagFusionService.RagFusionResult> fused = fused(
                ranked(RagFusionService.SOURCE_KEYWORD, chunk(1L, "A"), chunk(2L, "B"), chunk(3L, "C")),
                Map.of());
        Map<String, Double> rerankScores = Map.of("chunk:1", 0.1, "chunk:2", 0.2, "chunk:3", 0.9);

        List<RagChunkResponse> ordered = blender.blend(fused, rerankScores, 1.0, 20, 3, SCENARIO);

        assertThat(ordered).extracting(RagChunkResponse::id).containsExactly(3L, 2L, 1L);
    }

    @Test
    void shouldKeepRrfOrderWhenBlendWeightIsZero() {
        List<RagFusionService.RagFusionResult> fused = fused(
                ranked(RagFusionService.SOURCE_KEYWORD, chunk(1L, "A"), chunk(2L, "B"), chunk(3L, "C")),
                Map.of());
        Map<String, Double> rerankScores = Map.of("chunk:1", 0.1, "chunk:2", 0.2, "chunk:3", 0.9);

        List<RagChunkResponse> ordered = blender.blend(fused, rerankScores, 0.0, 20, 3, SCENARIO);

        assertThat(ordered).extracting(RagChunkResponse::id).containsExactly(1L, 2L, 3L);
    }

    @Test
    void shouldBalanceRrfConsensusAgainstRerankSignal() {
        // chunk:1 被两路同时召回（RRF 第 1），但 rerank 认为 chunk:2 更相关
        Map<String, List<RagChunkResponse>> rankedLists = new LinkedHashMap<>();
        rankedLists.put(RagFusionService.SOURCE_KEYWORD, List.of(chunk(1L, "A"), chunk(2L, "B")));
        rankedLists.put(RagFusionService.SOURCE_VECTOR, List.of(chunk(1L, "A")));
        List<RagFusionService.RagFusionResult> fused = fusionService.fuse(rankedLists, Map.of(), 60, 10);
        Map<String, Double> rerankScores = Map.of("chunk:1", 0.2, "chunk:2", 1.0);

        List<RagChunkResponse> rrfOnly = blender.blend(fused, Map.of(), 0.7, 20, 2, SCENARIO);
        List<RagChunkResponse> lightRerank = blender.blend(fused, rerankScores, 0.3, 20, 2, SCENARIO);
        List<RagChunkResponse> strongRerank = blender.blend(fused, rerankScores, 0.6, 20, 2, SCENARIO);
        List<RagChunkResponse> rerankOnly = blender.blend(fused, rerankScores, 1.0, 20, 2, SCENARIO);

        assertThat(rrfOnly.get(0).id()).as("纯 RRF：双路命中者第一").isEqualTo(1L);
        assertThat(lightRerank.get(0).id()).as("α 较低时 RRF 共识仍占主导").isEqualTo(1L);
        assertThat(strongRerank.get(0).id()).as("α 较高时重排信号反超").isEqualTo(2L);
        assertThat(rerankOnly.get(0).id()).isEqualTo(2L);
    }

    @Test
    void shouldOnlyRerankWindowAndKeepTailInRrfOrder() {
        List<RagFusionService.RagFusionResult> fused = fused(
                ranked(RagFusionService.SOURCE_KEYWORD, chunk(1L, "A"), chunk(2L, "B"),
                        chunk(3L, "C"), chunk(4L, "D"), chunk(5L, "E")),
                Map.of());
        Map<String, Double> rerankScores = Map.of("chunk:1", 0.1, "chunk:2", 0.9);

        List<RagChunkResponse> ordered = blender.blend(fused, rerankScores, 1.0, 2, 5, SCENARIO);

        assertThat(ordered).extracting(RagChunkResponse::id).as("只有窗口内的 1、2 参与重排").containsExactly(2L, 1L, 3L, 4L, 5L);
    }

    @Test
    void shouldLimitResults() {
        List<RagFusionService.RagFusionResult> fused = fused(
                ranked(RagFusionService.SOURCE_KEYWORD, chunk(1L, "A"), chunk(2L, "B"), chunk(3L, "C")),
                Map.of());

        assertThat(blender.blend(fused, Map.of(), 0.7, 20, 2, SCENARIO)).hasSize(2);
    }

    @Test
    void shouldReturnEmptyForEmptyInput() {
        assertThat(blender.blend(List.of(), Map.of(), 0.7, 20, 5, SCENARIO)).isEmpty();
        assertThat(blender.blend(null, Map.of(), 0.7, 20, 5, SCENARIO)).isEmpty();
    }

    @Test
    void shouldNormalizeScoresBeforeBlending() {
        // RRF 分数约 0.016 量级、rerank 分数 0~1；若不做归一化，加权求和会被 rerank 量纲吞掉
        List<RagFusionService.RagFusionResult> fused = fused(
                ranked(RagFusionService.SOURCE_KEYWORD, chunk(1L, "A"), chunk(2L, "B")),
                Map.of());
        double rrfTop = fused.get(0).fusedScore();
        double rrfSecond = fused.get(1).fusedScore();
        assertThat(rrfTop).isCloseTo(1.0 / 61, Offset.offset(1e-9));
        assertThat(rrfSecond).isCloseTo(1.0 / 62, Offset.offset(1e-9));

        // rerank 完全反向时，α=0.5 应让两个候选平分秋色：归一化后各占 0.5
        Map<String, Double> rerankScores = Map.of("chunk:1", 0.0, "chunk:2", 1.0);
        List<RagChunkResponse> ordered = blender.blend(fused, rerankScores, 0.5, 20, 2, SCENARIO);

        assertThat(ordered).hasSize(2);
        assertThat(ordered.get(0).id()).as("两条候选融合分相同，回退到 RRF 顺序").isEqualTo(1L);
    }

    private List<RagFusionService.RagFusionResult> fused(Map<String, List<RagChunkResponse>> rankedLists,
                                                        Map<String, Double> weights) {
        return fusionService.fuse(rankedLists, weights, 60, 20);
    }

    private Map<String, List<RagChunkResponse>> ranked(String source, RagChunkResponse... chunks) {
        Map<String, List<RagChunkResponse>> lists = new LinkedHashMap<>();
        lists.put(source, List.of(chunks));
        return lists;
    }

    private RagChunkResponse chunk(Long id, String content) {
        return new RagChunkResponse(id, 10L, "测试文档", content, 1.0);
    }
}
