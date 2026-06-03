package com.maou.apptemplateapi.common.config.ai;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "app.agent")
public class AgentToolProperties {

    private Bocha bocha = new Bocha();
    private Rag rag = new Rag();

    @Getter
    @Setter
    public static class Bocha {
        private WebSearch webSearch = new WebSearch();
        private Rerank rerank = new Rerank();
    }

    @Getter
    @Setter
    public static class WebSearch {
        private boolean enabled;
        private String baseUrl = "https://api.bocha.cn/v1/web-search";
        private String apiKey;
        private Integer timeoutSeconds = 20;
        private String freshness = "noLimit";
        private Boolean summary = true;
        private Integer count = 5;
        private String include;
        private String exclude;
    }

    @Getter
    @Setter
    public static class Rerank {
        private boolean enabled;
        private String baseUrl = "https://api.bocha.cn/v1/rerank";
        private String apiKey;
        private Integer timeoutSeconds = 20;
        private String model = "gte-rerank";
        private Integer topN = 6;
        private Boolean returnDocuments = true;
    }

    @Getter
    @Setter
    public static class Rag {
        private Milvus milvus = new Milvus();
        private Embedding embedding = new Embedding();
    }

    @Getter
    @Setter
    public static class Milvus {
        private boolean enabled;
        private String uri = "http://localhost:19530";
        private String token = "root:Milvus";
        private Integer timeoutSeconds = 20;
        private String collectionName = "app_knowledge";
        private String vectorField = "vector";
        private Integer dimension = 384;
        private boolean recreateOnDimensionMismatch = true;
        private Integer topK = 5;
        private Integer chunkSize = 800;
        private Integer chunkOverlap = 120;
        private Integer maxChunksPerDocument = 30;
    }

    @Getter
    @Setter
    public static class Embedding {
        private String provider = "LOCAL_HASH";
        private String baseUrl;
        private String apiKey;
        private String model;
        private Integer timeoutSeconds = 20;
    }
}

