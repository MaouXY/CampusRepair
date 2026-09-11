package com.maou.apptemplateapi.module.ticket.dto;

import java.util.List;

public record WorkerOptionResponse(
        Long id,
        String username,
        String realName,
        String phone,
        String departmentName,
        List<String> skillTags,
        Integer activeOrderCount,
        Integer maxActiveOrders
) {
}
