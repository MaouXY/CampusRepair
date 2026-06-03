package com.maou.apptemplateapi.module.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maou.apptemplateapi.common.enums.UserRole;
import com.maou.apptemplateapi.common.exception.BusinessException;
import com.maou.apptemplateapi.common.exception.ErrorCode;
import com.maou.apptemplateapi.common.security.CurrentUser;
import com.maou.apptemplateapi.common.security.CurrentUserProvider;
import com.maou.apptemplateapi.module.ai.dto.TicketAiAnalysisResponse;
import com.maou.apptemplateapi.module.ai.entity.AiTaskRecord;
import com.maou.apptemplateapi.module.ai.entity.RepairAiAnalysis;
import com.maou.apptemplateapi.module.ai.mapper.RepairAiAnalysisMapper;
import com.maou.apptemplateapi.module.rag.dto.RagChunkResponse;
import com.maou.apptemplateapi.module.rag.service.RagKnowledgeService;
import com.maou.apptemplateapi.module.ticket.entity.RepairTicket;
import com.maou.apptemplateapi.module.ticket.mapper.RepairTicketMapper;
import com.maou.apptemplateapi.module.user.entity.UserAccount;
import com.maou.apptemplateapi.module.user.mapper.UserAccountMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class TicketAiAnalysisService {

    private static final String TASK_TYPE = "TICKET_PRE_ANALYSIS";
    private static final String BIZ_TYPE = "REPAIR_TICKET";

    private final RepairTicketMapper ticketMapper;
    private final RepairAiAnalysisMapper analysisMapper;
    private final AiTaskService aiTaskService;
    private final AiClient aiClient;
    private final RagKnowledgeService ragKnowledgeService;
    private final TicketAiImageService ticketAiImageService;
    private final UserAccountMapper userAccountMapper;
    private final ObjectMapper objectMapper;

    @Transactional
    public TicketAiAnalysisResponse analyzeTicket(Long ticketId) {
        CurrentUser currentUser = requireAdmin("admin-ticket-ai-analyze", ticketId);
        return analyzeTicketInternal(ticketId, currentUser.getId(), "admin-ticket-ai-analyze");
    }

    @Transactional
    public TicketAiAnalysisResponse analyzeTicketAutomatically(Long ticketId, Long studentId, String triggerScenario) {
        String scenario = StringUtils.hasText(triggerScenario) ? triggerScenario : "ticket-ai-auto-pre-analysis";
        log.info("ticket ai auto analysis started, scenario={}, ticketId={}, studentId={}", scenario, ticketId, studentId);
        return analyzeTicketInternal(ticketId, null, scenario);
    }

    private TicketAiAnalysisResponse analyzeTicketInternal(Long ticketId, Long adminId, String scenario) {
        RepairTicket ticket = requireTicket(ticketId);
        List<RagChunkResponse> chunks = ragKnowledgeService.search(ticket.getCategoryId(), ticket.getDescription(), 5);
        List<AiImageInput> images = ticketAiImageService.loadTicketReportImages(ticketId, scenario);
        List<UserAccount> workers = loadEnabledWorkers();
        String requestSnapshot = buildUserPrompt(ticket, chunks, workers);
        AiTaskRecord task = aiTaskService.createPending(null, TASK_TYPE, BIZ_TYPE, ticketId, requestSnapshot);
        Instant started = Instant.now();
        try {
            String response = aiClient.generateWithImages(systemPrompt(), requestSnapshot, images);
            long durationMs = Duration.between(started, Instant.now()).toMillis();
            aiTaskService.markSuccess(task.getId(), response, durationMs);
            RepairAiAnalysis analysis = saveAnalysis(ticketId, task.getId(), "SUCCESS", response, parseResponse(response, ticket, workers));
            log.info("ticket ai analysis succeeded, scenario={}, ticketId={}, aiTaskId={}, studentId={}, adminId={}, imageCount={}, ragChunkCount={}",
                    scenario, ticketId, task.getId(), ticket.getStudentId(), adminId, images.size(), chunks.size());
            return toResponse(analysis);
        } catch (JsonProcessingException exception) {
            long durationMs = Duration.between(started, Instant.now()).toMillis();
            aiTaskService.markFailed(task.getId(), exception.getMessage(), durationMs);
            log.error("ticket ai output invalid, scenario={}, ticketId={}, aiTaskId={}, adminId={}, studentId={}",
                    scenario, ticketId, task.getId(), adminId, ticket.getStudentId(), exception);
            RepairAiAnalysis analysis = saveFallback(ticket, task.getId(), "FALLBACK", "AI输出解析失败，已使用规则兜底。");
            return toResponse(analysis);
        } catch (RuntimeException exception) {
            long durationMs = Duration.between(started, Instant.now()).toMillis();
            aiTaskService.markFailed(task.getId(), exception.getMessage(), durationMs);
            log.error("ticket ai analyze failed, scenario={}, ticketId={}, aiTaskId={}, adminId={}, studentId={}",
                    scenario, ticketId, task.getId(), adminId, ticket.getStudentId(), exception);
            RepairAiAnalysis analysis = saveFallback(ticket, task.getId(), "FAILED", exception.getMessage());
            return toResponse(analysis);
        }
    }

    public TicketAiAnalysisResponse latest(Long ticketId) {
        CurrentUser currentUser = requireAdmin("admin-ticket-ai-latest", ticketId);
        RepairTicket ticket = requireTicket(ticketId);
        RepairAiAnalysis analysis = analysisMapper.selectOne(new LambdaQueryWrapper<RepairAiAnalysis>()
                .eq(RepairAiAnalysis::getTicketId, ticket.getId())
                .eq(RepairAiAnalysis::getDeleted, 0)
                .orderByDesc(RepairAiAnalysis::getCreatedAt)
                .last("limit 1"));
        if (analysis == null) {
            log.warn("ticket ai analysis not found, scenario=admin-ticket-ai-latest, ticketId={}, adminId={}",
                    ticketId, currentUser.getId());
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        return toResponse(analysis);
    }

    private CurrentUser requireAdmin(String scenario, Long ticketId) {
        CurrentUser currentUser = CurrentUserProvider.require();
        if (currentUser.getRole() != UserRole.ADMIN) {
            log.warn("ticket ai role denied, scenario={}, ticketId={}, userId={}, roleCode={}",
                    scenario, ticketId, currentUser.getId(), currentUser.getRoleCode());
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return currentUser;
    }

    private RepairTicket requireTicket(Long ticketId) {
        RepairTicket ticket = ticketMapper.selectById(ticketId);
        if (ticket == null || ticket.getDeleted() != 0) {
            log.warn("ticket not found, scenario=admin-ticket-ai-load, ticketId={}", ticketId);
            throw new BusinessException(ErrorCode.TICKET_NOT_FOUND);
        }
        return ticket;
    }

    private String systemPrompt() {
        return """
                你是校园维修工单预分析助手。只返回 JSON，不要 Markdown。
                JSON schema:
                {
                  "suggestedCategoryId": number|null,
                  "suggestedPriority": "LOW"|"MEDIUM"|"HIGH",
                  "suggestedWorkerId": number|null,
                  "faultSummary": string,
                  "faultReason": string,
                  "solution": string,
                  "dispatchRemark": string,
                  "riskLevel": "LOW"|"MEDIUM"|"HIGH",
                  "confidence": number
                }
                """;
    }

    private String buildUserPrompt(RepairTicket ticket, List<RagChunkResponse> chunks, List<UserAccount> workers) {
        String rag = chunks.isEmpty()
                ? "无命中知识片段"
                : chunks.stream()
                .map(chunk -> "- " + chunk.title() + "：" + chunk.content())
                .reduce("", (left, right) -> left + System.lineSeparator() + right);
        String workerOptions = workers.isEmpty()
                ? "暂无可用维修员"
                : workers.stream()
                .map(worker -> "- workerId=%d，姓名=%s，账号=%s，电话=%s"
                        .formatted(worker.getId(), worker.getRealName(), worker.getUsername(), worker.getPhone()))
                .reduce("", (left, right) -> left + System.lineSeparator() + right);
        return """
                工单ID：%d
                分类ID：%d
                地点ID：%d
                描述：%s
                报修图片URL占位：%s
                可派单维修员：
                %s
                知识库片段：
                %s
                """.formatted(ticket.getId(), ticket.getCategoryId(), ticket.getLocationId(), ticket.getDescription(),
                ticket.getReportImageUrls(), workerOptions, rag);
    }

    private AnalysisPayload parseResponse(String response, RepairTicket ticket, List<UserAccount> workers) throws JsonProcessingException {
        JsonNode node = objectMapper.readTree(stripJsonFence(response));
        return new AnalysisPayload(
                node.path("suggestedCategoryId").isNumber() ? node.path("suggestedCategoryId").asLong() : ticket.getCategoryId(),
                normalizePriority(node.path("suggestedPriority").asText("LOW")),
                normalizeWorkerId(node.path("suggestedWorkerId").isNumber() ? node.path("suggestedWorkerId").asLong() : null, workers, ticket.getId()),
                node.path("faultSummary").asText(ticket.getSummary()),
                node.path("faultReason").asText("需现场核查"),
                node.path("solution").asText("请维修员现场检查后处理"),
                node.path("dispatchRemark").asText("请按建议方案现场核查并及时反馈处理结果"),
                normalizeRisk(node.path("riskLevel").asText("LOW")),
                BigDecimal.valueOf(Math.max(0, Math.min(1, node.path("confidence").asDouble(0.5))))
        );
    }

    private String stripJsonFence(String response) {
        String text = response == null ? "" : response.trim();
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            int lastFence = text.lastIndexOf("```");
            if (firstNewline >= 0 && lastFence > firstNewline) {
                return text.substring(firstNewline + 1, lastFence).trim();
            }
        }
        return text;
    }

    private String normalizePriority(String value) {
        return List.of("LOW", "MEDIUM", "HIGH").contains(value) ? value : "LOW";
    }

    private String normalizeRisk(String value) {
        return List.of("LOW", "MEDIUM", "HIGH").contains(value) ? value : "LOW";
    }

    private Long normalizeWorkerId(Long workerId, List<UserAccount> workers, Long ticketId) {
        if (workerId == null) {
            return null;
        }
        boolean exists = workers.stream().anyMatch(worker -> worker.getId().equals(workerId));
        if (!exists) {
            log.warn("ticket ai suggested worker invalid, scenario=admin-ticket-ai-analyze, ticketId={}, suggestedWorkerId={}",
                    ticketId, workerId);
            return null;
        }
        return workerId;
    }

    private List<UserAccount> loadEnabledWorkers() {
        return userAccountMapper.selectList(new LambdaQueryWrapper<UserAccount>()
                .eq(UserAccount::getRoleCode, UserRole.WORKER.name())
                .eq(UserAccount::getEnabled, 1)
                .eq(UserAccount::getDeleted, 0)
                .orderByAsc(UserAccount::getId));
    }

    private RepairAiAnalysis saveFallback(RepairTicket ticket, Long taskId, String status, String rawResponse) {
        return saveAnalysis(ticket.getId(), taskId, status, rawResponse, new AnalysisPayload(
                ticket.getCategoryId(),
                ticket.getPriority(),
                ticket.getAssignedWorkerId(),
                ticket.getSummary(),
                "AI分析暂不可用，需人工审核。",
                "请管理员结合描述、图片和知识库手动派单。",
                "AI暂不可用，请人工选择维修员并填写派单备注。",
                "LOW",
                BigDecimal.valueOf(0.2)
        ));
    }

    private RepairAiAnalysis saveAnalysis(Long ticketId, Long taskId, String status, String rawResponse, AnalysisPayload payload) {
        RepairAiAnalysis analysis = new RepairAiAnalysis();
        analysis.setTicketId(ticketId);
        analysis.setAiTaskId(taskId);
        analysis.setStatus(status);
        analysis.setSuggestedCategoryId(payload.suggestedCategoryId());
        analysis.setSuggestedPriority(payload.suggestedPriority());
        analysis.setSuggestedWorkerId(payload.suggestedWorkerId());
        analysis.setFaultSummary(payload.faultSummary());
        analysis.setFaultReason(payload.faultReason());
        analysis.setSolution(payload.solution());
        analysis.setDispatchRemark(payload.dispatchRemark());
        analysis.setRiskLevel(payload.riskLevel());
        analysis.setConfidence(payload.confidence());
        analysis.setRawResponse(rawResponse);
        analysisMapper.insert(analysis);
        return analysis;
    }

    private TicketAiAnalysisResponse toResponse(RepairAiAnalysis analysis) {
        return new TicketAiAnalysisResponse(analysis.getId(), analysis.getTicketId(), analysis.getAiTaskId(),
                analysis.getStatus(), analysis.getSuggestedCategoryId(), analysis.getSuggestedPriority(),
                analysis.getSuggestedWorkerId(), analysis.getFaultSummary(), analysis.getFaultReason(),
                analysis.getSolution(), analysis.getDispatchRemark(), analysis.getRiskLevel(),
                analysis.getConfidence(), analysis.getRawResponse(), analysis.getCreatedAt());
    }

    private record AnalysisPayload(
            Long suggestedCategoryId,
            String suggestedPriority,
            Long suggestedWorkerId,
            String faultSummary,
            String faultReason,
            String solution,
            String dispatchRemark,
            String riskLevel,
            BigDecimal confidence
    ) {
    }
}
