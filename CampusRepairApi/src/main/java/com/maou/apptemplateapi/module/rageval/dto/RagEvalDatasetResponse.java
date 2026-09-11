package com.maou.apptemplateapi.module.rageval.dto;

public record RagEvalDatasetResponse(
        String datasetName,
        int caseCount,
        int answerableCaseCount,
        int unanswerableCaseCount
) {
}
