package com.maou.apptemplateapi.module.ticket.dto;

import jakarta.validation.constraints.Size;

public record TicketUrgeRequest(
        @Size(max = 500) String remark
) {
}
