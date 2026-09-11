package com.maou.apptemplateapi.module.stats.controller;

import com.maou.apptemplateapi.common.result.ApiResponse;
import com.maou.apptemplateapi.module.stats.dto.HotspotsResponse;
import com.maou.apptemplateapi.module.stats.dto.MonthlyReportResponse;
import com.maou.apptemplateapi.module.stats.dto.StatsOverviewResponse;
import com.maou.apptemplateapi.module.stats.dto.WorkerPerformanceResponse;
import com.maou.apptemplateapi.module.stats.service.AnalyticsService;
import com.maou.apptemplateapi.module.stats.service.StatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/stats")
public class AdminStatsController {

    private final StatsService statsService;
    private final AnalyticsService analyticsService;

    @GetMapping("/overview")
    public ApiResponse<StatsOverviewResponse> overview() {
        return ApiResponse.success(statsService.overview());
    }

    @GetMapping("/worker-performance")
    public ApiResponse<List<WorkerPerformanceResponse>> workerPerformance(
            @RequestParam(required = false) Integer days) {
        return ApiResponse.success(analyticsService.workerPerformance(days));
    }

    @GetMapping("/hotspots")
    public ApiResponse<HotspotsResponse> hotspots(
            @RequestParam(required = false) Integer days,
            @RequestParam(required = false) Integer limit) {
        return ApiResponse.success(analyticsService.hotspots(days, limit));
    }

    @GetMapping("/monthly-report")
    public ApiResponse<MonthlyReportResponse> monthlyReport(
            @RequestParam(required = false) String month) {
        return ApiResponse.success(analyticsService.monthlyReport(month));
    }
}
