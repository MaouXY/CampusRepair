package com.maou.apptemplateapi.module.rag.dto;

import java.time.LocalDateTime;

public record KnowledgeDraftResponse(
        Long id,
        Long sourceTicketId,
        String title,
        String content,
        Long categoryId,
        String status,
        Boolean createdByAi,
        String generateSource,
        String reviewRemark,
        Long reviewedBy,
        LocalDateTime reviewedAt,
        Long knowledgeDocumentId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
