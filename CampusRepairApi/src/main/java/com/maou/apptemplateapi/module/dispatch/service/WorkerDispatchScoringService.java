package com.maou.apptemplateapi.module.dispatch.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maou.apptemplateapi.common.enums.UserRole;
import com.maou.apptemplateapi.module.base.entity.RepairCategory;
import com.maou.apptemplateapi.module.base.mapper.RepairCategoryMapper;
import com.maou.apptemplateapi.module.dispatch.dto.DispatchCandidateResponse;
import com.maou.apptemplateapi.module.dispatch.entity.WorkerDispatchScoreSnapshot;
import com.maou.apptemplateapi.module.dispatch.entity.WorkerProfile;
import com.maou.apptemplateapi.module.dispatch.mapper.WorkerDispatchScoreSnapshotMapper;
import com.maou.apptemplateapi.module.dispatch.mapper.WorkerProfileMapper;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class WorkerDispatchScoringService {

    private static final int TOP_SIZE = 3;
    private static final BigDecimal MAX_SKILL_SCORE = BigDecimal.valueOf(40);
    private static final BigDecimal MAX_DEPARTMENT_SCORE = BigDecimal.valueOf(20);
    private static final BigDecimal MAX_WORKLOAD_SCORE = BigDecimal.valueOf(20);
    private static final BigDecimal MAX_QUALITY_SCORE = BigDecimal.valueOf(20);
    private static final BigDecimal MAX_PENALTY_SCORE = BigDecimal.valueOf(15);
    private static final int DEFAULT_MAX_ACTIVE_ORDERS = 5;
    private static final Map<String, String> CATEGORY_DEPARTMENT = Map.of(
            "水电维修", "水电组",
            "网络设备", "网络组",
            "空调照明", "空调照明组",
            "门窗家具", "综合维修组",
            "公共设施", "综合维修组"
    );
    private static final Map<String, List<String>> CATEGORY_SKILLS = Map.of(
            "水电维修", List.of("水电"),
            "网络设备", List.of("网络"),
            "空调照明", List.of("空调", "照明"),
            "门窗家具", List.of("门窗"),
            "公共设施", List.of("公共设施")
    );

    private final UserAccountMapper userAccountMapper;
    private final WorkerProfileMapper workerProfileMapper;
    private final RepairTicketMapper ticketMapper;
    private final RepairCategoryMapper categoryMapper;
    private final RepairEvaluationMapper evaluationMapper;
    private final RepairTicketFlowMapper flowMapper;
    private final WorkerDispatchScoreSnapshotMapper snapshotMapper;
    private final ObjectMapper objectMapper;

    public List<DispatchCandidateResponse> topCandidates(RepairTicket ticket) {
        return plan(ticket).candidates();
    }

    public DispatchPlan plan(RepairTicket ticket) {
        RepairCategory category = categoryMapper.selectById(ticket.getCategoryId());
        String categoryName = category == null ? null : category.getName();
        List<String> requiredSkills = requiredSkills(categoryName, ticket.getDescription());
        String expectedDepartment = expectedDepartment(categoryName, requiredSkills);
        return new DispatchPlan(categoryName, requiredSkills, expectedDepartment,
                rankCandidates(ticket, requiredSkills, expectedDepartment));
    }

    private List<DispatchCandidateResponse> rankCandidates(RepairTicket ticket,
                                                           List<String> requiredSkills,
                                                           String expectedDepartment) {
        List<UserAccount> workers = userAccountMapper.selectList(new LambdaQueryWrapper<UserAccount>()
                .eq(UserAccount::getRoleCode, UserRole.WORKER.name())
                .eq(UserAccount::getEnabled, 1)
                .eq(UserAccount::getDeleted, 0)
                .orderByAsc(UserAccount::getId));
        Map<Long, WorkerProfile> profiles = workerProfileMapper.selectList(new LambdaQueryWrapper<WorkerProfile>()
                        .eq(WorkerProfile::getDeleted, 0))
                .stream()
                .collect(Collectors.toMap(WorkerProfile::getWorkerId, Function.identity(), (left, right) -> left));

        return workers.stream()
                .map(worker -> score(worker, profiles.get(worker.getId()), ticket, requiredSkills, expectedDepartment))
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(DispatchCandidateResponse::totalScore).reversed()
                        .thenComparing(DispatchCandidateResponse::activeOrderCount)
                        .thenComparing(DispatchCandidateResponse::workerId))
                .limit(TOP_SIZE)
                .toList();
    }

    public List<DispatchCandidateResponse> loadSnapshotCandidates(Long analysisId, Long ticketId) {
        if (analysisId == null) {
            return List.of();
        }
        return snapshotMapper.selectList(new LambdaQueryWrapper<WorkerDispatchScoreSnapshot>()
                        .eq(WorkerDispatchScoreSnapshot::getAiAnalysisId, analysisId)
                        .orderByDesc(WorkerDispatchScoreSnapshot::getTotalScore)
                        .orderByAsc(WorkerDispatchScoreSnapshot::getId))
                .stream()
                .map(snapshot -> new DispatchCandidateResponse(
                        snapshot.getWorkerId(),
                        snapshot.getWorkerName(),
                        snapshot.getDepartmentName(),
                        readSkillTags(snapshot.getSkillTags(), snapshot.getWorkerId()),
                        snapshot.getActiveOrderCount(),
                        snapshot.getMaxActiveOrders(),
                        snapshot.getSkillScore(),
                        snapshot.getDepartmentScore(),
                        snapshot.getWorkloadScore(),
                        snapshot.getQualityScore(),
                        snapshot.getPenaltyScore(),
                        snapshot.getTotalScore(),
                        snapshot.getRuleReason(),
                        snapshot.getAiRecommended() != null && snapshot.getAiRecommended() == 1
                ))
                .toList();
    }

    public List<DispatchCandidateResponse> markRecommended(List<DispatchCandidateResponse> candidates, Long recommendedWorkerId) {
        if (candidates.isEmpty()) {
            return candidates;
        }
        Long target = recommendedWorkerId == null ? candidates.get(0).workerId() : recommendedWorkerId;
        return candidates.stream()
                .map(candidate -> new DispatchCandidateResponse(candidate.workerId(), candidate.workerName(),
                        candidate.departmentName(), candidate.skillTags(), candidate.activeOrderCount(),
                        candidate.maxActiveOrders(), candidate.skillScore(), candidate.departmentScore(),
                        candidate.workloadScore(), candidate.qualityScore(), candidate.penaltyScore(),
                        candidate.totalScore(), candidate.ruleReason(), Objects.equals(candidate.workerId(), target)))
                .toList();
    }

    public record DispatchPlan(
            String categoryName,
            List<String> requiredSkills,
            String expectedDepartment,
            List<DispatchCandidateResponse> candidates
    ) {
    }

    public void saveSnapshots(Long ticketId, Long analysisId, List<DispatchCandidateResponse> candidates, Long recommendedWorkerId) {
        for (DispatchCandidateResponse candidate : candidates) {
            WorkerDispatchScoreSnapshot snapshot = new WorkerDispatchScoreSnapshot();
            snapshot.setTicketId(ticketId);
            snapshot.setAiAnalysisId(analysisId);
            snapshot.setWorkerId(candidate.workerId());
            snapshot.setWorkerName(candidate.workerName());
            snapshot.setDepartmentName(candidate.departmentName());
            snapshot.setSkillTags(writeJson(candidate.skillTags(), "dispatch-score-snapshot", ticketId, candidate.workerId()));
            snapshot.setActiveOrderCount(candidate.activeOrderCount());
            snapshot.setMaxActiveOrders(candidate.maxActiveOrders());
            snapshot.setSkillScore(candidate.skillScore());
            snapshot.setDepartmentScore(candidate.departmentScore());
            snapshot.setWorkloadScore(candidate.workloadScore());
            snapshot.setQualityScore(candidate.qualityScore());
            snapshot.setPenaltyScore(candidate.penaltyScore());
            snapshot.setTotalScore(candidate.totalScore());
            snapshot.setRuleReason(candidate.ruleReason());
            snapshot.setAiRecommended(Objects.equals(candidate.workerId(), recommendedWorkerId) ? 1 : 0);
            snapshotMapper.insert(snapshot);
        }
    }

    private DispatchCandidateResponse score(UserAccount worker,
                                            WorkerProfile profile,
                                            RepairTicket ticket,
                                            List<String> requiredSkills,
                                            String expectedDepartment) {
        if (profile != null && profile.getDispatchEnabled() != null && profile.getDispatchEnabled() != 1) {
            return null;
        }
        String department = profile == null ? "综合维修组" : profile.getDepartmentName();
        List<String> skills = profile == null ? List.of() : readSkillTags(profile.getSkillTags(), worker.getId());
        int maxActiveOrders = profile == null || profile.getMaxActiveOrders() == null || profile.getMaxActiveOrders() <= 0
                ? DEFAULT_MAX_ACTIVE_ORDERS
                : profile.getMaxActiveOrders();
        int activeOrders = activeOrderCount(worker.getId());
        BigDecimal skillScore = skillScore(skills, requiredSkills);
        BigDecimal departmentScore = StringUtils.hasText(expectedDepartment) && expectedDepartment.equals(department)
                ? MAX_DEPARTMENT_SCORE
                : BigDecimal.ZERO;
        BigDecimal workloadScore = workloadScore(activeOrders, maxActiveOrders);
        BigDecimal qualityScore = qualityScore(worker.getId());
        BigDecimal penaltyScore = penaltyScore(worker.getId());
        BigDecimal total = skillScore.add(departmentScore).add(workloadScore).add(qualityScore).subtract(penaltyScore);
        if (total.compareTo(BigDecimal.ZERO) < 0) {
            total = BigDecimal.ZERO;
        }
        String reason = "技能匹配%s，部门%s，当前活跃工单%d/%d，历史质量分%s，返工/退回扣分%s"
                .formatted(skillScore, departmentScore.compareTo(BigDecimal.ZERO) > 0 ? "匹配" : "未命中",
                        activeOrders, maxActiveOrders, qualityScore, penaltyScore);
        return new DispatchCandidateResponse(worker.getId(), worker.getRealName(), department, skills, activeOrders,
                maxActiveOrders, scale(skillScore), scale(departmentScore), scale(workloadScore), scale(qualityScore),
                scale(penaltyScore), scale(total), reason, false);
    }

    private List<String> requiredSkills(String categoryName, String description) {
        LinkedHashSet<String> skills = new LinkedHashSet<>();
        if (StringUtils.hasText(categoryName)) {
            skills.addAll(CATEGORY_SKILLS.getOrDefault(categoryName, List.of(categoryName.replace("维修", ""))));
        }
        String text = description == null ? "" : description;
        if (text.contains("漏水") || text.contains("水龙头") || text.contains("插座") || text.contains("用电")) {
            skills.add("水电");
        }
        if (text.contains("网络") || text.contains("网线") || text.contains("投影")) {
            skills.add("网络");
        }
        if (text.contains("空调")) {
            skills.add("空调");
        }
        if (text.contains("灯") || text.contains("照明")) {
            skills.add("照明");
        }
        if (text.contains("门") || text.contains("窗") || text.contains("桌") || text.contains("椅")) {
            skills.add("门窗");
        }
        if (text.contains("公共") || text.contains("洗衣") || text.contains("饮水")) {
            skills.add("公共设施");
        }
        return skills.isEmpty() ? List.of("公共设施") : List.copyOf(skills);
    }

    private String expectedDepartment(String categoryName, List<String> requiredSkills) {
        if (StringUtils.hasText(categoryName) && CATEGORY_DEPARTMENT.containsKey(categoryName)) {
            return CATEGORY_DEPARTMENT.get(categoryName);
        }
        if (requiredSkills.contains("水电")) {
            return "水电组";
        }
        if (requiredSkills.contains("网络")) {
            return "网络组";
        }
        if (requiredSkills.contains("空调") || requiredSkills.contains("照明")) {
            return "空调照明组";
        }
        return "综合维修组";
    }

    private BigDecimal skillScore(List<String> workerSkills, List<String> requiredSkills) {
        if (requiredSkills.isEmpty()) {
            return BigDecimal.ZERO;
        }
        long matches = requiredSkills.stream().filter(workerSkills::contains).count();
        if (matches == 0) {
            return BigDecimal.ZERO;
        }
        return MAX_SKILL_SCORE.multiply(BigDecimal.valueOf(matches))
                .divide(BigDecimal.valueOf(requiredSkills.size()), 2, RoundingMode.HALF_UP);
    }

    private BigDecimal workloadScore(int activeOrders, int maxActiveOrders) {
        if (maxActiveOrders <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal loadRate = BigDecimal.valueOf(activeOrders)
                .divide(BigDecimal.valueOf(maxActiveOrders), 4, RoundingMode.HALF_UP);
        BigDecimal score = MAX_WORKLOAD_SCORE.multiply(BigDecimal.ONE.subtract(loadRate));
        return score.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : score;
    }

    private BigDecimal qualityScore(Long workerId) {
        List<RepairEvaluation> evaluations = evaluationMapper.selectList(new LambdaQueryWrapper<RepairEvaluation>()
                .eq(RepairEvaluation::getWorkerId, workerId));
        if (evaluations.isEmpty()) {
            return BigDecimal.valueOf(12);
        }
        double average = evaluations.stream().mapToInt(RepairEvaluation::getScore).average().orElse(3.0);
        return MAX_QUALITY_SCORE.multiply(BigDecimal.valueOf(average))
                .divide(BigDecimal.valueOf(5), 2, RoundingMode.HALF_UP);
    }

    private BigDecimal penaltyScore(Long workerId) {
        Long returnCount = flowMapper.selectCount(new LambdaQueryWrapper<RepairTicketFlow>()
                .eq(RepairTicketFlow::getOperatorId, workerId)
                .eq(RepairTicketFlow::getOperatorRole, UserRole.WORKER.name())
                .eq(RepairTicketFlow::getAction, "WORKER_RETURN"));
        Long reworkCount = flowMapper.selectCount(new LambdaQueryWrapper<RepairTicketFlow>()
                .eq(RepairTicketFlow::getOperatorId, workerId)
                .eq(RepairTicketFlow::getAction, "REQUEST_REWORK"));
        long totalPenaltyEvents = (returnCount == null ? 0 : returnCount) + (reworkCount == null ? 0 : reworkCount);
        return BigDecimal.valueOf(Math.min(totalPenaltyEvents * 3, MAX_PENALTY_SCORE.intValue()));
    }

    private int activeOrderCount(Long workerId) {
        Long count = ticketMapper.selectCount(new LambdaQueryWrapper<RepairTicket>()
                .eq(RepairTicket::getAssignedWorkerId, workerId)
                .in(RepairTicket::getStatus, TicketStateMachine.activeStatusNames())
                .eq(RepairTicket::getDeleted, 0));
        return count == null ? 0 : count.intValue();
    }

    private List<String> readSkillTags(String rawJson, Long workerId) {
        if (!StringUtils.hasText(rawJson)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(rawJson, new TypeReference<List<String>>() {
                    }).stream()
                    .filter(StringUtils::hasText)
                    .distinct()
                    .toList();
        } catch (JsonProcessingException exception) {
            log.error("worker skill tags json invalid, scenario=worker-dispatch-score, workerId={}, rawJson={}",
                    workerId, rawJson, exception);
            return List.of();
        }
    }

    private String writeJson(List<String> value, String scenario, Long ticketId, Long workerId) {
        try {
            return objectMapper.writeValueAsString(value == null ? List.of() : value);
        } catch (JsonProcessingException exception) {
            log.error("worker dispatch snapshot json write failed, scenario={}, ticketId={}, workerId={}",
                    scenario, ticketId, workerId, exception);
            return "[]";
        }
    }

    private BigDecimal scale(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
