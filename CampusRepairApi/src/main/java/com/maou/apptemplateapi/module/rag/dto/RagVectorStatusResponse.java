package com.maou.apptemplateapi.module.rag.dto;

/**
 * RAG 向量检索真实状态核对：Embedding 提供方、模型、维度、Milvus 开关与切片同步情况。
 */
public record RagVectorStatusResponse(
        String embeddingProvider,
        String embeddingModel,
        int dimension,
        boolean milvusEnabled,
        String collectionName,
        long documentCount,
        long chunkCount,
        long syncedChunkCount,
        long pendingChunkCount,
        long failedChunkCount,
        boolean hybridEnabled,
        int rrfK,
        boolean rerankEnabled,
        String rerankModel,
        double rerankBlendWeight,
        int rerankWindow,
        double minVectorScore,
        int minKeywordHits,
        String message
) {
}
