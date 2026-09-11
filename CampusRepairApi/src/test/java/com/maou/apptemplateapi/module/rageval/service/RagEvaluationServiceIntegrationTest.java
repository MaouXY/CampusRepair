package com.maou.apptemplateapi.module.rageval.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.maou.apptemplateapi.common.enums.UserRole;
import com.maou.apptemplateapi.common.security.CurrentUser;
import com.maou.apptemplateapi.common.security.JwtTokenService;
import com.maou.apptemplateapi.common.security.LoginType;
import com.maou.apptemplateapi.module.rag.entity.RagKnowledgeChunk;
import com.maou.apptemplateapi.module.rag.entity.RagKnowledgeDocument;
import com.maou.apptemplateapi.module.rag.mapper.RagKnowledgeChunkMapper;
import com.maou.apptemplateapi.module.rag.mapper.RagKnowledgeDocumentMapper;
import com.maou.apptemplateapi.module.rageval.dto.RagEvalRunDetailResponse;
import com.maou.apptemplateapi.module.rageval.entity.RagEvalCase;
import com.maou.apptemplateapi.module.rageval.mapper.RagEvalCaseMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class RagEvaluationServiceIntegrationTest {

    private static final String DATASET = "test-eval";
    private static final long DOCUMENT_ID = 92001L;
    private static final long WATER_CHUNK_ID = 92012L;
    private static final long AIR_CHUNK_ID = 92011L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RagKnowledgeDocumentMapper documentMapper;

    @Autowired
    private RagKnowledgeChunkMapper chunkMapper;

    @Autowired
    private RagEvalCaseMapper evalCaseMapper;

    @BeforeEach
    void setUp() {
        insertKnowledgeBase();
        insertEvalCase(93001L, "水管漏水应该怎么处理？", "[\"水管漏水\"]", 1);
        insertEvalCase(93002L, "空调不制冷怎么排查？", "[\"空调不制冷\"]", 1);
        insertEvalCase(93003L, "学校食堂的营业时间是什么？", "[]", 0);
    }

    @Test
    void shouldRunEvaluationAndReportMetrics() throws Exception {
        RagEvalRunDetailResponse detail = runEvaluation(DATASET, 5);

        assertThat(detail.run().status()).isEqualTo(RagEvaluationService.STATUS_SUCCESS);
        assertThat(detail.run().metrics().caseCount()).isEqualTo(3);
        assertThat(detail.run().metrics().answerableCaseCount()).isEqualTo(2);
        assertThat(detail.run().metrics().unanswerableCaseCount()).isEqualTo(1);
        assertThat(detail.run().metrics().hitRate()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(detail.run().metrics().recallAtK()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(detail.run().metrics().mrr()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(detail.run().metrics().ndcgAtK()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(detail.run().metrics().refusalAccuracy()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(detail.results()).hasSize(3);
        assertThat(detail.results()).allMatch(result -> result.hit());
        assertThat(detail.results().stream()
                .filter(result -> result.question().contains("水管漏水"))
                .findFirst()
                .orElseThrow()
                .retrievedChunks())
                .isNotEmpty()
                .allMatch(chunk -> WATER_CHUNK_ID == chunk.chunkId() || DOCUMENT_ID == chunk.documentId());
    }

    @Test
    void shouldExposeRunHistoryAndDetail() throws Exception {
        RagEvalRunDetailResponse detail = runEvaluation(DATASET, 3);

        mockMvc.perform(get("/api/v1/admin/rag/eval/runs")
                        .header("Authorization", bearer(adminToken()))
                        .param("page", "1")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.items[0].id").value(String.valueOf(detail.run().id())));

        mockMvc.perform(get("/api/v1/admin/rag/eval/runs/{runId}", detail.run().id())
                        .header("Authorization", bearer(adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.results.length()").value(3));
    }

    @Test
    void shouldRejectEmptyDatasetAndNonAdmin() throws Exception {
        mockMvc.perform(post("/api/v1/admin/rag/eval/runs")
                        .header("Authorization", bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"datasetName\":\"not-exists\",\"topK\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(31001));

        mockMvc.perform(post("/api/v1/admin/rag/eval/runs")
                        .header("Authorization", bearer(token(10001L, "student01", UserRole.STUDENT)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"datasetName\":\"" + DATASET + "\",\"topK\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void shouldImportJsonlDatasetWithAliasFields() throws Exception {
        String content = """
                {"query": "空调不制冷怎么排查？", "gold_doc_ids": [92001], "answerable": true, "task_type": "factual"}
                # 这是注释行，会被忽略
                {"input": "学校食堂营业时间？", "answerable": false, "dataset": "imported-eval"}
                """;

        mockMvc.perform(post("/api/v1/admin/rag/eval/cases/import")
                        .header("Authorization", bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "datasetName", "imported-eval",
                                "source", "open-dataset-sample",
                                "content", content))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(2));

        mockMvc.perform(get("/api/v1/admin/rag/eval/cases")
                        .header("Authorization", bearer(adminToken()))
                        .param("datasetName", "imported-eval"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.items[0].question").value("空调不制冷怎么排查？"))
                .andExpect(jsonPath("$.data.items[0].expectedDocIds[0]").value("92001"))
                .andExpect(jsonPath("$.data.items[0].source").value("open-dataset-sample"));
    }

    private RagEvalRunDetailResponse runEvaluation(String datasetName, int topK) throws Exception {
        String body = mockMvc.perform(post("/api/v1/admin/rag/eval/runs")
                        .header("Authorization", bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"datasetName\":\"" + datasetName + "\",\"topK\":" + topK + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.treeToValue(objectMapper.readTree(body).path("data"), RagEvalRunDetailResponse.class);
    }

    private void insertKnowledgeBase() {
        if (documentMapper.selectById(DOCUMENT_ID) != null) {
            return;
        }
        RagKnowledgeDocument document = new RagKnowledgeDocument();
        document.setId(DOCUMENT_ID);
        document.setTitle("后勤维修知识库");
        document.setCategoryId(null);
        document.setContent("空调不制冷排查流程与水水管漏水处理流程");
        document.setEnabled(1);
        document.setCreatedBy(10003L);
        document.setDeleted(0);
        documentMapper.insert(document);
        insertChunk(AIR_CHUNK_ID, 0, "空调不制冷排查流程：先确认遥控器模式与设定温度，再清洗滤网并检查室外机。");
        insertChunk(WATER_CHUNK_ID, 1, "水管漏水处理流程：先关闭楼道总阀，再更换老化密封圈并复查水压。");
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

    private void insertEvalCase(Long caseId, String question, String expectedKeywords, int answerable) {
        RagEvalCase evalCase = new RagEvalCase();
        evalCase.setId(caseId);
        evalCase.setDatasetName(DATASET);
        evalCase.setQuestion(question);
        evalCase.setExpectedDocIds("[]");
        evalCase.setExpectedKeywords(expectedKeywords);
        evalCase.setAnswerable(answerable);
        evalCase.setTaskType(answerable == 1 ? "factual" : "unanswerable");
        evalCase.setSource("test");
        evalCase.setDeleted(0);
        evalCaseMapper.insert(evalCase);
    }

    private String adminToken() {
        return token(10003L, "admin01", UserRole.ADMIN);
    }

    private String token(Long userId, String subject, UserRole role) {
        return jwtTokenService.generateToken(CurrentUser.builder()
                .principalId(userId)
                .subject(subject)
                .loginType(LoginType.PASSWORD)
                .roleCode(role.name())
                .organizationId(null)
                .build());
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
