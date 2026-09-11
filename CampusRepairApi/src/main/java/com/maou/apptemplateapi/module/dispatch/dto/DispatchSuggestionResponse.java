package com.maou.apptemplateapi.module.dispatch.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record DispatchSuggestionResponse(
        Long ticketId,
        String categoryName,
        List<String> requiredSkills,
        String expectedDepartment,
        String recommendationSource,
        Long aiAnalysisId,
        String aiAnalysisStatus,
        Long recommendedWorkerId,
        String recommendedWorkerName,
        String recommendedReason,
        BigDecimal recommendedConfidence,
        String dispatchRemark,
        LocalDateTime generatedAt,
        List<DispatchCandidateResponse> candidates
) {
}
