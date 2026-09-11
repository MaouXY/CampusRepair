package com.maou.apptemplateapi.module.rag.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 工单闭环后的知识沉淀异步触发器：不阻塞学生评价主流程，失败只记录日志。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagKnowledgeDraftTrigger {

    private final RagKnowledgeDraftService ragKnowledgeDraftService;

    @Async
    public void trigger(Long ticketId, String scenario) {
        if (ticketId == null) {
            log.warn("rag draft trigger skipped, scenario={}, reason=missing-ticket-id", scenario);
            return;
        }
        try {
            ragKnowledgeDraftService.autoGenerateForCompletedTicket(ticketId, scenario);
        } catch (RuntimeException exception) {
            log.error("rag draft auto generate failed, scenario={}, ticketId={}", scenario, ticketId, exception);
        }
    }
}
