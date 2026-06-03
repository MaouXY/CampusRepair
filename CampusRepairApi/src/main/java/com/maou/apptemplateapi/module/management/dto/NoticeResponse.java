package com.maou.apptemplateapi.module.management.dto;

import java.time.LocalDateTime;

public record NoticeResponse(
        Long id,
        String title,
        String content,
        String targetRole,
        Integer published,
        Integer sortOrder,
        Long createdBy,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
