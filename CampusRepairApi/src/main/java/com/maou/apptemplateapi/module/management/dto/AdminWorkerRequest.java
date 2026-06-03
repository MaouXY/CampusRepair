package com.maou.apptemplateapi.module.management.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AdminWorkerRequest(
        @NotBlank @Size(max = 64) String username,
        @Size(max = 64) String password,
        @NotBlank @Size(max = 64) String realName,
        @Size(max = 32) String phone,
        @NotNull Integer enabled
) {
}
