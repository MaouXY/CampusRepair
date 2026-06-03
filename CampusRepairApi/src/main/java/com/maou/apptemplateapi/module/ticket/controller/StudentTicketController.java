package com.maou.apptemplateapi.module.ticket.controller;

import com.maou.apptemplateapi.common.result.ApiResponse;
import com.maou.apptemplateapi.common.result.PageResult;
import com.maou.apptemplateapi.module.ticket.dto.TicketCreateRequest;
import com.maou.apptemplateapi.module.ticket.dto.TicketDetailResponse;
import com.maou.apptemplateapi.module.ticket.dto.TicketEvaluationRequest;
import com.maou.apptemplateapi.module.ticket.dto.TicketReworkRequest;
import com.maou.apptemplateapi.module.ticket.dto.TicketSummaryResponse;
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
@RequestMapping("/api/v1/student/tickets")
public class StudentTicketController {

    private final TicketService ticketService;

    @PostMapping
    public ApiResponse<TicketDetailResponse> create(@Valid @RequestBody TicketCreateRequest request) {
        return ApiResponse.success(ticketService.createTicket(request));
    }

    @GetMapping
    public ApiResponse<PageResult<TicketSummaryResponse>> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size) {
        return ApiResponse.success(ticketService.listStudentTickets(status, page, size));
    }

    @GetMapping("/{ticketId}")
    public ApiResponse<TicketDetailResponse> detail(@PathVariable Long ticketId) {
        return ApiResponse.success(ticketService.detailForStudent(ticketId));
    }

    @PostMapping("/{ticketId}/evaluation")
    public ApiResponse<TicketDetailResponse> evaluate(
            @PathVariable Long ticketId,
            @Valid @RequestBody TicketEvaluationRequest request) {
        return ApiResponse.success(ticketService.evaluate(ticketId, request));
    }

    @PostMapping("/{ticketId}/rework")
    public ApiResponse<TicketDetailResponse> requestRework(
            @PathVariable Long ticketId,
            @Valid @RequestBody TicketReworkRequest request) {
        return ApiResponse.success(ticketService.requestRework(ticketId, request));
    }

    @PostMapping("/{ticketId}/resubmit")
    public ApiResponse<TicketDetailResponse> resubmit(
            @PathVariable Long ticketId,
            @Valid @RequestBody TicketCreateRequest request) {
        return ApiResponse.success(ticketService.resubmitRejectedTicket(ticketId, request));
    }
}
