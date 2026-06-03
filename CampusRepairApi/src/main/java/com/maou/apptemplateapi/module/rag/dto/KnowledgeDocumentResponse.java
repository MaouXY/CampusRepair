package com.maou.apptemplateapi.module.rag.dto;

import java.time.LocalDateTime;

public record KnowledgeDocumentResponse(
        Long id,
        String title,
        Long categoryId,
        String content,
        Integer enabled,
        Integer chunkCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
