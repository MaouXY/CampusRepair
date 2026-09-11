package com.maou.apptemplateapi.module.rageval.entity;

import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * RAG 评测用例：一个「问题 + 期望命中文献 + 期望关键词」的评测样本。
 */
@Getter
@Setter
@TableName("rag_eval_case")
public class RagEvalCase {

    private Long id;
    private String datasetName;
    private String question;
    /** JSON 数组：期望命中的切片ID（切片级语义标注，判据与检索器解耦，优先级最高） */
    private String expectedChunkIds;
    /** JSON 数组：期望命中的知识文档ID */
    private String expectedDocIds;
    /** JSON 数组：期望出现在命中片段里的关键词 */
    private String expectedKeywords;
    /** 1=可回答（应召回），0=不可回答（应拒答/不召回） */
    private Integer answerable;
    /** factual / multi-hop / counterfactual / unanswerable 等 */
    private String taskType;
    private Long categoryId;
    private String source;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
