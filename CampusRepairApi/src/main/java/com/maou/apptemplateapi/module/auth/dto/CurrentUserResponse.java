package com.maou.apptemplateapi.module.auth.dto;

public record CurrentUserResponse(
        Long userId,
        String username,
        String displayName,
        String role
) {
}
