package com.maou.apptemplateapi.module.stats.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maou.apptemplateapi.common.enums.UserRole;
import com.maou.apptemplateapi.common.security.CurrentUser;
import com.maou.apptemplateapi.common.security.JwtTokenService;
import com.maou.apptemplateapi.common.security.LoginType;
import com.maou.apptemplateapi.module.ticket.entity.RepairEvaluation;
import com.maou.apptemplateapi.module.ticket.entity.RepairTicket;
import com.maou.apptemplateapi.module.ticket.entity.RepairTicketFlow;
import com.maou.apptemplateapi.module.ticket.mapper.RepairEvaluationMapper;
import com.maou.apptemplateapi.module.ticket.mapper.RepairTicketFlowMapper;
import com.maou.apptemplateapi.module.ticket.mapper.RepairTicketMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 第三阶段 V3：绩效与满意度分析、高发故障/地点统计、月度维修报告。
 *
 * <p>统一使用维修员 10007 与分类「网络设备」(20003)，避免与其它测试类的数据互相干扰。
 */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AnalyticsServiceIntegrationTest {

    private static final long WORKER_ID = 10007L;
    private static final long CATEGORY_ID = 20003L;
    private static final long LOCATION_ID = 30003L;
    private static final long TICKET_BASE_ID = 75000L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RepairTicketMapper ticketMapper;

    @Autowired
    private RepairEvaluationMapper evaluationMapper;

    @Autowired
    private RepairTicketFlowMapper flowMapper;

    @BeforeEach
    void setUp() {
        insertCompletedTicket(TICKET_BASE_ID + 1, 5, true);
        insertCompletedTicket(TICKET_BASE_ID + 2, 5, false);
        insertCompletedTicket(TICKET_BASE_ID + 3, 3, false);
        insertActiveOverdueTicket(TICKET_BASE_ID + 4);
        insertReturnFlow();
    }

    @Test
    void shouldComputeWorkerPerformance() throws Exception {
        JsonNode workers = getData("/api/v1/admin/stats/worker-performance?days=30");
        JsonNode target = findWorker(workers, WORKER_ID);

        assertThat(target).isNotNull();
        assertThat(target.path("workerName").asText()).isEqualTo("周师傅");
        assertThat(target.path("departmentName").asText()).isEqualTo("水电组");
        assertThat(target.path("completedCount").asLong()).isEqualTo(3);
        assertThat(target.path("avgProcessMinutes").decimalValue()).isEqualByComparingTo(BigDecimal.valueOf(60.0));
        assertThat(target.path("avgScore").decimalValue()).isEqualByComparingTo(BigDecimal.valueOf(4.33));
        assertThat(target.path("goodRate").decimalValue()).isEqualByComparingTo(BigDecimal.valueOf(0.6667));
        assertThat(target.path("evaluationCount").asLong()).isEqualTo(3);
        assertThat(target.path("returnCount").asLong()).isEqualTo(1);
        assertThat(target.path("activeCount").asLong()).isEqualTo(1);
        assertThat(target.path("overdueCount").asLong()).isEqualTo(1);
    }

    @Test
    void shouldComputeHotspotsForCategoriesAndLocations() throws Exception {
        JsonNode data = getData("/api/v1/admin/stats/hotspots?days=30&limit=5");

        assertThat(data.path("days").asInt()).isEqualTo(30);
        assertThat(containsLabel(data.path("categories"), "网络设备")).isTrue();
        assertThat(containsLabel(data.path("locations"), "学生宿舍A区")).isTrue();
        assertThat(countOf(data.path("categories"), "网络设备")).isGreaterThanOrEqualTo(4);
    }

    @Test
    void shouldGenerateMonthlyReportWithRuleFallbackSummary() throws Exception {
        JsonNode report = getData("/api/v1/admin/stats/monthly-report");

        assertThat(report.path("month").asText()).isNotEmpty();
        assertThat(report.path("createdCount").asLong()).isGreaterThanOrEqualTo(4);
        assertThat(report.path("completedCount").asLong()).isGreaterThanOrEqualTo(3);
        assertThat(report.path("avgProcessMinutes").decimalValue()).isGreaterThan(BigDecimal.ZERO);
        assertThat(report.path("avgScore").decimalValue()).isGreaterThan(BigDecimal.ZERO);
        assertThat(report.path("goodRate").decimalValue()).isGreaterThan(BigDecimal.ZERO);
        assertThat(containsLabel(report.path("topCategories"), "网络设备")).isTrue();
        assertThat(report.path("topWorkers").isArray()).isTrue();
        assertThat(report.path("aiSummary").asText()).contains("建议");
        assertThat(report.path("aiDegraded").asBoolean()).as("测试环境 AI 不可用时应走规则模板").isTrue();
    }

    @Test
    void shouldRejectNonAdmin() throws Exception {
        mockMvc.perform(get("/api/v1/admin/stats/worker-performance")
                        .header("Authorization", bearer(token(10001L, "student01", UserRole.STUDENT))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));

        mockMvc.perform(get("/api/v1/admin/stats/hotspots")
                        .header("Authorization", bearer(token(10005L, "worker02", UserRole.WORKER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));

        mockMvc.perform(get("/api/v1/admin/stats/monthly-report")
                        .header("Authorization", bearer(token(10001L, "student01", UserRole.STUDENT))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));
    }

    private JsonNode getData(String uri) throws Exception {
        String body = mockMvc.perform(get(uri).header("Authorization", bearer(adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(body).path("data");
    }

    private JsonNode findWorker(JsonNode workers, long workerId) {
        for (JsonNode worker : workers) {
            if (worker.path("workerId").asLong() == workerId) {
                return worker;
            }
        }
        return null;
    }

    private boolean containsLabel(JsonNode items, String label) {
        for (JsonNode item : items) {
            if (label.equals(item.path("label").asText())) {
                return true;
            }
        }
        return false;
    }

    private long countOf(JsonNode items, String label) {
        for (JsonNode item : items) {
            if (label.equals(item.path("label").asText())) {
                return item.path("value").asLong();
            }
        }
        return 0;
    }

    private void insertCompletedTicket(long ticketId, int score, boolean overdue) {
        LocalDateTime assignedAt = LocalDateTime.now().minusHours(2);
        LocalDateTime processedAt = LocalDateTime.now().minusHours(1);
        RepairTicket ticket = new RepairTicket();
        ticket.setId(ticketId);
        ticket.setStudentId(10001L);
        ticket.setLocationId(LOCATION_ID);
        ticket.setCategoryId(CATEGORY_ID);
        ticket.setDescription("宿舍网线接口松动，网络不通。");
        ticket.setContactPhone("13800000001");
        ticket.setSummary("宿舍网络故障");
        ticket.setPriority("MEDIUM");
        ticket.setStatus("COMPLETED");
        ticket.setAssignedWorkerId(WORKER_ID);
        ticket.setAssignedAt(assignedAt);
        ticket.setProcessedAt(processedAt);
        ticket.setSlaDeadlineAt(overdue ? LocalDateTime.now().minusMinutes(30) : LocalDateTime.now().plusHours(2));
        ticket.setProcessResult("已更换水晶头并复测网络连通性");
        ticket.setProcessRemark("网线老化，建议整批更换");
        ticket.setDeleted(0);
        ticketMapper.insert(ticket);

        RepairEvaluation evaluation = new RepairEvaluation();
        evaluation.setId(ticketId * 10);
        evaluation.setTicketId(ticketId);
        evaluation.setStudentId(10001L);
        evaluation.setWorkerId(WORKER_ID);
        evaluation.setScore(score);
        evaluation.setContent(score >= 4 ? "处理很快" : "等待时间偏长");
        evaluationMapper.insert(evaluation);
    }

    private void insertActiveOverdueTicket(long ticketId) {
        RepairTicket ticket = new RepairTicket();
        ticket.setId(ticketId);
        ticket.setStudentId(10001L);
        ticket.setLocationId(LOCATION_ID);
        ticket.setCategoryId(CATEGORY_ID);
        ticket.setDescription("宿舍网络仍然不稳定，请继续排查。");
        ticket.setContactPhone("13800000001");
        ticket.setSummary("宿舍网络复发");
        ticket.setPriority("HIGH");
        ticket.setStatus("PROCESSING");
        ticket.setAssignedWorkerId(WORKER_ID);
        ticket.setAssignedAt(LocalDateTime.now().minusHours(6));
        ticket.setSlaDeadlineAt(LocalDateTime.now().minusHours(2));
        ticket.setDeleted(0);
        ticketMapper.insert(ticket);
    }

    private void insertReturnFlow() {
        RepairTicketFlow flow = new RepairTicketFlow();
        flow.setId(76001L);
        flow.setTicketId(TICKET_BASE_ID + 4);
        flow.setFromStatus("ASSIGNED");
        flow.setToStatus("RETURNED");
        flow.setOperatorId(WORKER_ID);
        flow.setOperatorRole(UserRole.WORKER.name());
        flow.setAction("WORKER_RETURN");
        flow.setRemark("派错专业，申请转派");
        flowMapper.insert(flow);
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
