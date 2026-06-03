package com.maou.apptemplateapi.module.management.controller;

import com.maou.apptemplateapi.common.result.ApiResponse;
import com.maou.apptemplateapi.module.management.dto.NoticeResponse;
import com.maou.apptemplateapi.module.management.service.ManagementService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/notices")
public class NoticeController {

    private final ManagementService managementService;

    @GetMapping
    public ApiResponse<List<NoticeResponse>> list() {
        return ApiResponse.success(managementService.listPublishedNotices());
    }
}
