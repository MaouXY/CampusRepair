package com.maou.apptemplateapi.module.management.dto;

import java.time.LocalDateTime;

public record AdminLocationResponse(
        Long id,
        Long parentId,
        String name,
        Integer sortOrder,
        Integer enabled,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
