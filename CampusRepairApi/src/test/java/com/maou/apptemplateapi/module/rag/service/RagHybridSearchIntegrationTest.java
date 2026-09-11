package com.maou.apptemplateapi.module.rag.service;

import com.maou.apptemplateapi.common.config.ai.AgentToolProperties;
import com.maou.apptemplateapi.module.rag.dto.RagChunkResponse;
import com.maou.apptemplateapi.module.rag.dto.RagSearchResult;
import com.maou.apptemplateapi.module.rag.entity.RagKnowledgeChunk;
import com.maou.apptemplateapi.module.rag.entity.RagKnowledgeDocument;
import com.maou.apptemplateapi.module.rag.mapper.RagKnowledgeChunkMapper;
import com.maou.apptemplateapi.module.rag.mapper.RagKnowledgeDocumentMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 混合检索（关键词 + 向量 + RRF）集成测试：用可控的向量服务桩替代真实 Milvus。
 */
@ActiveProfiles("test")
@SpringBootTest
@Transactional
class RagHybridSearchIntegrationTest {

    private static final long DOCUMENT_ID = 91001L;
    private static final long WATER_CHUNK_ID = 90002L;
    private static final long AIR_CONDITIONER_CHUNK_ID = 90001L;
    private static final String WATER_CONTENT = "水管漏水处理流程：关闭楼道总阀，更换老化密封圈后复查水压。";
    private static final String AIR_CONDITIONER_CONTENT = "空调不制冷排查流程：先检查滤网堵塞情况，再测量冷媒压力是否不足。";

    @TestConfiguration
    static class StubVectorConfig {

        @Bean
        @Primary
        StubMilvusRagVectorService stubMilvusRagVectorService(AgentToolProperties agentToolProperties,
                                                              RagEmbeddingService embeddingService) {
            return new StubMilvusRagVectorService(agentToolProperties, embeddingService);
        }
    }

    static class StubMilvusRagVectorService extends MilvusRagVectorService {

        private volatile List<RagChunkResponse> matches = List.of();
        private volatile boolean vectorEnabled = true;

        StubMilvusRagVectorService(AgentToolProperties agentToolProperties, RagEmbeddingService embeddingService) {
            super(agentToolProperties, embeddingService);
        }

        void stubMatches(List<RagChunkResponse> matches) {
            this.matches = matches;
        }

        void stubEnabled(boolean vectorEnabled) {
            this.vectorEnabled = vectorEnabled;
        }

        @Override
        public boolean enabled() {
            return vectorEnabled;
        }

        @Override
        public List<RagChunkResponse> search(String query, int limit, String scenario) {
            return matches.stream().limit(Math.max(limit, 1)).toList();
        }
    }

    @Autowired
    private RagKnowledgeService ragKnowledgeService;

    @Autowired
    private RagKnowledgeDocumentMapper documentMapper;

    @Autowired
    private RagKnowledgeChunkMapper chunkMapper;

    @Autowired
    private StubMilvusRagVectorService vectorService;

    @BeforeEach
    void setUp() {
        vectorService.stubEnabled(true);
        vectorService.stubMatches(List.of());
        insertDocumentAndChunks();
    }

    @Test
    void shouldReturnKeywordMatchesWhenVectorRecallsNothing() {
        RagSearchResult result = ragKnowledgeService.searchDetailed(null, "水管漏水维修", 3);

        assertThat(result.keywordCandidateCount()).isGreaterThan(0);
        assertThat(result.vectorCandidateCount()).isZero();
        assertThat(result.chunks()).isNotEmpty();
        assertThat(result.chunks().get(0).id()).isEqualTo(WATER_CHUNK_ID);
        assertThat(result.topSource()).isEqualTo(RagFusionService.SOURCE_KEYWORD);
    }

    @Test
    void shouldMergeVectorOnlyChunkIntoHybridResult() {
        vectorService.stubMatches(List.of(vectorMatch(AIR_CONDITIONER_CHUNK_ID)));

        RagSearchResult result = ragKnowledgeService.searchDetailed(null, "水管漏水维修", 3);

        assertThat(result.vectorCandidateCount()).isEqualTo(1);
        assertThat(result.chunks()).extracting(RagChunkResponse::id)
                .contains(WATER_CHUNK_ID, AIR_CONDITIONER_CHUNK_ID);
        assertThat(result.overlapCount()).isZero();
    }

    @Test
    void shouldRankChunkRecalledByBothSourcesFirst() {
        vectorService.stubMatches(List.of(vectorMatch(WATER_CHUNK_ID)));

        RagSearchResult result = ragKnowledgeService.searchDetailed(null, "水管漏水维修", 3);

        assertThat(result.overlapCount()).isEqualTo(1);
        assertThat(result.chunks().get(0).id()).isEqualTo(WATER_CHUNK_ID);
        assertThat(result.topSource()).isEqualTo("KEYWORD+VECTOR");
    }

    @Test
    void shouldFallbackToKeywordWhenVectorDisabled() {
        vectorService.stubEnabled(false);

        RagSearchResult result = ragKnowledgeService.searchDetailed(null, "水管漏水维修", 3);

        assertThat(result.vectorEnabled()).isFalse();
        assertThat(result.vectorCandidateCount()).isZero();
        assertThat(result.chunks()).extracting(RagChunkResponse::id).contains(WATER_CHUNK_ID);
    }

    private RagChunkResponse vectorMatch(Long chunkId) {
        return new RagChunkResponse(chunkId, DOCUMENT_ID, "Milvus", "向量召回片段", 0.92);
    }

    private void insertDocumentAndChunks() {
        if (documentMapper.selectById(DOCUMENT_ID) != null) {
            return;
        }
        RagKnowledgeDocument document = new RagKnowledgeDocument();
        document.setId(DOCUMENT_ID);
        document.setTitle("后勤维修知识库");
        document.setCategoryId(20001L);
        document.setContent(WATER_CONTENT + System.lineSeparator() + AIR_CONDITIONER_CONTENT);
        document.setEnabled(1);
        document.setCreatedBy(10003L);
        document.setDeleted(0);
        documentMapper.insert(document);

        insertChunk(WATER_CHUNK_ID, 0, WATER_CONTENT);
        insertChunk(AIR_CONDITIONER_CHUNK_ID, 1, AIR_CONDITIONER_CONTENT);
    }

    private void insertChunk(Long chunkId, int index, String content) {
        RagKnowledgeChunk chunk = new RagKnowledgeChunk();
        chunk.setId(chunkId);
        chunk.setDocumentId(DOCUMENT_ID);
        chunk.setChunkIndex(index);
        chunk.setContent(content);
        chunk.setTokenCount(content.length() / 2);
        chunk.setEmbeddingProvider("LOCAL_HASH:test");
        chunk.setVectorStoreStatus("DISABLED");
        chunk.setEnabled(1);
        chunk.setDeleted(0);
        chunkMapper.insert(chunk);
    }
}
