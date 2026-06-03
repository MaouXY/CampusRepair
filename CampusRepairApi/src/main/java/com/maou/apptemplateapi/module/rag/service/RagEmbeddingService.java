package com.maou.apptemplateapi.module.rag.service;

import dev.langchain4j.data.embedding.Embedding;

public interface RagEmbeddingService {

    Embedding embed(String text, String scenario);

    int dimension();

    String provider();

    String modelName();
}
