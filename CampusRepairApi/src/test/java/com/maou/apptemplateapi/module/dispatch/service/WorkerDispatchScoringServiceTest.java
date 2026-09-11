package com.maou.apptemplateapi.module.dispatch.service;

import com.maou.apptemplateapi.module.dispatch.dto.DispatchCandidateResponse;
import com.maou.apptemplateapi.module.ticket.entity.RepairEvaluation;
import com.maou.apptemplateapi.module.ticket.entity.RepairTicket;
import com.maou.apptemplateapi.module.ticket.entity.RepairTicketFlow;
import com.maou.apptemplateapi.module.ticket.enums.TicketStatus;
import com.maou.apptemplateapi.module.ticket.mapper.RepairEvaluationMapper;
import com.maou.apptemplateapi.module.ticket.mapper.RepairTicketFlowMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@SpringBootTest
class WorkerDispatchScoringServiceTest {

    private static final long WATER_GROUP_WORKER_ID = 10005L;
    private static final long DISPATCH_DISABLED_WORKER_ID = 10006L;
    private static final long SAME_SKILL_WORKER_ID = 10007L;

    @Autowired
    private WorkerDispatchScoringService scoringService;

    @Autowired
    private RepairEvaluationMapper evaluationMapper;

    @Autowired
    private RepairTicketFlowMapper flowMapper;

    @Test
    void shouldRankSkillMatchedWorkerFirst() {
        List<DispatchCandidateResponse> candidates = scoringService.topCandidates(waterLeakTicket());

        assertThat(candidates).isNotEmpty();
        assertThat(candidates.get(0).workerId()).as(candidates.toString()).isEqualTo(WATER_GROUP_WORKER_ID);
        assertThat(candidates.get(0).skillTags()).contains("水电");
        assertThat(candidates.get(0).totalScore())
                .isGreaterThan(candidates.get(candidates.size() - 1).totalScore());
    }

    @Test
    void shouldExcludeWorkerWithDispatchDisabled() {
        List<DispatchCandidateResponse> candidates = scoringService.topCandidates(waterLeakTicket());

        assertThat(candidates).extracting(DispatchCandidateResponse::workerId)
                .doesNotContain(DISPATCH_DISABLED_WORKER_ID);
    }

    @Test
    void shouldRankNetworkSkillWorkerFirstForNetworkTicket() {
        RepairTicket ticket = new RepairTicket();
        ticket.setId(90002L);
        ticket.setCategoryId(20003L);
        ticket.setDescription("宿舍网线接口松动，网络不通。");

        List<DispatchCandidateResponse> candidates = scoringService.topCandidates(ticket);

        assertThat(candidates).isNotEmpty();
        assertThat(candidates.get(0).workerId()).isEqualTo(10002L);
        assertThat(candidates.get(0).skillTags()).contains("网络");
    }

    @Test
    @Transactional
    void shouldRankHigherQualityWorkerFirst() {
        RepairEvaluation evaluation = new RepairEvaluation();
        evaluation.setId(50001L);
        evaluation.setTicketId(40001L);
        evaluation.setStudentId(10001L);
        evaluation.setWorkerId(SAME_SKILL_WORKER_ID);
        evaluation.setScore(5);
        evaluation.setContent("处理很及时");
        evaluationMapper.insert(evaluation);

        List<DispatchCandidateResponse> candidates = scoringService.topCandidates(waterLeakTicket());
        DispatchCandidateResponse preferred = candidates.stream()
                .filter(candidate -> candidate.workerId().equals(SAME_SKILL_WORKER_ID))
                .findFirst()
                .orElseThrow();

        assertThat(preferred.qualityScore()).isEqualByComparingTo(BigDecimal.valueOf(20));
        assertThat(candidates.get(0).workerId()).isEqualTo(SAME_SKILL_WORKER_ID);
    }

    @Test
    @Transactional
    void shouldDeductPenaltyForReturnedWorker() {
        RepairTicketFlow flow = new RepairTicketFlow();
        flow.setId(60001L);
        flow.setTicketId(40001L);
        flow.setFromStatus(TicketStatus.ASSIGNED.name());
        flow.setToStatus(TicketStatus.RETURNED.name());
        flow.setOperatorId(SAME_SKILL_WORKER_ID);
        flow.setOperatorRole("WORKER");
        flow.setAction("WORKER_RETURN");
        flow.setRemark("派错专业，申请转派");
        flowMapper.insert(flow);

        List<DispatchCandidateResponse> candidates = scoringService.topCandidates(waterLeakTicket());
        DispatchCandidateResponse penalized = candidates.stream()
                .filter(candidate -> candidate.workerId().equals(SAME_SKILL_WORKER_ID))
                .findFirst()
                .orElseThrow();
        DispatchCandidateResponse leader = candidates.stream()
                .filter(candidate -> candidate.workerId().equals(WATER_GROUP_WORKER_ID))
                .findFirst()
                .orElseThrow();

        assertThat(penalized.penaltyScore()).isEqualByComparingTo(BigDecimal.valueOf(3));
        assertThat(penalized.totalScore()).isLessThan(leader.totalScore());
        assertThat(candidates.get(0).workerId()).isEqualTo(WATER_GROUP_WORKER_ID);
    }

    @Test
    void shouldMarkTopCandidateRecommended() {
        List<DispatchCandidateResponse> candidates = scoringService.topCandidates(waterLeakTicket());

        List<DispatchCandidateResponse> marked = scoringService.markRecommended(candidates, null);

        assertThat(marked.get(0).aiRecommended()).isTrue();
        assertThat(marked.stream().filter(candidate -> Boolean.TRUE.equals(candidate.aiRecommended()))).hasSize(1);
    }

    private RepairTicket waterLeakTicket() {
        RepairTicket ticket = new RepairTicket();
        ticket.setId(90001L);
        ticket.setCategoryId(20001L);
        ticket.setDescription("教学楼水管漏水，插座附近有用电风险，请尽快维修。");
        return ticket;
    }
}
