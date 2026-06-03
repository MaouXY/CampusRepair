package com.maou.apptemplateapi.module.base.dto;

import java.util.List;

public record LocationTreeResponse(
        Long id,
        Long parentId,
        String name,
        List<LocationTreeResponse> children
) {
}
