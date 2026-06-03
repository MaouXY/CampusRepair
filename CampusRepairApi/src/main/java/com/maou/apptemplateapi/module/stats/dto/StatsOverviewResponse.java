package com.maou.apptemplateapi.module.stats.dto;

import java.math.BigDecimal;
import java.util.List;

public record StatsOverviewResponse(
        Long todayTickets,
        Long pendingReview,
        Long processing,
        Long completed,
        BigDecimal averageScore,
        List<StatsItemResponse> categoryDistribution
) {
}
