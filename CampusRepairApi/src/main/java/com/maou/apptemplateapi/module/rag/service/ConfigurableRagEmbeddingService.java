package com.maou.apptemplateapi.module.rag.service;

import com.maou.apptemplateapi.common.config.ai.AgentToolProperties;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.Locale;

@Slf4j
@Service
public class ConfigurableRagEmbeddingService implements RagEmbeddingService {

    private static final String PROVIDER_LOCAL_HASH = "LOCAL_HASH";
    private static final String PROVIDER_ARK_OPENAI = "ARK_OPENAI";
    private static final String PROVIDER_OPENAI_COMPATIBLE = "OPENAI_COMPATIBLE";

    private final AgentToolProperties agentToolProperties;
    private final LocalHashEmbeddingService localHashEmbeddingService;
    private final EmbeddingModel remoteEmbeddingModel;
    private final String provider;

    public ConfigurableRagEmbeddingService(AgentToolProperties agentToolProperties,
                                           LocalHashEmbeddingService localHashEmbeddingService) {
        this.agentToolProperties = agentToolProperties;
        this.localHashEmbeddingService = localHashEmbeddingService;
        this.provider = normalizeProvider(agentToolProperties.getRag().getEmbedding().getProvider());
        this.remoteEmbeddingModel = buildRemoteEmbeddingModel();
        log.info("rag embedding service initialized, scenario=rag-embedding-init, provider={}, model={}, dimension={}, baseUrl={}",
                provider(), modelName(), dimension(), safeBaseUrl());
    }

    @Override
    public Embedding embed(String text, String scenario) {
        if (PROVIDER_LOCAL_HASH.equals(provider)) {
            return localHashEmbeddingService.embed(text);
        }
        try {
            return remoteEmbeddingModel.embed(text).content();
        } catch (RuntimeException exception) {
            log.error("rag embedding failed, scenario={}, provider={}, model={}, baseUrl={}, textLength={}",
                    scenario, provider(), modelName(), safeBaseUrl(), text == null ? 0 : text.length(), exception);
            throw exception;
        }
    }

    @Override
    public int dimension() {
        Integer dimension = agentToolProperties.getRag().getMilvus().getDimension();
        return dimension == null || dimension <= 0 ? 384 : dimension;
    }

    @Override
    public String provider() {
        return provider;
    }

    @Override
    public String modelName() {
        if (PROVIDER_LOCAL_HASH.equals(provider)) {
            return localHashEmbeddingService.modelName();
        }
        return agentToolProperties.getRag().getEmbedding().getModel();
    }

    private EmbeddingModel buildRemoteEmbeddingModel() {
        if (PROVIDER_LOCAL_HASH.equals(provider)) {
            return null;
        }
        if (!PROVIDER_ARK_OPENAI.equals(provider) && !PROVIDER_OPENAI_COMPATIBLE.equals(provider)) {
            throw new IllegalArgumentException("Unsupported RAG embedding provider: " + provider);
        }
        AgentToolProperties.Embedding embedding = agentToolProperties.getRag().getEmbedding();
        if (!StringUtils.hasText(embedding.getBaseUrl())) {
            throw new IllegalArgumentException("RAG embedding base-url is required when provider=" + provider);
        }
        if (!StringUtils.hasText(embedding.getApiKey())) {
            throw new IllegalArgumentException("RAG embedding api-key is required when provider=" + provider);
        }
        if (!StringUtils.hasText(embedding.getModel())) {
            throw new IllegalArgumentException("RAG embedding model is required when provider=" + provider);
        }
        Integer timeoutSeconds = embedding.getTimeoutSeconds();
        return OpenAiEmbeddingModel.builder()
                .baseUrl(embedding.getBaseUrl())
                .apiKey(embedding.getApiKey())
                .modelName(embedding.getModel())
                .dimensions(dimension())
                .timeout(Duration.ofSeconds(timeoutSeconds == null || timeoutSeconds <= 0 ? 120 : timeoutSeconds))
                .maxRetries(1)
                .build();
    }

    private String normalizeProvider(String value) {
        if (!StringUtils.hasText(value)) {
            return PROVIDER_LOCAL_HASH;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private String safeBaseUrl() {
        return agentToolProperties.getRag().getEmbedding().getBaseUrl();
    }
}
