package com.maou.apptemplateapi.module.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.maou.apptemplateapi.module.ai.dto.AiCompletion;
import com.maou.apptemplateapi.module.ai.dto.AiTokenUsage;
import com.maou.apptemplateapi.module.ai.dto.TicketAiAnalysisResponse;
import com.maou.apptemplateapi.module.ai.entity.AiTaskRecord;
import com.maou.apptemplateapi.module.ai.mapper.AiTaskRecordMapper;
import com.maou.apptemplateapi.module.ticket.entity.RepairTicket;
import com.maou.apptemplateapi.module.ticket.mapper.RepairTicketMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * token 预算耗尽时的兜底验证：daily-budget 调成 1，当日已有用量即触发「不调用模型，规则兜底」。
 */
@ActiveProfiles("test")
@SpringBootTest(properties = {
        "app.ai.ark.token.daily-budget=1",
        "app.ai.ark.token.rule-only-ratio=1.0"
})
@Transactional
class AiTokenRuleOnlyIntegrationTest {

    private static final long TICKET_ID = 73001L;

    @TestConfiguration
    static class StubAiClientConfig {

        @Bean
        @Primary
        CountingAiClient countingAiClient() {
            return new CountingAiClient();
        }
    }

    static class CountingAiClient implements AiClient {

        private final AtomicInteger callCount = new AtomicInteger();

        @Override
        public AiCompletion complete(String systemPrompt, String userPrompt) {
            callCount.incrementAndGet();
            return AiCompletion.of("{}", "stub-model", AiTokenUsage.api(1, 1, 2));
        }

        @Override
        public AiCompletion completeWithImages(String systemPrompt, String userPrompt, List<AiImageInput> images) {
            callCount.incrementAndGet();
            return AiCompletion.of("{}", "stub-model", AiTokenUsage.api(1, 1, 2));
        }
    }

    @Autowired
    private TicketAiAnalysisService ticketAiAnalysisService;

    @Autowired
    private CountingAiClient countingAiClient;

    @Autowired
    private RepairTicketMapper ticketMapper;

    @Autowired
    private AiTaskRecordMapper aiTaskRecordMapper;

    @Test
    void shouldSkipModelAndFallbackToRuleResultWhenBudgetExhausted() {
        insertConsumedBudgetRecord();
        insertTicket(TICKET_ID);
        countingAiClient.callCount.set(0);

        TicketAiAnalysisResponse response = ticketAiAnalysisService.analyzeTicketAutomatically(TICKET_ID, 10001L, "test-token-rule-only");

        assertThat(countingAiClient.callCount.get()).as("预算耗尽时不应调用模型").isZero();
        assertThat(response.status()).isEqualTo("FALLBACK");
        assertThat(response.rawResponse()).contains("token 预算不足");
        assertThat(response.dispatchCandidates()).isNotEmpty();
        assertThat(response.suggestedWorkerId()).isEqualTo(10005L);

        AiTaskRecord task = aiTaskRecordMapper.selectOne(new LambdaQueryWrapper<AiTaskRecord>()
                .eq(AiTaskRecord::getBizId, TICKET_ID)
                .orderByDesc(AiTaskRecord::getCreatedAt)
                .last("limit 1"));
        assertThat(task.getStatus()).isEqualTo(AiTaskService.STATUS_DEGRADED);
        assertThat(task.getDegradeLevel()).isEqualTo("RULE_ONLY");
        assertThat(task.getDegradeReason()).contains("跳过模型调用");
        assertThat(task.getTotalTokens()).as("未调用模型不应计入 token 消耗").isNull();
        assertThat(task.getPromptTokens()).isNotNull();
    }

    private void insertConsumedBudgetRecord() {
        AiTaskRecord record = new AiTaskRecord();
        record.setId(88001L);
        record.setTaskType("TICKET_PRE_ANALYSIS");
        record.setBizType("REPAIR_TICKET");
        record.setBizId(1L);
        record.setModelName("budget-seed");
        record.setStatus(AiTaskService.STATUS_SUCCESS);
        record.setPromptTokens(500);
        record.setInputTokens(600);
        record.setOutputTokens(400);
        record.setTotalTokens(1000);
        record.setTokenSource(AiTokenUsage.SOURCE_API);
        record.setDegradeLevel("NORMAL");
        record.setCreatedAt(LocalDateTime.now());
        record.setUpdatedAt(LocalDateTime.now());
        record.setDeleted(0);
        aiTaskRecordMapper.insert(record);
    }

    private void insertTicket(Long ticketId) {
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
    }
}
