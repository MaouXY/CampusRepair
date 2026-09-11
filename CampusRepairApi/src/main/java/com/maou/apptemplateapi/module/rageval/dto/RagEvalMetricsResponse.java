package com.maou.apptemplateapi.module.rageval.dto;

import java.math.BigDecimal;

public record RagEvalMetricsResponse(
        int caseCount,
        int answerableCaseCount,
        int unanswerableCaseCount,
        BigDecimal hitRate,
        BigDecimal recallAtK,
        BigDecimal precisionAtK,
        BigDecimal mrr,
        BigDecimal ndcgAtK,
        BigDecimal refusalAccuracy,
        long avgLatencyMs
) {
}
