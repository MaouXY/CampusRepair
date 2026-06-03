package com.maou.apptemplateapi.module.ai.service;

import java.util.List;

public interface AiClient {

    String generate(String systemPrompt, String userPrompt);

    String generateWithImages(String systemPrompt, String userPrompt, List<AiImageInput> images);
}

