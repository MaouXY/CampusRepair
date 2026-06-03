package com.maou.apptemplateapi.module.ticket.dto;

import java.time.LocalDateTime;
import java.util.List;

public record TicketDetailResponse(
        Long id,
        String status,
        String priority,
        Long studentId,
        String studentName,
        Long locationId,
        String locationName,
        Long categoryId,
        String categoryName,
        String description,
        String contactPhone,
        String summary,
        Long assignedWorkerId,
        String assignedWorkerName,
        Long assignedAdminId,
        String assignedAdminName,
        LocalDateTime assignedAt,
        String rejectReason,
        String returnReason,
        String processResult,
        String processRemark,
        LocalDateTime processedAt,
        LocalDateTime slaDeadlineAt,
        Boolean slaOverdue,
        LocalDateTime urgedAt,
        Long urgedBy,
        String urgeRemark,
        String reportImageUrls,
        String resultImageUrls,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        List<TicketFlowResponse> flows,
        TicketEvaluationResponse evaluation
) {
}
