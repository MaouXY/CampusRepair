package com.maou.apptemplateapi.module.management.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * 公告请求。effectiveAt / expireAt 可为空：为空表示不限制（立即生效 / 永不过期）。
 */
public record NoticeRequest(
        @NotBlank @Size(max = 120) String title,
        @NotBlank String content,
        @NotBlank @Size(max = 32) String targetRole,
        @NotNull Integer published,
        @NotNull Integer sortOrder,
        LocalDateTime effectiveAt,
        LocalDateTime expireAt
) {
}
