package com.maou.apptemplateapi.module.ai.controller;

import com.maou.apptemplateapi.common.result.ApiResponse;
import com.maou.apptemplateapi.module.ai.dto.TicketAiAnalysisResponse;
import com.maou.apptemplateapi.module.ai.service.TicketAiAnalysisService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/tickets/{ticketId}/ai")
public class AdminTicketAiController {

    private final TicketAiAnalysisService ticketAiAnalysisService;

    @PostMapping("/analysis")
    public ApiResponse<TicketAiAnalysisResponse> analyze(@PathVariable Long ticketId) {
        return ApiResponse.success(ticketAiAnalysisService.analyzeTicket(ticketId));
    }

    @GetMapping("/analysis/latest")
    public ApiResponse<TicketAiAnalysisResponse> latest(@PathVariable Long ticketId) {
        return ApiResponse.success(ticketAiAnalysisService.latest(ticketId));
    }
}
