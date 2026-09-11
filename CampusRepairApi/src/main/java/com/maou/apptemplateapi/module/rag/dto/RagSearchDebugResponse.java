package com.maou.apptemplateapi.module.rag.dto;

import java.util.List;

/**
 * 检索链路调试视图：把「关键词召回 → 向量召回 → RRF 融合 → 重排融合」每一段的候选与分数都暴露出来，
 * 用于验证混合检索是否真的生效、以及定位「召回不到」还是「排不进前 N」。
 */
public record RagSearchDebugResponse(
        String query,
        int limit,
        boolean hybridEnabled,
        boolean vectorEnabled,
        int keywordTopK,
        int vectorTopK,
        int minKeywordHits,
        double minVectorScore,
        boolean rerankEnabled,
        String rerankSkipReason,
        double rerankBlendWeight,
        int rerankWindow,
        List<StageItem> keywordStage,
        List<StageItem> vectorStage,
        List<FusedItem> fusedStage,
        List<StageItem> finalStage
) {

    public record StageItem(
            Long chunkId,
            Long documentId,
            String title,
            Double score,
            String source
    ) {
    }

    public record FusedItem(
            Long chunkId,
            Long documentId,
            String title,
            double rrfScore,
            List<String> sources,
            Integer keywordRank,
            Integer vectorRank,
            Double rerankScore,
            Double finalScore,
            int finalRank
    ) {
    }
}
