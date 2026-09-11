package com.maou.apptemplateapi.module.stats.dto;

import java.math.BigDecimal;
import java.util.List;

public record MonthlyReportResponse(
        String month,
        long createdCount,
        long completedCount,
        long rejectedCount,
        long evaluatingScoreCount,
        BigDecimal avgProcessMinutes,
        BigDecimal avgScore,
        BigDecimal goodRate,
        BigDecimal overdueRate,
        List<StatsItemResponse> topCategories,
        List<StatsItemResponse> topLocations,
        List<WorkerPerformanceResponse> topWorkers,
        String aiSummary,
        boolean aiDegraded
) {
}
