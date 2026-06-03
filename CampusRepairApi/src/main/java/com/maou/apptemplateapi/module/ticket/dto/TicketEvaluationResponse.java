package com.maou.apptemplateapi.module.ticket.dto;

import java.time.LocalDateTime;

public record TicketEvaluationResponse(
        Long id,
        Integer score,
        String content,
        LocalDateTime createdAt
) {
}
