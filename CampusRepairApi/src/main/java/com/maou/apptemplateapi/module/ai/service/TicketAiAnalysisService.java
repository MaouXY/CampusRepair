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
import com.maou.apptemplateapi.module.ai.dto.AiCompletion;
import com.maou.apptemplateapi.module.ai.dto.AiTokenUsage;
import com.maou.apptemplateapi.module.ai.dto.DegradeDecision;
import com.maou.apptemplateapi.module.ai.dto.TicketAiAnalysisResponse;
import com.maou.apptemplateapi.module.ai.entity.AiTaskRecord;
import com.maou.apptemplateapi.module.ai.entity.RepairAiAnalysis;
import com.maou.apptemplateapi.module.ai.enums.DegradeLevel;
import com.maou.apptemplateapi.module.ai.mapper.RepairAiAnalysisMapper;
import com.maou.apptemplateapi.module.dispatch.dto.DispatchCandidateResponse;
import com.maou.apptemplateapi.module.dispatch.service.WorkerDispatchScoringService;
import com.maou.apptemplateapi.module.rag.dto.RagChunkResponse;
import com.maou.apptemplateapi.module.rag.service.RagKnowledgeService;
import com.maou.apptemplateapi.module.ticket.entity.RepairTicket;
import com.maou.apptemplateapi.module.ticket.mapper.RepairTicketMapper;
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
    private static final int RAG_TOP_K = 5;

    private final RepairTicketMapper ticketMapper;
    private final RepairAiAnalysisMapper analysisMapper;
    private final AiTaskService aiTaskService;
    private final AiClient aiClient;
    private final RagKnowledgeService ragKnowledgeService;
    private final TicketAiImageService ticketAiImageService;
    private final WorkerDispatchScoringService workerDispatchScoringService;
    private final AiTokenEstimator aiTokenEstimator;
    private final AiTokenMonitor aiTokenMonitor;
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
        List<RagChunkResponse> chunks = ragKnowledgeService.search(ticket.getCategoryId(), ticket.getDescription(), RAG_TOP_K);
        List<AiImageInput> images = ticketAiImageService.loadTicketReportImages(ticketId, scenario);
        List<DispatchCandidateResponse> candidates = workerDispatchScoringService.topCandidates(ticket);

        long plannedPromptTokens = aiTokenEstimator.estimate(buildUserPrompt(ticket, chunks, candidates))
                + aiTokenEstimator.estimateImages(images.size());
        DegradeDecision decision = aiTokenMonitor.decideForRequest(plannedPromptTokens, images.size(), chunks.size());
        List<RagChunkResponse> effectiveChunks = effectiveChunks(chunks, decision);
        List<AiImageInput> effectiveImages = decision.level() == DegradeLevel.DROP_IMAGES ? List.of() : images;
        String requestSnapshot = buildUserPrompt(ticket, effectiveChunks, candidates);
        int promptTokens = (int) (aiTokenEstimator.estimate(requestSnapshot) + aiTokenEstimator.estimateImages(effectiveImages.size()));
        AiTaskRecord task = aiTaskService.createPending(null, TASK_TYPE, BIZ_TYPE, ticketId, requestSnapshot);
        Instant started = Instant.now();

        if (decision.level().isRuleOnly()) {
            aiTaskService.markDegraded(task.getId(), decision.reasonText(), 0, promptTokens, decision.level(), decision.reasonText());
            log.warn("ticket ai skipped by token budget, scenario={}, ticketId={}, aiTaskId={}, usedTokensToday={}, dailyBudget={}, degradeLevel={}, reason={}",
                    scenario, ticketId, task.getId(), decision.usedTokensToday(), decision.dailyBudget(),
                    decision.level(), decision.reasonText());
            RepairAiAnalysis analysis = saveFallback(ticket, task.getId(), "FALLBACK",
                    "token 预算不足，已降级为规则兜底：" + decision.reasonText(), candidates);
            workerDispatchScoringService.saveSnapshots(ticketId, analysis.getId(), candidates, analysis.getSuggestedWorkerId());
            return toResponse(analysis);
        }
        if (decision.degraded()) {
            log.warn("ticket ai degraded before call, scenario={}, ticketId={}, aiTaskId={}, degradeLevel={}, reason={}, promptTokens={}, imageCount={}, ragChunkCount={}",
                    scenario, ticketId, task.getId(), decision.level(), decision.reasonText(),
                    promptTokens, effectiveImages.size(), effectiveChunks.size());
        }
        try {
            AiCompletion completion = aiClient.completeWithImages(systemPrompt(), requestSnapshot, effectiveImages);
            String response = completion.text();
            long durationMs = Duration.between(started, Instant.now()).toMillis();
            AiTokenUsage usage = completion.usage() == null ? AiTokenUsage.estimated(promptTokens, 0) : completion.usage();
            aiTaskService.markSuccess(task.getId(), response, durationMs, usage, promptTokens, decision.level(), decision.reasonText());
            aiTokenMonitor.recordUsage(usage, ticketId, scenario, decision.level());
            RepairAiAnalysis analysis = saveAnalysis(ticketId, task.getId(), "SUCCESS", response, parseResponse(response, ticket, candidates));
            workerDispatchScoringService.saveSnapshots(ticketId, analysis.getId(), candidates, analysis.getSuggestedWorkerId());
            log.info("ticket ai analysis succeeded, scenario={}, ticketId={}, aiTaskId={}, studentId={}, adminId={}, imageCount={}, ragChunkCount={}, degradeLevel={}, inputTokens={}, outputTokens={}, totalTokens={}, tokenSource={}",
                    scenario, ticketId, task.getId(), ticket.getStudentId(), adminId, effectiveImages.size(), effectiveChunks.size(),
                    decision.level(), usage.inputTokens(), usage.outputTokens(), usage.total(), usage.source());
            return toResponse(analysis);
        } catch (JsonProcessingException exception) {
            long durationMs = Duration.between(started, Instant.now()).toMillis();
            aiTaskService.markFailed(task.getId(), exception.getMessage(), durationMs, promptTokens, decision.level(), decision.reasonText());
            log.error("ticket ai output invalid, scenario={}, ticketId={}, aiTaskId={}, adminId={}, studentId={}",
                    scenario, ticketId, task.getId(), adminId, ticket.getStudentId(), exception);
            RepairAiAnalysis analysis = saveFallback(ticket, task.getId(), "FALLBACK", "AI输出解析失败，已使用规则评分兜底。", candidates);
            workerDispatchScoringService.saveSnapshots(ticketId, analysis.getId(), candidates, analysis.getSuggestedWorkerId());
            return toResponse(analysis);
        } catch (RuntimeException exception) {
            long durationMs = Duration.between(started, Instant.now()).toMillis();
            aiTaskService.markFailed(task.getId(), exception.getMessage(), durationMs, promptTokens, decision.level(), decision.reasonText());
            log.error("ticket ai analyze failed, scenario={}, ticketId={}, aiTaskId={}, adminId={}, studentId={}",
                    scenario, ticketId, task.getId(), adminId, ticket.getStudentId(), exception);
            RepairAiAnalysis analysis = saveFallback(ticket, task.getId(), "FAILED", exception.getMessage(), candidates);
            workerDispatchScoringService.saveSnapshots(ticketId, analysis.getId(), candidates, analysis.getSuggestedWorkerId());
            return toResponse(analysis);
        }
    }

    private List<RagChunkResponse> effectiveChunks(List<RagChunkResponse> chunks, DegradeDecision decision) {
        if (decision.level().ordinal() < DegradeLevel.MINIMAL_CONTEXT.ordinal()) {
            return chunks;
        }
        return chunks.stream().limit(aiTokenMonitor.minimalContextChunks()).toList();
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
                你是校园维修工单预分析和派单建议助手。只返回 JSON，不要 Markdown。
                派单必须遵循：建议维修员只能从候选维修员 Top3 中选择；优先选择规则总分更高的候选，
                除非候选的技能标签、部门或当前负载有明确理由支持其他选择；若难以判断，
                suggestedWorkerId 返回候选第一名或 null。
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

    private String buildUserPrompt(RepairTicket ticket, List<RagChunkResponse> chunks, List<DispatchCandidateResponse> candidates) {
        String rag = chunks.isEmpty()
                ? "无命中知识片段"
                : chunks.stream()
                .map(chunk -> "- " + chunk.title() + "：" + chunk.content())
                .reduce("", (left, right) -> left + System.lineSeparator() + right);
        String workerOptions = candidates.isEmpty()
                ? "暂无候选维修员"
                : candidates.stream()
                .map(candidate -> "- workerId=%d，姓名=%s，部门=%s，技能=%s，活跃工单=%d/%d，规则总分=%s，规则原因=%s"
                        .formatted(candidate.workerId(), candidate.workerName(), candidate.departmentName(), candidate.skillTags(),
                                candidate.activeOrderCount(), candidate.maxActiveOrders(), candidate.totalScore(), candidate.ruleReason()))
                .reduce("", (left, right) -> left + System.lineSeparator() + right);
        return """
                工单ID：%d
                分类ID：%d
                地点ID：%d
                描述：%s
                报修图片URL占位：%s
                候选维修员Top3：
                %s
                知识库片段：
                %s
                """.formatted(ticket.getId(), ticket.getCategoryId(), ticket.getLocationId(), ticket.getDescription(),
                ticket.getReportImageUrls(), workerOptions, rag);
    }

    private AnalysisPayload parseResponse(String response, RepairTicket ticket, List<DispatchCandidateResponse> candidates) throws JsonProcessingException {
        JsonNode node = objectMapper.readTree(stripJsonFence(response));
        return new AnalysisPayload(
                node.path("suggestedCategoryId").isNumber() ? node.path("suggestedCategoryId").asLong() : ticket.getCategoryId(),
                normalizePriority(node.path("suggestedPriority").asText("LOW")),
                normalizeWorkerId(node.path("suggestedWorkerId").isNumber() ? node.path("suggestedWorkerId").asLong() : null, candidates, ticket.getId()),
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

    private Long normalizeWorkerId(Long workerId, List<DispatchCandidateResponse> candidates, Long ticketId) {
        if (candidates.isEmpty()) {
            return null;
        }
        if (workerId != null && candidates.stream().anyMatch(candidate -> candidate.workerId().equals(workerId))) {
            return workerId;
        }
        Long fallbackWorkerId = candidates.get(0).workerId();
        if (workerId != null) {
            log.warn("ticket ai suggested worker outside candidates, scenario=admin-ticket-ai-analyze, ticketId={}, suggestedWorkerId={}, fallbackWorkerId={}",
                    ticketId, workerId, fallbackWorkerId);
        }
        return fallbackWorkerId;
    }

    private RepairAiAnalysis saveFallback(RepairTicket ticket,
                                          Long taskId,
                                          String status,
                                          String rawResponse,
                                          List<DispatchCandidateResponse> candidates) {
        DispatchCandidateResponse fallback = candidates.isEmpty() ? null : candidates.get(0);
        Long fallbackWorkerId = fallback == null ? ticket.getAssignedWorkerId() : fallback.workerId();
        String dispatchRemark = fallback == null
                ? "AI暂不可用，且暂无可用候选维修员，请管理员人工选择维修员并填写派单备注。"
                : "AI暂不可用，规则评分推荐%s，总分%s。%s"
                .formatted(fallback.workerName(), fallback.totalScore(), fallback.ruleReason());
        return saveAnalysis(ticket.getId(), taskId, status, rawResponse, new AnalysisPayload(
                ticket.getCategoryId(),
                ticket.getPriority(),
                fallbackWorkerId,
                ticket.getSummary(),
                "AI分析暂不可用，需人工审核。",
                fallback == null ? "请管理员结合描述、图片和知识库手动派单。" : "已按维修员画像、技能、负载和历史质量生成规则兜底建议。",
                dispatchRemark,
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
                analysis.getConfidence(), analysis.getRawResponse(), analysis.getCreatedAt(), loadCandidates(analysis));
    }

    private List<DispatchCandidateResponse> loadCandidates(RepairAiAnalysis analysis) {
        return workerDispatchScoringService.loadSnapshotCandidates(analysis.getId(), analysis.getTicketId());
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
