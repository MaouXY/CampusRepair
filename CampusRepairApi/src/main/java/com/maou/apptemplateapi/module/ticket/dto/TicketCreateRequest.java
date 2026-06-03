package com.maou.apptemplateapi.module.ticket.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record TicketCreateRequest(
        @NotNull Long locationId,
        @NotNull Long categoryId,
        @NotBlank @Size(max = 1000) String description,
        @NotBlank @Size(max = 32) String contactPhone,
        @Size(max = 5) List<Long> reportImageFileIds
) {
}
