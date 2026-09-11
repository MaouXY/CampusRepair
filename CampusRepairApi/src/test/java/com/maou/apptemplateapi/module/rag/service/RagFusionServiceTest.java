package com.maou.apptemplateapi.module.rag.service;

import com.maou.apptemplateapi.module.rag.dto.RagChunkResponse;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RagFusionServiceTest {

    private static final int RRF_K = 60;
    private static final int LIMIT = 10;

    private final RagFusionService fusionService = new RagFusionService();

    @Test
    void shouldRankChunkRecalledByBothSourcesFirst() {
        Map<String, List<RagChunkResponse>> rankedLists = mergeLists(
                ranked(RagFusionService.SOURCE_KEYWORD, chunk(1L, "水管漏水排查步骤"), chunk(2L, "空调滤网清洗")),
                ranked(RagFusionService.SOURCE_VECTOR, chunk(1L, "水管漏水排查步骤"), chunk(3L, "照明灯管更换")));

        List<RagFusionService.RagFusionResult> fused = fusionService.fuse(rankedLists, Map.of(), RRF_K, LIMIT);

        assertThat(fused).hasSize(3);
        assertThat(fused.get(0).chunk().id()).isEqualTo(1L);
        assertThat(fused.get(0).fromBothSources()).isTrue();
        assertThat(fused.get(0).keywordRank()).isEqualTo(1);
        assertThat(fused.get(0).vectorRank()).isEqualTo(1);
        assertThat(fused.get(0).fusedScore()).isGreaterThan(fused.get(1).fusedScore());
    }

    @Test
    void shouldKeepRankOrderWithinSingleSource() {
        Map<String, List<RagChunkResponse>> rankedLists = ranked(RagFusionService.SOURCE_KEYWORD,
                chunk(1L, "A"), chunk(2L, "B"), chunk(3L, "C"));

        List<RagFusionService.RagFusionResult> fused = fusionService.fuse(rankedLists, Map.of(), RRF_K, LIMIT);

        assertThat(fused).extracting(result -> result.chunk().id()).containsExactly(1L, 2L, 3L);
        assertThat(fused.get(0).fusedScore()).isCloseTo(1.0 / (RRF_K + 1), Offset.offset(1e-9));
        assertThat(fused.get(2).fusedScore()).isCloseTo(1.0 / (RRF_K + 3), Offset.offset(1e-9));
    }

    @Test
    void shouldRespectSourceWeights() {
        Map<String, List<RagChunkResponse>> rankedLists = mergeLists(
                ranked(RagFusionService.SOURCE_KEYWORD, chunk(1L, "关键词命中")),
                ranked(RagFusionService.SOURCE_VECTOR, chunk(2L, "向量命中")));

        List<RagFusionService.RagFusionResult> equalWeight = fusionService.fuse(rankedLists, Map.of(), RRF_K, LIMIT);
        assertThat(equalWeight.get(0).chunk().id()).isEqualTo(1L);

        Map<String, Double> vectorHeavy = new LinkedHashMap<>();
        vectorHeavy.put(RagFusionService.SOURCE_KEYWORD, 1.0);
        vectorHeavy.put(RagFusionService.SOURCE_VECTOR, 3.0);
        List<RagFusionService.RagFusionResult> weighted = fusionService.fuse(rankedLists, vectorHeavy, RRF_K, LIMIT);

        assertThat(weighted.get(0).chunk().id()).isEqualTo(2L);
        assertThat(weighted.get(0).fusedScore()).isCloseTo(3.0 / (RRF_K + 1), Offset.offset(1e-9));
    }

    @Test
    void shouldDeduplicateSameChunkAndCountOverlap() {
        Map<String, List<RagChunkResponse>> rankedLists = mergeLists(
                ranked(RagFusionService.SOURCE_KEYWORD, chunk(1L, "共同命中"), chunk(2L, "关键词独有")),
                ranked(RagFusionService.SOURCE_VECTOR, chunk(1L, "共同命中")));

        List<RagFusionService.RagFusionResult> fused = fusionService.fuse(rankedLists, Map.of(), RRF_K, LIMIT);

        assertThat(fused).hasSize(2);
        assertThat(fused.stream().filter(RagFusionService.RagFusionResult::fromBothSources)).hasSize(1);
        assertThat(fused.get(0).sourceLabel()).isEqualTo("KEYWORD+VECTOR");
    }

    @Test
    void shouldFallbackToContentKeyWhenChunkIdMissing() {
        RagChunkResponse idless = new RagChunkResponse(null, 88L, "Milvus", "无主键的向量片段", 0.8);
        Map<String, List<RagChunkResponse>> rankedLists = ranked(RagFusionService.SOURCE_VECTOR, idless, idless);

        List<RagFusionService.RagFusionResult> fused = fusionService.fuse(rankedLists, Map.of(), RRF_K, LIMIT);

        assertThat(fused).hasSize(1);
        assertThat(fused.get(0).key()).startsWith("doc:88:hash:");
        assertThat(fused.get(0).vectorRank()).isEqualTo(1);
    }

    @Test
    void shouldReturnEmptyWhenNothingRecalled() {
        Map<String, List<RagChunkResponse>> emptyLists = mergeLists(
                ranked(RagFusionService.SOURCE_KEYWORD, new RagChunkResponse[0]),
                ranked(RagFusionService.SOURCE_VECTOR, new RagChunkResponse[0]));

        assertThat(fusionService.fuse(emptyLists, Map.of(), RRF_K, 5)).isEmpty();
        assertThat(fusionService.fuse(Map.of(), Map.of(), RRF_K, 5)).isEmpty();
        assertThat(fusionService.fuse(emptyLists, Map.of(), RRF_K, 0)).isEmpty();
    }

    private Map<String, List<RagChunkResponse>> ranked(String source, RagChunkResponse... chunks) {
        Map<String, List<RagChunkResponse>> lists = new LinkedHashMap<>();
        lists.put(source, List.of(chunks));
        return lists;
    }

    private Map<String, List<RagChunkResponse>> mergeLists(Map<String, List<RagChunkResponse>> first,
                                                          Map<String, List<RagChunkResponse>> second) {
        Map<String, List<RagChunkResponse>> merged = new LinkedHashMap<>(first);
        merged.putAll(second);
        return merged;
    }

    private RagChunkResponse chunk(Long id, String content) {
        return new RagChunkResponse(id, 10L, "测试文档", content, 1.0);
    }
}
