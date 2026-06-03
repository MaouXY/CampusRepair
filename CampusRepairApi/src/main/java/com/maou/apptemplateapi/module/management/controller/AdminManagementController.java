package com.maou.apptemplateapi.module.management.controller;

import com.maou.apptemplateapi.common.result.ApiResponse;
import com.maou.apptemplateapi.common.result.PageResult;
import com.maou.apptemplateapi.module.management.dto.AdminCategoryRequest;
import com.maou.apptemplateapi.module.management.dto.AdminCategoryResponse;
import com.maou.apptemplateapi.module.management.dto.AdminLocationRequest;
import com.maou.apptemplateapi.module.management.dto.AdminLocationResponse;
import com.maou.apptemplateapi.module.management.dto.AdminWorkerRequest;
import com.maou.apptemplateapi.module.management.dto.AdminWorkerResponse;
import com.maou.apptemplateapi.module.management.dto.NoticeRequest;
import com.maou.apptemplateapi.module.management.dto.NoticeResponse;
import com.maou.apptemplateapi.module.management.service.ManagementService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin")
public class AdminManagementController {

    private final ManagementService managementService;

    @GetMapping("/categories")
    public ApiResponse<PageResult<AdminCategoryResponse>> categories(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size) {
        return ApiResponse.success(managementService.listCategories(page, size));
    }

    @PostMapping("/categories")
    public ApiResponse<AdminCategoryResponse> createCategory(@Valid @RequestBody AdminCategoryRequest request) {
        return ApiResponse.success(managementService.createCategory(request));
    }

    @PutMapping("/categories/{id}")
    public ApiResponse<AdminCategoryResponse> updateCategory(
            @PathVariable Long id,
            @Valid @RequestBody AdminCategoryRequest request) {
        return ApiResponse.success(managementService.updateCategory(id, request));
    }

    @DeleteMapping("/categories/{id}")
    public ApiResponse<Void> deleteCategory(@PathVariable Long id) {
        managementService.deleteCategory(id);
        return ApiResponse.success(null);
    }

    @GetMapping("/locations")
    public ApiResponse<PageResult<AdminLocationResponse>> locations(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size) {
        return ApiResponse.success(managementService.listLocations(page, size));
    }

    @PostMapping("/locations")
    public ApiResponse<AdminLocationResponse> createLocation(@Valid @RequestBody AdminLocationRequest request) {
        return ApiResponse.success(managementService.createLocation(request));
    }

    @PutMapping("/locations/{id}")
    public ApiResponse<AdminLocationResponse> updateLocation(
            @PathVariable Long id,
            @Valid @RequestBody AdminLocationRequest request) {
        return ApiResponse.success(managementService.updateLocation(id, request));
    }

    @DeleteMapping("/locations/{id}")
    public ApiResponse<Void> deleteLocation(@PathVariable Long id) {
        managementService.deleteLocation(id);
        return ApiResponse.success(null);
    }

    @GetMapping("/workers")
    public ApiResponse<PageResult<AdminWorkerResponse>> workers(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size) {
        return ApiResponse.success(managementService.listWorkers(page, size));
    }

    @PostMapping("/workers")
    public ApiResponse<AdminWorkerResponse> createWorker(@Valid @RequestBody AdminWorkerRequest request) {
        return ApiResponse.success(managementService.createWorker(request));
    }

    @PutMapping("/workers/{id}")
    public ApiResponse<AdminWorkerResponse> updateWorker(
            @PathVariable Long id,
            @Valid @RequestBody AdminWorkerRequest request) {
        return ApiResponse.success(managementService.updateWorker(id, request));
    }

    @DeleteMapping("/workers/{id}")
    public ApiResponse<Void> deleteWorker(@PathVariable Long id) {
        managementService.deleteWorker(id);
        return ApiResponse.success(null);
    }

    @GetMapping("/notices")
    public ApiResponse<PageResult<NoticeResponse>> notices(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size) {
        return ApiResponse.success(managementService.listAdminNotices(page, size));
    }

    @PostMapping("/notices")
    public ApiResponse<NoticeResponse> createNotice(@Valid @RequestBody NoticeRequest request) {
        return ApiResponse.success(managementService.createNotice(request));
    }

    @PutMapping("/notices/{id}")
    public ApiResponse<NoticeResponse> updateNotice(
            @PathVariable Long id,
            @Valid @RequestBody NoticeRequest request) {
        return ApiResponse.success(managementService.updateNotice(id, request));
    }

    @DeleteMapping("/notices/{id}")
    public ApiResponse<Void> deleteNotice(@PathVariable Long id) {
        managementService.deleteNotice(id);
        return ApiResponse.success(null);
    }
}
