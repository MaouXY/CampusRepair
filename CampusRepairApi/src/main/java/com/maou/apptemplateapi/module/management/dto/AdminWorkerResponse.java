package com.maou.apptemplateapi.module.management.dto;

import java.time.LocalDateTime;
import java.util.List;

public record AdminWorkerResponse(
        Long id,
        String username,
        String realName,
        String phone,
        Integer enabled,
        String departmentName,
        List<String> skillTags,
        Integer dispatchEnabled,
        Integer maxActiveOrders,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
