package com.maou.apptemplateapi.module.ai.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

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
        LocalDateTime createdAt
) {
}
