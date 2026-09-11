package com.maou.apptemplateapi.common.config.ai;

import com.maou.apptemplateapi.module.ai.dto.AiCompletion;
import com.maou.apptemplateapi.module.ai.dto.AiTokenUsage;
import com.maou.apptemplateapi.module.ai.service.AiClient;
import com.maou.apptemplateapi.module.ai.service.AiImageInput;
import com.maou.apptemplateapi.module.ai.service.AiTokenEstimator;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.output.TokenUsage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

@Configuration
@EnableConfigurationProperties(AiProperties.class)
@Slf4j
public class AiConfig {

    @Bean
    public AiClient aiClient(AiProperties properties, AiTokenEstimator tokenEstimator) {
        if (!properties.enabled()) {
            String reason = "app.ai.ark.enabled=false";
            if (properties.failFast()) {
                throw new IllegalStateException("AI fallback client activated while fail-fast is enabled: " + reason);
            }
            log.warn("AI fallback client activated: {}", reason);
            return unavailableClient(reason);
        }

        if (isBlank(properties.apiKey())) {
            String reason = "API key is blank";
            if (properties.failFast()) {
                throw new IllegalStateException("AI fallback client activated while fail-fast is enabled: " + reason);
            }
            log.warn("AI fallback client activated: {}", reason);
            return unavailableClient(reason);
        }

        if (isBlank(properties.modelName())) {
            String reason = "model name is blank";
            if (properties.failFast()) {
                throw new IllegalStateException("AI fallback client activated while fail-fast is enabled: " + reason);
            }
            log.warn("AI fallback client activated: {}", reason);
            return unavailableClient(reason);
        }

        log.info(
                "AI ChatModel initializing, provider=ark, enabled={}, baseUrl={}, modelName={}, timeoutSeconds={}, temperature={}, imageTransferMode={}",
                properties.enabled(),
                properties.baseUrl(),
                properties.modelName(),
                properties.timeoutSeconds(),
                properties.temperature(),
                properties.imageTransferMode()
        );

        ChatModel chatModel = OpenAiChatModel.builder()
                .baseUrl(properties.baseUrl())
                .apiKey(properties.apiKey())
                .modelName(properties.modelName())
                .timeout(Duration.ofSeconds(properties.timeoutSeconds() == null ? 20 : properties.timeoutSeconds()))
                .temperature(properties.temperature() == null ? 0.7 : properties.temperature())
                .build();

        log.info("AI real client activated: LangChain4jAiClient");
        return new LangChain4jAiClientImpl(chatModel, tokenEstimator);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private AiClient unavailableClient(String reason) {
        return new AiClient() {
            @Override
            public AiCompletion complete(String systemPrompt, String userPrompt) {
                throw new IllegalStateException("AI client unavailable: " + reason);
            }

            @Override
            public AiCompletion completeWithImages(String systemPrompt, String userPrompt, List<AiImageInput> images) {
                throw new IllegalStateException("AI client unavailable: " + reason);
            }
        };
    }

    private static final class LangChain4jAiClientImpl implements AiClient {

        private final ChatModel chatModel;
        private final AiTokenEstimator tokenEstimator;

        private LangChain4jAiClientImpl(ChatModel chatModel, AiTokenEstimator tokenEstimator) {
            this.chatModel = chatModel;
            this.tokenEstimator = tokenEstimator;
        }

        @Override
        public AiCompletion complete(String systemPrompt, String userPrompt) {
            String mergedPrompt = systemPrompt + System.lineSeparator() + System.lineSeparator() + userPrompt;
            ChatResponse response = chatModel.chat(UserMessage.from(mergedPrompt));
            String text = text(response);
            return toCompletion(response, text,
                    tokenEstimator.estimate(mergedPrompt), tokenEstimator.estimate(text));
        }

        @Override
        public AiCompletion completeWithImages(String systemPrompt, String userPrompt, List<AiImageInput> images) {
            List<Content> contents = new ArrayList<>();
            contents.add(TextContent.from(userPrompt));
            int imageCount = 0;
            if (images != null) {
                for (AiImageInput image : images) {
                    if (StringUtils.hasText(image.imageUrl())) {
                        contents.add(ImageContent.from(image.imageUrl()));
                        imageCount++;
                    } else if (image.bytes() != null && image.bytes().length > 0) {
                        String base64 = Base64.getEncoder().encodeToString(image.bytes());
                        contents.add(ImageContent.from(base64, image.contentType()));
                        imageCount++;
                    }
                }
            }
            ChatResponse response = chatModel.chat(
                    SystemMessage.from(systemPrompt),
                    UserMessage.from(contents)
            );
            String text = text(response);
            long estimatedInput = tokenEstimator.estimate(systemPrompt)
                    + tokenEstimator.estimate(userPrompt)
                    + tokenEstimator.estimateImages(imageCount);
            return toCompletion(response, text, estimatedInput, tokenEstimator.estimate(text));
        }

        private String text(ChatResponse response) {
            return response == null || response.aiMessage() == null ? null : response.aiMessage().text();
        }

        private AiCompletion toCompletion(ChatResponse response,
                                         String text,
                                         long estimatedInputTokens,
                                         long estimatedOutputTokens) {
            TokenUsage usage = response == null ? null : response.tokenUsage();
            AiTokenUsage tokenUsage = usage == null || usage.inputTokenCount() == null
                    ? AiTokenUsage.estimated(estimatedInputTokens, estimatedOutputTokens)
                    : AiTokenUsage.api(usage.inputTokenCount(), usage.outputTokenCount(), usage.totalTokenCount());
            String modelName = response == null ? null : response.modelName();
            return AiCompletion.of(text, modelName, tokenUsage);
        }
    }
}
