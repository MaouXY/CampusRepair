package com.maou.apptemplateapi.module.rag.dto;

import jakarta.validation.constraints.Size;

public record KnowledgeDraftReviewRequest(
        @Size(max = 500) String remark
) {
}
