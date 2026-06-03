package com.maou.apptemplateapi.module.ai.entity;

import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
@TableName("repair_ai_analysis")
public class RepairAiAnalysis {

    private Long id;
    private Long ticketId;
    private Long aiTaskId;
    private String status;
    private Long suggestedCategoryId;
    private String suggestedPriority;
    private Long suggestedWorkerId;
    private String faultSummary;
    private String faultReason;
    private String solution;
    private String dispatchRemark;
    private String riskLevel;
    private BigDecimal confidence;
    private String rawResponse;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
