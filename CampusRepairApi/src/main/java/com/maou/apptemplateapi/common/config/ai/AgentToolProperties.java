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
        private Hybrid hybrid = new Hybrid();
        private Ranking ranking = new Ranking();
    }

    @Getter
    @Setter
    public static class Hybrid {
        private boolean enabled = true;
        private Integer rrfK = 60;
        private Integer keywordTopK = 10;
        private Integer vectorTopK = 10;
        private Double keywordWeight = 1.0;
        private Double vectorWeight = 1.0;
    }

    /**
     * 召回阈值与重排融合参数。
     *
     * <p>RRF 只看名次，因此「低质量候选排在向量列表第 1」也会拿到满权重，
     * 所以进入融合前必须先用阈值过滤；重排阶段则用 {@code rerankBlendWeight}
     * 把 cross-encoder 分数与 RRF 分数加权融合，避免重排完全覆盖双路共识信号。
     */
    @Getter
    @Setter
    public static class Ranking {
        /** 向量召回最低余弦相似度，低于该值的匹配直接丢弃（0 表示不过滤）。 */
        private Double minVectorScore = 0.25;
        /** 关键词召回最低命中词数，低于该值的片段直接丢弃。 */
        private Integer minKeywordHits = 1;
        /**
         * RRF 融合后保留的候选池大小（与最终返回条数解耦）。
         * 若候选池等于最终 limit，重排就没有可操作空间，本该命中但 RRF 排名靠后的片段会直接丢失。
         */
        private Integer fuseTopK = 20;
        /** 只对 RRF 结果的前 N 条做重排，N 之外保持 RRF 顺序。 */
        private Integer rerankWindow = 20;
        /** 重排融合权重 α：final = α·norm(rerank) + (1-α)·norm(rrf)；1.0 为纯重排，0 表示忽略重排结果。 */
        private Double rerankBlendWeight = 0.7;
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

