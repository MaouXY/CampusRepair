package com.maou.apptemplateapi.module.ai.service;

import com.maou.apptemplateapi.common.config.ai.AiProperties;
import com.maou.apptemplateapi.module.ai.dto.AiTokenUsage;
import com.maou.apptemplateapi.module.ai.dto.AiTokenUsageResponse;
import com.maou.apptemplateapi.module.ai.dto.DegradeDecision;
import com.maou.apptemplateapi.module.ai.enums.DegradeLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * AI token 用量监控与降级兜底。
 *
 * <p>实时统计当日 token 消耗（以 ai_task_record 为准，重启后仍准确），
 * 并在调用模型前给出降级决策，避免「提示词超长 / 预算超限」直接报错：
 * <ol>
 *   <li>当日用量达到预算 {@code ruleOnlyRatio}（默认 100%）→ 不再调用模型，直接规则兜底；</li>
 *   <li>提示词预估 token 超过 {@code maxPromptTokens} → 先丢图片，再压缩知识片段；</li>
 *   <li>当日用量达到预算 {@code degradeRatio}（默认 80%）→ 提前压缩上下文；</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiTokenMonitor {

    private static final long DEFAULT_DAILY_BUDGET = 200_000L;
    private static final double DEFAULT_DEGRADE_RATIO = 0.8;
    private static final double DEFAULT_RULE_ONLY_RATIO = 1.0;
    private static final int DEFAULT_MAX_PROMPT_TOKENS = 6_000;
    private static final int DEFAULT_MINIMAL_CONTEXT_CHUNKS = 2;

    private final AiProperties aiProperties;
    private final AiTokenEstimator estimator;
    private final AiTaskService aiTaskService;

    public boolean monitorEnabled() {
        AiProperties.Token token = token();
        return token == null || token.monitorEnabled() == null || token.monitorEnabled();
    }

    public long dailyBudget() {
        AiProperties.Token token = token();
        Long budget = token == null ? null : token.dailyBudget();
        return budget == null || budget <= 0 ? DEFAULT_DAILY_BUDGET : budget;
    }

    public int minimalContextChunks() {
        AiProperties.Token token = token();
        Integer chunks = token == null ? null : token.minimalContextChunks();
        return chunks == null || chunks <= 0 ? DEFAULT_MINIMAL_CONTEXT_CHUNKS : chunks;
    }

    public long usedTokensToday() {
        return aiTaskService.sumTotalTokensSince(startOfToday());
    }

    public DegradeDecision decideForRequest(long promptTokens, int imageCount, int ragChunkCount) {
        return decide(usedTokensToday(), promptTokens, imageCount, ragChunkCount);
    }

    /**
     * 纯函数降级决策，便于单测。
     */
    public DegradeDecision decide(long usedTokensToday, long promptTokens, int imageCount, int ragChunkCount) {
        long budget = dailyBudget();
        if (!monitorEnabled()) {
            return DegradeDecision.normal(promptTokens, usedTokensToday, budget);
        }
        double usedRatio = budget <= 0 ? 0 : (double) usedTokensToday / budget;
        double degradeRatio = ratio(token() == null ? null : token().degradeRatio(), DEFAULT_DEGRADE_RATIO);
        double ruleOnlyRatio = ratio(token() == null ? null : token().ruleOnlyRatio(), DEFAULT_RULE_ONLY_RATIO);
        int maxPromptTokens = positive(token() == null ? null : token().maxPromptTokens(), DEFAULT_MAX_PROMPT_TOKENS);

        List<String> reasons = new ArrayList<>();
        DegradeLevel level = DegradeLevel.NORMAL;

        if (usedRatio >= ruleOnlyRatio) {
            level = level.max(DegradeLevel.RULE_ONLY);
            reasons.add("今日 token 用量 %d 已达预算 %d 的 %.0f%%，跳过模型调用".formatted(usedTokensToday, budget, usedRatio * 100));
        } else if (usedRatio >= degradeRatio) {
            level = level.max(DegradeLevel.MINIMAL_CONTEXT);
            reasons.add("今日 token 用量 %d 已达预算 %d 的 %.0f%%，提前压缩上下文".formatted(usedTokensToday, budget, usedRatio * 100));
        }

        if (promptTokens > maxPromptTokens) {
            if (imageCount > 0) {
                level = level.max(DegradeLevel.DROP_IMAGES);
                reasons.add("提示词预估 %d token 超过阈值 %d，先舍弃 %d 张图片输入".formatted(promptTokens, maxPromptTokens, imageCount));
            }
            if (ragChunkCount > minimalContextChunks()) {
                level = level.max(DegradeLevel.MINIMAL_CONTEXT);
                reasons.add("提示词预估 %d token 超过阈值 %d，知识片段压缩到 %d 条".formatted(promptTokens, maxPromptTokens, minimalContextChunks()));
            }
            if (reasons.isEmpty()) {
                reasons.add("提示词预估 %d token 超过阈值 %d，但已无可压缩内容，按原样调用".formatted(promptTokens, maxPromptTokens));
            }
        }

        if (level.isRuleOnly()) {
            reasons.add("本次不调用大模型，改用规则评分与知识库结果兜底");
        }
        return new DegradeDecision(level, List.copyOf(reasons), promptTokens, usedTokensToday, budget);
    }

    public void recordUsage(AiTokenUsage usage, Long ticketId, String scenario, DegradeLevel level) {
        if (usage == null) {
            return;
        }
        log.info("ai token usage recorded, scenario={}, ticketId={}, degradeLevel={}, inputTokens={}, outputTokens={}, totalTokens={}, usageSource={}",
                scenario, ticketId, level, usage.inputTokens(), usage.outputTokens(), usage.total(), usage.source());
    }

    public AiTokenUsageResponse snapshot() {
        long used = usedTokensToday();
        long budget = dailyBudget();
        long degraded = aiTaskService.countDegradedSince(startOfToday());
        long calls = aiTaskService.countSince(startOfToday());
        double ratio = budget <= 0 ? 0 : Math.min((double) used / budget, 1.0);
        DegradeLevel currentLevel = decide(used, 0, 0, 0).level();
        String message = switch (currentLevel) {
            case NORMAL -> "token 用量正常";
            case DROP_IMAGES -> "接近阈值：新请求将舍弃图片输入";
            case MINIMAL_CONTEXT -> "用量偏高：将压缩知识片段上下文";
            case RULE_ONLY -> "用量已达预算：暂不调用大模型，仅规则兜底";
        };
        return new AiTokenUsageResponse(LocalDate.now(), monitorEnabled(), budget, used,
                Math.max(budget - used, 0), ratio, calls, degraded, currentLevel, message);
    }

    private LocalDateTime startOfToday() {
        return LocalDate.now().atStartOfDay();
    }

    private AiProperties.Token token() {
        return aiProperties.token();
    }

    private double ratio(Double value, double fallback) {
        return value == null || value <= 0 ? fallback : value;
    }

    private int positive(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }
}
