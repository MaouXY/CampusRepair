package com.maou.apptemplateapi.module.ticket.dto;

import com.maou.apptemplateapi.module.ticket.enums.TicketPriority;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record TicketAssignRequest(
        @NotNull Long categoryId,
        @NotNull TicketPriority priority,
        @NotBlank @Size(max = 200) String summary,
        @NotNull Long workerId,
        @Size(max = 500) String remark
) {
}
