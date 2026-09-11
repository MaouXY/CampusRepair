package com.maou.apptemplateapi.module.ai.dto;

/**
 * 单次 AI 调用的 token 用量。source 区分「接口真实返回」与「本地估算」。
 */
public record AiTokenUsage(
        Integer inputTokens,
        Integer outputTokens,
        Integer totalTokens,
        String source
) {

    public static final String SOURCE_API = "API";
    public static final String SOURCE_ESTIMATED = "ESTIMATED";

    public static AiTokenUsage api(Integer inputTokens, Integer outputTokens, Integer totalTokens) {
        Integer input = inputTokens == null ? 0 : inputTokens;
        Integer output = outputTokens == null ? 0 : outputTokens;
        Integer total = totalTokens == null ? input + output : totalTokens;
        return new AiTokenUsage(input, output, total, SOURCE_API);
    }

    public static AiTokenUsage estimated(long inputTokens, long outputTokens) {
        int input = (int) Math.max(inputTokens, 0);
        int output = (int) Math.max(outputTokens, 0);
        return new AiTokenUsage(input, output, input + output, SOURCE_ESTIMATED);
    }

    public int total() {
        if (totalTokens != null) {
            return totalTokens;
        }
        return (inputTokens == null ? 0 : inputTokens) + (outputTokens == null ? 0 : outputTokens);
    }

    public boolean fromApi() {
        return SOURCE_API.equals(source);
    }
}
