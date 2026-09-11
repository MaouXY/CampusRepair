package com.maou.apptemplateapi.module.rag.service;

import com.maou.apptemplateapi.module.rag.dto.RagSearchDebugResponse;
import com.maou.apptemplateapi.module.rag.entity.RagKnowledgeDocument;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 检索链路可观测性：把各阶段候选与分数整理成调试视图。
 *
 * <p>与 {@link RagKnowledgeService#searchDetailed} 使用同一套检索组件，但保留中间结果，
 * 便于回答「是召回不到，还是排不进前 N」，也方便答辩现场演示混合检索的分段效果。
 */
@Slf4j
@Service
public class RagSearchTraceService {

    public RagSearchDebugResponse toDebugResponse(String query,
                                                  int limit,
                                                  boolean hybridEnabled,
                                                  boolean vectorEnabled,
                                                  int keywordTopK,
                                                  int vectorTopK,
                                                  int minKeywordHits,
                                                  double minVectorScore,
                                                  boolean rerankEnabled,
                                                  String rerankSkipReason,
                                                  double blendWeight,
                                                  int rerankWindow,
                                                  Map<Long, RagKnowledgeDocument> docMap,
                                                  List<com.maou.apptemplateapi.module.rag.dto.RagChunkResponse> keywordMatches,
                                                  List<com.maou.apptemplateapi.module.rag.dto.RagChunkResponse> vectorMatches,
                                                  List<RagFusionService.RagFusionResult> fused,
                                                  Map<Long, Integer> finalRanks,
                                                  Map<String, Double> rerankScores) {
        List<RagSearchDebugResponse.StageItem> keywordStage = keywordMatches.stream()
                .map(match -> new RagSearchDebugResponse.StageItem(match.id(), match.documentId(),
                        titleOf(docMap, match.documentId()), match.score(), RagFusionService.SOURCE_KEYWORD))
                .toList();
        List<RagSearchDebugResponse.StageItem> vectorStage = vectorMatches.stream()
                .map(match -> new RagSearchDebugResponse.StageItem(match.id(), match.documentId(),
                        titleOf(docMap, match.documentId()), match.score(), RagFusionService.SOURCE_VECTOR))
                .toList();
        List<RagSearchDebugResponse.FusedItem> fusedStage = new ArrayList<>();
        for (RagFusionService.RagFusionResult result : fused) {
            Long chunkId = result.chunk().id();
            fusedStage.add(new RagSearchDebugResponse.FusedItem(chunkId, result.chunk().documentId(),
                    titleOf(docMap, result.chunk().documentId()), round(result.fusedScore()), result.sources(),
                    result.keywordRank(), result.vectorRank(),
                    rerankScores == null ? null : scoreOf(rerankScores, result.key()),
                    null,
                    finalRanks.getOrDefault(chunkId, 0)));
        }
        List<RagSearchDebugResponse.StageItem> finalStage = new ArrayList<>();
        fusedStage.stream()
                .filter(item -> item.finalRank() > 0)
                .sorted(java.util.Comparator.comparingInt(RagSearchDebugResponse.FusedItem::finalRank))
                .forEach(item -> finalStage.add(new RagSearchDebugResponse.StageItem(item.chunkId(), item.documentId(),
                        item.title(),
                        item.rerankScore() != null ? item.rerankScore() : item.rrfScore(),
                        String.join("+", item.sources()))));
        log.info("rag search debug, scenario=admin-rag-search-debug, query={}, keywordStage={}, vectorStage={}, fusedStage={}, finalStage={}, rerankEnabled={}, rerankSkipReason={}",
                query, keywordStage.size(), vectorStage.size(), fusedStage.size(), finalStage.size(),
                rerankEnabled, rerankSkipReason);
        return new RagSearchDebugResponse(query, limit, hybridEnabled, vectorEnabled, keywordTopK, vectorTopK,
                minKeywordHits, minVectorScore, rerankEnabled, rerankSkipReason, blendWeight, rerankWindow,
                keywordStage, vectorStage, fusedStage, finalStage);
    }

    public Map<Long, Integer> rankMap(List<com.maou.apptemplateapi.module.rag.dto.RagChunkResponse> ordered) {
        Map<Long, Integer> ranks = new LinkedHashMap<>();
        int rank = 1;
        for (com.maou.apptemplateapi.module.rag.dto.RagChunkResponse chunk : ordered) {
            if (chunk.id() != null) {
                ranks.putIfAbsent(chunk.id(), rank);
            }
            rank++;
        }
        return ranks;
    }

    private String titleOf(Map<Long, RagKnowledgeDocument> docMap, Long documentId) {
        if (documentId == null || docMap == null) {
            return null;
        }
        RagKnowledgeDocument document = docMap.get(documentId);
        return document == null ? null : document.getTitle();
    }

    private Double scoreOf(Map<String, Double> scores, String key) {
        Double score = scores.get(key);
        return score == null ? null : round(score);
    }

    private double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }
}
