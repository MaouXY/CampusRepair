package com.maou.apptemplateapi.module.management.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record NoticeRequest(
        @NotBlank @Size(max = 120) String title,
        @NotBlank String content,
        @NotBlank @Size(max = 32) String targetRole,
        @NotNull Integer published,
        @NotNull Integer sortOrder
) {
}
