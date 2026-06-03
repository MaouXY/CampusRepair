package com.maou.apptemplateapi.common.config.ai;

import com.maou.apptemplateapi.module.ai.service.AiClient;
import com.maou.apptemplateapi.module.ai.service.AiImageInput;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
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
    public AiClient aiClient(AiProperties properties) {
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
        return new LangChain4jAiClientImpl(chatModel);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private AiClient unavailableClient(String reason) {
        return new AiClient() {
            @Override
            public String generate(String systemPrompt, String userPrompt) {
                throw new IllegalStateException("AI client unavailable: " + reason);
            }

            @Override
            public String generateWithImages(String systemPrompt, String userPrompt, List<AiImageInput> images) {
                throw new IllegalStateException("AI client unavailable: " + reason);
            }
        };
    }

    private static final class LangChain4jAiClientImpl implements AiClient {

        private final ChatModel chatModel;

        private LangChain4jAiClientImpl(ChatModel chatModel) {
            this.chatModel = chatModel;
        }

        @Override
        public String generate(String systemPrompt, String userPrompt) {
            String mergedPrompt = systemPrompt + System.lineSeparator() + System.lineSeparator() + userPrompt;
            return chatModel.chat(mergedPrompt);
        }

        @Override
        public String generateWithImages(String systemPrompt, String userPrompt, List<AiImageInput> images) {
            List<Content> contents = new ArrayList<>();
            contents.add(TextContent.from(userPrompt));
            if (images != null) {
                for (AiImageInput image : images) {
                    if (StringUtils.hasText(image.imageUrl())) {
                        contents.add(ImageContent.from(image.imageUrl()));
                    } else if (image.bytes() != null && image.bytes().length > 0) {
                        String base64 = Base64.getEncoder().encodeToString(image.bytes());
                        contents.add(ImageContent.from(base64, image.contentType()));
                    }
                }
            }
            return chatModel.chat(
                    SystemMessage.from(systemPrompt),
                    UserMessage.from(contents)
            ).aiMessage().text();
        }
    }
}

