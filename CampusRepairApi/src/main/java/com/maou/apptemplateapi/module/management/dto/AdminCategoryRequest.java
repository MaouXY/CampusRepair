package com.maou.apptemplateapi.module.management.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AdminCategoryRequest(
        @NotBlank @Size(max = 64) String name,
        @NotNull Integer sortOrder,
        @NotNull Integer enabled
) {
}
