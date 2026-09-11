package com.maou.apptemplateapi.module.ai.dto;

/**
 * 一次 AI 调用的完整结果：文本 + 模型名 + token 用量。
 */
public record AiCompletion(
        String text,
        String modelName,
        AiTokenUsage usage
) {

    public static AiCompletion of(String text, String modelName, AiTokenUsage usage) {
        return new AiCompletion(text, modelName, usage == null ? AiTokenUsage.estimated(0, 0) : usage);
    }
}
