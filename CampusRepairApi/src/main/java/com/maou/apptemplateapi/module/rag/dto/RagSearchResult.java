package com.maou.apptemplateapi.module.rag.dto;

import java.util.List;

public record RagSearchResult(
        List<RagChunkResponse> chunks,
        int keywordCandidateCount,
        int vectorCandidateCount,
        int overlapCount,
        int fusedCount,
        String topSource,
        boolean vectorEnabled
) {

    public static RagSearchResult empty() {
        return new RagSearchResult(List.of(), 0, 0, 0, 0, "NONE", false);
    }
}
