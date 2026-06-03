package com.maou.apptemplateapi.module.ticket.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record TicketEvaluationRequest(
        @NotNull @Min(1) @Max(5) Integer score,
        @Size(max = 500) String content
) {
}
