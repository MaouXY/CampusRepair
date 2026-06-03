package com.maou.apptemplateapi.module.file.dto;

import java.time.LocalDateTime;

public record FileResponse(
        Long id,
        String originalName,
        String objectKey,
        String bucketName,
        String contentType,
        Long sizeBytes,
        Long uploaderId,
        String uploaderRole,
        String bizType,
        Long bizId,
        String publicUrl,
        LocalDateTime createdAt
) {
}
