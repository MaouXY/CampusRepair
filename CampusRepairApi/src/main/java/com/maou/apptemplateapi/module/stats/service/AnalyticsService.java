package com.maou.apptemplateapi.module.stats.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.maou.apptemplateapi.common.enums.UserRole;
import com.maou.apptemplateapi.common.exception.BusinessException;
import com.maou.apptemplateapi.common.exception.ErrorCode;
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
import com.maou.apptemplateapi.module.dispatch.entity.WorkerProfile;
import com.maou.apptemplateapi.module.dispatch.mapper.WorkerProfileMapper;
import com.maou.apptemplateapi.module.stats.dto.HotspotsResponse;
import com.maou.apptemplateapi.module.stats.dto.MonthlyReportResponse;
import com.maou.apptemplateapi.module.stats.dto.StatsItemResponse;
import com.maou.apptemplateapi.module.stats.dto.WorkerPerformanceResponse;
import com.maou.apptemplateapi.module.ticket.entity.RepairEvaluation;
import com.maou.apptemplateapi.module.ticket.entity.RepairTicket;
import com.maou.apptemplateapi.module.ticket.entity.RepairTicketFlow;
import com.maou.apptemplateapi.module.ticket.enums.TicketStatus;
import com.maou.apptemplateapi.module.ticket.mapper.RepairEvaluationMapper;
import com.maou.apptemplateapi.module.ticket.mapper.RepairTicketFlowMapper;
import com.maou.apptemplateapi.module.ticket.mapper.RepairTicketMapper;
import com.maou.apptemplateapi.module.ticket.state.TicketStateMachine;
import com.maou.apptemplateapi.module.user.entity.UserAccount;
import com.maou.apptemplateapi.module.user.mapper.UserAccountMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 第三阶段 V3 数据治理：维修员绩效与满意度分析、高发故障与高发地点统计、月度维修报告（含 AI 运营建议）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnalyticsService {

    private static final int DEFAULT_DAYS = 30;
    private static final int MAX_DAYS = 365;
    private static final int GOOD_SCORE = 4;
    private static final int DEFAULT_TOP = 5;

    private final RepairTicketMapper ticketMapper;
    private final RepairEvaluationMapper evaluationMapper;
    private final RepairTicketFlowMapper flowMapper;
    private final RepairCategoryMapper categoryMapper;
    private final RepairLocationMapper locationMapper;
    private final UserAccountMapper userAccountMapper;
    private final WorkerProfileMapper workerProfileMapper;
    private final AiClient aiClient;
    private final AiTokenEstimator tokenEstimator;
    private final AiTokenMonitor tokenMonitor;

    public List<WorkerPerformanceResponse> workerPerformance(Integer days) {
        requireAdmin("admin-stats-worker-performance");
        LocalDateTime to = LocalDateTime.now();
        LocalDateTime from = to.minusDays(normalizeDays(days));
        List<RepairTicket> tickets = loadTickets(from, to);
        List<RepairEvaluation> evaluations = loadEvaluations(from, to);
        List<RepairTicketFlow> flows = flowMapper.selectList(new LambdaQueryWrapper<RepairTicketFlow>()
                .ge(RepairTicketFlow::getCreatedAt, from)
                .le(RepairTicketFlow::getCreatedAt, to));
        List<RepairTicket> allTickets = ticketMapper.selectList(new LambdaQueryWrapper<RepairTicket>()
                .eq(RepairTicket::getDeleted, 0));
        return buildPerformance(tickets, evaluations, flows, allTickets);
    }

    public HotspotsResponse hotspots(Integer days, Integer limit) {
        requireAdmin("admin-stats-hotspots");
        LocalDateTime to = LocalDateTime.now();
        LocalDateTime from = to.minusDays(normalizeDays(days));
        int top = limit == null || limit <= 0 ? DEFAULT_TOP : Math.min(limit, 20);
        List<RepairTicket> tickets = loadTickets(from, to);
        List<StatsItemResponse> categories = topBy(tickets, RepairTicket::getCategoryId, this::categoryNames, top);
        List<StatsItemResponse> locations = topBy(tickets, RepairTicket::getLocationId, this::locationNames, top);
        log.info("hotspots computed, scenario=admin-stats-hotspots, days={}, totalTickets={}, topCategory={}, topLocation={}",
                normalizeDays(days), tickets.size(),
                categories.isEmpty() ? null : categories.get(0).label(),
                locations.isEmpty() ? null : locations.get(0).label());
        return new HotspotsResponse(normalizeDays(days), tickets.size(), categories, locations);
    }

    public MonthlyReportResponse monthlyReport(String month) {
        requireAdmin("admin-stats-monthly-report");
        YearMonth yearMonth = parseMonth(month);
        LocalDateTime from = yearMonth.atDay(1).atStartOfDay();
        LocalDateTime to = yearMonth.atEndOfMonth().atTime(23, 59, 59);

        List<RepairTicket> tickets = loadTickets(from, to);
        List<RepairEvaluation> evaluations = loadEvaluations(from, to);
        List<RepairTicketFlow> flows = flowMapper.selectList(new LambdaQueryWrapper<RepairTicketFlow>()
                .ge(RepairTicketFlow::getCreatedAt, from)
                .le(RepairTicketFlow::getCreatedAt, to));

        long createdCount = tickets.stream().filter(ticket -> inRange(ticket.getCreatedAt(), from, to)).count();
        List<RepairTicket> completed = tickets.stream()
                .filter(ticket -> TicketStatus.COMPLETED.name().equals(ticket.getStatus()))
                .filter(ticket -> inRange(ticket.getProcessedAt(), from, to))
                .toList();
        long rejectedCount = tickets.stream()
                .filter(ticket -> TicketStatus.REJECTED.name().equals(ticket.getStatus()))
                .filter(ticket -> inRange(ticket.getUpdatedAt(), from, to) || inRange(ticket.getCreatedAt(), from, to))
                .count();
        BigDecimal avgProcessMinutes = averageMinutes(completed);
        BigDecimal avgScore = averageScore(evaluations);
        BigDecimal goodRate = goodRate(evaluations);
        BigDecimal overdueRate = overdueRate(completed);

        List<StatsItemResponse> topCategories = topBy(tickets, RepairTicket::getCategoryId, this::categoryNames, DEFAULT_TOP);
        List<StatsItemResponse> topLocations = topBy(tickets, RepairTicket::getLocationId, this::locationNames, DEFAULT_TOP);
        List<WorkerPerformanceResponse> topWorkers = buildPerformance(tickets, evaluations, flows,
                        ticketMapper.selectList(new LambdaQueryWrapper<RepairTicket>().eq(RepairTicket::getDeleted, 0)))
                .stream()
                .limit(DEFAULT_TOP)
                .toList();

        MonthlyReportResponse metrics = new MonthlyReportResponse(yearMonth.toString(), createdCount, completed.size(),
                rejectedCount, evaluations.size(), avgProcessMinutes, avgScore, goodRate, overdueRate,
                topCategories, topLocations, topWorkers, null, false);
        AiSummary summary = buildAiSummary(yearMonth.toString(), metrics);
        log.info("monthly report generated, scenario=admin-stats-monthly-report, month={}, createdCount={}, completedCount={}, rejectedCount={}, avgProcessMinutes={}, avgScore={}, goodRate={}, overdueRate={}, aiDegraded={}",
                yearMonth, createdCount, completed.size(), rejectedCount, avgProcessMinutes, avgScore, goodRate,
                overdueRate, summary.degraded());
        return new MonthlyReportResponse(metrics.month(), metrics.createdCount(), metrics.completedCount(),
                metrics.rejectedCount(), metrics.evaluatingScoreCount(), metrics.avgProcessMinutes(), metrics.avgScore(),
                metrics.goodRate(), metrics.overdueRate(), metrics.topCategories(), metrics.topLocations(),
                metrics.topWorkers(), summary.text(), summary.degraded());
    }

    private List<WorkerPerformanceResponse> buildPerformance(List<RepairTicket> tickets,
                                                            List<RepairEvaluation> evaluations,
                                                            List<RepairTicketFlow> flows,
                                                            List<RepairTicket> allTickets) {
        Map<Long, UserAccount> workers = userAccountMapper.selectList(new LambdaQueryWrapper<UserAccount>()
                        .eq(UserAccount::getRoleCode, UserRole.WORKER.name())
                        .eq(UserAccount::getDeleted, 0))
                .stream()
                .collect(Collectors.toMap(UserAccount::getId, Function.identity(), (left, right) -> left));
        Map<Long, WorkerProfile> profiles = workerProfileMapper.selectList(new LambdaQueryWrapper<WorkerProfile>()
                        .eq(WorkerProfile::getDeleted, 0))
                .stream()
                .collect(Collectors.toMap(WorkerProfile::getWorkerId, Function.identity(), (left, right) -> left));

        Map<Long, List<RepairTicket>> byWorker = tickets.stream()
                .filter(ticket -> ticket.getAssignedWorkerId() != null)
                .collect(Collectors.groupingBy(RepairTicket::getAssignedWorkerId, LinkedHashMap::new, Collectors.toList()));

        List<WorkerPerformanceResponse> responses = new ArrayList<>();
        for (Map.Entry<Long, List<RepairTicket>> entry : byWorker.entrySet()) {
            Long workerId = entry.getKey();
            List<RepairTicket> workerTickets = entry.getValue();
            List<RepairTicket> completed = workerTickets.stream()
                    .filter(ticket -> TicketStatus.COMPLETED.name().equals(ticket.getStatus()))
                    .toList();
            List<RepairEvaluation> workerEvaluations = evaluations.stream()
                    .filter(evaluation -> Objects.equals(evaluation.getWorkerId(), workerId))
                    .toList();
            long returnCount = countFlow(flows, workerId, "WORKER_RETURN");
            long reworkCount = countFlow(flows, workerId, "REQUEST_REWORK");
            List<RepairTicket> activeTickets = allTickets.stream()
                    .filter(ticket -> Objects.equals(ticket.getAssignedWorkerId(), workerId))
                    .filter(ticket -> TicketStateMachine.isActive(parseStatus(ticket.getStatus())))
                    .toList();
            long overdueCount = activeTickets.stream()
                    .filter(ticket -> ticket.getSlaDeadlineAt() != null && ticket.getSlaDeadlineAt().isBefore(LocalDateTime.now()))
                    .count();
            UserAccount worker = workers.get(workerId);
            WorkerProfile profile = profiles.get(workerId);
            responses.add(new WorkerPerformanceResponse(workerId,
                    worker == null ? String.valueOf(workerId) : worker.getRealName(),
                    profile == null ? null : profile.getDepartmentName(),
                    completed.size(), averageMinutes(completed), averageScore(workerEvaluations),
                    goodRate(workerEvaluations), workerEvaluations.size(), returnCount, reworkCount,
                    activeTickets.size(), overdueCount));
        }
        responses.sort(Comparator.comparingLong(WorkerPerformanceResponse::completedCount).reversed()
                .thenComparing(WorkerPerformanceResponse::avgScore, Comparator.reverseOrder())
                .thenComparing(WorkerPerformanceResponse::workerId));
        return responses;
    }

    private AiSummary buildAiSummary(String month, MonthlyReportResponse metrics) {
        String facts = """
                月份：%s
                新增工单：%d，已完成：%d，已驳回：%d
                平均处理时长：%s 分钟
                平均满意度：%s 分（好评率 %s%%）
                超时率：%s%%
                高发故障：%s
                高发地点：%s
                维修员完成量：%s
                """.formatted(month, metrics.createdCount(), metrics.completedCount(), metrics.rejectedCount(),
                metrics.avgProcessMinutes(), metrics.avgScore(), percent(metrics.goodRate()),
                percent(metrics.overdueRate()),
                joinItems(metrics.topCategories()), joinItems(metrics.topLocations()),
                metrics.topWorkers().stream()
                        .map(worker -> "%s(%d单)".formatted(worker.workerName(), worker.completedCount()))
                        .collect(Collectors.joining("、")));
        String systemPrompt = """
                你是校园后勤维修运营分析师。请根据月度统计数据，输出 150-250 字的运营分析建议，
                内容包括：整体运行结论、需要关注的异常指标、下个月可执行的改进动作。
                要求：只输出纯文本段落，不要 Markdown，不要编造数据中没有的内容。
                """;
        try {
            long promptTokens = tokenEstimator.estimate(systemPrompt) + tokenEstimator.estimate(facts);
            DegradeDecision decision = tokenMonitor.decideForRequest(promptTokens, 0, 0);
            if (decision.level().isRuleOnly()) {
                log.warn("monthly report ai summary skipped by token budget, scenario=admin-stats-monthly-report, month={}, reason={}",
                        month, decision.reasonText());
                return new AiSummary(templateSummary(metrics), true);
            }
            AiCompletion completion = aiClient.complete(systemPrompt, facts);
            tokenMonitor.recordUsage(completion.usage(), null, "admin-stats-monthly-report", decision.level());
            String text = completion.text();
            if (!StringUtils.hasText(text)) {
                return new AiSummary(templateSummary(metrics), true);
            }
            log.info("monthly report ai summary generated, scenario=admin-stats-monthly-report, month={}, degradeLevel={}, totalTokens={}",
                    month, decision.level(), completion.usage().total());
            return new AiSummary(text.trim(), decision.degraded());
        } catch (RuntimeException exception) {
            log.error("monthly report ai summary failed, scenario=admin-stats-monthly-report, month={}, fallback=rule-template",
                    month, exception);
            return new AiSummary(templateSummary(metrics), true);
        }
    }

    private String templateSummary(MonthlyReportResponse metrics) {
        String hottest = metrics.topCategories().isEmpty()
                ? "暂无高发故障"
                : "高发故障为 %s".formatted(joinItems(metrics.topCategories()));
        String hottestLocation = metrics.topLocations().isEmpty()
                ? "暂无高发地点"
                : "高发地点为 %s".formatted(joinItems(metrics.topLocations()));
        return "本月新增工单 %d 单、完成 %d 单，平均处理时长 %s 分钟，平均满意度 %s 分，好评率 %s%%，超时率 %s%%。%s，%s。建议：一是针对高频故障补充备件与知识库条目，缩短现场判断时间；二是对超时较多的区域增加巡检频次；三是结合维修员绩效数据做派单倾斜，让高好评、低超时的维修员承接更多紧急工单。"
                .formatted(metrics.createdCount(), metrics.completedCount(), metrics.avgProcessMinutes(),
                        metrics.avgScore(), percent(metrics.goodRate()), percent(metrics.overdueRate()),
                        hottest, hottestLocation);
    }

    private List<StatsItemResponse> topBy(List<RepairTicket> tickets,
                                         Function<RepairTicket, Long> keyExtractor,
                                         Function<List<Long>, Map<Long, String>> nameLoader,
                                         int limit) {
        Map<Long, Long> counts = tickets.stream()
                .map(keyExtractor)
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        if (counts.isEmpty()) {
            return List.of();
        }
        Map<Long, String> names = nameLoader.apply(new ArrayList<>(counts.keySet()));
        return counts.entrySet().stream()
                .sorted(Map.Entry.<Long, Long>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(limit)
                .map(entry -> new StatsItemResponse(names.getOrDefault(entry.getKey(), "未知"), entry.getValue()))
                .toList();
    }

    private Map<Long, String> categoryNames(List<Long> ids) {
        return categoryMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(RepairCategory::getId, RepairCategory::getName));
    }

    private Map<Long, String> locationNames(List<Long> ids) {
        return locationMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(RepairLocation::getId, RepairLocation::getName));
    }

    private List<RepairTicket> loadTickets(LocalDateTime from, LocalDateTime to) {
        return ticketMapper.selectList(new LambdaQueryWrapper<RepairTicket>()
                .eq(RepairTicket::getDeleted, 0)
                .and(wrapper -> wrapper
                        .ge(RepairTicket::getCreatedAt, from).le(RepairTicket::getCreatedAt, to)
                        .or()
                        .ge(RepairTicket::getProcessedAt, from).le(RepairTicket::getProcessedAt, to)));
    }

    private List<RepairEvaluation> loadEvaluations(LocalDateTime from, LocalDateTime to) {
        return evaluationMapper.selectList(new LambdaQueryWrapper<RepairEvaluation>()
                .ge(RepairEvaluation::getCreatedAt, from)
                .le(RepairEvaluation::getCreatedAt, to));
    }

    private long countFlow(List<RepairTicketFlow> flows, Long workerId, String action) {
        return flows.stream()
                .filter(flow -> Objects.equals(flow.getOperatorId(), workerId))
                .filter(flow -> action.equals(flow.getAction()))
                .count();
    }

    private BigDecimal averageMinutes(List<RepairTicket> tickets) {
        List<Long> minutes = tickets.stream()
                .filter(ticket -> ticket.getAssignedAt() != null && ticket.getProcessedAt() != null)
                .map(ticket -> Math.max(Duration.between(ticket.getAssignedAt(), ticket.getProcessedAt()).toMinutes(), 0))
                .toList();
        if (minutes.isEmpty()) {
            return BigDecimal.ZERO;
        }
        double average = minutes.stream().mapToLong(Long::longValue).average().orElse(0);
        return BigDecimal.valueOf(average).setScale(1, RoundingMode.HALF_UP);
    }

    private BigDecimal averageScore(List<RepairEvaluation> evaluations) {
        if (evaluations.isEmpty()) {
            return BigDecimal.ZERO;
        }
        double average = evaluations.stream().mapToInt(RepairEvaluation::getScore).average().orElse(0);
        return BigDecimal.valueOf(average).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal goodRate(List<RepairEvaluation> evaluations) {
        if (evaluations.isEmpty()) {
            return BigDecimal.ZERO;
        }
        long good = evaluations.stream().filter(evaluation -> evaluation.getScore() >= GOOD_SCORE).count();
        return BigDecimal.valueOf((double) good / evaluations.size()).setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal overdueRate(List<RepairTicket> completed) {
        List<RepairTicket> withSla = completed.stream()
                .filter(ticket -> ticket.getSlaDeadlineAt() != null && ticket.getProcessedAt() != null)
                .toList();
        if (withSla.isEmpty()) {
            return BigDecimal.ZERO;
        }
        long overdue = withSla.stream()
                .filter(ticket -> ticket.getProcessedAt().isAfter(ticket.getSlaDeadlineAt()))
                .count();
        return BigDecimal.valueOf((double) overdue / withSla.size()).setScale(4, RoundingMode.HALF_UP);
    }

    private boolean inRange(LocalDateTime value, LocalDateTime from, LocalDateTime to) {
        return value != null && !value.isBefore(from) && !value.isAfter(to);
    }

    private TicketStatus parseStatus(String status) {
        try {
            return TicketStatus.valueOf(status);
        } catch (RuntimeException exception) {
            log.warn("analytics ticket status invalid, scenario=admin-stats, status={}", status);
            return TicketStatus.PENDING_REVIEW;
        }
    }

    private YearMonth parseMonth(String month) {
        if (!StringUtils.hasText(month)) {
            return YearMonth.from(LocalDate.now());
        }
        try {
            return YearMonth.parse(month.trim());
        } catch (RuntimeException exception) {
            log.warn("monthly report month invalid, scenario=admin-stats-monthly-report, month={}, fallback=current-month", month);
            return YearMonth.from(LocalDate.now());
        }
    }

    private int normalizeDays(Integer days) {
        if (days == null || days <= 0) {
            return DEFAULT_DAYS;
        }
        return Math.min(days, MAX_DAYS);
    }

    private String percent(BigDecimal ratio) {
        return ratio.multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP).toPlainString();
    }

    private String joinItems(List<StatsItemResponse> items) {
        if (items.isEmpty()) {
            return "暂无数据";
        }
        return items.stream()
                .map(item -> "%s(%d单)".formatted(item.label(), item.value()))
                .collect(Collectors.joining("、"));
    }

    private void requireAdmin(String scenario) {
        CurrentUser currentUser = CurrentUserProvider.require();
        if (currentUser.getRole() != UserRole.ADMIN) {
            log.warn("analytics role denied, scenario={}, userId={}, roleCode={}",
                    scenario, currentUser.getId(), currentUser.getRoleCode());
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }

    private record AiSummary(String text, boolean degraded) {
    }
}
