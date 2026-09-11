package com.maou.apptemplateapi.module.rageval.dto;

import java.math.BigDecimal;
import java.util.List;

public record RagEvalCaseResultResponse(
        Long caseId,
        String question,
        boolean hit,
        Integer firstRelevantRank,
        BigDecimal recall,
        BigDecimal precisionScore,
        BigDecimal reciprocalRank,
        BigDecimal ndcg,
        Long latencyMs,
        List<RetrievedChunkResponse> retrievedChunks
) {

    public record RetrievedChunkResponse(
            Long chunkId,
            Long documentId,
            String title,
            Double score,
            String source
    ) {
    }
}
