package com.maou.apptemplateapi.module.dispatch.controller;

import com.maou.apptemplateapi.common.result.ApiResponse;
import com.maou.apptemplateapi.module.dispatch.dto.DispatchSuggestionResponse;
import com.maou.apptemplateapi.module.dispatch.service.WorkerDispatchService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/tickets/{ticketId}/dispatch-suggestion")
public class AdminDispatchController {

    private final WorkerDispatchService workerDispatchService;

    @GetMapping
    public ApiResponse<DispatchSuggestionResponse> suggest(@PathVariable Long ticketId) {
        return ApiResponse.success(workerDispatchService.suggest(ticketId));
    }
}
