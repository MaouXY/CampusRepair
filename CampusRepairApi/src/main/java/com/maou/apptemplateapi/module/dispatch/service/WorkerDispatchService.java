package com.maou.apptemplateapi.module.dispatch.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.maou.apptemplateapi.common.enums.UserRole;
import com.maou.apptemplateapi.common.exception.BusinessException;
import com.maou.apptemplateapi.common.exception.ErrorCode;
import com.maou.apptemplateapi.common.security.CurrentUser;
import com.maou.apptemplateapi.common.security.CurrentUserProvider;
import com.maou.apptemplateapi.module.ai.entity.RepairAiAnalysis;
import com.maou.apptemplateapi.module.ai.mapper.RepairAiAnalysisMapper;
import com.maou.apptemplateapi.module.dispatch.dto.DispatchCandidateResponse;
import com.maou.apptemplateapi.module.dispatch.dto.DispatchSuggestionResponse;
import com.maou.apptemplateapi.module.ticket.entity.RepairTicket;
import com.maou.apptemplateapi.module.ticket.mapper.RepairTicketMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class WorkerDispatchService {

    public static final String SOURCE_AI = "AI";
    public static final String SOURCE_AI_FALLBACK = "AI_FALLBACK";
    public static final String SOURCE_RULE = "RULE";

    private static final String ANALYSIS_STATUS_SUCCESS = "SUCCESS";

    private final RepairTicketMapper ticketMapper;
    private final RepairAiAnalysisMapper analysisMapper;
    private final WorkerDispatchScoringService scoringService;

    @Transactional(readOnly = true)
    public DispatchSuggestionResponse suggest(Long ticketId) {
        CurrentUser currentUser = requireAdmin("admin-dispatch-suggestion", ticketId);
        RepairTicket ticket = requireTicket(ticketId);
        WorkerDispatchScoringService.DispatchPlan plan = scoringService.plan(ticket);
        RepairAiAnalysis analysis = latestAnalysis(ticketId);

        List<DispatchCandidateResponse> candidates = analysis == null
                ? plan.candidates()
                : resolveCandidates(analysis, plan, ticketId);
        if (candidates.isEmpty()) {
            log.warn("dispatch suggestion has no candidate, scenario=admin-dispatch-suggestion, ticketId={}, adminId={}, expectedDepartment={}, requiredSkills={}",
                    ticketId, currentUser.getId(), plan.expectedDepartment(), plan.requiredSkills());
            return new DispatchSuggestionResponse(ticketId, plan.categoryName(), plan.requiredSkills(),
                    plan.expectedDepartment(), SOURCE_RULE, null, null, null, null,
                    "暂无参与智能派单的可用维修员，请管理员先维护维修员画像与技能标签，再人工派单。",
                    null, null, LocalDateTime.now(), List.of());
        }

        Long recommendedWorkerId = resolveRecommendedWorkerId(analysis, candidates, ticketId);
        List<DispatchCandidateResponse> markedCandidates = scoringService.markRecommended(candidates, recommendedWorkerId);
        DispatchCandidateResponse recommended = markedCandidates.stream()
                .filter(candidate -> Objects.equals(candidate.workerId(), recommendedWorkerId))
                .findFirst()
                .orElse(markedCandidates.get(0));
        String source = resolveSource(analysis);
        String reason = buildReason(analysis, recommended, source);

        log.info("dispatch suggestion generated, scenario=admin-dispatch-suggestion, ticketId={}, adminId={}, source={}, candidateCount={}, recommendedWorkerId={}, recommendedWorkerName={}, accuracySource={}",
                ticketId, currentUser.getId(), source, markedCandidates.size(), recommended.workerId(),
                recommended.workerName(), analysis == null ? "rule-only" : "latest-ai-analysis");
        return new DispatchSuggestionResponse(ticketId, plan.categoryName(), plan.requiredSkills(),
                plan.expectedDepartment(), source,
                analysis == null ? null : analysis.getId(),
                analysis == null ? null : analysis.getStatus(),
                recommended.workerId(), recommended.workerName(), reason,
                analysis == null ? null : analysis.getConfidence(),
                analysis == null ? null : analysis.getDispatchRemark(),
                LocalDateTime.now(), markedCandidates);
    }

    private List<DispatchCandidateResponse> resolveCandidates(RepairAiAnalysis analysis,
                                                              WorkerDispatchScoringService.DispatchPlan plan,
                                                              Long ticketId) {
        List<DispatchCandidateResponse> snapshotCandidates = scoringService.loadSnapshotCandidates(analysis.getId(), ticketId);
        if (snapshotCandidates.isEmpty()) {
            log.warn("dispatch snapshot candidates absent, scenario=admin-dispatch-suggestion, ticketId={}, aiAnalysisId={}, fallback=rule-scoring",
                    ticketId, analysis.getId());
            return plan.candidates();
        }
        return snapshotCandidates;
    }

    private Long resolveRecommendedWorkerId(RepairAiAnalysis analysis,
                                            List<DispatchCandidateResponse> candidates,
                                            Long ticketId) {
        Long ruleTopWorkerId = candidates.get(0).workerId();
        if (analysis == null || analysis.getSuggestedWorkerId() == null) {
            return ruleTopWorkerId;
        }
        boolean inCandidates = candidates.stream()
                .anyMatch(candidate -> Objects.equals(candidate.workerId(), analysis.getSuggestedWorkerId()));
        if (!inCandidates) {
            log.warn("dispatch ai suggestion outside candidates, scenario=admin-dispatch-suggestion, ticketId={}, aiAnalysisId={}, suggestedWorkerId={}, fallbackWorkerId={}",
                    ticketId, analysis.getId(), analysis.getSuggestedWorkerId(), ruleTopWorkerId);
            return ruleTopWorkerId;
        }
        return analysis.getSuggestedWorkerId();
    }

    private String resolveSource(RepairAiAnalysis analysis) {
        if (analysis == null) {
            return SOURCE_RULE;
        }
        return ANALYSIS_STATUS_SUCCESS.equals(analysis.getStatus()) ? SOURCE_AI : SOURCE_AI_FALLBACK;
    }

    private String buildReason(RepairAiAnalysis analysis,
                               DispatchCandidateResponse recommended,
                               String source) {
        String rulePart = "规则评分：总分%s，%s".formatted(recommended.totalScore(), recommended.ruleReason());
        if (SOURCE_RULE.equals(source)) {
            return "尚未生成 AI 预分析，已按维修员画像、技能匹配、当前负载与历史质量给出规则建议；" + rulePart;
        }
        String aiPart = StringUtils.hasText(analysis.getDispatchRemark())
                ? analysis.getDispatchRemark()
                : "AI 未返回派单说明";
        if (SOURCE_AI_FALLBACK.equals(source)) {
            return "AI 预分析降级（状态=%s），沿用规则兜底结果；%s；%s".formatted(analysis.getStatus(), aiPart, rulePart);
        }
        return "%s；%s".formatted(aiPart, rulePart);
    }

    private RepairAiAnalysis latestAnalysis(Long ticketId) {
        return analysisMapper.selectOne(new LambdaQueryWrapper<RepairAiAnalysis>()
                .eq(RepairAiAnalysis::getTicketId, ticketId)
                .eq(RepairAiAnalysis::getDeleted, 0)
                .orderByDesc(RepairAiAnalysis::getCreatedAt)
                .last("limit 1"));
    }

    private CurrentUser requireAdmin(String scenario, Long ticketId) {
        CurrentUser currentUser = CurrentUserProvider.require();
        if (currentUser.getRole() != UserRole.ADMIN) {
            log.warn("dispatch suggestion role denied, scenario={}, ticketId={}, userId={}, roleCode={}",
                    scenario, ticketId, currentUser.getId(), currentUser.getRoleCode());
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return currentUser;
    }

    private RepairTicket requireTicket(Long ticketId) {
        RepairTicket ticket = ticketMapper.selectById(ticketId);
        if (ticket == null || ticket.getDeleted() != 0) {
            log.warn("ticket not found, scenario=admin-dispatch-suggestion, ticketId={}", ticketId);
            throw new BusinessException(ErrorCode.TICKET_NOT_FOUND);
        }
        return ticket;
    }
}
