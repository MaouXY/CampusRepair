package com.maou.apptemplateapi.module.rag.entity;

import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Setter
@TableName("rag_knowledge_document")
public class RagKnowledgeDocument {

    private Long id;
    private String title;
    private Long categoryId;
    /** 语料来源（标准名/手册名/校内制度名） */
    private String source;
    /** 标准号或文号，如 GB 55022-2021 */
    private String standardNo;
    /** 版本/年版 */
    private String docVersion;
    /** 生效日期 */
    private LocalDate effectiveDate;
    /** standard / manual / policy / case */
    private String docType;
    /** 导入批次 */
    private String importBatch;
    private String content;
    private Integer enabled;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
