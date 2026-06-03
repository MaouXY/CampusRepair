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
import com.maou.apptemplateapi.module.ticket.enums.TicketPriority;
import com.maou.apptemplateapi.module.ticket.enums.TicketStatus;
import com.maou.apptemplateapi.module.ticket.mapper.RepairAssignmentMapper;
import com.maou.apptemplateapi.module.ticket.mapper.RepairEvaluationMapper;
import com.maou.apptemplateapi.module.ticket.mapper.RepairTicketFlowMapper;
import com.maou.apptemplateapi.module.ticket.mapper.RepairTicketMapper;
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

    private final RepairTicketMapper ticketMapper;
    private final RepairTicketFlowMapper flowMapper;
    private final RepairAssignmentMapper assignmentMapper;
    private final RepairEvaluationMapper evaluationMapper;
    private final RepairCategoryMapper categoryMapper;
    private final RepairLocationMapper locationMapper;
    private final UserAccountMapper userAccountMapper;
    private final FileMetadataMapper fileMetadataMapper;
    private final ObjectMapper objectMapper;
    private final TicketAiPreAnalysisTrigger ticketAiPreAnalysisTrigger;
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
        addFlow(ticket.getId(), null, TicketStatus.PENDING_REVIEW, currentUser, "CREATE", "学生提交报修");
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

        transition(ticket, TicketStatus.COMPLETED, currentUser, "EVALUATE", "学生评价完成");
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
        transition(ticket, TicketStatus.PROCESSING, currentUser, "REQUEST_REWORK", request.reason());
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
        transition(ticket, TicketStatus.PENDING_REVIEW, currentUser, "RESUBMIT", "学生修改后重新提交");
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
        Long overdue = countTickets(activeTicketWrapper()
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
        if (!Set.of(TicketStatus.ASSIGNED, TicketStatus.PROCESSING, TicketStatus.WAITING_CONFIRM).contains(currentStatus)) {
            log.warn("ticket urge status invalid, scenario=admin-ticket-urge, ticketId={}, currentStatus={}, adminId={}, studentId={}, workerId={}",
                    ticketId, ticket.getStatus(), currentUser.getId(), ticket.getStudentId(), ticket.getAssignedWorkerId());
            throw new BusinessException(ErrorCode.TICKET_STATUS_INVALID);
        }
        ticket.setUrgedAt(LocalDateTime.now());
        ticket.setUrgedBy(currentUser.getId());
        ticket.setUrgeRemark(request.remark());
        ticketMapper.updateById(ticket);
        addFlow(ticketId, ticket.getStatus(), currentStatus, currentUser, "URGE", request.remark());
        operationAuditService.record(currentUser, "REPAIR_TICKET", ticketId, "URGE",
                ticketSnapshot(ticket, currentStatus.name()), ticketSnapshot(ticket, currentStatus.name()), request.remark());
        return toDetail(getTicket(ticketId));
    }

    @Transactional
    public TicketDetailResponse assign(Long ticketId, TicketAssignRequest request) {
        CurrentUser currentUser = requireRole(UserRole.ADMIN, "admin-ticket-assign", ticketId);
        RepairTicket ticket = getTicket(ticketId);
        requireAnyStatus(ticket, "admin-ticket-assign", TicketStatus.PENDING_REVIEW, TicketStatus.RETURNED);
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
        transition(ticket, TicketStatus.ASSIGNED, currentUser, "ASSIGN", request.remark());

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
        requireAnyStatus(ticket, "admin-ticket-reject", TicketStatus.PENDING_REVIEW, TicketStatus.RETURNED);
        ticket.setRejectReason(request.reason());
        transition(ticket, TicketStatus.REJECTED, currentUser, "REJECT", request.reason());
        return toDetail(getTicket(ticketId));
    }

    public List<WorkerOptionResponse> listWorkerOptions() {
        requireRole(UserRole.ADMIN, "admin-worker-options", null);
        return userAccountMapper.selectList(new LambdaQueryWrapper<UserAccount>()
                        .eq(UserAccount::getRoleCode, UserRole.WORKER.name())
                        .eq(UserAccount::getEnabled, 1)
                        .eq(UserAccount::getDeleted, 0)
                        .orderByAsc(UserAccount::getId))
                .stream()
                .map(worker -> new WorkerOptionResponse(worker.getId(), worker.getUsername(), worker.getRealName(), worker.getPhone()))
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
        Long overdue = countTickets(activeTicketWrapper()
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
        transition(ticket, TicketStatus.PROCESSING, currentUser, "ACCEPT", "维修员接单并开始处理");
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
        transition(ticket, TicketStatus.RETURNED, currentUser, "WORKER_RETURN", request.reason());
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
        transition(ticket, TicketStatus.WAITING_CONFIRM, currentUser, "SUBMIT_RESULT", request.result());
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
            wrapper.notIn(RepairTicket::getStatus, TicketStatus.COMPLETED.name(), TicketStatus.REJECTED.name(), TicketStatus.RETURNED.name())
                    .isNotNull(RepairTicket::getSlaDeadlineAt)
                    .lt(RepairTicket::getSlaDeadlineAt, LocalDateTime.now());
        }
        if (Boolean.TRUE.equals(urged)) {
            wrapper.notIn(RepairTicket::getStatus, TicketStatus.COMPLETED.name(), TicketStatus.REJECTED.name(), TicketStatus.RETURNED.name())
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

    private void transition(RepairTicket ticket, TicketStatus toStatus, CurrentUser operator, String action, String remark) {
        String fromStatus = ticket.getStatus();
        ensureTransitionAllowed(ticket, toStatus, action);
        ticket.setStatus(toStatus.name());
        ticketMapper.updateById(ticket);
        addFlow(ticket.getId(), fromStatus, toStatus, operator, action, remark);
        operationAuditService.record(operator, "REPAIR_TICKET", ticket.getId(), action,
                ticketSnapshot(ticket, fromStatus), ticketSnapshot(ticket, toStatus.name()), remark);
    }

    private void addFlow(Long ticketId, String fromStatus, TicketStatus toStatus, CurrentUser operator, String action, String remark) {
        RepairTicketFlow flow = new RepairTicketFlow();
        flow.setTicketId(ticketId);
        flow.setFromStatus(fromStatus);
        flow.setToStatus(toStatus.name());
        flow.setOperatorId(operator.getId());
        flow.setOperatorRole(operator.getRoleCode());
        flow.setAction(action);
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

    private void requireAnyStatus(RepairTicket ticket, String scenario, TicketStatus... allowedStatuses) {
        TicketStatus current = parseStatus(ticket.getStatus(), scenario);
        if (Set.of(allowedStatuses).contains(current)) {
            return;
        }
        log.warn("ticket status invalid, scenario={}, ticketId={}, currentStatus={}, allowedStatuses={}, studentId={}, workerId={}",
                scenario, ticket.getId(), ticket.getStatus(), Set.of(allowedStatuses), ticket.getStudentId(), ticket.getAssignedWorkerId());
        throw new BusinessException(ErrorCode.TICKET_STATUS_INVALID);
    }

    private void ensureTransitionAllowed(RepairTicket ticket, TicketStatus toStatus, String action) {
        String fromStatus = ticket.getStatus();
        if (fromStatus == null) {
            return;
        }
        TicketStatus from = parseStatus(fromStatus, "ticket-state-machine");
        boolean allowed = switch (from) {
            case PENDING_REVIEW -> Set.of(TicketStatus.ASSIGNED, TicketStatus.REJECTED).contains(toStatus);
            case ASSIGNED -> Set.of(TicketStatus.PROCESSING, TicketStatus.RETURNED).contains(toStatus);
            case PROCESSING -> toStatus == TicketStatus.WAITING_CONFIRM;
            case WAITING_CONFIRM -> Set.of(TicketStatus.COMPLETED, TicketStatus.PROCESSING).contains(toStatus);
            case RETURNED -> Set.of(TicketStatus.ASSIGNED, TicketStatus.REJECTED).contains(toStatus);
            case REJECTED -> toStatus == TicketStatus.PENDING_REVIEW;
            case COMPLETED -> false;
        };
        if (!allowed) {
            log.warn("ticket transition denied, scenario=ticket-state-machine, ticketId={}, action={}, fromStatus={}, toStatus={}, studentId={}, workerId={}",
                    ticket.getId(), action, fromStatus, toStatus.name(), ticket.getStudentId(), ticket.getAssignedWorkerId());
            throw new BusinessException(ErrorCode.TICKET_STATUS_INVALID);
        }
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
                .notIn(RepairTicket::getStatus, TicketStatus.COMPLETED.name(), TicketStatus.REJECTED.name(), TicketStatus.RETURNED.name());
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

    private boolean isSlaOverdue(RepairTicket ticket) {
        if (ticket.getSlaDeadlineAt() == null) {
            return false;
        }
        if (Set.of(TicketStatus.COMPLETED.name(), TicketStatus.REJECTED.name()).contains(ticket.getStatus())) {
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
