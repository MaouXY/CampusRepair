package com.maou.apptemplateapi.module.rageval.dto;

import java.util.List;

public record RagEvalRunDetailResponse(
        RagEvalRunResponse run,
        List<RagEvalCaseResultResponse> results
) {
}
