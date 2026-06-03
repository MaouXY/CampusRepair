package com.maou.apptemplateapi.module.audit.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("operation_audit_log")
public class OperationAuditLog {

    private Long id;
    private Long operatorId;
    private String operatorRole;
    private String bizType;
    private Long bizId;
    private String action;
    private String beforeSnapshot;
    private String afterSnapshot;
    private String remark;
    private LocalDateTime createdAt;
}
