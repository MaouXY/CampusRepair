package com.maou.apptemplateapi.module.rag.entity;

import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 典型工单沉淀的知识库草稿：AI 生成（或规则模板兜底）→ 管理员审核 → 入库切片并同步向量库。
 */
@Getter
@Setter
@TableName("rag_knowledge_draft")
public class RagKnowledgeDraft {

    public static final String STATUS_PENDING_REVIEW = "PENDING_REVIEW";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";

    public static final String SOURCE_AI = "AI";
    public static final String SOURCE_AI_DEGRADED = "AI_DEGRADED";
    public static final String SOURCE_RULE = "RULE";

    private Long id;
    private Long sourceTicketId;
    private String title;
    private String content;
    private Long categoryId;
    private String status;
    private Integer createdByAi;
    private String generateSource;
    private String reviewRemark;
    private Long reviewedBy;
    private LocalDateTime reviewedAt;
    private Long knowledgeDocumentId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
