package com.maou.apptemplateapi.module.file.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record FileBindRequest(
        @NotEmpty List<Long> fileIds
) {
}
