package com.maou.apptemplateapi.module.ticket.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TicketResultRequest(
        @NotBlank @Size(max = 500) String result,
        @Size(max = 1000) String remark
) {
}
