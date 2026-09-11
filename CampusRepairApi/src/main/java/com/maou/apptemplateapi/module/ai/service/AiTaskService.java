package com.maou.apptemplateapi.module.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.maou.apptemplateapi.module.ai.dto.AiTokenUsage;
import com.maou.apptemplateapi.module.ai.entity.AiTaskRecord;
import com.maou.apptemplateapi.module.ai.enums.DegradeLevel;
import com.maou.apptemplateapi.module.ai.mapper.AiTaskRecordMapper;
import com.maou.apptemplateapi.common.config.ai.AiProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

@Service
@RequiredArgsConstructor
public class AiTaskService {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_TIMEOUT = "TIMEOUT";
    public static final String STATUS_DEGRADED = "DEGRADED";

    private static final AtomicLong AI_TASK_ID_SEQ = new AtomicLong(System.currentTimeMillis() * 10 + 900_000);
    private static final int SNAPSHOT_LIMIT = 4000;

    private final AiTaskRecordMapper aiTaskRecordMapper;
    private final AiProperties aiProperties;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AiTaskRecord createPending(Long organizationId,
                                      String taskType,
                                      String bizType,
                                      Long bizId,
                                      String requestSnapshot) {
        LocalDateTime now = LocalDateTime.now();
        AiTaskRecord record = new AiTaskRecord();
        record.setId(AI_TASK_ID_SEQ.incrementAndGet());
        record.setOrganizationId(organizationId);
        record.setTaskType(taskType);
        record.setBizType(bizType);
        record.setBizId(bizId);
        record.setModelName(aiProperties.modelName());
        record.setRequestSnapshot(truncate(requestSnapshot));
        record.setStatus(STATUS_PENDING);
        record.setCreatedAt(now);
        record.setUpdatedAt(now);
        record.setDeleted(0);
        aiTaskRecordMapper.insert(record);
        return record;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSuccess(Long taskId, String responseSnapshot, long durationMs) {
        markSuccess(taskId, responseSnapshot, durationMs, null, null, null, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSuccess(Long taskId,
                            String responseSnapshot,
                            long durationMs,
                            AiTokenUsage usage,
                            Integer promptTokens,
                            DegradeLevel degradeLevel,
                            String degradeReason) {
        AiTaskRecord record = aiTaskRecordMapper.selectById(taskId);
        if (record == null) {
            return;
        }
        record.setStatus(STATUS_SUCCESS);
        record.setResponseSnapshot(truncate(responseSnapshot));
        record.setDurationMs(durationMs);
        applyTokenAudit(record, usage, promptTokens, degradeLevel, degradeReason);
        record.setUpdatedAt(LocalDateTime.now());
        aiTaskRecordMapper.updateById(record);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long taskId, String errorMessage, long durationMs) {
        markFailed(taskId, errorMessage, durationMs, null, null, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long taskId,
                           String errorMessage,
                           long durationMs,
                           Integer promptTokens,
                           DegradeLevel degradeLevel,
                           String degradeReason) {
        markTerminal(taskId, STATUS_FAILED, errorMessage, durationMs, promptTokens, degradeLevel, degradeReason);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markTimeout(Long taskId, String errorMessage, long durationMs) {
        markTerminal(taskId, STATUS_TIMEOUT, errorMessage, durationMs, null, null, null);
    }

    /**
     * 未调用模型、直接规则兜底的降级记录：只保留提示词预估与降级级别。
     * 注意：这里不写入 token 用量（保持 null），因为本次没有实际消耗，
     * 否则「当日已用 token」会被虚增，导致后续请求一直被降级。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markDegraded(Long taskId,
                             String message,
                             long durationMs,
                             Integer promptTokens,
                             DegradeLevel degradeLevel,
                             String degradeReason) {
        AiTaskRecord record = aiTaskRecordMapper.selectById(taskId);
        if (record == null) {
            return;
        }
        record.setStatus(STATUS_DEGRADED);
        record.setErrorMessage(truncate(message));
        record.setDurationMs(durationMs);
        applyTokenAudit(record, null, promptTokens, degradeLevel, degradeReason);
        record.setUpdatedAt(LocalDateTime.now());
        aiTaskRecordMapper.updateById(record);
    }

    @Transactional(readOnly = true)
    public long sumTotalTokensSince(LocalDateTime since) {
        return aiTaskRecordMapper.sumTotalTokensSince(since);
    }

    @Transactional(readOnly = true)
    public long countSince(LocalDateTime since) {
        return aiTaskRecordMapper.countSince(since);
    }

    @Transactional(readOnly = true)
    public long countDegradedSince(LocalDateTime since) {
        return aiTaskRecordMapper.countDegradedSince(since);
    }

    @Transactional(readOnly = true)
    public Optional<AiTaskRecord> findLatestPending(Long organizationId, String taskType, String bizType, Long bizId) {
        return Optional.ofNullable(aiTaskRecordMapper.selectOne(new LambdaQueryWrapper<AiTaskRecord>()
                .eq(AiTaskRecord::getOrganizationId, organizationId)
                .eq(AiTaskRecord::getTaskType, taskType)
                .eq(AiTaskRecord::getBizType, bizType)
                .eq(AiTaskRecord::getBizId, bizId)
                .eq(AiTaskRecord::getStatus, STATUS_PENDING)
                .orderByDesc(AiTaskRecord::getCreatedAt)
                .last("limit 1")));
    }

    public boolean isPendingExpired(AiTaskRecord record) {
        long timeoutSeconds = aiProperties.pendingTimeoutSeconds() == null ? 600 : aiProperties.pendingTimeoutSeconds();
        return record.getCreatedAt() != null && record.getCreatedAt().plusSeconds(timeoutSeconds).isBefore(LocalDateTime.now());
    }

    public int maxPromptChars() {
        return aiProperties.maxPromptChars() == null ? 12000 : aiProperties.maxPromptChars();
    }

    public int maxImageCount() {
        return aiProperties.maxImageCount() == null ? 3 : aiProperties.maxImageCount();
    }

    public long maxImageSizeBytes() {
        return aiProperties.maxImageSizeBytes() == null ? 5 * 1024 * 1024L : aiProperties.maxImageSizeBytes();
    }

    public long maxImageTotalBytes() {
        return aiProperties.maxImageTotalBytes() == null ? 10 * 1024 * 1024L : aiProperties.maxImageTotalBytes();
    }

    private void markTerminal(Long taskId,
                              String status,
                              String errorMessage,
                              long durationMs,
                              Integer promptTokens,
                              DegradeLevel degradeLevel,
                              String degradeReason) {
        AiTaskRecord record = aiTaskRecordMapper.selectById(taskId);
        if (record == null) {
            return;
        }
        record.setStatus(status);
        record.setErrorMessage(truncate(errorMessage));
        record.setDurationMs(durationMs);
        applyTokenAudit(record, null, promptTokens, degradeLevel, degradeReason);
        record.setUpdatedAt(LocalDateTime.now());
        aiTaskRecordMapper.updateById(record);
    }

    private void applyTokenAudit(AiTaskRecord record,
                                 AiTokenUsage usage,
                                 Integer promptTokens,
                                 DegradeLevel degradeLevel,
                                 String degradeReason) {
        if (promptTokens != null) {
            record.setPromptTokens(promptTokens);
        }
        if (usage != null) {
            record.setInputTokens(usage.inputTokens());
            record.setOutputTokens(usage.outputTokens());
            record.setTotalTokens(usage.total());
            record.setTokenSource(usage.source());
        }
        if (degradeLevel != null) {
            record.setDegradeLevel(degradeLevel.name());
            record.setDegradeReason(truncateMessage(degradeReason));
        }
    }

    private String truncateMessage(String value) {
        if (value == null || value.length() <= 500) {
            return value;
        }
        return value.substring(0, 500);
    }

    private String truncate(String value) {
        if (value == null || value.length() <= SNAPSHOT_LIMIT) {
            return value;
        }
        return value.substring(0, SNAPSHOT_LIMIT);
    }
}

