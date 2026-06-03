package com.maou.apptemplateapi.module.auth.dto;

public record LoginResponse(
        String token,
        long expiresIn,
        Long userId,
        String username,
        String displayName,
        String role
) {
}
