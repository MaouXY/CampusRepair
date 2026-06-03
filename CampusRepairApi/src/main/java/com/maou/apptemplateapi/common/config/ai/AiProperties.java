package com.maou.apptemplateapi.common.config.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.ai.ark")
public record AiProperties(
        boolean enabled,
        boolean failFast,
        String baseUrl,
        String apiKey,
        String modelName,
        Integer timeoutSeconds,
        Double temperature,
        Long pendingTimeoutSeconds,
        Integer maxPromptChars,
        Integer maxImageCount,
        Long maxImageSizeBytes,
        Long maxImageTotalBytes,
        String imageTransferMode
) {
}

