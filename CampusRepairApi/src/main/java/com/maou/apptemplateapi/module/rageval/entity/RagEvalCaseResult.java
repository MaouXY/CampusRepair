package com.maou.apptemplateapi.module.rageval.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 单个评测用例的检索结果与指标，便于定位「哪条问题召回失败」。
 */
@Getter
@Setter
@TableName("rag_eval_case_result")
public class RagEvalCaseResult {

    private Long id;
    private Long runId;
    private Long caseId;
    private String question;
    private Integer hit;
    private Integer firstRelevantRank;
    private BigDecimal recall;
    private BigDecimal precisionScore;
    private BigDecimal reciprocalRank;
    private BigDecimal ndcg;
    private Long latencyMs;
    /** JSON 数组：本次召回片段 [{chunkId, documentId, score, source}] */
    private String retrievedChunks;
    private LocalDateTime createdAt;
}
