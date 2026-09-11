package com.maou.apptemplateapi.module.rag.service;

import com.maou.apptemplateapi.common.config.ai.AgentToolProperties;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Slf4j
@Service
public class ConfigurableRagEmbeddingService implements RagEmbeddingService {

    private static final String PROVIDER_LOCAL_HASH = "LOCAL_HASH";
    private static final String PROVIDER_ARK_OPENAI = "ARK_OPENAI";
    private static final String PROVIDER_OPENAI_COMPATIBLE = "OPENAI_COMPATIBLE";

    /** 单次请求提交的文本条数：既减少请求数，又避免单请求过大被服务端拒绝 */
    private static final int BATCH_SIZE = 16;
    /** 瞬时网络故障（Network is unreachable / 超时 / 限流）的重试次数与退避基数 */
    private static final int MAX_ATTEMPTS = 4;
    private static final long BASE_BACKOFF_MS = 500L;

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
        log.info("rag embedding service initialized, scenario=rag-embedding-init, provider={}, model={}, dimension={}, baseUrl={}, batchSize={}, maxAttempts={}",
                provider(), modelName(), dimension(), safeBaseUrl(), BATCH_SIZE, MAX_ATTEMPTS);
    }

    @Override
    public Embedding embed(String text, String scenario) {
        return embedAll(List.of(text == null ? "" : text), scenario).get(0);
    }

    /**
     * 批量向量化：语料入库时一次提交多条，避免逐条调用（191 个切片 = 191 次请求）在批量导入时
     * 触发 Network is unreachable / 限流而导致整篇切片同步失败。
     */
    @Override
    public List<Embedding> embedAll(List<String> texts, String scenario) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        if (PROVIDER_LOCAL_HASH.equals(provider)) {
            List<Embedding> local = new ArrayList<>(texts.size());
            for (String text : texts) {
                local.add(localHashEmbeddingService.embed(text));
            }
            return local;
        }
        List<Embedding> embeddings = new ArrayList<>(texts.size());
        for (int start = 0; start < texts.size(); start += BATCH_SIZE) {
            int end = Math.min(start + BATCH_SIZE, texts.size());
            embeddings.addAll(embedBatch(texts.subList(start, end), scenario, start));
        }
        return embeddings;
    }

    private List<Embedding> embedBatch(List<String> batch, String scenario, int offset) {
        List<TextSegment> segments = batch.stream()
                .map(text -> TextSegment.from(text == null ? "" : text))
                .toList();
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return remoteEmbeddingModel.embedAll(segments).content();
            } catch (RuntimeException exception) {
                lastFailure = exception;
                long backoff = BASE_BACKOFF_MS * (1L << (attempt - 1));
                log.warn("rag embedding batch failed, scenario={}, provider={}, model={}, offset={}, batchSize={}, attempt={}/{}, backoffMs={}, reason={}",
                        scenario, provider(), modelName(), offset, batch.size(), attempt, MAX_ATTEMPTS, backoff,
                        exception.getMessage());
                if (attempt < MAX_ATTEMPTS) {
                    sleep(backoff);
                }
            }
        }
        log.error("rag embedding failed after retries, scenario={}, provider={}, model={}, baseUrl={}, offset={}, batchSize={}",
                scenario, provider(), modelName(), safeBaseUrl(), offset, batch.size(), lastFailure);
        throw lastFailure == null ? new IllegalStateException("rag embedding failed") : lastFailure;
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("rag embedding retry interrupted", exception);
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
