package com.maou.apptemplateapi.module.ticket.controller;

import com.maou.apptemplateapi.common.result.ApiResponse;
import com.maou.apptemplateapi.common.result.PageResult;
import com.maou.apptemplateapi.module.ticket.dto.TicketAssignRequest;
import com.maou.apptemplateapi.module.ticket.dto.AdminTodoOverviewResponse;
import com.maou.apptemplateapi.module.ticket.dto.TicketDetailResponse;
import com.maou.apptemplateapi.module.ticket.dto.TicketRejectRequest;
import com.maou.apptemplateapi.module.ticket.dto.TicketSummaryResponse;
import com.maou.apptemplateapi.module.ticket.dto.TicketUrgeRequest;
import com.maou.apptemplateapi.module.ticket.dto.WorkerOptionResponse;
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

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin")
public class AdminTicketController {

    private final TicketService ticketService;

    @GetMapping("/tickets")
    public ApiResponse<PageResult<TicketSummaryResponse>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Boolean overdue,
            @RequestParam(required = false) Boolean urged,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size) {
        return ApiResponse.success(ticketService.listAdminTickets(status, overdue, urged, page, size));
    }

    @GetMapping("/tickets/todo-overview")
    public ApiResponse<AdminTodoOverviewResponse> todoOverview() {
        return ApiResponse.success(ticketService.adminTodoOverview());
    }

    @GetMapping("/tickets/{ticketId}")
    public ApiResponse<TicketDetailResponse> detail(@PathVariable Long ticketId) {
        return ApiResponse.success(ticketService.detailForAdmin(ticketId));
    }

    @PostMapping("/tickets/{ticketId}/assign")
    public ApiResponse<TicketDetailResponse> assign(
            @PathVariable Long ticketId,
            @Valid @RequestBody TicketAssignRequest request) {
        return ApiResponse.success(ticketService.assign(ticketId, request));
    }

    @PostMapping("/tickets/{ticketId}/reject")
    public ApiResponse<TicketDetailResponse> reject(
            @PathVariable Long ticketId,
            @Valid @RequestBody TicketRejectRequest request) {
        return ApiResponse.success(ticketService.reject(ticketId, request));
    }

    @PostMapping("/tickets/{ticketId}/urge")
    public ApiResponse<TicketDetailResponse> urge(
            @PathVariable Long ticketId,
            @Valid @RequestBody TicketUrgeRequest request) {
        return ApiResponse.success(ticketService.urge(ticketId, request));
    }

    @GetMapping("/workers/options")
    public ApiResponse<List<WorkerOptionResponse>> workerOptions() {
        return ApiResponse.success(ticketService.listWorkerOptions());
    }
}
