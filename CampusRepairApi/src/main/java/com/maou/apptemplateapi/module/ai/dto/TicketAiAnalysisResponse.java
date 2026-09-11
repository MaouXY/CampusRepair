package com.maou.apptemplateapi.module.ai.dto;

import com.maou.apptemplateapi.module.dispatch.dto.DispatchCandidateResponse;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record TicketAiAnalysisResponse(
        Long id,
        Long ticketId,
        Long aiTaskId,
        String status,
        Long suggestedCategoryId,
        String suggestedPriority,
        Long suggestedWorkerId,
        String faultSummary,
        String faultReason,
        String solution,
        String dispatchRemark,
        String riskLevel,
        BigDecimal confidence,
        String rawResponse,
        LocalDateTime createdAt,
        List<DispatchCandidateResponse> dispatchCandidates
) {
}
