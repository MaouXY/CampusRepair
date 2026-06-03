package com.maou.apptemplateapi.module.ticket.dto;

import java.time.LocalDateTime;

public record TicketFlowResponse(
        Long id,
        String fromStatus,
        String toStatus,
        Long operatorId,
        String operatorRole,
        String action,
        String remark,
        LocalDateTime createdAt
) {
}
