package com.maou.apptemplateapi.module.rageval.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 一次 RAG 评测运行的汇总指标。
 */
@Getter
@Setter
@TableName("rag_eval_run")
public class RagEvalRun {

    private Long id;
    private String datasetName;
    private Integer topK;
    private Integer caseCount;
    private Integer answerableCaseCount;
    private Integer unanswerableCaseCount;
    private BigDecimal hitRate;
    private BigDecimal recallAtK;
    private BigDecimal precisionAtK;
    private BigDecimal mrr;
    private BigDecimal ndcgAtK;
    private BigDecimal refusalAccuracy;
    private Long avgLatencyMs;
    private String status;
    private String errorMessage;
    private Long triggeredBy;
    private LocalDateTime createdAt;
    private LocalDateTime finishedAt;
}
