package com.maou.apptemplateapi.module.ai.dto;

import com.maou.apptemplateapi.module.ai.enums.DegradeLevel;

import java.util.List;

/**
 * 调用前的降级决策：级别 + 原因列表 + 提示词预估 token。
 */
public record DegradeDecision(
        DegradeLevel level,
        List<String> reasons,
        long estimatedPromptTokens,
        long usedTokensToday,
        long dailyBudget
) {

    public static DegradeDecision normal(long estimatedPromptTokens, long usedTokensToday, long dailyBudget) {
        return new DegradeDecision(DegradeLevel.NORMAL, List.of(), estimatedPromptTokens, usedTokensToday, dailyBudget);
    }

    public boolean degraded() {
        return level != null && !level.isNormal();
    }

    public String reasonText() {
        return reasons.isEmpty() ? "无" : String.join("；", reasons);
    }
}
