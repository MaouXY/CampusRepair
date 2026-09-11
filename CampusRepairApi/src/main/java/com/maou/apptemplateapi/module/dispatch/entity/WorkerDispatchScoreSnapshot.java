package com.maou.apptemplateapi.module.dispatch.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
@TableName("worker_dispatch_score_snapshot")
public class WorkerDispatchScoreSnapshot {

    private Long id;
    private Long ticketId;
    private Long aiAnalysisId;
    private Long workerId;
    private String workerName;
    private String departmentName;
    private String skillTags;
    private Integer activeOrderCount;
    private Integer maxActiveOrders;
    private BigDecimal skillScore;
    private BigDecimal departmentScore;
    private BigDecimal workloadScore;
    private BigDecimal qualityScore;
    private BigDecimal penaltyScore;
    private BigDecimal totalScore;
    private String ruleReason;
    private Integer aiRecommended;
    private LocalDateTime createdAt;
}
