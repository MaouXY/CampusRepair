package com.maou.apptemplateapi.module.rageval.dto;

import java.time.LocalDateTime;

public record RagEvalRunResponse(
        Long id,
        String datasetName,
        Integer topK,
        String status,
        String errorMessage,
        RagEvalMetricsResponse metrics,
        Long triggeredBy,
        LocalDateTime createdAt,
        LocalDateTime finishedAt
) {
}
