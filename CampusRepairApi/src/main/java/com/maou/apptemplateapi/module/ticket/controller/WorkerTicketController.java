package com.maou.apptemplateapi.module.ticket.controller;

import com.maou.apptemplateapi.common.result.ApiResponse;
import com.maou.apptemplateapi.common.result.PageResult;
import com.maou.apptemplateapi.module.ticket.dto.TicketDetailResponse;
import com.maou.apptemplateapi.module.ticket.dto.TicketReturnRequest;
import com.maou.apptemplateapi.module.ticket.dto.TicketResultRequest;
import com.maou.apptemplateapi.module.ticket.dto.TicketSummaryResponse;
import com.maou.apptemplateapi.module.ticket.dto.WorkerTodayOverviewResponse;
import com.maou.apptemplateapi.module.ticket.service.TicketService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/worker/tickets")
public class WorkerTicketController {

    private final TicketService ticketService;

    @GetMapping
    public ApiResponse<PageResult<TicketSummaryResponse>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Boolean overdue,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size) {
        return ApiResponse.success(ticketService.listWorkerTickets(status, overdue, page, size));
    }

    @GetMapping("/today-overview")
    public ApiResponse<WorkerTodayOverviewResponse> todayOverview() {
        return ApiResponse.success(ticketService.workerTodayOverview());
    }

    @GetMapping("/{ticketId}")
    public ApiResponse<TicketDetailResponse> detail(@PathVariable Long ticketId) {
        return ApiResponse.success(ticketService.detailForWorker(ticketId));
    }

    @PostMapping("/{ticketId}/accept")
    public ApiResponse<TicketDetailResponse> accept(@PathVariable Long ticketId) {
        return ApiResponse.success(ticketService.accept(ticketId));
    }

    @PostMapping("/{ticketId}/result")
    public ApiResponse<TicketDetailResponse> submitResult(
            @PathVariable Long ticketId,
            @Valid @RequestBody TicketResultRequest request) {
        return ApiResponse.success(ticketService.submitResult(ticketId, request));
    }

    @PostMapping("/{ticketId}/return")
    public ApiResponse<TicketDetailResponse> returnTicket(
            @PathVariable Long ticketId,
            @Valid @RequestBody TicketReturnRequest request) {
        return ApiResponse.success(ticketService.returnTicket(ticketId, request));
    }
}
