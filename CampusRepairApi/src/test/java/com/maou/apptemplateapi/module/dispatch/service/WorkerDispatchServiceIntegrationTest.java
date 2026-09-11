package com.maou.apptemplateapi.module.dispatch.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maou.apptemplateapi.common.enums.UserRole;
import com.maou.apptemplateapi.common.security.CurrentUser;
import com.maou.apptemplateapi.common.security.JwtTokenService;
import com.maou.apptemplateapi.common.security.LoginType;
import com.maou.apptemplateapi.module.ai.entity.AiTaskRecord;
import com.maou.apptemplateapi.module.ai.entity.RepairAiAnalysis;
import com.maou.apptemplateapi.module.ai.mapper.AiTaskRecordMapper;
import com.maou.apptemplateapi.module.ai.mapper.RepairAiAnalysisMapper;
import com.maou.apptemplateapi.module.dispatch.dto.DispatchCandidateResponse;
import com.maou.apptemplateapi.module.dispatch.dto.DispatchSuggestionResponse;
import com.maou.apptemplateapi.module.dispatch.entity.WorkerDispatchScoreSnapshot;
import com.maou.apptemplateapi.module.dispatch.mapper.WorkerDispatchScoreSnapshotMapper;
import com.maou.apptemplateapi.module.ticket.entity.RepairTicket;
import com.maou.apptemplateapi.module.ticket.mapper.RepairTicketMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class WorkerDispatchServiceIntegrationTest {

    private static final long TICKET_ID = 71001L;
    private static final long RULE_TOP_WORKER_ID = 10005L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RepairTicketMapper ticketMapper;

    @Autowired
    private RepairAiAnalysisMapper analysisMapper;

    @Autowired
    private WorkerDispatchScoreSnapshotMapper snapshotMapper;

    @Autowired
    private AiTaskRecordMapper aiTaskRecordMapper;

    @Test
    void shouldReturnRuleSuggestionWhenNoAiAnalysis() throws Exception {
        insertWaterLeakTicket(TICKET_ID);

        DispatchSuggestionResponse suggestion = fetchSuggestion(TICKET_ID);

        assertThat(suggestion.recommendationSource()).isEqualTo(WorkerDispatchService.SOURCE_RULE);
        assertThat(suggestion.aiAnalysisId()).isNull();
        assertThat(suggestion.candidates()).isNotEmpty();
        assertThat(suggestion.recommendedWorkerId()).isEqualTo(RULE_TOP_WORKER_ID);
        assertThat(suggestion.recommendedWorkerName()).isEqualTo("赵师傅");
        assertThat(suggestion.recommendedReason()).contains("规则评分");
        assertThat(suggestion.expectedDepartment()).isEqualTo("水电组");
        assertThat(suggestion.requiredSkills()).contains("水电");
        assertThat(suggestion.candidates().stream()
                .filter(candidate -> Boolean.TRUE.equals(candidate.aiRecommended()))
                .map(DispatchCandidateResponse::workerId))
                .containsExactly(RULE_TOP_WORKER_ID);
    }

    @Test
    void shouldPreferAiSuggestedWorkerWhenAnalysisSucceeded() throws Exception {
        insertWaterLeakTicket(TICKET_ID);
        Long analysisId = insertAnalysis(TICKET_ID, "SUCCESS", 10007L, BigDecimal.valueOf(0.88));

        DispatchSuggestionResponse suggestion = fetchSuggestion(TICKET_ID);

        assertThat(suggestion.recommendationSource()).isEqualTo(WorkerDispatchService.SOURCE_AI);
        assertThat(suggestion.aiAnalysisId()).isEqualTo(analysisId);
        assertThat(suggestion.aiAnalysisStatus()).isEqualTo("SUCCESS");
        assertThat(suggestion.recommendedWorkerId()).isEqualTo(10007L);
        assertThat(suggestion.recommendedConfidence()).isEqualByComparingTo(BigDecimal.valueOf(0.88));
        assertThat(suggestion.recommendedReason()).contains("优先派给水电组周师傅");
    }

    @Test
    void shouldFallbackToRuleTopWhenAiSuggestionOutsideCandidates() throws Exception {
        insertWaterLeakTicket(TICKET_ID);
        insertAnalysis(TICKET_ID, "SUCCESS", 999999L, BigDecimal.valueOf(0.9));

        DispatchSuggestionResponse suggestion = fetchSuggestion(TICKET_ID);

        assertThat(suggestion.recommendedWorkerId()).isEqualTo(RULE_TOP_WORKER_ID);
        assertThat(suggestion.recommendedWorkerName()).isEqualTo("赵师傅");
    }

    @Test
    void shouldUseSnapshotCandidatesWhenAnalysisHasSnapshots() throws Exception {
        insertWaterLeakTicket(TICKET_ID);
        Long analysisId = insertAnalysis(TICKET_ID, "SUCCESS", 10002L, BigDecimal.valueOf(0.7));
        insertSnapshot(analysisId, 10002L, BigDecimal.valueOf(66.50), 1);

        DispatchSuggestionResponse suggestion = fetchSuggestion(TICKET_ID);

        assertThat(suggestion.candidates()).hasSize(1);
        assertThat(suggestion.candidates().get(0).workerId()).isEqualTo(10002L);
        assertThat(suggestion.candidates().get(0).totalScore()).isEqualByComparingTo(BigDecimal.valueOf(66.50));
        assertThat(suggestion.recommendedWorkerId()).isEqualTo(10002L);
    }

    @Test
    void shouldRejectNonAdminCallerAndAcceptAdminCaller() throws Exception {
        insertWaterLeakTicket(TICKET_ID);

        mockMvc.perform(get("/api/v1/admin/tickets/{ticketId}/dispatch-suggestion", TICKET_ID)
                        .header("Authorization", bearer(token(10001L, "student01", UserRole.STUDENT))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));

        mockMvc.perform(get("/api/v1/admin/tickets/{ticketId}/dispatch-suggestion", TICKET_ID)
                        .header("Authorization", bearer(adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.recommendationSource").value("RULE"))
                .andExpect(jsonPath("$.data.recommendedWorkerId").value(RULE_TOP_WORKER_ID))
                .andExpect(jsonPath("$.data.candidates[0].workerName").value("赵师傅"));
    }

    @Test
    void shouldPersistRuleFallbackWhenAiClientUnavailable() throws Exception {
        insertWaterLeakTicket(TICKET_ID);

        mockMvc.perform(post("/api/v1/admin/tickets/{ticketId}/ai/analysis", TICKET_ID)
                        .header("Authorization", bearer(adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.suggestedWorkerId").value(RULE_TOP_WORKER_ID))
                .andExpect(jsonPath("$.data.dispatchCandidates").isNotEmpty());

        Long analysisId = analysisMapper.selectOne(new LambdaQueryWrapper<RepairAiAnalysis>()
                        .eq(RepairAiAnalysis::getTicketId, TICKET_ID)
                        .orderByDesc(RepairAiAnalysis::getCreatedAt)
                        .last("limit 1"))
                .getId();
        List<WorkerDispatchScoreSnapshot> snapshots = snapshotMapper.selectList(
                new LambdaQueryWrapper<WorkerDispatchScoreSnapshot>()
                        .eq(WorkerDispatchScoreSnapshot::getAiAnalysisId, analysisId));
        assertThat(snapshots).isNotEmpty();
        assertThat(snapshots.stream().filter(snapshot -> snapshot.getAiRecommended() == 1))
                .extracting(WorkerDispatchScoreSnapshot::getWorkerId)
                .containsExactly(RULE_TOP_WORKER_ID);
        assertThat(aiTaskRecordMapper.selectList(new LambdaQueryWrapper<AiTaskRecord>()
                .eq(AiTaskRecord::getBizId, TICKET_ID)))
                .extracting(AiTaskRecord::getStatus)
                .contains("FAILED");
    }

    private DispatchSuggestionResponse fetchSuggestion(Long ticketId) throws Exception {
        String body = mockMvc.perform(get("/api/v1/admin/tickets/{ticketId}/dispatch-suggestion", ticketId)
                        .header("Authorization", bearer(adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.treeToValue(objectMapper.readTree(body).path("data"), DispatchSuggestionResponse.class);
    }

    private void insertWaterLeakTicket(Long ticketId) {
        RepairTicket ticket = new RepairTicket();
        ticket.setId(ticketId);
        ticket.setStudentId(10001L);
        ticket.setLocationId(30002L);
        ticket.setCategoryId(20001L);
        ticket.setDescription("一号教学楼101水管漏水，插座附近有用电风险，请尽快维修。");
        ticket.setContactPhone("13800000001");
        ticket.setSummary("一号教学楼水管漏水");
        ticket.setPriority("MEDIUM");
        ticket.setStatus("PENDING_REVIEW");
        ticket.setDeleted(0);
        ticketMapper.insert(ticket);
    }

    private Long insertAnalysis(Long ticketId, String status, Long suggestedWorkerId, BigDecimal confidence) {
        RepairAiAnalysis analysis = new RepairAiAnalysis();
        analysis.setId(81001L);
        analysis.setTicketId(ticketId);
        analysis.setStatus(status);
        analysis.setSuggestedCategoryId(20001L);
        analysis.setSuggestedPriority("MEDIUM");
        analysis.setSuggestedWorkerId(suggestedWorkerId);
        analysis.setFaultSummary("水管漏水");
        analysis.setFaultReason("阀门老化");
        analysis.setSolution("更换阀门");
        analysis.setDispatchRemark("优先派给水电组周师傅，其负载较低且历史质量稳定。");
        analysis.setRiskLevel("MEDIUM");
        analysis.setConfidence(confidence);
        analysis.setDeleted(0);
        analysisMapper.insert(analysis);
        return analysis.getId();
    }

    private void insertSnapshot(Long analysisId, Long workerId, BigDecimal totalScore, int aiRecommended) {
        WorkerDispatchScoreSnapshot snapshot = new WorkerDispatchScoreSnapshot();
        snapshot.setId(82001L);
        snapshot.setTicketId(TICKET_ID);
        snapshot.setAiAnalysisId(analysisId);
        snapshot.setWorkerId(workerId);
        snapshot.setWorkerName("李师傅");
        snapshot.setDepartmentName("网络组");
        snapshot.setSkillTags("[\"网络\"]");
        snapshot.setActiveOrderCount(0);
        snapshot.setMaxActiveOrders(3);
        snapshot.setSkillScore(BigDecimal.valueOf(40));
        snapshot.setDepartmentScore(BigDecimal.ZERO);
        snapshot.setWorkloadScore(BigDecimal.valueOf(20));
        snapshot.setQualityScore(BigDecimal.valueOf(12));
        snapshot.setPenaltyScore(BigDecimal.ZERO);
        snapshot.setTotalScore(totalScore);
        snapshot.setRuleReason("技能匹配40，部门未命中，当前活跃工单0/3");
        snapshot.setAiRecommended(aiRecommended);
        snapshotMapper.insert(snapshot);
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
