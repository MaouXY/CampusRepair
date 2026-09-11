package com.maou.apptemplateapi.module.rageval.dto;

public record RagEvalRunRequest(
        String datasetName,
        Integer topK
) {
}
