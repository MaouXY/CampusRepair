package com.maou.apptemplateapi.module.ticket.dto;

import java.util.List;

public record WorkerTodayOverviewResponse(
        Long assigned,
        Long processing,
        Long waitingConfirm,
        Long overdue,
        List<TicketSummaryResponse> dueTodayTickets
) {
}
