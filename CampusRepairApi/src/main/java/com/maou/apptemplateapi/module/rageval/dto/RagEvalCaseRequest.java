package com.maou.apptemplateapi.module.rageval.dto;

import java.util.List;

public record RagEvalCaseRequest(
        String datasetName,
        String question,
        List<Long> expectedDocIds,
        List<String> expectedKeywords,
        Integer answerable,
        String taskType,
        Long categoryId,
        String source
) {
}
