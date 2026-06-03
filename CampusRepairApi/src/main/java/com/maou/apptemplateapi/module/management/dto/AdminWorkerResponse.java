package com.maou.apptemplateapi.module.management.dto;

import java.time.LocalDateTime;

public record AdminWorkerResponse(
        Long id,
        String username,
        String realName,
        String phone,
        Integer enabled,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
