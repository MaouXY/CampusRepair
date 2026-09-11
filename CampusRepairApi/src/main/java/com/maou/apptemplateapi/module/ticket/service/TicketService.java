package com.maou.apptemplateapi.module.ticket.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maou.apptemplateapi.common.enums.UserRole;
import com.maou.apptemplateapi.common.exception.BusinessException;
import com.maou.apptemplateapi.common.exception.ErrorCode;
import com.maou.apptemplateapi.common.result.PageResult;
import com.maou.apptemplateapi.common.security.CurrentUser;
import com.maou.apptemplateapi.common.security.CurrentUserProvider;
import com.maou.apptemplateapi.module.audit.service.OperationAuditService;
import com.maou.apptemplateapi.module.base.entity.RepairCategory;
import com.maou.apptemplateapi.module.base.entity.RepairLocation;
import com.maou.apptemplateapi.module.base.mapper.RepairCategoryMapper;
import com.maou.apptemplateapi.module.base.mapper.RepairLocationMapper;
import com.maou.apptemplateapi.module.ai.service.TicketAiPreAnalysisTrigger;
import com.maou.apptemplateapi.module.dispatch.entity.WorkerProfile;
import com.maou.apptemplateapi.module.dispatch.mapper.WorkerProfileMapper;
import com.maou.apptemplateapi.module.rag.service.RagKnowledgeDraftTrigger;
import com.maou.apptemplateapi.module.file.entity.FileMetadata;
import com.maou.apptemplateapi.module.file.mapper.FileMetadataMapper;
import com.maou.apptemplateapi.module.ticket.dto.TicketAssignRequest;
import com.maou.apptemplateapi.module.ticket.dto.TicketCreateRequest;
import com.maou.apptemplateapi.module.ticket.dto.TicketDetailResponse;
import com.maou.apptemplateapi.module.ticket.dto.TicketEvaluationRequest;
import com.maou.apptemplateapi.module.ticket.dto.TicketEvaluationResponse;
import com.maou.apptemplateapi.module.ticket.dto.TicketFlowResponse;
import com.maou.apptemplateapi.module.ticket.dto.AdminTodoOverviewResponse;
import com.maou.apptemplateapi.module.ticket.dto.TicketReworkRequest;
import com.maou.apptemplateapi.module.ticket.dto.TicketRejectRequest;
import com.maou.apptemplateapi.module.ticket.dto.TicketReturnRequest;
import com.maou.apptemplateapi.module.ticket.dto.TicketResultRequest;
import com.maou.apptemplateapi.module.ticket.dto.TicketSummaryResponse;
import com.maou.apptemplateapi.module.ticket.dto.TicketUrgeRequest;
import com.maou.apptemplateapi.module.ticket.dto.WorkerTodayOverviewResponse;
import com.maou.apptemplateapi.module.ticket.dto.WorkerOptionResponse;
import com.maou.apptemplateapi.module.ticket.entity.RepairAssignment;
import com.maou.apptemplateapi.module.ticket.entity.RepairEvaluation;
import com.maou.apptemplateapi.module.ticket.entity.RepairTicket;
import com.maou.apptemplateapi.module.ticket.entity.RepairTicketFlow;
import com.maou.apptemplateapi.module.ticket.enums.TicketAction;
import com.maou.apptemplateapi.module.ticket.enums.TicketPriority;
import com.maou.apptemplateapi.module.ticket.enums.TicketStatus;
import com.maou.apptemplateapi.module.ticket.mapper.RepairAssignmentMapper;
import com.maou.apptemplateapi.module.ticket.mapper.RepairEvaluationMapper;
import com.maou.apptemplateapi.module.ticket.mapper.RepairTicketFlowMapper;
import com.maou.apptemplateapi.module.ticket.mapper.RepairTicketMapper;
import com.maou.apptemplateapi.module.ticket.state.TicketStateMachine;
import com.maou.apptemplateapi.module.user.entity.UserAccount;
import com.maou.apptemplateapi.module.user.mapper.UserAccountMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class TicketService {

    private static final String EMPTY_JSON_ARRAY = "[]";
    private static final String BIZ_TEMP = "TEMP";
    private static final String BIZ_TICKET_REPORT = "TICKET_REPORT_IMAGE";
    private static final String DEFAULT_WORKER_DEPARTMENT = "综合维修组";
    private static final int DEFAULT_MAX_ACTIVE_ORDERS = 5;

    private final RepairTicketMapper ticketMapper;
    private final RepairTicketFlowMapper flowMapper;
    private final RepairAssignmentMapper assignmentMapper;
    private final RepairEvaluationMapper evaluationMapper;
    private final RepairCategoryMapper categoryMapper;
    private final RepairLocationMapper locationMapper;
    private final UserAccountMapper userAccountMapper;
    private final FileMetadataMapper fileMetadataMapper;
    private final WorkerProfileMapper workerProfileMapper;
    private final ObjectMapper objectMapper;
    private final TicketAiPreAnalysisTrigger ticketAiPreAnalysisTrigger;
    private final RagKnowledgeDraftTrigger ragKnowledgeDraftTrigger;
    private final OperationAuditService operationAuditService;

    @Transactional
    public TicketDetailResponse createTicket(TicketCreateRequest request) {
        CurrentUser currentUser = requireRole(UserRole.STUDENT, "student-create-ticket", null);
        ensureBaseData(request.categoryId(), request.locationId());

        RepairTicket ticket = new RepairTicket();
        ticket.setStudentId(currentUser.getId());
        ticket.setLocationId(request.locationId());
        ticket.setCategoryId(request.categoryId());
        ticket.setDescription(request.description());
        ticket.setContactPhone(request.contactPhone());
        ticket.setSummary(buildSummary(request.description()));
        ticket.setPriority(TicketPriority.LOW.name());
        ticket.setStatus(TicketStatus.PENDING_REVIEW.name());
        ticket.setSlaDeadlineAt(defaultSlaDeadline(TicketPriority.LOW, LocalDateTime.now()));
        ticket.setReportImageUrls(EMPTY_JSON_ARRAY);
        ticket.setResultImageUrls(EMPTY_JSON_ARRAY);
        if (ticketMapper.insert(ticket) != 1) {
            log.error("ticket create failed, scenario=student-create-ticket, reason=ticket-insert-not-applied, studentId={}",
                    currentUser.getId());
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
        if (request.reportImageFileIds() != null) {
            replaceReportImages(ticket, request.reportImageFileIds(), currentUser);
            updateTicketOrFail(ticket, "student-create-ticket-bind-report-images");
        }
        addFlow(ticket.getId(), null, TicketStatus.PENDING_REVIEW, currentUser, TicketAction.CREATE, "学生提交报修");
        triggerAiPreAnalysisAfterCommit(ticket.getId(), currentUser.getId(), "student-create-ticket-ai-pre-analysis");
        return detailForStudent(ticket.getId());
    }

    public PageResult<TicketSummaryResponse> listStudentTickets(String status, long page, long size) {
        CurrentUser currentUser = requireRole(UserRole.STUDENT, "student-list-ticket", null);
        return pageTickets(new LambdaQueryWrapper<RepairTicket>()
                .eq(RepairTicket::getStudentId, currentUser.getId()), status, page, size);
    }

    public TicketDetailResponse detailForStudent(Long ticketId) {
        CurrentUser currentUser = requireRole(UserRole.STUDENT, "student-ticket-detail", ticketId);
        RepairTicket ticket = getTicket(ticketId);
        if (!Objects.equals(ticket.getStudentId(), currentUser.getId())) {
            log.warn("ticket access denied, scenario=student-ticket-detail, ticketId={}, studentId={}, ownerStudentId={}",
                    ticketId, currentUser.getId(), ticket.getStudentId());
            throw new BusinessException(ErrorCode.TICKET_ACCESS_DENIED);
        }
        return toDetail(ticket);
    }

    @Transactional
    public TicketDetailResponse evaluate(Long ticketId, TicketEvaluationRequest request) {
        CurrentUser currentUser = requireRole(UserRole.STUDENT, "student-ticket-evaluate", ticketId);
        RepairTicket ticket = getTicket(ticketId);
        if (!Objects.equals(ticket.getStudentId(), currentUser.getId())) {
            log.warn("ticket evaluation denied, scenario=student-ticket-evaluate, ticketId={}, studentId={}, ownerStudentId={}",
                    ticketId, currentUser.getId(), ticket.getStudentId());
            throw new BusinessException(ErrorCode.TICKET_ACCESS_DENIED);
        }
        requireStatus(ticket, TicketStatus.WAITING_CONFIRM, "student-ticket-evaluate");
        Long count = evaluationMapper.selectCount(new LambdaQueryWrapper<RepairEvaluation>()
                .eq(RepairEvaluation::getTicketId, ticketId));
        if (count != null && count > 0) {
            log.warn("ticket evaluation duplicated, scenario=student-ticket-evaluate, ticketId={}, studentId={}",
                    ticketId, currentUser.getId());
            throw new BusinessException(ErrorCode.TICKET_EVALUATION_DUPLICATED);
        }

        RepairEvaluation evaluation = new RepairEvaluation();
        evaluation.setTicketId(ticketId);
        evaluation.setStudentId(currentUser.getId());
        evaluation.setWorkerId(ticket.getAssignedWorkerId());
        evaluation.setScore(request.score());
        evaluation.setContent(request.content());
        evaluationMapper.insert(evaluation);

        transition(ticket, TicketStatus.COMPLETED, currentUser, TicketAction.EVALUATE, "学生评价完成");
        triggerKnowledgeDraftAfterCommit(ticketId);
        return toDetail(getTicket(ticketId));
    }

    @Transactional
    public TicketDetailResponse requestRework(Long ticketId, TicketReworkRequest request) {
        CurrentUser currentUser = requireRole(UserRole.STUDENT, "student-ticket-rework", ticketId);
        RepairTicket ticket = getTicket(ticketId);
        if (!Objects.equals(ticket.getStudentId(), currentUser.getId())) {
            log.warn("ticket rework denied, scenario=student-ticket-rework, ticketId={}, studentId={}, ownerStudentId={}",
                    ticketId, currentUser.getId(), ticket.getStudentId());
            throw new BusinessException(ErrorCode.TICKET_ACCESS_DENIED);
        }
        requireStatus(ticket, TicketStatus.WAITING_CONFIRM, "student-ticket-rework");
        transition(ticket, TicketStatus.PROCESSING, currentUser, TicketAction.REQUEST_REWORK, request.reason());
        return toDetail(getTicket(ticketId));
    }

    @Transactional
    public TicketDetailResponse resubmitRejectedTicket(Long ticketId, TicketCreateRequest request) {
        CurrentUser currentUser = requireRole(UserRole.STUDENT, "student-ticket-resubmit", ticketId);
        ensureBaseData(request.categoryId(), request.locationId());
        RepairTicket ticket = getTicket(ticketId);
        if (!Objects.equals(ticket.getStudentId(), currentUser.getId())) {
            log.warn("ticket resubmit denied, scenario=student-ticket-resubmit, ticketId={}, studentId={}, ownerStudentId={}",
                    ticketId, currentUser.getId(), ticket.getStudentId());
            throw new BusinessException(ErrorCode.TICKET_ACCESS_DENIED);
        }
        requireStatus(ticket, TicketStatus.REJECTED, "student-ticket-resubmit");
        ticket.setLocationId(request.locationId());
        ticket.setCategoryId(request.categoryId());
        ticket.setDescription(request.description());
        ticket.setContactPhone(request.contactPhone());
        ticket.setSummary(buildSummary(request.description()));
        ticket.setSlaDeadlineAt(defaultSlaDeadline(TicketPriority.LOW, LocalDateTime.now()));
        ticket.setRejectReason(null);
        ticket.setReturnReason(null);
        ticket.setAssignedWorkerId(null);
        ticket.setAssignedAdminId(null);
        ticket.setAssignedAt(null);
        if (request.reportImageFileIds() != null) {
            replaceReportImages(ticket, request.reportImageFileIds(), currentUser);
        }
        transition(ticket, TicketStatus.PENDING_REVIEW, currentUser, TicketAction.RESUBMIT, "学生修改后重新提交");
        triggerAiPreAnalysisAfterCommit(ticket.getId(), currentUser.getId(), "student-resubmit-ticket-ai-pre-analysis");
        return toDetail(getTicket(ticketId));
    }

    public PageResult<TicketSummaryResponse> listAdminTickets(String status, Boolean overdue, Boolean urged, long page, long size) {
        requireRole(UserRole.ADMIN, "admin-list-ticket", null);
        return pageTickets(applyQualityFilters(new LambdaQueryWrapper<>(), overdue, urged), status, page, size);
    }

    public AdminTodoOverviewResponse adminTodoOverview() {
        requireRole(UserRole.ADMIN, "admin-ticket-todo", null);
        LocalDateTime now = LocalDateTime.now();
        Long pendingReview = countTickets(new LambdaQueryWrapper<RepairTicket>()
                .eq(RepairTicket::getStatus, TicketStatus.PENDING_REVIEW.name()));
        Long returned = countTickets(new LambdaQueryWrapper<RepairTicket>()
                .eq(RepairTicket::getStatus, TicketStatus.RETURNED.name()));
        Long waitingConfirm = countTickets(new LambdaQueryWrapper<RepairTicket>()
                .eq(RepairTicket::getStatus, TicketStatus.WAITING_CONFIRM.name()));
        Long overdue = countTickets(new LambdaQueryWrapper<RepairTicket>()
                .in(RepairTicket::getStatus, slaTrackedStatusNames())
                .isNotNull(RepairTicket::getSlaDeadlineAt)
                .lt(RepairTicket::getSlaDeadlineAt, now));
        Long urged = countTickets(activeTicketWrapper()
                .isNotNull(RepairTicket::getUrgedAt));
        PageResult<TicketSummaryResponse> latest = pageTickets(new LambdaQueryWrapper<>(), null, 1, 5);
        return new AdminTodoOverviewResponse(pendingReview, returned, overdue, urged, waitingConfirm, latest.getItems());
    }

    public TicketDetailResponse detailForAdmin(Long ticketId) {
        requireRole(UserRole.ADMIN, "admin-ticket-detail", ticketId);
        return toDetail(getTicket(ticketId));
    }

    @Transactional
    public TicketDetailResponse urge(Long ticketId, TicketUrgeRequest request) {
        CurrentUser currentUser = requireRole(UserRole.ADMIN, "admin-ticket-urge", ticketId);
        RepairTicket ticket = getTicket(ticketId);
        TicketStatus currentStatus = parseStatus(ticket.getStatus(), "admin-ticket-urge");
        if (!TicketStateMachine.isUrgeable(currentStatus)) {
            log.warn("ticket urge status invalid, scenario=admin-ticket-urge, ticketId={}, currentStatus={}, adminId={}, studentId={}, workerId={}",
                    ticketId, ticket.getStatus(), currentUser.getId(), ticket.getStudentId(), ticket.getAssignedWorkerId());
            throw new BusinessException(ErrorCode.TICKET_STATUS_INVALID);
        }
        ticket.setUrgedAt(LocalDateTime.now());
        ticket.setUrgedBy(currentUser.getId());
        ticket.setUrgeRemark(request.remark());
        ticketMapper.updateById(ticket);
        addFlow(ticketId, ticket.getStatus(), currentStatus, currentUser, TicketAction.URGE, request.remark());
        operationAuditService.record(currentUser, "REPAIR_TICKET", ticketId, TicketAction.URGE.name(),
                ticketSnapshot(ticket, currentStatus.name()), ticketSnapshot(ticket, currentStatus.name()), request.remark());
        return toDetail(getTicket(ticketId));
    }

    @Transactional
    public TicketDetailResponse assign(Long ticketId, TicketAssignRequest request) {
        CurrentUser currentUser = requireRole(UserRole.ADMIN, "admin-ticket-assign", ticketId);
        RepairTicket ticket = getTicket(ticketId);
        requireActionAllowed(ticket, "admin-ticket-assign", TicketAction.ASSIGN);
        ensureBaseData(request.categoryId(), ticket.getLocationId());
        UserAccount worker = getEnabledWorker(request.workerId(), "admin-ticket-assign", ticketId);

        ticket.setCategoryId(request.categoryId());
        ticket.setPriority(request.priority().name());
        ticket.setSummary(request.summary());
        ticket.setAssignedWorkerId(worker.getId());
        ticket.setAssignedAdminId(currentUser.getId());
        ticket.setAssignedAt(LocalDateTime.now());
        ticket.setReturnReason(null);
        ticket.setSlaDeadlineAt(defaultSlaDeadline(request.priority(), ticket.getAssignedAt()));
        transition(ticket, TicketStatus.ASSIGNED, currentUser, TicketAction.ASSIGN, request.remark());

        RepairAssignment assignment = new RepairAssignment();
        assignment.setTicketId(ticketId);
        assignment.setAdminId(currentUser.getId());
        assignment.setWorkerId(worker.getId());
        assignment.setRemark(request.remark());
        assignment.setAssignedAt(ticket.getAssignedAt());
        assignmentMapper.insert(assignment);
        return toDetail(getTicket(ticketId));
    }

    @Transactional
    public TicketDetailResponse reject(Long ticketId, TicketRejectRequest request) {
        CurrentUser currentUser = requireRole(UserRole.ADMIN, "admin-ticket-reject", ticketId);
        RepairTicket ticket = getTicket(ticketId);
        requireActionAllowed(ticket, "admin-ticket-reject", TicketAction.REJECT);
        ticket.setRejectReason(request.reason());
        transition(ticket, TicketStatus.REJECTED, currentUser, TicketAction.REJECT, request.reason());
        return toDetail(getTicket(ticketId));
    }

    public List<WorkerOptionResponse> listWorkerOptions() {
        requireRole(UserRole.ADMIN, "admin-worker-options", null);
        List<UserAccount> workers = userAccountMapper.selectList(new LambdaQueryWrapper<UserAccount>()
                        .eq(UserAccount::getRoleCode, UserRole.WORKER.name())
                        .eq(UserAccount::getEnabled, 1)
                        .eq(UserAccount::getDeleted, 0)
                        .orderByAsc(UserAccount::getId));
        Map<Long, WorkerProfile> profiles = loadWorkerProfiles(workers.stream().map(UserAccount::getId).toList());
        return workers.stream()
                .map(worker -> toWorkerOption(worker, profiles.get(worker.getId())))
                .toList();
    }

    @Transactional
    public TicketDetailResponse bindReportImages(Long ticketId, List<String> imageUrls) {
        CurrentUser currentUser = requireRole(UserRole.STUDENT, "student-bind-report-images", ticketId);
        RepairTicket ticket = getTicket(ticketId);
        if (!Objects.equals(ticket.getStudentId(), currentUser.getId())) {
            log.warn("ticket image bind denied, scenario=student-bind-report-images, ticketId={}, studentId={}, ownerStudentId={}",
                    ticketId, currentUser.getId(), ticket.getStudentId());
            throw new BusinessException(ErrorCode.TICKET_ACCESS_DENIED);
        }
        requireStatus(ticket, TicketStatus.PENDING_REVIEW, "student-bind-report-images");
        ticket.setReportImageUrls(mergeImageUrls(ticket.getReportImageUrls(), imageUrls, "student-bind-report-images", ticketId));
        ticketMapper.updateById(ticket);
        return toDetail(getTicket(ticketId));
    }

    @Transactional
    public TicketDetailResponse bindResultImages(Long ticketId, List<String> imageUrls) {
        CurrentUser currentUser = requireRole(UserRole.WORKER, "worker-bind-result-images", ticketId);
        RepairTicket ticket = getTicket(ticketId);
        ensureAssignedToWorker(ticket, currentUser.getId(), "worker-bind-result-images");
        requireStatus(ticket, TicketStatus.PROCESSING, "worker-bind-result-images");
        ticket.setResultImageUrls(mergeImageUrls(ticket.getResultImageUrls(), imageUrls, "worker-bind-result-images", ticketId));
        ticketMapper.updateById(ticket);
        return toDetail(getTicket(ticketId));
    }

    public void ensureTicketReadable(Long ticketId, CurrentUser currentUser) {
        RepairTicket ticket = getTicket(ticketId);
        if (currentUser.getRole() == UserRole.ADMIN) {
            return;
        }
        if (currentUser.getRole() == UserRole.STUDENT && Objects.equals(ticket.getStudentId(), currentUser.getId())) {
            return;
        }
        if (currentUser.getRole() == UserRole.WORKER && Objects.equals(ticket.getAssignedWorkerId(), currentUser.getId())) {
            return;
        }
        log.warn("ticket file list denied, scenario=ticket-file-list, ticketId={}, userId={}, roleCode={}, studentId={}, workerId={}",
                ticketId, currentUser.getId(), currentUser.getRoleCode(), ticket.getStudentId(), ticket.getAssignedWorkerId());
        throw new BusinessException(ErrorCode.TICKET_ACCESS_DENIED);
    }

    public PageResult<TicketSummaryResponse> listWorkerTickets(String status, Boolean overdue, long page, long size) {
        CurrentUser currentUser = requireRole(UserRole.WORKER, "worker-list-ticket", null);
        return pageTickets(applyQualityFilters(new LambdaQueryWrapper<RepairTicket>()
                .eq(RepairTicket::getAssignedWorkerId, currentUser.getId()), overdue, null), status, page, size);
    }

    public WorkerTodayOverviewResponse workerTodayOverview() {
        CurrentUser currentUser = requireRole(UserRole.WORKER, "worker-today-overview", null);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime endOfToday = now.toLocalDate().atTime(23, 59, 59);
        Long assigned = countTickets(new LambdaQueryWrapper<RepairTicket>()
                .eq(RepairTicket::getAssignedWorkerId, currentUser.getId())
                .eq(RepairTicket::getStatus, TicketStatus.ASSIGNED.name()));
        Long processing = countTickets(new LambdaQueryWrapper<RepairTicket>()
                .eq(RepairTicket::getAssignedWorkerId, currentUser.getId())
                .eq(RepairTicket::getStatus, TicketStatus.PROCESSING.name()));
        Long waitingConfirm = countTickets(new LambdaQueryWrapper<RepairTicket>()
                .eq(RepairTicket::getAssignedWorkerId, currentUser.getId())
                .eq(RepairTicket::getStatus, TicketStatus.WAITING_CONFIRM.name()));
        Long overdue = countTickets(new LambdaQueryWrapper<RepairTicket>()
                .in(RepairTicket::getStatus, slaTrackedStatusNames())
                .eq(RepairTicket::getAssignedWorkerId, currentUser.getId())
                .isNotNull(RepairTicket::getSlaDeadlineAt)
                .lt(RepairTicket::getSlaDeadlineAt, now));
        PageResult<TicketSummaryResponse> dueToday = pageTickets(activeTicketWrapper()
                .eq(RepairTicket::getAssignedWorkerId, currentUser.getId())
                .isNotNull(RepairTicket::getSlaDeadlineAt)
                .le(RepairTicket::getSlaDeadlineAt, endOfToday), null, 1, 8);
        return new WorkerTodayOverviewResponse(assigned, processing, waitingConfirm, overdue, dueToday.getItems());
    }

    public TicketDetailResponse detailForWorker(Long ticketId) {
        CurrentUser currentUser = requireRole(UserRole.WORKER, "worker-ticket-detail", ticketId);
        RepairTicket ticket = getTicket(ticketId);
        ensureAssignedToWorker(ticket, currentUser.getId(), "worker-ticket-detail");
        return toDetail(ticket);
    }

    @Transactional
    public TicketDetailResponse accept(Long ticketId) {
        CurrentUser currentUser = requireRole(UserRole.WORKER, "worker-ticket-accept", ticketId);
        RepairTicket ticket = getTicket(ticketId);
        ensureAssignedToWorker(ticket, currentUser.getId(), "worker-ticket-accept");
        requireStatus(ticket, TicketStatus.ASSIGNED, "worker-ticket-accept");
        transition(ticket, TicketStatus.PROCESSING, currentUser, TicketAction.ACCEPT, "维修员接单并开始处理");
        return toDetail(getTicket(ticketId));
    }

    @Transactional
    public TicketDetailResponse returnTicket(Long ticketId, TicketReturnRequest request) {
        CurrentUser currentUser = requireRole(UserRole.WORKER, "worker-ticket-return", ticketId);
        RepairTicket ticket = getTicket(ticketId);
        ensureAssignedToWorker(ticket, currentUser.getId(), "worker-ticket-return");
        requireStatus(ticket, TicketStatus.ASSIGNED, "worker-ticket-return");
        ticket.setReturnReason(request.reason());
        ticket.setAssignedWorkerId(null);
        ticket.setAssignedAdminId(null);
        ticket.setAssignedAt(null);
        transition(ticket, TicketStatus.RETURNED, currentUser, TicketAction.WORKER_RETURN, request.reason());
        return toDetail(getTicket(ticketId));
    }

    @Transactional
    public TicketDetailResponse submitResult(Long ticketId, TicketResultRequest request) {
        CurrentUser currentUser = requireRole(UserRole.WORKER, "worker-ticket-result", ticketId);
        RepairTicket ticket = getTicket(ticketId);
        ensureAssignedToWorker(ticket, currentUser.getId(), "worker-ticket-result");
        requireStatus(ticket, TicketStatus.PROCESSING, "worker-ticket-result");
        ticket.setProcessResult(request.result());
        ticket.setProcessRemark(request.remark());
        ticket.setProcessedAt(LocalDateTime.now());
        transition(ticket, TicketStatus.WAITING_CONFIRM, currentUser, TicketAction.SUBMIT_RESULT, request.result());
        return toDetail(getTicket(ticketId));
    }

    private PageResult<TicketSummaryResponse> pageTickets(LambdaQueryWrapper<RepairTicket> wrapper, String status, long page, long size) {
        if (StringUtils.hasText(status)) {
            wrapper.eq(RepairTicket::getStatus, parseStatus(status, "ticket-list").name());
        }
        wrapper.eq(RepairTicket::getDeleted, 0)
                .orderByDesc(RepairTicket::getCreatedAt)
                .orderByDesc(RepairTicket::getId);
        Page<RepairTicket> result = ticketMapper.selectPage(new Page<>(Math.max(page, 1), Math.max(size, 1)), wrapper);
        List<TicketSummaryResponse> items = enrichSummary(result.getRecords());
        return PageResult.of(items, result.getCurrent(), result.getSize(), result.getTotal());
    }

    private LambdaQueryWrapper<RepairTicket> applyQualityFilters(LambdaQueryWrapper<RepairTicket> wrapper, Boolean overdue, Boolean urged) {
        if (Boolean.TRUE.equals(overdue)) {
            // 超时筛选按 SLA 口径（含已退回），见 slaTrackedStatusNames()
            wrapper.in(RepairTicket::getStatus, slaTrackedStatusNames())
                    .isNotNull(RepairTicket::getSlaDeadlineAt)
                    .lt(RepairTicket::getSlaDeadlineAt, LocalDateTime.now());
        }
        if (Boolean.TRUE.equals(urged)) {
            wrapper.in(RepairTicket::getStatus, TicketStateMachine.activeStatusNames())
                    .isNotNull(RepairTicket::getUrgedAt);
        }
        return wrapper;
    }

    private List<TicketSummaryResponse> enrichSummary(List<RepairTicket> tickets) {
        Map<Long, UserAccount> users = loadUsers(tickets.stream()
                .flatMap(ticket -> java.util.stream.Stream.of(ticket.getStudentId(), ticket.getAssignedWorkerId()))
                .filter(Objects::nonNull)
                .toList());
        Map<Long, RepairCategory> categories = loadCategories(tickets.stream().map(RepairTicket::getCategoryId).toList());
        Map<Long, RepairLocation> locations = loadLocations(tickets.stream().map(RepairTicket::getLocationId).toList());
        return tickets.stream()
                .map(ticket -> new TicketSummaryResponse(
                        ticket.getId(),
                        ticket.getStatus(),
                        ticket.getPriority(),
                        ticket.getStudentId(),
                        nameOf(users.get(ticket.getStudentId())),
                        ticket.getLocationId(),
                        nameOf(locations.get(ticket.getLocationId())),
                        ticket.getCategoryId(),
                        nameOf(categories.get(ticket.getCategoryId())),
                        ticket.getAssignedWorkerId(),
                        nameOf(users.get(ticket.getAssignedWorkerId())),
                        ticket.getSummary(),
                        ticket.getSlaDeadlineAt(),
                        isSlaOverdue(ticket),
                        ticket.getUrgedAt(),
                        ticket.getCreatedAt(),
                        ticket.getUpdatedAt()
                ))
                .toList();
    }

    private TicketDetailResponse toDetail(RepairTicket ticket) {
        Map<Long, UserAccount> users = loadUsers(java.util.stream.Stream.of(
                        ticket.getStudentId(),
                        ticket.getAssignedWorkerId(),
                        ticket.getAssignedAdminId())
                .filter(Objects::nonNull)
                .toList());
        RepairCategory category = categoryMapper.selectById(ticket.getCategoryId());
        RepairLocation location = locationMapper.selectById(ticket.getLocationId());
        List<TicketFlowResponse> flows = flowMapper.selectList(new LambdaQueryWrapper<RepairTicketFlow>()
                        .eq(RepairTicketFlow::getTicketId, ticket.getId())
                        .orderByAsc(RepairTicketFlow::getCreatedAt)
                        .orderByAsc(RepairTicketFlow::getId))
                .stream()
                .map(flow -> new TicketFlowResponse(
                        flow.getId(),
                        flow.getFromStatus(),
                        flow.getToStatus(),
                        flow.getOperatorId(),
                        flow.getOperatorRole(),
                        flow.getAction(),
                        flow.getRemark(),
                        flow.getCreatedAt()
                ))
                .toList();
        RepairEvaluation evaluation = evaluationMapper.selectOne(new LambdaQueryWrapper<RepairEvaluation>()
                .eq(RepairEvaluation::getTicketId, ticket.getId())
                .last("limit 1"));
        TicketEvaluationResponse evaluationResponse = evaluation == null
                ? null
                : new TicketEvaluationResponse(evaluation.getId(), evaluation.getScore(), evaluation.getContent(), evaluation.getCreatedAt());
        return new TicketDetailResponse(
                ticket.getId(),
                ticket.getStatus(),
                ticket.getPriority(),
                ticket.getStudentId(),
                nameOf(users.get(ticket.getStudentId())),
                ticket.getLocationId(),
                nameOf(location),
                ticket.getCategoryId(),
                nameOf(category),
                ticket.getDescription(),
                ticket.getContactPhone(),
                ticket.getSummary(),
                ticket.getAssignedWorkerId(),
                nameOf(users.get(ticket.getAssignedWorkerId())),
                ticket.getAssignedAdminId(),
                nameOf(users.get(ticket.getAssignedAdminId())),
                ticket.getAssignedAt(),
                ticket.getRejectReason(),
                ticket.getReturnReason(),
                ticket.getProcessResult(),
                ticket.getProcessRemark(),
                ticket.getProcessedAt(),
                ticket.getSlaDeadlineAt(),
                isSlaOverdue(ticket),
                ticket.getUrgedAt(),
                ticket.getUrgedBy(),
                ticket.getUrgeRemark(),
                ticket.getReportImageUrls(),
                ticket.getResultImageUrls(),
                ticket.getCreatedAt(),
                ticket.getUpdatedAt(),
                flows,
                evaluationResponse
        );
    }

    private RepairTicket getTicket(Long ticketId) {
        RepairTicket ticket = ticketMapper.selectById(ticketId);
        if (ticket == null || ticket.getDeleted() != 0) {
            log.warn("ticket not found, scenario=ticket-load, ticketId={}", ticketId);
            throw new BusinessException(ErrorCode.TICKET_NOT_FOUND);
        }
        return ticket;
    }

    private void transition(RepairTicket ticket, TicketStatus toStatus, CurrentUser operator, TicketAction action, String remark) {
        String fromStatus = ticket.getStatus();
        ensureTransitionAllowed(ticket, toStatus, action);
        ticket.setStatus(toStatus.name());
        ticketMapper.updateById(ticket);
        addFlow(ticket.getId(), fromStatus, toStatus, operator, action, remark);
        operationAuditService.record(operator, "REPAIR_TICKET", ticket.getId(), action.name(),
                ticketSnapshot(ticket, fromStatus), ticketSnapshot(ticket, toStatus.name()), remark);
    }

    private void addFlow(Long ticketId, String fromStatus, TicketStatus toStatus, CurrentUser operator, TicketAction action, String remark) {
        RepairTicketFlow flow = new RepairTicketFlow();
        flow.setTicketId(ticketId);
        flow.setFromStatus(fromStatus);
        flow.setToStatus(toStatus.name());
        flow.setOperatorId(operator.getId());
        flow.setOperatorRole(operator.getRoleCode());
        flow.setAction(action.name());
        flow.setRemark(remark);
        flowMapper.insert(flow);
    }

    private void requireStatus(RepairTicket ticket, TicketStatus required, String scenario) {
        if (!required.name().equals(ticket.getStatus())) {
            log.warn("ticket status invalid, scenario={}, ticketId={}, currentStatus={}, requiredStatus={}, studentId={}, workerId={}",
                    scenario, ticket.getId(), ticket.getStatus(), required.name(), ticket.getStudentId(), ticket.getAssignedWorkerId());
            throw new BusinessException(ErrorCode.TICKET_STATUS_INVALID);
        }
    }

    private void requireActionAllowed(RepairTicket ticket, String scenario, TicketAction action) {
        TicketStatus current = parseStatus(ticket.getStatus(), scenario);
        if (TicketStateMachine.allowedFromStatuses(action).contains(current)) {
            return;
        }
        log.warn("ticket status invalid, scenario={}, ticketId={}, action={}, currentStatus={}, allowedStatuses={}, studentId={}, workerId={}",
                scenario, ticket.getId(), action.name(), ticket.getStatus(), TicketStateMachine.allowedFromStatuses(action),
                ticket.getStudentId(), ticket.getAssignedWorkerId());
        throw new BusinessException(ErrorCode.TICKET_STATUS_INVALID);
    }

    private void ensureTransitionAllowed(RepairTicket ticket, TicketStatus toStatus, TicketAction action) {
        String fromStatus = ticket.getStatus();
        if (TicketStateMachine.canTransition(fromStatus == null ? null : parseStatus(fromStatus, "ticket-state-machine"), toStatus, action)) {
            return;
        }
        log.warn("ticket transition denied, scenario=ticket-state-machine, ticketId={}, action={}, fromStatus={}, toStatus={}, studentId={}, workerId={}",
                ticket.getId(), action.name(), fromStatus, toStatus.name(), ticket.getStudentId(), ticket.getAssignedWorkerId());
        throw new BusinessException(ErrorCode.TICKET_STATUS_INVALID);
    }

    private TicketStatus parseStatus(String status, String scenario) {
        try {
            return TicketStatus.valueOf(status);
        } catch (IllegalArgumentException exception) {
            log.warn("ticket status invalid, scenario={}, status={}", scenario, status, exception);
            throw new BusinessException(ErrorCode.TICKET_STATUS_INVALID);
        }
    }

    private CurrentUser requireRole(UserRole role, String scenario, Long ticketId) {
        CurrentUser currentUser = CurrentUserProvider.require();
        if (currentUser.getRole() != role) {
            log.warn("ticket role denied, scenario={}, ticketId={}, userId={}, roleCode={}, requiredRole={}",
                    scenario, ticketId, currentUser.getId(), currentUser.getRoleCode(), role.name());
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return currentUser;
    }

    private void ensureAssignedToWorker(RepairTicket ticket, Long workerId, String scenario) {
        if (!Objects.equals(ticket.getAssignedWorkerId(), workerId)) {
            log.warn("ticket worker access denied, scenario={}, ticketId={}, workerId={}, assignedWorkerId={}",
                    scenario, ticket.getId(), workerId, ticket.getAssignedWorkerId());
            throw new BusinessException(ErrorCode.TICKET_ACCESS_DENIED);
        }
    }

    private void ensureBaseData(Long categoryId, Long locationId) {
        RepairCategory category = categoryMapper.selectById(categoryId);
        RepairLocation location = locationMapper.selectById(locationId);
        if (category == null || category.getDeleted() != 0 || category.getEnabled() != 1
                || location == null || location.getDeleted() != 0 || location.getEnabled() != 1) {
            log.warn("ticket base data invalid, scenario=ticket-base-data, categoryId={}, locationId={}", categoryId, locationId);
            throw new BusinessException(ErrorCode.TICKET_BASE_DATA_INVALID);
        }
    }

    private UserAccount getEnabledWorker(Long workerId, String scenario, Long ticketId) {
        UserAccount worker = userAccountMapper.selectById(workerId);
        if (worker == null || worker.getDeleted() != 0 || worker.getEnabled() != 1
                || !UserRole.WORKER.name().equals(worker.getRoleCode())) {
            log.warn("ticket worker invalid, scenario={}, ticketId={}, workerId={}", scenario, ticketId, workerId);
            throw new BusinessException(ErrorCode.TICKET_WORKER_INVALID);
        }
        return worker;
    }

    private Long countTickets(LambdaQueryWrapper<RepairTicket> wrapper) {
        wrapper.eq(RepairTicket::getDeleted, 0);
        Long count = ticketMapper.selectCount(wrapper);
        return count == null ? 0 : count;
    }

    private LambdaQueryWrapper<RepairTicket> activeTicketWrapper() {
        return new LambdaQueryWrapper<RepairTicket>()
                .in(RepairTicket::getStatus, TicketStateMachine.activeStatusNames());
    }

    /**
     * SLA 计时口径的工单状态：进行中的四个状态 + 已退回。
     *
     * <p>「已退回」的工单同样躺在管理员那里等重新派单，SLA 事实上已经违约，必须计入超时，
     * 否则最该督办的工单会被灰色标签藏起来；但它不能加进 {@code TicketStateMachine.ACTIVE_STATUSES}，
     * 因为那个集合还被派单评分用来统计维修员「当前活跃工单数」，改动会连带影响派单排序。
     */
    private Set<String> slaTrackedStatusNames() {
        Set<String> statuses = new java.util.LinkedHashSet<>(TicketStateMachine.activeStatusNames());
        statuses.add(TicketStatus.RETURNED.name());
        return statuses;
    }

    private LocalDateTime defaultSlaDeadline(TicketPriority priority, LocalDateTime baseTime) {
        LocalDateTime base = baseTime == null ? LocalDateTime.now() : baseTime;
        return switch (priority) {
            case HIGH -> base.plusHours(4);
            case MEDIUM -> base.plusHours(24);
            case LOW -> base.plusHours(48);
        };
    }

    private void triggerAiPreAnalysisAfterCommit(Long ticketId, Long studentId, String scenario) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    ticketAiPreAnalysisTrigger.trigger(ticketId, studentId, scenario);
                }
            });
            return;
        }
        ticketAiPreAnalysisTrigger.trigger(ticketId, studentId, scenario);
    }

    /**
     * 工单闭环后异步沉淀知识草稿（第三阶段 V3），不阻塞学生评价主流程。
     */
    private void triggerKnowledgeDraftAfterCommit(Long ticketId) {
        String scenario = "ticket-evaluated-knowledge-draft";
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    ragKnowledgeDraftTrigger.trigger(ticketId, scenario);
                }
            });
            return;
        }
        ragKnowledgeDraftTrigger.trigger(ticketId, scenario);
    }

    private boolean isSlaOverdue(RepairTicket ticket) {
        if (ticket.getSlaDeadlineAt() == null) {
            return false;
        }
        TicketStatus status = parseStatus(ticket.getStatus(), "ticket-sla-overdue");
        // 与列表筛选、待办统计保持一致：进行中 + 已退回 都参与 SLA 计时
        if (!TicketStateMachine.isActive(status) && status != TicketStatus.RETURNED) {
            return false;
        }
        return ticket.getSlaDeadlineAt().isBefore(LocalDateTime.now());
    }

    private Map<Long, UserAccount> loadUsers(List<Long> ids) {
        return ids.stream().filter(Objects::nonNull).distinct().toList().isEmpty()
                ? Map.of()
                : userAccountMapper.selectBatchIds(ids.stream().filter(Objects::nonNull).distinct().toList())
                .stream()
                .collect(Collectors.toMap(UserAccount::getId, Function.identity()));
    }

    private Map<Long, RepairCategory> loadCategories(List<Long> ids) {
        List<Long> distinctIds = ids.stream().filter(Objects::nonNull).distinct().toList();
        return distinctIds.isEmpty()
                ? Map.of()
                : categoryMapper.selectBatchIds(distinctIds).stream().collect(Collectors.toMap(RepairCategory::getId, Function.identity()));
    }

    private Map<Long, RepairLocation> loadLocations(List<Long> ids) {
        List<Long> distinctIds = ids.stream().filter(Objects::nonNull).distinct().toList();
        return distinctIds.isEmpty()
                ? Map.of()
                : locationMapper.selectBatchIds(distinctIds).stream().collect(Collectors.toMap(RepairLocation::getId, Function.identity()));
    }

    private Map<Long, WorkerProfile> loadWorkerProfiles(List<Long> workerIds) {
        List<Long> distinctIds = workerIds.stream().filter(Objects::nonNull).distinct().toList();
        if (distinctIds.isEmpty()) {
            return Map.of();
        }
        return workerProfileMapper.selectBatchIds(distinctIds).stream()
                .filter(profile -> profile.getDeleted() == null || profile.getDeleted() == 0)
                .collect(Collectors.toMap(WorkerProfile::getWorkerId, Function.identity(), (left, right) -> left));
    }

    private WorkerOptionResponse toWorkerOption(UserAccount worker, WorkerProfile profile) {
        String department = profile == null ? DEFAULT_WORKER_DEPARTMENT : profile.getDepartmentName();
        List<String> skillTags = profile == null ? List.of() : readJsonArray(profile.getSkillTags(), "admin-worker-options", null);
        Integer maxActiveOrders = profile == null || profile.getMaxActiveOrders() == null
                ? DEFAULT_MAX_ACTIVE_ORDERS
                : profile.getMaxActiveOrders();
        return new WorkerOptionResponse(worker.getId(), worker.getUsername(), worker.getRealName(), worker.getPhone(),
                department, skillTags, activeOrderCount(worker.getId()), maxActiveOrders);
    }

    private Integer activeOrderCount(Long workerId) {
        Long count = ticketMapper.selectCount(new LambdaQueryWrapper<RepairTicket>()
                .eq(RepairTicket::getAssignedWorkerId, workerId)
                .in(RepairTicket::getStatus, TicketStateMachine.activeStatusNames())
                .eq(RepairTicket::getDeleted, 0));
        return count == null ? 0 : count.intValue();
    }

    private String buildSummary(String description) {
        String normalized = description.strip().replaceAll("\\s+", " ");
        return normalized.length() <= 80 ? normalized : normalized.substring(0, 80);
    }

    private String ticketSnapshot(RepairTicket ticket, String status) {
        if (ticket == null) {
            return null;
        }
        return "ticketId=%s,status=%s,studentId=%s,workerId=%s,priority=%s,locationId=%s,categoryId=%s,summary=%s"
                .formatted(ticket.getId(), status, ticket.getStudentId(), ticket.getAssignedWorkerId(),
                        ticket.getPriority(), ticket.getLocationId(), ticket.getCategoryId(), ticket.getSummary());
    }

    private String mergeImageUrls(String oldJson, List<String> imageUrls, String scenario, Long ticketId) {
        List<String> urls;
        try {
            urls = StringUtils.hasText(oldJson)
                    ? objectMapper.readValue(oldJson, new TypeReference<>() {
                    })
                    : new java.util.ArrayList<>();
        } catch (JsonProcessingException exception) {
            log.error("ticket image json invalid, scenario={}, ticketId={}, rawJson={}", scenario, ticketId, oldJson, exception);
            urls = new java.util.ArrayList<>();
        }
        for (String imageUrl : imageUrls) {
            if (StringUtils.hasText(imageUrl) && !urls.contains(imageUrl)) {
                urls.add(imageUrl);
            }
        }
        try {
            return objectMapper.writeValueAsString(urls);
        } catch (JsonProcessingException exception) {
            log.error("ticket image json write failed, scenario={}, ticketId={}, imageUrls={}", scenario, ticketId, imageUrls, exception);
            throw new BusinessException(ErrorCode.FILE_BIND_INVALID);
        }
    }

    private List<String> readJsonArray(String rawJson, String scenario, Long ticketId) {
        if (!StringUtils.hasText(rawJson)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(rawJson, new TypeReference<List<String>>() {
            });
        } catch (JsonProcessingException exception) {
            log.error("ticket json array read failed, scenario={}, ticketId={}, rawJson={}",
                    scenario, ticketId, rawJson, exception);
            return List.of();
        }
    }

    private void replaceReportImages(RepairTicket ticket, List<Long> fileIds, CurrentUser currentUser) {
        Long ticketId = ticket.getId();
        if (fileIds.isEmpty()) {
            unbindCurrentReportFiles(ticketId);
            ticket.setReportImageUrls(EMPTY_JSON_ARRAY);
            log.info("ticket report images cleared, scenario=student-ticket-resubmit, ticketId={}, studentId={}",
                    ticketId, currentUser.getId());
            return;
        }
        List<Long> distinctFileIds = fileIds.stream().filter(Objects::nonNull).distinct().toList();
        if (distinctFileIds.size() != fileIds.size()) {
            log.warn("ticket report image replace invalid, scenario=student-ticket-resubmit, ticketId={}, studentId={}, fileIds={}",
                    ticketId, currentUser.getId(), fileIds);
            throw new BusinessException(ErrorCode.FILE_BIND_INVALID);
        }
        List<FileMetadata> files = fileMetadataMapper.selectBatchIds(distinctFileIds);
        if (files.size() != distinctFileIds.size()) {
            log.warn("ticket report image replace file missing, scenario=student-ticket-resubmit, ticketId={}, studentId={}, fileIds={}",
                    ticketId, currentUser.getId(), distinctFileIds);
            throw new BusinessException(ErrorCode.FILE_NOT_FOUND);
        }
        for (FileMetadata file : files) {
            if (file.getDeleted() != 0 || !currentUser.getId().equals(file.getUploaderId())) {
                log.warn("ticket report image replace denied, scenario=student-ticket-resubmit, ticketId={}, studentId={}, fileId={}, uploaderId={}",
                        ticketId, currentUser.getId(), file.getId(), file.getUploaderId());
                throw new BusinessException(ErrorCode.FILE_ACCESS_DENIED);
            }
            boolean tempFile = BIZ_TEMP.equals(file.getBizType());
            boolean currentTicketReportFile = BIZ_TICKET_REPORT.equals(file.getBizType()) && ticketId.equals(file.getBizId());
            if (!tempFile && !currentTicketReportFile) {
                log.warn("ticket report image replace already bound, scenario=student-ticket-resubmit, ticketId={}, studentId={}, fileId={}, bizType={}, bizId={}",
                        ticketId, currentUser.getId(), file.getId(), file.getBizType(), file.getBizId());
                throw new BusinessException(ErrorCode.FILE_ALREADY_BOUND);
            }
        }
        unbindCurrentReportFiles(ticketId);
        for (FileMetadata file : files) {
            file.setBizType(BIZ_TICKET_REPORT);
            file.setBizId(ticketId);
            updateFileMetadataOrFail(file, "student-ticket-report-image-bind", ticketId, currentUser.getId());
        }
        ticket.setReportImageUrls(writeJsonArray(files.stream().map(FileMetadata::getPublicUrl).toList(), "student-ticket-resubmit", ticketId));
        log.info("ticket report images replaced, scenario=student-ticket-resubmit, ticketId={}, studentId={}, fileCount={}",
                ticketId, currentUser.getId(), files.size());
    }

    private void unbindCurrentReportFiles(Long ticketId) {
        List<FileMetadata> oldFiles = fileMetadataMapper.selectList(new LambdaQueryWrapper<FileMetadata>()
                .eq(FileMetadata::getBizType, BIZ_TICKET_REPORT)
                .eq(FileMetadata::getBizId, ticketId)
                .eq(FileMetadata::getDeleted, 0));
        for (FileMetadata oldFile : oldFiles) {
            oldFile.setBizType(BIZ_TEMP);
            oldFile.setBizId(null);
            updateFileMetadataOrFail(oldFile, "student-ticket-report-image-unbind", ticketId, null);
        }
    }

    private void updateTicketOrFail(RepairTicket ticket, String scenario) {
        if (ticketMapper.updateById(ticket) == 1) {
            return;
        }
        log.error("ticket update failed, scenario={}, ticketId={}, studentId={}, workerId={}, status={}",
                scenario, ticket.getId(), ticket.getStudentId(), ticket.getAssignedWorkerId(), ticket.getStatus());
        throw new BusinessException(ErrorCode.FILE_BIND_INVALID);
    }

    private void updateFileMetadataOrFail(FileMetadata file, String scenario, Long ticketId, Long studentId) {
        if (fileMetadataMapper.updateById(file) == 1) {
            return;
        }
        log.error("file metadata update failed, scenario={}, ticketId={}, studentId={}, fileId={}, bizType={}, bizId={}",
                scenario, ticketId, studentId, file.getId(), file.getBizType(), file.getBizId());
        throw new BusinessException(ErrorCode.FILE_BIND_INVALID);
    }

    private String writeJsonArray(List<String> imageUrls, String scenario, Long ticketId) {
        try {
            return objectMapper.writeValueAsString(imageUrls);
        } catch (JsonProcessingException exception) {
            log.error("ticket image json write failed, scenario={}, ticketId={}, imageUrls={}", scenario, ticketId, imageUrls, exception);
            throw new BusinessException(ErrorCode.FILE_BIND_INVALID);
        }
    }

    private String nameOf(UserAccount user) {
        return user == null ? null : user.getRealName();
    }

    private String nameOf(RepairCategory category) {
        return category == null ? null : category.getName();
    }

    private String nameOf(RepairLocation location) {
        return location == null ? null : location.getName();
    }
}
