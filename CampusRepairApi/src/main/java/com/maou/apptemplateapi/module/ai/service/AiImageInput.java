package com.maou.apptemplateapi.module.ai.service;

public record AiImageInput(
        String fileName,
        String contentType,
        String imageUrl,
        byte[] bytes
) {
}

