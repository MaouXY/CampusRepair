package com.maou.apptemplateapi.module.stats.dto;

import java.math.BigDecimal;

public record WorkerPerformanceResponse(
        Long workerId,
        String workerName,
        String departmentName,
        long completedCount,
        BigDecimal avgProcessMinutes,
        BigDecimal avgScore,
        BigDecimal goodRate,
        long evaluationCount,
        long returnCount,
        long reworkCount,
        long activeCount,
        long overdueCount
) {
}
