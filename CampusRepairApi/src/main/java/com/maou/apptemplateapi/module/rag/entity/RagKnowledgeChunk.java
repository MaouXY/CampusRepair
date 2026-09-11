package com.maou.apptemplateapi.module.rag.entity;

import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("rag_knowledge_chunk")
public class RagKnowledgeChunk {

    private Long id;
    private Long documentId;
    private Integer chunkIndex;
    private String content;
    /** 所属章节标题，检索结果可直接回引「标准号 + 章节」 */
    private String sectionTitle;
    private Integer tokenCount;
    private String embeddingProvider;
    private String vectorStoreStatus;
    private Integer enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
