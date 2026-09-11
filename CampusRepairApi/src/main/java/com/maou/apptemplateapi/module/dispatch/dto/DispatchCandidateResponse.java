package com.maou.apptemplateapi.module.dispatch.dto;

import java.math.BigDecimal;
import java.util.List;

public record DispatchCandidateResponse(
        Long workerId,
        String workerName,
        String departmentName,
        List<String> skillTags,
        Integer activeOrderCount,
        Integer maxActiveOrders,
        BigDecimal skillScore,
        BigDecimal departmentScore,
        BigDecimal workloadScore,
        BigDecimal qualityScore,
        BigDecimal penaltyScore,
        BigDecimal totalScore,
        String ruleReason,
        Boolean aiRecommended
) {
}
