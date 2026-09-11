package com.maou.apptemplateapi.module.rag.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maou.apptemplateapi.common.enums.UserRole;
import com.maou.apptemplateapi.common.security.CurrentUser;
import com.maou.apptemplateapi.common.security.JwtTokenService;
import com.maou.apptemplateapi.common.security.LoginType;
import com.maou.apptemplateapi.module.ai.dto.AiCompletion;
import com.maou.apptemplateapi.module.ai.dto.AiTokenUsage;
import com.maou.apptemplateapi.module.ai.service.AiClient;
import com.maou.apptemplateapi.module.ai.service.AiImageInput;
import com.maou.apptemplateapi.module.rag.dto.KnowledgeDraftResponse;
import com.maou.apptemplateapi.module.rag.entity.RagKnowledgeChunk;
import com.maou.apptemplateapi.module.rag.entity.RagKnowledgeDocument;
import com.maou.apptemplateapi.module.rag.entity.RagKnowledgeDraft;
import com.maou.apptemplateapi.module.rag.mapper.RagKnowledgeChunkMapper;
import com.maou.apptemplateapi.module.rag.mapper.RagKnowledgeDocumentMapper;
import com.maou.apptemplateapi.module.rag.mapper.RagKnowledgeDraftMapper;
import com.maou.apptemplateapi.module.ticket.entity.RepairEvaluation;
import com.maou.apptemplateapi.module.ticket.entity.RepairTicket;
import com.maou.apptemplateapi.module.ticket.mapper.RepairEvaluationMapper;
import com.maou.apptemplateapi.module.ticket.mapper.RepairTicketMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 第三阶段 V3：典型工单自动沉淀知识草稿 → 管理员审核入库。
 */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class RagKnowledgeDraftServiceIntegrationTest {

    private static final long TICKET_ID = 74001L;
    private static final long TICKET_NOT_WORTH_ID = 74002L;

    @TestConfiguration
    static class StubAiClientConfig {

        @Bean
        @Primary
        StubDraftAiClient stubDraftAiClient() {
            return new StubDraftAiClient();
        }
    }

    static class StubDraftAiClient implements AiClient {

        private final AtomicBoolean failing = new AtomicBoolean(false);

        @Override
        public AiCompletion complete(String systemPrompt, String userPrompt) {
            if (failing.get()) {
                throw new IllegalStateException("stub ai unavailable");
            }
            return AiCompletion.of("""
                    {"title": "水管漏水快速处理指引", "content": "【适用场景】教学楼水房\\n【故障现象】管路接口持续渗水\\n【处理步骤】关闭角阀→更换密封圈→复压检查\\n【注意事项】先断电关阀再作业。"}
                    """, "stub-model", AiTokenUsage.api(300, 120, 420));
        }

        @Override
        public AiCompletion completeWithImages(String systemPrompt, String userPrompt, List<AiImageInput> images) {
            return complete(systemPrompt, userPrompt);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private StubDraftAiClient stubAiClient;

    @Autowired
    private RepairTicketMapper ticketMapper;

    @Autowired
    private RepairEvaluationMapper evaluationMapper;

    @Autowired
    private RagKnowledgeDraftMapper draftMapper;

    @Autowired
    private RagKnowledgeDocumentMapper documentMapper;

    @Autowired
    private RagKnowledgeChunkMapper chunkMapper;

    @BeforeEach
    void setUp() {
        stubAiClient.failing.set(false);
        insertCompletedTicket(TICKET_ID, true);
        insertCompletedTicket(TICKET_NOT_WORTH_ID, false);
    }

    @Test
    void shouldGenerateAiDraftForCompletedTicket() throws Exception {
        KnowledgeDraftResponse draft = generate(TICKET_ID);

        assertThat(draft.status()).isEqualTo(RagKnowledgeDraft.STATUS_PENDING_REVIEW);
        assertThat(draft.createdByAi()).isTrue();
        assertThat(draft.generateSource()).isEqualTo(RagKnowledgeDraft.SOURCE_AI);
        assertThat(draft.title()).isEqualTo("水管漏水快速处理指引");
        assertThat(draft.content()).contains("关闭角阀");
        assertThat(draft.sourceTicketId()).isEqualTo(TICKET_ID);
    }

    @Test
    void shouldReuseExistingDraftInsteadOfDuplicating() throws Exception {
        KnowledgeDraftResponse first = generate(TICKET_ID);
        KnowledgeDraftResponse second = generate(TICKET_ID);

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(draftMapper.selectList(new LambdaQueryWrapper<RagKnowledgeDraft>()
                .eq(RagKnowledgeDraft::getSourceTicketId, TICKET_ID))).hasSize(1);
    }

    @Test
    void shouldFallbackToRuleTemplateWhenAiUnavailable() throws Exception {
        stubAiClient.failing.set(true);

        KnowledgeDraftResponse draft = generate(TICKET_ID);

        assertThat(draft.status()).isEqualTo(RagKnowledgeDraft.STATUS_PENDING_REVIEW);
        assertThat(draft.createdByAi()).isFalse();
        assertThat(draft.generateSource()).isEqualTo(RagKnowledgeDraft.SOURCE_RULE);
        assertThat(draft.content()).contains("【故障现象】").contains("已更换损坏水阀");
    }

    @Test
    void shouldApproveDraftAndCreateKnowledgeDocumentWithChunks() throws Exception {
        KnowledgeDraftResponse draft = generate(TICKET_ID);

        mockMvc.perform(post("/api/v1/admin/rag/drafts/{draftId}/approve", draft.id())
                        .header("Authorization", bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"remark\":\"内容合理，入库\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value(RagKnowledgeDraft.STATUS_APPROVED))
                .andExpect(jsonPath("$.data.knowledgeDocumentId").isNotEmpty());

        RagKnowledgeDraft approved = draftMapper.selectById(draft.id());
        assertThat(approved.getKnowledgeDocumentId()).isNotNull();
        assertThat(approved.getReviewedBy()).isEqualTo(10003L);
        assertThat(approved.getReviewedAt()).isNotNull();
        RagKnowledgeDocument document = documentMapper.selectById(approved.getKnowledgeDocumentId());
        assertThat(document.getTitle()).isEqualTo(draft.title());
        assertThat(document.getEnabled()).isEqualTo(1);
        assertThat(chunkMapper.selectList(new LambdaQueryWrapper<RagKnowledgeChunk>()
                .eq(RagKnowledgeChunk::getDocumentId, document.getId()))).isNotEmpty();
    }

    @Test
    void shouldBeIdempotentWhenApprovingTwice() throws Exception {
        KnowledgeDraftResponse draft = generate(TICKET_ID);
        mockMvc.perform(post("/api/v1/admin/rag/drafts/{draftId}/approve", draft.id())
                        .header("Authorization", bearer(adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        Long documentId = draftMapper.selectById(draft.id()).getKnowledgeDocumentId();

        mockMvc.perform(post("/api/v1/admin/rag/drafts/{draftId}/approve", draft.id())
                        .header("Authorization", bearer(adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.knowledgeDocumentId").isNotEmpty());

        assertThat(draftMapper.selectById(draft.id()).getKnowledgeDocumentId()).isEqualTo(documentId);
        assertThat(documentMapper.selectCount(new LambdaQueryWrapper<RagKnowledgeDocument>()
                .eq(RagKnowledgeDocument::getId, documentId))).isEqualTo(1);
    }

    @Test
    void shouldRejectDraftWithoutCreatingDocument() throws Exception {
        KnowledgeDraftResponse draft = generate(TICKET_ID);

        mockMvc.perform(post("/api/v1/admin/rag/drafts/{draftId}/reject", draft.id())
                        .header("Authorization", bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"remark\":\"与已有知识重复\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value(RagKnowledgeDraft.STATUS_REJECTED));

        RagKnowledgeDraft rejected = draftMapper.selectById(draft.id());
        assertThat(rejected.getKnowledgeDocumentId()).isNull();
        assertThat(rejected.getReviewRemark()).isEqualTo("与已有知识重复");
    }

    @Test
    void shouldRejectGenerationWhenTicketNotWorthPrecipitating() throws Exception {
        mockMvc.perform(post("/api/v1/admin/rag/drafts/generate")
                        .param("ticketId", String.valueOf(TICKET_NOT_WORTH_ID))
                        .header("Authorization", bearer(adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(409));
    }

    @Test
    void shouldRejectNonAdminAndListDraftsForAdmin() throws Exception {
        generate(TICKET_ID);

        mockMvc.perform(get("/api/v1/admin/rag/drafts")
                        .header("Authorization", bearer(token(10001L, "student01", UserRole.STUDENT))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));

        String body = mockMvc.perform(get("/api/v1/admin/rag/drafts")
                        .header("Authorization", bearer(adminToken()))
                        .param("status", RagKnowledgeDraft.STATUS_PENDING_REVIEW))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("水管漏水快速处理指引");
    }

    private KnowledgeDraftResponse generate(long ticketId) throws Exception {
        String body = mockMvc.perform(post("/api/v1/admin/rag/drafts/generate")
                        .param("ticketId", String.valueOf(ticketId))
                        .header("Authorization", bearer(adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.treeToValue(objectMapper.readTree(body).path("data"), KnowledgeDraftResponse.class);
    }

    private void insertCompletedTicket(long ticketId, boolean worthPrecipitating) {
        RepairTicket ticket = new RepairTicket();
        ticket.setId(ticketId);
        ticket.setStudentId(10001L);
        ticket.setLocationId(30002L);
        ticket.setCategoryId(20001L);
        ticket.setDescription("一号教学楼101水管漏水，插座附近有用电风险。");
        ticket.setContactPhone("13800000001");
        ticket.setSummary("一号教学楼水管漏水");
        ticket.setPriority("MEDIUM");
        ticket.setStatus("COMPLETED");
        ticket.setAssignedWorkerId(10005L);
        ticket.setAssignedAt(LocalDateTime.now().minusHours(2));
        ticket.setProcessedAt(LocalDateTime.now().minusHours(1));
        ticket.setSlaDeadlineAt(LocalDateTime.now().plusHours(1));
        ticket.setProcessResult(worthPrecipitating ? "已更换损坏水阀并复压检查，恢复正常使用" : null);
        ticket.setProcessRemark(worthPrecipitating ? "现场已清理，建议同类阀门定期更换" : null);
        ticket.setDeleted(0);
        ticketMapper.insert(ticket);
        if (worthPrecipitating) {
            RepairEvaluation evaluation = new RepairEvaluation();
            evaluation.setId(ticketId * 10);
            evaluation.setTicketId(ticketId);
            evaluation.setStudentId(10001L);
            evaluation.setWorkerId(10005L);
            evaluation.setScore(5);
            evaluation.setContent("处理很及时");
            evaluationMapper.insert(evaluation);
        }
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
