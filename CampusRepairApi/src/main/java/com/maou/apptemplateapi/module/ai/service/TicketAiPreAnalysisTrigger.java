package com.maou.apptemplateapi.module.ai.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class TicketAiPreAnalysisTrigger {

    private final TicketAiAnalysisService ticketAiAnalysisService;

    @Async
    public void trigger(Long ticketId, Long studentId, String scenario) {
        if (ticketId == null) {
            log.warn("ticket ai pre analysis skipped, scenario={}, reason=missing-ticket-id, studentId={}",
                    scenario, studentId);
            return;
        }
        try {
            ticketAiAnalysisService.analyzeTicketAutomatically(ticketId, studentId, scenario);
        } catch (RuntimeException exception) {
            log.error("ticket ai pre analysis failed, scenario={}, ticketId={}, studentId={}",
                    scenario, ticketId, studentId, exception);
        }
    }
}
