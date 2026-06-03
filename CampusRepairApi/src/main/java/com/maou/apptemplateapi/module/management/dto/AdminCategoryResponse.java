package com.maou.apptemplateapi.module.management.dto;

import java.time.LocalDateTime;

public record AdminCategoryResponse(
        Long id,
        String name,
        Integer sortOrder,
        Integer enabled,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
