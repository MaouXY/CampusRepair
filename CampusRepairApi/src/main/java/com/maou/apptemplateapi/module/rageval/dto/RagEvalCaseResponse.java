package com.maou.apptemplateapi.module.rageval.dto;

import java.time.LocalDateTime;
import java.util.List;

public record RagEvalCaseResponse(
        Long id,
        String datasetName,
        String question,
        List<Long> expectedChunkIds,
        List<Long> expectedDocIds,
        List<String> expectedKeywords,
        Integer answerable,
        String taskType,
        Long categoryId,
        String source,
        LocalDateTime createdAt
) {
}
