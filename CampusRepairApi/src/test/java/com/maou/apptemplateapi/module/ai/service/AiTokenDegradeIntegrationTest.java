package com.maou.apptemplateapi.module.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maou.apptemplateapi.common.enums.UserRole;
import com.maou.apptemplateapi.common.security.CurrentUser;
import com.maou.apptemplateapi.common.security.JwtTokenService;
import com.maou.apptemplateapi.common.security.LoginType;
import com.maou.apptemplateapi.module.ai.dto.AiCompletion;
import com.maou.apptemplateapi.module.ai.dto.AiTokenUsage;
import com.maou.apptemplateapi.module.ai.dto.AiTokenUsageResponse;
import com.maou.apptemplateapi.module.ai.dto.TicketAiAnalysisResponse;
import com.maou.apptemplateapi.module.ai.entity.AiTaskRecord;
import com.maou.apptemplateapi.module.ai.mapper.AiTaskRecordMapper;
import com.maou.apptemplateapi.module.file.entity.FileMetadata;
import com.maou.apptemplateapi.module.file.mapper.FileMetadataMapper;
import com.maou.apptemplateapi.module.ticket.entity.RepairTicket;
import com.maou.apptemplateapi.module.ticket.mapper.RepairTicketMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * token 监控接入验证：max-prompt-tokens 故意调小到 1，用于触发「提示词超长自动降级」。
 */
@ActiveProfiles("test")
@SpringBootTest(properties = {
        "app.ai.ark.image-transfer-mode=URL",
        "app.ai.ark.token.max-prompt-tokens=1",
        "app.ai.ark.token.daily-budget=100000000"
})
@AutoConfigureMockMvc
@Transactional
class AiTokenDegradeIntegrationTest {

    private static final long TICKET_ID = 72001L;
    private static final long TICKET_WITH_IMAGE_ID = 72002L;

    @TestConfiguration
    static class StubAiClientConfig {

        @Bean
        @Primary
        StubAiClient stubAiClient() {
            return new StubAiClient();
        }
    }

    static class StubAiClient implements AiClient {

        private final AtomicInteger callCount = new AtomicInteger();
        private final AtomicInteger lastImageCount = new AtomicInteger(-1);

        @Override
        public AiCompletion complete(String systemPrompt, String userPrompt) {
            callCount.incrementAndGet();
            lastImageCount.set(0);
            return response();
        }

        @Override
        public AiCompletion completeWithImages(String systemPrompt, String userPrompt, List<AiImageInput> images) {
            callCount.incrementAndGet();
            lastImageCount.set(images == null ? 0 : images.size());
            return response();
        }

        private AiCompletion response() {
            return AiCompletion.of("""
                    {"suggestedCategoryId": 20001, "suggestedPriority": "MEDIUM", "suggestedWorkerId": 10005,
                     "faultSummary": "水管漏水", "faultReason": "阀门老化", "solution": "更换阀门",
                     "dispatchRemark": "派给水电组", "riskLevel": "MEDIUM", "confidence": 0.9}
                    """, "stub-model", AiTokenUsage.api(120, 80, 200));
        }
    }

    @Autowired
    private TicketAiAnalysisService ticketAiAnalysisService;

    @Autowired
    private StubAiClient stubAiClient;

    @Autowired
    private RepairTicketMapper ticketMapper;

    @Autowired
    private FileMetadataMapper fileMetadataMapper;

    @Autowired
    private AiTaskRecordMapper aiTaskRecordMapper;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Test
    void shouldCallModelAndRecordTokensForShortPrompt() {
        insertTicket(TICKET_ID, 0);
        stubAiClient.callCount.set(0);

        TicketAiAnalysisResponse response = ticketAiAnalysisService.analyzeTicketAutomatically(TICKET_ID, 10001L, "test-token-normal");

        assertThat(response.status()).isEqualTo("SUCCESS");
        AiTaskRecord task = latestTask(TICKET_ID);
        assertThat(task.getStatus()).isEqualTo(AiTaskService.STATUS_SUCCESS);
        assertThat(task.getInputTokens()).isEqualTo(120);
        assertThat(task.getOutputTokens()).isEqualTo(80);
        assertThat(task.getTotalTokens()).isEqualTo(200);
        assertThat(task.getTokenSource()).isEqualTo(AiTokenUsage.SOURCE_API);
        assertThat(task.getPromptTokens()).isNotNull();
        assertThat(task.getDegradeLevel()).isIn("NORMAL", "MINIMAL_CONTEXT");
        assertThat(stubAiClient.lastImageCount.get()).isZero();
    }

    @Test
    void shouldDropImagesWhenPromptExceedsTokenBudget() {
        insertTicket(TICKET_WITH_IMAGE_ID, 2);
        stubAiClient.callCount.set(0);

        TicketAiAnalysisResponse response = ticketAiAnalysisService.analyzeTicketAutomatically(
                TICKET_WITH_IMAGE_ID, 10001L, "test-token-drop-images");

        assertThat(response.status()).isEqualTo("SUCCESS");
        assertThat(stubAiClient.callCount.get()).isEqualTo(1);
        assertThat(stubAiClient.lastImageCount.get()).as("超长提示词应舍弃图片输入").isZero();
        AiTaskRecord task = latestTask(TICKET_WITH_IMAGE_ID);
        assertThat(task.getDegradeLevel()).isEqualTo("DROP_IMAGES");
        assertThat(task.getDegradeReason()).contains("舍弃");
        assertThat(task.getTotalTokens()).isEqualTo(200);
    }

    @Test
    void shouldExposeTokenUsageForAdmin() throws Exception {
        insertTicket(TICKET_ID, 0);
        ticketAiAnalysisService.analyzeTicketAutomatically(TICKET_ID, 10001L, "test-token-endpoint");

        String body = mockMvc.perform(get("/api/v1/admin/ai/token-usage")
                        .header("Authorization", bearer(token(10003L, "admin01", UserRole.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn()
                .getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        AiTokenUsageResponse usage = objectMapper.treeToValue(objectMapper.readTree(body).path("data"), AiTokenUsageResponse.class);
        assertThat(usage.monitorEnabled()).isTrue();
        assertThat(usage.dailyBudget()).isEqualTo(100_000_000L);
        assertThat(usage.usedTokens()).isGreaterThanOrEqualTo(200L);

        mockMvc.perform(get("/api/v1/admin/ai/token-usage")
                        .header("Authorization", bearer(token(10001L, "student01", UserRole.STUDENT))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));
    }

    private AiTaskRecord latestTask(Long ticketId) {
        return aiTaskRecordMapper.selectOne(new LambdaQueryWrapper<AiTaskRecord>()
                .eq(AiTaskRecord::getBizId, ticketId)
                .orderByDesc(AiTaskRecord::getCreatedAt)
                .last("limit 1"));
    }

    private void insertTicket(Long ticketId, int imageCount) {
        RepairTicket ticket = new RepairTicket();
        ticket.setId(ticketId);
        ticket.setStudentId(10001L);
        ticket.setLocationId(30002L);
        ticket.setCategoryId(20001L);
        ticket.setDescription("一号教学楼101水管漏水，请尽快维修。");
        ticket.setContactPhone("13800000001");
        ticket.setSummary("一号教学楼水管漏水");
        ticket.setPriority("MEDIUM");
        ticket.setStatus("PENDING_REVIEW");
        ticket.setReportImageUrls("[]");
        ticket.setDeleted(0);
        ticketMapper.insert(ticket);
        for (int index = 0; index < imageCount; index++) {
            FileMetadata file = new FileMetadata();
            file.setId(ticketId * 10 + index);
            file.setOriginalName("report-" + index + ".jpg");
            file.setObjectKey("ticket/" + ticketId + "/report-" + index + ".jpg");
            file.setBucketName("campus-repair");
            file.setContentType("image/jpeg");
            file.setSizeBytes(1024L);
            file.setUploaderId(10001L);
            file.setUploaderRole("STUDENT");
            file.setBizType("TICKET_REPORT_IMAGE");
            file.setBizId(ticketId);
            file.setPublicUrl("https://example.com/ticket/" + ticketId + "/report-" + index + ".jpg");
            file.setDeleted(0);
            fileMetadataMapper.insert(file);
        }
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
