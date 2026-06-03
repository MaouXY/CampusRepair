package com.maou.apptemplateapi.module.stats.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.maou.apptemplateapi.common.enums.UserRole;
import com.maou.apptemplateapi.common.exception.BusinessException;
import com.maou.apptemplateapi.common.exception.ErrorCode;
import com.maou.apptemplateapi.common.security.CurrentUser;
import com.maou.apptemplateapi.common.security.CurrentUserProvider;
import com.maou.apptemplateapi.module.base.entity.RepairCategory;
import com.maou.apptemplateapi.module.base.mapper.RepairCategoryMapper;
import com.maou.apptemplateapi.module.stats.dto.StatsItemResponse;
import com.maou.apptemplateapi.module.stats.dto.StatsOverviewResponse;
import com.maou.apptemplateapi.module.ticket.entity.RepairEvaluation;
import com.maou.apptemplateapi.module.ticket.entity.RepairTicket;
import com.maou.apptemplateapi.module.ticket.enums.TicketStatus;
import com.maou.apptemplateapi.module.ticket.mapper.RepairEvaluationMapper;
import com.maou.apptemplateapi.module.ticket.mapper.RepairTicketMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class StatsService {

    private final RepairTicketMapper ticketMapper;
    private final RepairEvaluationMapper evaluationMapper;
    private final RepairCategoryMapper categoryMapper;

    public StatsOverviewResponse overview() {
        CurrentUser currentUser = CurrentUserProvider.require();
        if (currentUser.getRole() != UserRole.ADMIN) {
            log.warn("stats role denied, scenario=admin-stats-overview, userId={}, roleCode={}",
                    currentUser.getId(), currentUser.getRoleCode());
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        LocalDate today = LocalDate.now();
        Long todayTickets = ticketMapper.selectCount(new LambdaQueryWrapper<RepairTicket>()
                .ge(RepairTicket::getCreatedAt, today.atStartOfDay())
                .eq(RepairTicket::getDeleted, 0));
        Long pendingReview = countStatus(TicketStatus.PENDING_REVIEW);
        Long processing = countStatus(TicketStatus.PROCESSING);
        Long completed = countStatus(TicketStatus.COMPLETED);
        BigDecimal averageScore = averageScore();
        return new StatsOverviewResponse(todayTickets, pendingReview, processing, completed, averageScore, categoryDistribution());
    }

    private Long countStatus(TicketStatus status) {
        return ticketMapper.selectCount(new LambdaQueryWrapper<RepairTicket>()
                .eq(RepairTicket::getStatus, status.name())
                .eq(RepairTicket::getDeleted, 0));
    }

    private BigDecimal averageScore() {
        List<RepairEvaluation> evaluations = evaluationMapper.selectList(new LambdaQueryWrapper<>());
        if (evaluations.isEmpty()) {
            return BigDecimal.ZERO;
        }
        double average = evaluations.stream().mapToInt(RepairEvaluation::getScore).average().orElse(0);
        return BigDecimal.valueOf(average).setScale(1, RoundingMode.HALF_UP);
    }

    private List<StatsItemResponse> categoryDistribution() {
        List<RepairTicket> tickets = ticketMapper.selectList(new LambdaQueryWrapper<RepairTicket>().eq(RepairTicket::getDeleted, 0));
        Map<Long, Long> counts = tickets.stream().collect(Collectors.groupingBy(RepairTicket::getCategoryId, Collectors.counting()));
        Map<Long, String> names = categoryMapper.selectBatchIds(counts.keySet()).stream()
                .collect(Collectors.toMap(RepairCategory::getId, RepairCategory::getName));
        return counts.entrySet().stream()
                .map(entry -> new StatsItemResponse(names.getOrDefault(entry.getKey(), "未知分类"), entry.getValue()))
                .toList();
    }
}
