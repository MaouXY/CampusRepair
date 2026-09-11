package com.maou.apptemplateapi.module.rag.service;

import com.maou.apptemplateapi.common.config.ai.AgentToolProperties;
import com.maou.apptemplateapi.module.rag.dto.RagChunkResponse;
import com.maou.apptemplateapi.module.rag.entity.RagKnowledgeChunk;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.milvus.MilvusEmbeddingStore;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.grpc.DescribeCollectionResponse;
import io.milvus.grpc.KeyValuePair;
import io.milvus.param.ConnectParam;
import io.milvus.param.R;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.collection.DescribeCollectionParam;
import io.milvus.param.collection.DropCollectionParam;
import io.milvus.param.collection.HasCollectionParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class MilvusRagVectorService {

    private final AgentToolProperties agentToolProperties;
    private final RagEmbeddingService embeddingService;

    private volatile MilvusEmbeddingStore embeddingStore;

    public boolean enabled() {
        return agentToolProperties.getRag().getMilvus().isEnabled();
    }

    public boolean rebuildDocument(Long documentId, List<RagKnowledgeChunk> chunks, String scenario) {
        if (!enabled()) {
            return false;
        }
        try {
            MilvusEmbeddingStore store = store();
            java.util.ArrayList<String> ids = new java.util.ArrayList<>();
            java.util.ArrayList<TextSegment> segments = new java.util.ArrayList<>();
            for (RagKnowledgeChunk chunk : chunks) {
                Metadata metadata = new Metadata()
                        .put("documentId", documentId)
                        .put("chunkId", chunk.getId())
                        .put("chunkIndex", chunk.getChunkIndex());
                ids.add(String.valueOf(chunk.getId()));
                segments.add(TextSegment.from(chunk.getContent(), metadata));
            }
            if (ids.isEmpty()) {
                return true;
            }
            // 批量向量化：一次提交多条，避免逐条请求在批量导入时把连接打崩
            java.util.List<dev.langchain4j.data.embedding.Embedding> embeddings = embeddingService.embedAll(
                    chunks.stream().map(RagKnowledgeChunk::getContent).toList(), scenario);
            if (embeddings.size() != ids.size()) {
                throw new IllegalStateException("rag embedding size mismatch, expected=%d, actual=%d"
                        .formatted(ids.size(), embeddings.size()));
            }
            store.addAll(ids, embeddings, segments);
            log.info("rag milvus indexed, scenario={}, documentId={}, chunkCount={}, batchEmbedding=true, collectionName={}",
                    scenario, documentId, chunks.size(), agentToolProperties.getRag().getMilvus().getCollectionName());
            return true;
        } catch (RuntimeException exception) {
            log.error("rag milvus index failed, scenario={}, documentId={}, collectionName={}, uri={}",
                    scenario, documentId, agentToolProperties.getRag().getMilvus().getCollectionName(),
                    agentToolProperties.getRag().getMilvus().getUri(), exception);
            return false;
        }
    }

    public List<RagChunkResponse> search(String query, int limit, String scenario) {
        if (!enabled() || !StringUtils.hasText(query)) {
            return List.of();
        }
        double minScore = minVectorScore();
        try {
            EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                    .queryEmbedding(embeddingService.embed(query, scenario))
                    .maxResults(Math.max(limit, 1))
                    .minScore(minScore)
                    .build();
            EmbeddingSearchResult<TextSegment> result = store().search(request);
            log.info("rag milvus search done, scenario={}, candidateCount={}, minScore={}, collectionName={}",
                    scenario, result.matches().size(), minScore,
                    agentToolProperties.getRag().getMilvus().getCollectionName());
            return result.matches().stream()
                    .map(this::toResponse)
                    .toList();
        } catch (RuntimeException exception) {
            log.error("rag milvus search failed, scenario={}, collectionName={}, uri={}, query={}",
                    scenario, agentToolProperties.getRag().getMilvus().getCollectionName(),
                    agentToolProperties.getRag().getMilvus().getUri(), query, exception);
            return List.of();
        }
    }

    /**
     * 向量召回最低相似度阈值：RRF 只看名次，低质量匹配必须在进入融合前过滤，否则「最差候选排第一」也会拿满权重。
     */
    private double minVectorScore() {
        Double configured = agentToolProperties.getRag().getRanking().getMinVectorScore();
        if (configured == null || configured <= 0) {
            return 0.0;
        }
        return configured;
    }

    /**
     * 删除指定切片在向量库中的向量。
     *
     * <p>重建文档时切片行会被物理删除并重新生成，如果向量库只增不删，旧向量会长期残留：
     * 检索会命中这些已不存在的 chunkId，回表时被丢弃，表现为「返回 10 条、实际只剩 4 条」。
     */
    public void removeChunks(List<String> chunkIds, String scenario) {
        if (!enabled() || chunkIds == null || chunkIds.isEmpty()) {
            return;
        }
        try {
            store().removeAll(chunkIds);
            log.info("rag milvus stale vectors removed, scenario={}, chunkCount={}, collectionName={}",
                    scenario, chunkIds.size(), agentToolProperties.getRag().getMilvus().getCollectionName());
        } catch (RuntimeException exception) {
            log.error("rag milvus stale vector removal failed, scenario={}, chunkCount={}, collectionName={}",
                    scenario, chunkIds.size(), agentToolProperties.getRag().getMilvus().getCollectionName(), exception);
        }
    }

    /**
     * 清空并重建集合：多项目共用 Milvus 时用于彻底清理本项目集合中的历史脏向量。
     */
    public boolean resetCollection(String scenario) {
        if (!enabled()) {
            return false;
        }
        try {
            MilvusServiceClient client = new MilvusServiceClient(connectParam(agentToolProperties.getRag().getMilvus()));
            try {
                R<?> dropResult = client.dropCollection(DropCollectionParam.newBuilder()
                        .withCollectionName(agentToolProperties.getRag().getMilvus().getCollectionName())
                        .build());
                if (dropResult.getStatus() != 0) {
                    throw new IllegalStateException("Milvus dropCollection failed, status=%s, message=%s"
                            .formatted(dropResult.getStatus(), dropResult.getMessage()));
                }
                waitUntilCollectionDropped(client, agentToolProperties.getRag().getMilvus());
            } finally {
                try {
                    client.close(1);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
            embeddingStore = null;
            log.warn("rag milvus collection reset, scenario={}, collectionName={}",
                    scenario, agentToolProperties.getRag().getMilvus().getCollectionName());
            return true;
        } catch (RuntimeException exception) {
            log.error("rag milvus collection reset failed, scenario={}, collectionName={}",
                    scenario, agentToolProperties.getRag().getMilvus().getCollectionName(), exception);
            return false;
        }
    }

    private RagChunkResponse toResponse(EmbeddingMatch<TextSegment> match) {
        TextSegment segment = match.embedded();
        Long documentId = longMetadata(segment, "documentId");
        Long chunkId = longMetadata(segment, "chunkId");
        return new RagChunkResponse(chunkId, documentId, "Milvus", segment.text(), match.score());
    }

    private Long longMetadata(TextSegment segment, String key) {
        Object value = segment.metadata().toMap().get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private MilvusEmbeddingStore store() {
        if (embeddingStore == null) {
            synchronized (this) {
                if (embeddingStore == null) {
                    AgentToolProperties.Milvus milvus = agentToolProperties.getRag().getMilvus();
                    ensureCollectionDimensionCompatible(milvus);
                    embeddingStore = MilvusEmbeddingStore.builder()
                            .uri(milvus.getUri())
                            .token(milvus.getToken())
                            .collectionName(milvus.getCollectionName())
                            .dimension(embeddingService.dimension())
                            .vectorFieldName(milvus.getVectorField())
                            .indexType(IndexType.FLAT)
                            .metricType(MetricType.COSINE)
                            .autoFlushOnInsert(true)
                            .retrieveEmbeddingsOnSearch(false)
                            .build();
                    log.info("rag milvus store initialized, scenario=rag-milvus-init, uri={}, collectionName={}, dimension={}, vectorField={}, embeddingProvider={}, embeddingModel={}",
                            milvus.getUri(), milvus.getCollectionName(), embeddingService.dimension(), milvus.getVectorField(),
                            embeddingService.provider(), embeddingService.modelName());
                }
            }
        }
        return embeddingStore;
    }

    private void ensureCollectionDimensionCompatible(AgentToolProperties.Milvus milvus) {
        MilvusServiceClient client = new MilvusServiceClient(connectParam(milvus));
        try {
            R<Boolean> hasCollection = client.hasCollection(HasCollectionParam.newBuilder()
                    .withCollectionName(milvus.getCollectionName())
                    .build());
            if (hasCollection.getStatus() != 0) {
                throw new IllegalStateException("Milvus hasCollection failed, status=%s, message=%s"
                        .formatted(hasCollection.getStatus(), hasCollection.getMessage()));
            }
            if (!Boolean.TRUE.equals(hasCollection.getData())) {
                log.info("rag milvus collection absent, scenario=rag-milvus-dimension-check, collectionName={}, expectedDimension={}",
                        milvus.getCollectionName(), embeddingService.dimension());
                return;
            }

            R<DescribeCollectionResponse> response = client.describeCollection(DescribeCollectionParam.newBuilder()
                    .withCollectionName(milvus.getCollectionName())
                    .build());
            if (response.getStatus() != 0) {
                throw new IllegalStateException("Milvus describeCollection failed, status=%s, message=%s"
                        .formatted(response.getStatus(), response.getMessage()));
            }
            if (response.getData() == null) {
                handleDimensionMismatch(client, milvus, null, "describe-empty");
                return;
            }

            Integer actualDimension = vectorDimension(response.getData(), milvus.getVectorField());
            int expectedDimension = embeddingService.dimension();
            if (actualDimension != null && actualDimension == expectedDimension) {
                log.info("rag milvus dimension checked, scenario=rag-milvus-dimension-check, collectionName={}, vectorField={}, actualDimension={}, expectedDimension={}",
                        milvus.getCollectionName(), milvus.getVectorField(), actualDimension, expectedDimension);
                return;
            }

            handleDimensionMismatch(client, milvus, actualDimension, "dimension-mismatch");
        } catch (RuntimeException exception) {
            log.error("rag milvus dimension check failed, scenario=rag-milvus-dimension-check, collectionName={}, uri={}, expectedDimension={}",
                    milvus.getCollectionName(), milvus.getUri(), embeddingService.dimension(), exception);
            throw exception;
        } finally {
            try {
                client.close(1);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                log.error("rag milvus client close interrupted, scenario=rag-milvus-dimension-check, collectionName={}, uri={}",
                        milvus.getCollectionName(), milvus.getUri(), exception);
            }
        }
    }

    private void handleDimensionMismatch(MilvusServiceClient client,
                                         AgentToolProperties.Milvus milvus,
                                         Integer actualDimension,
                                         String reason) {
        int expectedDimension = embeddingService.dimension();
        if (!milvus.isRecreateOnDimensionMismatch()) {
            throw new IllegalStateException("Milvus collection dimension mismatch, collectionName=%s, vectorField=%s, actualDimension=%s, expectedDimension=%s, reason=%s, recreateOnDimensionMismatch=false"
                    .formatted(milvus.getCollectionName(), milvus.getVectorField(), actualDimension, expectedDimension, reason));
        }

        R<?> dropResult = client.dropCollection(DropCollectionParam.newBuilder()
                .withCollectionName(milvus.getCollectionName())
                .build());
        if (dropResult.getStatus() != 0) {
            throw new IllegalStateException("Milvus dropCollection failed, collectionName=%s, status=%s, message=%s"
                    .formatted(milvus.getCollectionName(), dropResult.getStatus(), dropResult.getMessage()));
        }

        waitUntilCollectionDropped(client, milvus);
        log.warn("rag milvus collection dropped for dimension mismatch, scenario=rag-milvus-dimension-recreate, collectionName={}, vectorField={}, actualDimension={}, expectedDimension={}, reason={}, status={}, message={}",
                milvus.getCollectionName(), milvus.getVectorField(), actualDimension, expectedDimension,
                reason, dropResult.getStatus(), dropResult.getMessage());
    }

    private void waitUntilCollectionDropped(MilvusServiceClient client, AgentToolProperties.Milvus milvus) {
        for (int attempt = 1; attempt <= 20; attempt++) {
            R<Boolean> hasCollection = client.hasCollection(HasCollectionParam.newBuilder()
                    .withCollectionName(milvus.getCollectionName())
                    .build());
            if (hasCollection.getStatus() != 0) {
                throw new IllegalStateException("Milvus hasCollection after drop failed, collectionName=%s, status=%s, message=%s"
                        .formatted(milvus.getCollectionName(), hasCollection.getStatus(), hasCollection.getMessage()));
            }
            if (!Boolean.TRUE.equals(hasCollection.getData())) {
                return;
            }
            try {
                TimeUnit.MILLISECONDS.sleep(200);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Milvus wait collection dropped interrupted, collectionName=%s"
                        .formatted(milvus.getCollectionName()), exception);
            }
        }
        throw new IllegalStateException("Milvus collection still exists after drop, collectionName=%s"
                .formatted(milvus.getCollectionName()));
    }

    private ConnectParam connectParam(AgentToolProperties.Milvus milvus) {
        Integer timeoutSeconds = milvus.getTimeoutSeconds();
        ConnectParam.Builder builder = ConnectParam.newBuilder()
                .withUri(milvus.getUri())
                .withConnectTimeout(timeoutSeconds == null || timeoutSeconds <= 0 ? 20 : timeoutSeconds, TimeUnit.SECONDS);
        if (StringUtils.hasText(milvus.getToken())) {
            builder.withToken(milvus.getToken());
        }
        return builder.build();
    }

    private Integer vectorDimension(DescribeCollectionResponse response, String vectorField) {
        return response.getSchema().getFieldsList().stream()
                .filter(field -> Objects.equals(field.getName(), vectorField))
                .filter(field -> field.getDataType() == DataType.FloatVector)
                .findFirst()
                .flatMap(field -> field.getTypeParamsList().stream()
                        .filter(param -> "dim".equals(param.getKey()))
                        .map(KeyValuePair::getValue)
                        .findFirst())
                .map(Integer::valueOf)
                .orElse(null);
    }
}
