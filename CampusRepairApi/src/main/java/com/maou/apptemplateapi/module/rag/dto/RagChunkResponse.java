package com.maou.apptemplateapi.module.rag.dto;

public record RagChunkResponse(
        Long id,
        Long documentId,
        String title,
        String content,
        double score
) {
}
