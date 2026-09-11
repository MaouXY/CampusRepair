package com.maou.apptemplateapi.module.ticket.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maou.apptemplateapi.common.enums.UserRole;
import com.maou.apptemplateapi.common.security.CurrentUser;
import com.maou.apptemplateapi.common.security.JwtTokenService;
import com.maou.apptemplateapi.common.security.LoginType;
import com.maou.apptemplateapi.module.ticket.entity.RepairTicket;
import com.maou.apptemplateapi.module.ticket.mapper.RepairTicketMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SLA 超时口径（走真实接口验证，而非复述状态清单）：
 * 「已退回」与进行中状态一样参与 SLA 计时（过期即超时），已完结状态不计。
 */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class TicketSlaOverdueScopeTest {

    private static final long WORKER_ID = 10005L;
    private static final long STUDENT_ID = 10001L;
    private static final long RETURNED_TICKET_ID = 78101L;
    private static final long PROCESSING_TICKET_ID = 78102L;
    private static final long COMPLETED_TICKET_ID = 78103L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RepairTicketMapper ticketMapper;

    @BeforeEach
    void setUp() {
        LocalDateTime overdueDeadline = LocalDateTime.now().minusDays(2);
        insertTicket(RETURNED_TICKET_ID, "RETURNED", overdueDeadline);
        insertTicket(PROCESSING_TICKET_ID, "PROCESSING", overdueDeadline);
        insertTicket(COMPLETED_TICKET_ID, "COMPLETED", overdueDeadline);
    }

    @Test
    void shouldIncludeReturnedTicketInOverdueFilter() throws Exception {
        List<Long> overdueIds = queryTicketIds("/api/v1/worker/tickets?page=1&size=50&overdue=true");

        assertThat(overdueIds).as("已退回且已过期 → 计入超时筛选").contains(RETURNED_TICKET_ID);
        assertThat(overdueIds).as("处理中且已过期 → 计入超时筛选").contains(PROCESSING_TICKET_ID);
        assertThat(overdueIds).as("已完成不计超时").doesNotContain(COMPLETED_TICKET_ID);
    }

    @Test
    void shouldMarkReturnedTicketAsSlaOverdueInList() throws Exception {
        String body = mockMvc.perform(get("/api/v1/worker/tickets")
                        .header("Authorization", bearer(workerToken()))
                        .param("page", "1")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        JsonNode items = objectMapper.readTree(body).path("data").path("items");
        boolean returnedOverdue = false;
        boolean completedOverdue = true;
        for (JsonNode item : items) {
            long id = item.path("id").asLong();
            if (id == RETURNED_TICKET_ID) {
                returnedOverdue = item.path("slaOverdue").asBoolean();
            }
            if (id == COMPLETED_TICKET_ID) {
                completedOverdue = item.path("slaOverdue").asBoolean();
            }
        }
        assertThat(returnedOverdue).as("已退回工单的 slaOverdue 应为 true").isTrue();
        assertThat(completedOverdue).as("已完成工单不应标记超时").isFalse();
    }

    @Test
    void shouldCountReturnedTicketInWorkerOverdueSummary() throws Exception {
        String body = mockMvc.perform(get("/api/v1/worker/tickets/today-overview")
                        .header("Authorization", bearer(workerToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(objectMapper.readTree(body).path("data").path("overdue").asLong())
                .as("工作台 SLA 超时统计应包含已退回工单")
                .isGreaterThanOrEqualTo(2);
    }

    private List<Long> queryTicketIds(String uri) throws Exception {
        String body = mockMvc.perform(get(uri).header("Authorization", bearer(workerToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<Long> ids = new ArrayList<>();
        for (JsonNode item : objectMapper.readTree(body).path("data").path("items")) {
            ids.add(item.path("id").asLong());
        }
        return ids;
    }

    private void insertTicket(long ticketId, String status, LocalDateTime slaDeadline) {
        RepairTicket ticket = new RepairTicket();
        ticket.setId(ticketId);
        ticket.setStudentId(STUDENT_ID);
        ticket.setLocationId(30002L);
        ticket.setCategoryId(20001L);
        ticket.setDescription("SLA 口径测试工单：" + status);
        ticket.setContactPhone("13800000001");
        ticket.setSummary("SLA 口径测试：" + status);
        ticket.setPriority("MEDIUM");
        ticket.setStatus(status);
        ticket.setAssignedWorkerId(WORKER_ID);
        ticket.setAssignedAt(slaDeadline.minusHours(1));
        ticket.setSlaDeadlineAt(slaDeadline);
        ticket.setDeleted(0);
        ticketMapper.insert(ticket);
    }

    private String workerToken() {
        return jwtTokenService.generateToken(CurrentUser.builder()
                .principalId(WORKER_ID)
                .subject("worker02")
                .loginType(LoginType.PASSWORD)
                .roleCode(UserRole.WORKER.name())
                .organizationId(null)
                .build());
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
