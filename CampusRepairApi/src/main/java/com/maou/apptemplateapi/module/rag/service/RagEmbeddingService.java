package com.maou.apptemplateapi.module.rag.service;

import dev.langchain4j.data.embedding.Embedding;

import java.util.List;

public interface RagEmbeddingService {

    Embedding embed(String text, String scenario);

    /**
     * 批量向量化：语料入库时一次提交多条，避免「逐条调用」在批量导入时打爆连接/触发限流。
     * 实现方需保证返回顺序与入参一致。
     */
    List<Embedding> embedAll(List<String> texts, String scenario);

    int dimension();

    String provider();

    String modelName();
}
