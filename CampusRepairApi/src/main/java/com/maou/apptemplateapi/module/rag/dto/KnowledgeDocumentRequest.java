package com.maou.apptemplateapi.module.rag.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record KnowledgeDocumentRequest(
        @NotBlank @Size(max = 160) String title,
        Long categoryId,
        @NotBlank String content,
        @NotNull Integer enabled
) {
}
