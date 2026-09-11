package com.maou.apptemplateapi.module.rag.service;

import com.maou.apptemplateapi.module.rag.dto.RagChunkResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 倒数排名融合（Reciprocal Rank Fusion, RRF）。
 *
 * <p>把关键词检索与向量检索两路召回结果按名次融合：score = Σ weight / (k + rank)，
 * 其中 k 为平滑常数（默认 60，取自 RRF 原始论文 Cormack et al. 2009）。
 * RRF 只依赖名次、不依赖分数量纲，因此可以把「关键词命中次数」与「向量余弦相似度」
 * 这两种不可比的分数安全地融合到一起。
 */
@Slf4j
@Service
public class RagFusionService {

    public static final String SOURCE_KEYWORD = "KEYWORD";
    public static final String SOURCE_VECTOR = "VECTOR";

    public static final int DEFAULT_RRF_K = 60;

    public List<RagFusionResult> fuse(Map<String, List<RagChunkResponse>> rankedLists,
                                      Map<String, Double> sourceWeights,
                                      int rrfK,
                                      int limit) {
        if (rankedLists == null || rankedLists.isEmpty() || limit <= 0) {
            return List.of();
        }
        int effectiveK = Math.max(rrfK, 1);
        Map<String, MutableFusion> fusionMap = new LinkedHashMap<>();
        for (Map.Entry<String, List<RagChunkResponse>> entry : rankedLists.entrySet()) {
            String source = entry.getKey();
            List<RagChunkResponse> candidates = entry.getValue();
            if (candidates == null || candidates.isEmpty()) {
                continue;
            }
            double weight = weightOf(sourceWeights, source);
            int rank = 0;
            for (RagChunkResponse candidate : candidates) {
                if (candidate == null) {
                    continue;
                }
                rank++;
                String key = fusionKey(candidate);
                MutableFusion fusion = fusionMap.computeIfAbsent(key, ignored -> new MutableFusion(key, candidate));
                fusion.addScore(weight / (effectiveK + rank));
                fusion.addSource(source, rank);
            }
        }
        List<RagFusionResult> results = fusionMap.values().stream()
                .map(MutableFusion::toResult)
                .sorted(Comparator.comparingDouble(RagFusionResult::fusedScore).reversed()
                        .thenComparing(RagFusionResult::bestRank)
                        .thenComparing(RagFusionResult::key))
                .limit(limit)
                .toList();
        log.debug("rag rrf fused, scenario=rag-rrf-fuse, rrfK={}, inputLists={}, fusedCount={}, returnedCount={}",
                effectiveK, rankedLists.size(), fusionMap.size(), results.size());
        return results;
    }

    public String fusionKey(RagChunkResponse chunk) {
        if (chunk.id() != null) {
            return "chunk:" + chunk.id();
        }
        String documentId = chunk.documentId() == null ? "unknown" : String.valueOf(chunk.documentId());
        String content = chunk.content() == null ? "" : chunk.content().trim();
        return "doc:" + documentId + ":hash:" + Integer.toHexString(content.hashCode());
    }

    private double weightOf(Map<String, Double> sourceWeights, String source) {
        if (sourceWeights == null) {
            return 1.0;
        }
        Double weight = sourceWeights.get(source);
        return weight == null || weight <= 0 ? 1.0 : weight;
    }

    private static final class MutableFusion {

        private final String key;
        private final RagChunkResponse chunk;
        private final Set<String> sources = new LinkedHashSet<>();
        private final Map<String, Integer> ranks = new LinkedHashMap<>();
        private double fusedScore;
        private int bestRank = Integer.MAX_VALUE;

        private MutableFusion(String key, RagChunkResponse chunk) {
            this.key = key;
            this.chunk = chunk;
        }

        private void addScore(double score) {
            this.fusedScore += score;
        }

        private void addSource(String source, int rank) {
            this.sources.add(source);
            Integer existing = this.ranks.get(source);
            if (existing == null || rank < existing) {
                this.ranks.put(source, rank);
            }
            if (rank < this.bestRank) {
                this.bestRank = rank;
            }
        }

        private RagFusionResult toResult() {
            return new RagFusionResult(key, chunk, fusedScore, List.copyOf(sources),
                    ranks.get(SOURCE_KEYWORD), ranks.get(SOURCE_VECTOR), bestRank);
        }
    }

    public record RagFusionResult(
            String key,
            RagChunkResponse chunk,
            double fusedScore,
            List<String> sources,
            Integer keywordRank,
            Integer vectorRank,
            int bestRank
    ) {

        public boolean fromBothSources() {
            return sources.size() > 1;
        }

        public String sourceLabel() {
            return StringUtils.collectionToDelimitedString(sources, "+");
        }
    }
}
