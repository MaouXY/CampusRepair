package com.maou.apptemplateapi.module.rag.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maou.apptemplateapi.common.enums.UserRole;
import com.maou.apptemplateapi.common.exception.BusinessException;
import com.maou.apptemplateapi.common.exception.ErrorCode;
import com.maou.apptemplateapi.common.result.PageResult;
import com.maou.apptemplateapi.common.security.CurrentUser;
import com.maou.apptemplateapi.common.security.CurrentUserProvider;
import com.maou.apptemplateapi.module.ai.dto.AiCompletion;
import com.maou.apptemplateapi.module.ai.dto.DegradeDecision;
import com.maou.apptemplateapi.module.ai.service.AiClient;
import com.maou.apptemplateapi.module.ai.service.AiTokenEstimator;
import com.maou.apptemplateapi.module.ai.service.AiTokenMonitor;
import com.maou.apptemplateapi.module.base.entity.RepairCategory;
import com.maou.apptemplateapi.module.base.entity.RepairLocation;
import com.maou.apptemplateapi.module.base.mapper.RepairCategoryMapper;
import com.maou.apptemplateapi.module.base.mapper.RepairLocationMapper;
import com.maou.apptemplateapi.module.rag.dto.KnowledgeDocumentResponse;
import com.maou.apptemplateapi.module.rag.dto.KnowledgeDraftResponse;
import com.maou.apptemplateapi.module.rag.entity.RagKnowledgeDraft;
import com.maou.apptemplateapi.module.rag.mapper.RagKnowledgeDraftMapper;
import com.maou.apptemplateapi.module.ticket.entity.RepairEvaluation;
import com.maou.apptemplateapi.module.ticket.entity.RepairTicket;
import com.maou.apptemplateapi.module.ticket.enums.TicketStatus;
import com.maou.apptemplateapi.module.ticket.mapper.RepairEvaluationMapper;
import com.maou.apptemplateapi.module.ticket.mapper.RepairTicketMapper;
import com.maou.apptemplateapi.module.user.entity.UserAccount;
import com.maou.apptemplateapi.module.user.mapper.UserAccountMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 典型工单知识沉淀：工单闭环后自动生成知识草稿，管理员审核后入库切片并同步向量库。
 *
 * <p>沉淀条件（满足其一）：学生评价 ≥ 4 分；或处理结果 + 备注/结果图片完整。
 * AI 不可用或 token 预算耗尽时退化为规则模板草稿，保证流程不中断。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagKnowledgeDraftService {

    private static final int MIN_PROCESS_RESULT_LENGTH = 8;
    private static final int AUTO_PASS_SCORE = 4;
    private static final int MAX_TITLE_LENGTH = 60;

    private final RagKnowledgeDraftMapper draftMapper;
    private final RepairTicketMapper ticketMapper;
    private final RepairEvaluationMapper evaluationMapper;
    private final RepairCategoryMapper categoryMapper;
    private final RepairLocationMapper locationMapper;
    private final UserAccountMapper userAccountMapper;
    private final RagKnowledgeService ragKnowledgeService;
    private final AiClient aiClient;
    private final AiTokenEstimator tokenEstimator;
    private final AiTokenMonitor tokenMonitor;
    private final ObjectMapper objectMapper;

    public PageResult<KnowledgeDraftResponse> listDrafts(String status, long page, long size) {
        requireAdmin("admin-rag-draft-list");
        Page<RagKnowledgeDraft> result = draftMapper.selectPage(
                new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 100)),
                new LambdaQueryWrapper<RagKnowledgeDraft>()
                        .eq(RagKnowledgeDraft::getDeleted, 0)
                        .eq(StringUtils.hasText(status), RagKnowledgeDraft::getStatus, status)
                        .orderByDesc(RagKnowledgeDraft::getId));
        return PageResult.of(result.getRecords().stream().map(this::toResponse).toList(),
                result.getCurrent(), result.getSize(), result.getTotal());
    }

    public KnowledgeDraftResponse detail(Long draftId) {
        requireAdmin("admin-rag-draft-detail");
        return toResponse(requireDraft(draftId, "admin-rag-draft-detail"));
    }

    @Transactional
    public KnowledgeDraftResponse generateFromTicket(Long ticketId) {
        CurrentUser admin = requireAdmin("admin-rag-draft-generate");
        RepairTicket ticket = requireCompletedTicket(ticketId, "admin-rag-draft-generate");
        return generate(ticket, admin.getId(), "admin-rag-draft-generate");
    }

    /**
     * 工单闭环后的自动沉淀入口（异步触发，不做角色校验，但仍要求工单已完成且满足沉淀条件）。
     */
    @Transactional
    public KnowledgeDraftResponse autoGenerateForCompletedTicket(Long ticketId, String scenario) {
        RepairTicket ticket = ticketMapper.selectById(ticketId);
        if (ticket == null || ticket.getDeleted() != 0) {
            log.warn("rag draft auto generate skipped, scenario={}, reason=ticket-not-found, ticketId={}", scenario, ticketId);
            return null;
        }
        if (!TicketStatus.COMPLETED.name().equals(ticket.getStatus())) {
            log.info("rag draft auto generate skipped, scenario={}, reason=ticket-not-completed, ticketId={}, status={}",
                    scenario, ticketId, ticket.getStatus());
            return null;
        }
        return generate(ticket, null, scenario);
    }

    @Transactional
    public KnowledgeDraftResponse approve(Long draftId, String remark) {
        CurrentUser admin = requireAdmin("admin-rag-draft-approve");
        RagKnowledgeDraft draft = requireDraft(draftId, "admin-rag-draft-approve");
        if (RagKnowledgeDraft.STATUS_APPROVED.equals(draft.getStatus()) && draft.getKnowledgeDocumentId() != null) {
            log.info("rag draft already approved, scenario=admin-rag-draft-approve, draftId={}, documentId={}",
                    draftId, draft.getKnowledgeDocumentId());
            return toResponse(draft);
        }
        if (RagKnowledgeDraft.STATUS_REJECTED.equals(draft.getStatus())) {
            log.warn("rag draft approve denied, scenario=admin-rag-draft-approve, draftId={}, reason=already-rejected", draftId);
            throw new BusinessException(ErrorCode.CONFLICT);
        }
        KnowledgeDocumentResponse document = ragKnowledgeService.createDocumentFromDraft(
                draft.getTitle(), draft.getCategoryId(), draft.getContent(), admin.getId(), "admin-rag-draft-approve");
        draft.setStatus(RagKnowledgeDraft.STATUS_APPROVED);
        draft.setKnowledgeDocumentId(document.id());
        draft.setReviewedBy(admin.getId());
        draft.setReviewedAt(LocalDateTime.now());
        draft.setReviewRemark(StringUtils.hasText(remark) ? remark : "审核通过并入库");
        draftMapper.updateById(draft);
        log.info("rag draft approved, scenario=admin-rag-draft-approve, draftId={}, documentId={}, chunkCount={}, adminId={}",
                draftId, document.id(), document.chunkCount(), admin.getId());
        return toResponse(draft);
    }

    @Transactional
    public KnowledgeDraftResponse reject(Long draftId, String remark) {
        CurrentUser admin = requireAdmin("admin-rag-draft-reject");
        RagKnowledgeDraft draft = requireDraft(draftId, "admin-rag-draft-reject");
        if (RagKnowledgeDraft.STATUS_APPROVED.equals(draft.getStatus())) {
            log.warn("rag draft reject denied, scenario=admin-rag-draft-reject, draftId={}, reason=already-approved", draftId);
            throw new BusinessException(ErrorCode.CONFLICT);
        }
        draft.setStatus(RagKnowledgeDraft.STATUS_REJECTED);
        draft.setReviewedBy(admin.getId());
        draft.setReviewedAt(LocalDateTime.now());
        draft.setReviewRemark(StringUtils.hasText(remark) ? remark : "知识内容不具复用价值，已驳回");
        draftMapper.updateById(draft);
        log.info("rag draft rejected, scenario=admin-rag-draft-reject, draftId={}, adminId={}, remark={}",
                draftId, admin.getId(), draft.getReviewRemark());
        return toResponse(draft);
    }

    private KnowledgeDraftResponse generate(RepairTicket ticket, Long adminId, String scenario) {
        RagKnowledgeDraft existing = findReusableDraft(ticket.getId());
        if (existing != null) {
            log.info("rag draft reuse existing, scenario={}, ticketId={}, draftId={}, status={}",
                    scenario, ticket.getId(), existing.getId(), existing.getStatus());
            return toResponse(existing);
        }
        RepairEvaluation evaluation = latestEvaluation(ticket.getId());
        if (!worthPrecipitating(ticket, evaluation)) {
            log.info("rag draft generate skipped, scenario={}, reason=not-worth-precipitating, ticketId={}, score={}, processResultLength={}",
                    scenario, ticket.getId(), evaluation == null ? null : evaluation.getScore(),
                    ticket.getProcessResult() == null ? 0 : ticket.getProcessResult().length());
            throw new BusinessException(ErrorCode.CONFLICT);
        }

        DraftContent content = buildDraftContent(ticket, evaluation, scenario);
        RagKnowledgeDraft draft = new RagKnowledgeDraft();
        draft.setSourceTicketId(ticket.getId());
        draft.setTitle(content.title());
        draft.setContent(content.content());
        draft.setCategoryId(ticket.getCategoryId());
        draft.setStatus(RagKnowledgeDraft.STATUS_PENDING_REVIEW);
        draft.setCreatedByAi(content.aiGenerated() ? 1 : 0);
        draft.setGenerateSource(content.source());
        draft.setDeleted(0);
        draftMapper.insert(draft);
        log.info("rag draft generated, scenario={}, ticketId={}, draftId={}, source={}, adminId={}, title={}",
                scenario, ticket.getId(), draft.getId(), content.source(), adminId, draft.getTitle());
        return toResponse(draft);
    }

    private DraftContent buildDraftContent(RepairTicket ticket, RepairEvaluation evaluation, String scenario) {
        String facts = buildFacts(ticket, evaluation);
        long promptTokens = tokenEstimator.estimate(systemPrompt()) + tokenEstimator.estimate(facts);
        DegradeDecision decision = tokenMonitor.decideForRequest(promptTokens, 0, 0);
        if (decision.level().isRuleOnly()) {
            log.warn("rag draft ai skipped by token budget, scenario={}, ticketId={}, reason={}",
                    scenario, ticket.getId(), decision.reasonText());
            return templateDraft(ticket, evaluation);
        }
        try {
            AiCompletion completion = aiClient.complete(systemPrompt(), facts);
            DraftContent parsed = parseAiDraft(completion.text(), ticket, evaluation, decision);
            tokenMonitor.recordUsage(completion.usage(), ticket.getId(), scenario, decision.level());
            log.info("rag draft ai generated, scenario={}, ticketId={}, degradeLevel={}, inputTokens={}, outputTokens={}",
                    scenario, ticket.getId(), decision.level(), completion.usage().inputTokens(), completion.usage().outputTokens());
            return parsed;
        } catch (RuntimeException exception) {
            log.error("rag draft ai generation failed, scenario={}, ticketId={}, fallback=rule-template",
                    scenario, ticket.getId(), exception);
            return templateDraft(ticket, evaluation);
        }
    }

    private DraftContent parseAiDraft(String response,
                                      RepairTicket ticket,
                                      RepairEvaluation evaluation,
                                      DegradeDecision decision) {
        if (!StringUtils.hasText(response)) {
            return templateDraft(ticket, evaluation);
        }
        try {
            JsonNode node = objectMapper.readTree(stripJsonFence(response));
            String title = node.path("title").asText(null);
            String content = node.path("content").asText(null);
            if (!StringUtils.hasText(title) || !StringUtils.hasText(content)) {
                log.warn("rag draft ai output incomplete, scenario=rag-draft-parse, ticketId={}, response={}", ticket.getId(), response);
                return templateDraft(ticket, evaluation);
            }
            String normalizedTitle = normalizeTitle(title, ticket);
            return new DraftContent(normalizedTitle, content.trim(),
                    decision.degraded() ? RagKnowledgeDraft.SOURCE_AI_DEGRADED : RagKnowledgeDraft.SOURCE_AI, true);
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException exception) {
            log.error("rag draft ai output invalid, scenario=rag-draft-parse, ticketId={}, response={}",
                    ticket.getId(), response, exception);
            return templateDraft(ticket, evaluation);
        }
    }

    /**
     * 规则模板兜底：把工单事实整理成结构化知识条目，保证 AI 不可用时知识沉淀仍然可用。
     */
    private DraftContent templateDraft(RepairTicket ticket, RepairEvaluation evaluation) {
        String categoryName = categoryName(ticket.getCategoryId());
        String locationName = locationName(ticket.getLocationId());
        String title = normalizeTitle("%s故障处理：%s".formatted(categoryName, safe(ticket.getSummary())), ticket);
        StringBuilder content = new StringBuilder();
        content.append("【适用场景】").append(categoryName).append(' ')
                .append(StringUtils.hasText(locationName) ? locationName : "校园区域")
                .append(' ').append(safe(ticket.getSummary())).append('\n');
        content.append("【故障现象】").append(safe(ticket.getDescription())).append('\n');
        content.append("【处理步骤】").append(StringUtils.hasText(ticket.getProcessResult())
                ? ticket.getProcessResult() : "现场检查后按标准流程处理，并反馈处理结果。").append('\n');
        if (StringUtils.hasText(ticket.getProcessRemark())) {
            content.append("【处理备注】").append(ticket.getProcessRemark()).append('\n');
        }
        content.append("【处理人】").append(workerName(ticket.getAssignedWorkerId()));
        Long durationMinutes = processMinutes(ticket);
        if (durationMinutes != null) {
            content.append("，处理时长约 ").append(durationMinutes).append(" 分钟");
        }
        content.append('\n');
        if (evaluation != null) {
            content.append("【用户反馈】").append(evaluation.getScore()).append(" 分");
            if (StringUtils.hasText(evaluation.getContent())) {
                content.append('：').append(evaluation.getContent());
            }
            content.append('\n');
        }
        content.append("【注意事项】处理涉及水电、用电安全的故障时，先断电/关阀并设置警示，确认无风险后再作业。");
        return new DraftContent(title, content.toString(), RagKnowledgeDraft.SOURCE_RULE, false);
    }

    private boolean worthPrecipitating(RepairTicket ticket, RepairEvaluation evaluation) {
        if (evaluation != null && evaluation.getScore() != null && evaluation.getScore() >= AUTO_PASS_SCORE) {
            return true;
        }
        boolean resultComplete = StringUtils.hasText(ticket.getProcessResult())
                && ticket.getProcessResult().trim().length() >= MIN_PROCESS_RESULT_LENGTH;
        boolean hasDetail = StringUtils.hasText(ticket.getProcessRemark())
                || StringUtils.hasText(ticket.getResultImageUrls()) && !"[]".equals(ticket.getResultImageUrls().trim());
        return resultComplete && hasDetail;
    }

    private RagKnowledgeDraft findReusableDraft(Long ticketId) {
        return draftMapper.selectOne(new LambdaQueryWrapper<RagKnowledgeDraft>()
                .eq(RagKnowledgeDraft::getSourceTicketId, ticketId)
                .ne(RagKnowledgeDraft::getStatus, RagKnowledgeDraft.STATUS_REJECTED)
                .eq(RagKnowledgeDraft::getDeleted, 0)
                .orderByDesc(RagKnowledgeDraft::getId)
                .last("limit 1"));
    }

    private RepairEvaluation latestEvaluation(Long ticketId) {
        return evaluationMapper.selectOne(new LambdaQueryWrapper<RepairEvaluation>()
                .eq(RepairEvaluation::getTicketId, ticketId)
                .orderByDesc(RepairEvaluation::getId)
                .last("limit 1"));
    }

    private String buildFacts(RepairTicket ticket, RepairEvaluation evaluation) {
        return """
                工单ID：%d
                分类：%s
                地点：%s
                故障描述：%s
                处理结果：%s
                处理备注：%s
                处理人：%s
                处理时长：%s
                学生评价：%s
                """.formatted(ticket.getId(), categoryName(ticket.getCategoryId()), locationName(ticket.getLocationId()),
                safe(ticket.getDescription()), safe(ticket.getProcessResult()), safe(ticket.getProcessRemark()),
                workerName(ticket.getAssignedWorkerId()),
                processMinutes(ticket) == null ? "未知" : processMinutes(ticket) + " 分钟",
                evaluation == null ? "暂无评价" : evaluation.getScore() + " 分 / " + safe(evaluation.getContent()));
    }

    private String systemPrompt() {
        return """
                你是校园后勤维修知识库编辑。请把一张已完成工单整理成可复用的维修知识条目，供后续维修员检索使用。
                要求：
                1. 只返回 JSON，不要 Markdown；
                2. title 不超过 40 个字，包含故障类型与关键现象；
                3. content 使用「【适用场景】【故障现象】【可能原因】【处理步骤】【注意事项】」结构，200-500 字；
                4. 只依据工单信息整理，不要编造不存在的设备型号或数据。
                JSON schema:
                {"title": string, "content": string}
                """;
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

    private String normalizeTitle(String rawTitle, RepairTicket ticket) {
        String title = rawTitle == null ? "" : rawTitle.trim().replace("\n", " ");
        if (!StringUtils.hasText(title)) {
            title = "%s故障处理".formatted(categoryName(ticket.getCategoryId()));
        }
        return title.length() > MAX_TITLE_LENGTH ? title.substring(0, MAX_TITLE_LENGTH) : title;
    }

    private Long processMinutes(RepairTicket ticket) {
        if (ticket.getAssignedAt() == null || ticket.getProcessedAt() == null) {
            return null;
        }
        return Math.max(Duration.between(ticket.getAssignedAt(), ticket.getProcessedAt()).toMinutes(), 0);
    }

    private String categoryName(Long categoryId) {
        if (categoryId == null) {
            return "综合维修";
        }
        RepairCategory category = categoryMapper.selectById(categoryId);
        return category == null ? "综合维修" : category.getName();
    }

    private String locationName(Long locationId) {
        if (locationId == null) {
            return null;
        }
        RepairLocation location = locationMapper.selectById(locationId);
        return location == null ? null : location.getName();
    }

    private String workerName(Long workerId) {
        if (workerId == null) {
            return "未指派";
        }
        UserAccount worker = userAccountMapper.selectById(workerId);
        return worker == null ? String.valueOf(workerId) : worker.getRealName();
    }

    private String safe(String value) {
        return StringUtils.hasText(value) ? value : "-";
    }

    private RagKnowledgeDraft requireDraft(Long draftId, String scenario) {
        RagKnowledgeDraft draft = draftMapper.selectById(draftId);
        if (draft == null || draft.getDeleted() != 0) {
            log.warn("rag draft not found, scenario={}, draftId={}", scenario, draftId);
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        return draft;
    }

    private RepairTicket requireCompletedTicket(Long ticketId, String scenario) {
        RepairTicket ticket = ticketMapper.selectById(ticketId);
        if (ticket == null || ticket.getDeleted() != 0) {
            log.warn("rag draft ticket not found, scenario={}, ticketId={}", scenario, ticketId);
            throw new BusinessException(ErrorCode.TICKET_NOT_FOUND);
        }
        return ticket;
    }

    private CurrentUser requireAdmin(String scenario) {
        CurrentUser currentUser = CurrentUserProvider.require();
        if (currentUser.getRole() != UserRole.ADMIN) {
            log.warn("rag draft role denied, scenario={}, userId={}, roleCode={}",
                    scenario, currentUser.getId(), currentUser.getRoleCode());
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return currentUser;
    }

    private KnowledgeDraftResponse toResponse(RagKnowledgeDraft draft) {
        return new KnowledgeDraftResponse(draft.getId(), draft.getSourceTicketId(), draft.getTitle(), draft.getContent(),
                draft.getCategoryId(), draft.getStatus(), draft.getCreatedByAi() != null && draft.getCreatedByAi() == 1,
                draft.getGenerateSource(), draft.getReviewRemark(), draft.getReviewedBy(), draft.getReviewedAt(),
                draft.getKnowledgeDocumentId(), draft.getCreatedAt(), draft.getUpdatedAt());
    }

    private record DraftContent(String title, String content, String source, boolean aiGenerated) {
    }
}
