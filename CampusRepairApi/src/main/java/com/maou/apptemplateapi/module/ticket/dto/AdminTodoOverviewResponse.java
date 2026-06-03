package com.maou.apptemplateapi.module.ticket.dto;

import java.util.List;

public record AdminTodoOverviewResponse(
        Long pendingReview,
        Long returned,
        Long overdue,
        Long urged,
        Long waitingConfirm,
        List<TicketSummaryResponse> latestTickets
) {
}
