package com.maou.apptemplateapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maou.apptemplateapi.common.enums.UserRole;
import com.maou.apptemplateapi.common.security.CurrentUser;
import com.maou.apptemplateapi.common.security.JwtTokenService;
import com.maou.apptemplateapi.common.security.LoginType;
import com.maou.apptemplateapi.module.ticket.mapper.RepairTicketMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class TicketFlowIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RepairTicketMapper repairTicketMapper;

    @Test
    void shouldCompleteTicketMainFlow() throws Exception {
        Long ticketId = createTicket(studentToken(10001L));

        mockMvc.perform(post("/api/v1/admin/tickets/{ticketId}/assign", ticketId)
                        .header("Authorization", bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "categoryId": 20001,
                                  "priority": "MEDIUM",
                                  "summary": "一号教学楼水管漏水",
                                  "workerId": 10002,
                                  "remark": "请尽快处理"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ASSIGNED"));

        mockMvc.perform(post("/api/v1/worker/tickets/{ticketId}/accept", ticketId)
                        .header("Authorization", bearer(workerToken(10002L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PROCESSING"));

        mockMvc.perform(post("/api/v1/worker/tickets/{ticketId}/result", ticketId)
                        .header("Authorization", bearer(workerToken(10002L)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "result": "已更换损坏水阀",
                                  "remark": "现场已恢复正常"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("WAITING_CONFIRM"));

        mockMvc.perform(post("/api/v1/student/tickets/{ticketId}/evaluation", ticketId)
                        .header("Authorization", bearer(studentToken(10001L)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "score": 5,
                                  "content": "处理很及时"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.flows", hasSize(5)))
                .andExpect(jsonPath("$.data.evaluation.score").value(5));
    }

    @Test
    void shouldRejectCrossRoleAndInvalidStateOperations() throws Exception {
        Long ticketId = createTicket(studentToken(10001L));

        mockMvc.perform(get("/api/v1/student/tickets/{ticketId}", ticketId)
                        .header("Authorization", bearer(studentToken(10004L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(9603));

        mockMvc.perform(post("/api/v1/worker/tickets/{ticketId}/accept", ticketId)
                        .header("Authorization", bearer(workerToken(10002L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(9603));

        mockMvc.perform(post("/api/v1/admin/tickets/{ticketId}/assign", ticketId)
                        .header("Authorization", bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "categoryId": 20001,
                                  "priority": "LOW",
                                  "summary": "测试派单",
                                  "workerId": 10002,
                                  "remark": "首次派单"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ASSIGNED"));

        mockMvc.perform(post("/api/v1/admin/tickets/{ticketId}/assign", ticketId)
                        .header("Authorization", bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "categoryId": 20001,
                                  "priority": "LOW",
                                  "summary": "重复派单",
                                  "workerId": 10002,
                                  "remark": "重复"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(9602));
    }

    @Test
    void shouldRollbackTicketWhenReportImageBindFailsOnCreate() throws Exception {
        Long beforeCount = repairTicketMapper.selectCount(null);

        mockMvc.perform(post("/api/v1/student/tickets")
                        .header("Authorization", bearer(studentToken(10001L)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "locationId": 30002,
                                  "categoryId": 20001,
                                  "description": "教学楼照明损坏，提交时携带不存在的图片文件。",
                                  "contactPhone": "13800000001",
                                  "reportImageFileIds": [999999]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(12001));

        Long afterCount = repairTicketMapper.selectCount(null);
        org.assertj.core.api.Assertions.assertThat(afterCount).isEqualTo(beforeCount);
    }

    private Long createTicket(String token) throws Exception {
        String response = mockMvc.perform(post("/api/v1/student/tickets")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "locationId": 30002,
                                  "categoryId": 20001,
                                  "description": "一号教学楼101水管漏水，请尽快维修。",
                                  "contactPhone": "13800000001"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING_REVIEW"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode root = objectMapper.readTree(response);
        return root.path("data").path("id").asLong();
    }

    private String studentToken(Long userId) {
        return token(userId, "student" + userId, UserRole.STUDENT);
    }

    private String workerToken(Long userId) {
        return token(userId, "worker" + userId, UserRole.WORKER);
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
