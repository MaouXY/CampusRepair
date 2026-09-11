package com.maou.apptemplateapi.module.stats.dto;

import java.util.List;

public record HotspotsResponse(
        int days,
        long totalTickets,
        List<StatsItemResponse> categories,
        List<StatsItemResponse> locations
) {
}
