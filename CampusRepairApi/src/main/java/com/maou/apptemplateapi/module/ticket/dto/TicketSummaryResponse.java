package com.maou.apptemplateapi.module.ticket.dto;

import java.time.LocalDateTime;

public record TicketSummaryResponse(
        Long id,
        String status,
        String priority,
        Long studentId,
        String studentName,
        Long locationId,
        String locationName,
        Long categoryId,
        String categoryName,
        Long assignedWorkerId,
        String assignedWorkerName,
        String summary,
        LocalDateTime slaDeadlineAt,
        Boolean slaOverdue,
        LocalDateTime urgedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
