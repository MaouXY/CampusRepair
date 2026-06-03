package com.maou.apptemplateapi.module.audit.service;

import com.maou.apptemplateapi.common.security.CurrentUser;
import com.maou.apptemplateapi.module.audit.entity.OperationAuditLog;
import com.maou.apptemplateapi.module.audit.mapper.OperationAuditLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class OperationAuditService {

    private static final int SNAPSHOT_LIMIT = 4000;
    private static final int REMARK_LIMIT = 1000;

    private final OperationAuditLogMapper auditLogMapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(CurrentUser operator,
                       String bizType,
                       Long bizId,
                       String action,
                       String beforeSnapshot,
                       String afterSnapshot,
                       String remark) {
        if (operator == null) {
            log.warn("operation audit skipped, scenario=operation-audit-record, reason=missing-operator, bizType={}, bizId={}, action={}",
                    bizType, bizId, action);
            return;
        }
        try {
            OperationAuditLog logRecord = new OperationAuditLog();
            logRecord.setOperatorId(operator.getId());
            logRecord.setOperatorRole(operator.getRoleCode());
            logRecord.setBizType(bizType);
            logRecord.setBizId(bizId);
            logRecord.setAction(action);
            logRecord.setBeforeSnapshot(truncate(beforeSnapshot, SNAPSHOT_LIMIT));
            logRecord.setAfterSnapshot(truncate(afterSnapshot, SNAPSHOT_LIMIT));
            logRecord.setRemark(truncate(remark, REMARK_LIMIT));
            auditLogMapper.insert(logRecord);
        } catch (RuntimeException exception) {
            log.error("operation audit record failed, scenario=operation-audit-record, operatorId={}, roleCode={}, bizType={}, bizId={}, action={}",
                    operator.getId(), operator.getRoleCode(), bizType, bizId, action, exception);
        }
    }

    private String truncate(String value, int limit) {
        if (value == null || value.length() <= limit) {
            return value;
        }
        return value.substring(0, limit);
    }
}
