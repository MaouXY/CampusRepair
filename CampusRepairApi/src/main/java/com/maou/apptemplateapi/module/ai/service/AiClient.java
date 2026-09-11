package com.maou.apptemplateapi.module.ai.service;

import com.maou.apptemplateapi.module.ai.dto.AiCompletion;

import java.util.List;

public interface AiClient {

    AiCompletion complete(String systemPrompt, String userPrompt);

    AiCompletion completeWithImages(String systemPrompt, String userPrompt, List<AiImageInput> images);

    default String generate(String systemPrompt, String userPrompt) {
        return complete(systemPrompt, userPrompt).text();
    }

    default String generateWithImages(String systemPrompt, String userPrompt, List<AiImageInput> images) {
        return completeWithImages(systemPrompt, userPrompt, images).text();
    }
}
