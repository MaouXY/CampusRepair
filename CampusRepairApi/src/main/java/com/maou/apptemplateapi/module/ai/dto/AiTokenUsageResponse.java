package com.maou.apptemplateapi.module.ai.dto;

import com.maou.apptemplateapi.module.ai.enums.DegradeLevel;

import java.time.LocalDate;

public record AiTokenUsageResponse(
        LocalDate date,
        boolean monitorEnabled,
        long dailyBudget,
        long usedTokens,
        long remainingTokens,
        double usedRatio,
        long todayCallCount,
        long degradedCallCount,
        DegradeLevel currentLevel,
        String message
) {
}
