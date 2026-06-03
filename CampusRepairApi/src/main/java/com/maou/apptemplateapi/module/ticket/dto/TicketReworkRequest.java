package com.maou.apptemplateapi.module.ticket.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TicketReworkRequest(
        @NotBlank @Size(max = 500) String reason
) {
}
