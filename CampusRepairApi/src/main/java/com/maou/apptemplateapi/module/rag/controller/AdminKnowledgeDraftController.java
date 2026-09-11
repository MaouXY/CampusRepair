package com.maou.apptemplateapi.module.rag.controller;

import com.maou.apptemplateapi.common.result.ApiResponse;
import com.maou.apptemplateapi.common.result.PageResult;
import com.maou.apptemplateapi.module.rag.dto.KnowledgeDraftResponse;
import com.maou.apptemplateapi.module.rag.dto.KnowledgeDraftReviewRequest;
import com.maou.apptemplateapi.module.rag.service.RagKnowledgeDraftService;
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
@RequestMapping("/api/v1/admin/rag/drafts")
public class AdminKnowledgeDraftController {

    private final RagKnowledgeDraftService ragKnowledgeDraftService;

    @GetMapping
    public ApiResponse<PageResult<KnowledgeDraftResponse>> drafts(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) String status) {
        return ApiResponse.success(ragKnowledgeDraftService.listDrafts(status, page, size));
    }

    @GetMapping("/{draftId}")
    public ApiResponse<KnowledgeDraftResponse> detail(@PathVariable Long draftId) {
        return ApiResponse.success(ragKnowledgeDraftService.detail(draftId));
    }

    @PostMapping("/generate")
    public ApiResponse<KnowledgeDraftResponse> generate(@RequestParam Long ticketId) {
        return ApiResponse.success(ragKnowledgeDraftService.generateFromTicket(ticketId));
    }

    @PostMapping("/{draftId}/approve")
    public ApiResponse<KnowledgeDraftResponse> approve(
            @PathVariable Long draftId,
            @Valid @RequestBody(required = false) KnowledgeDraftReviewRequest request) {
        return ApiResponse.success(ragKnowledgeDraftService.approve(draftId, request == null ? null : request.remark()));
    }

    @PostMapping("/{draftId}/reject")
    public ApiResponse<KnowledgeDraftResponse> reject(
            @PathVariable Long draftId,
            @Valid @RequestBody(required = false) KnowledgeDraftReviewRequest request) {
        return ApiResponse.success(ragKnowledgeDraftService.reject(draftId, request == null ? null : request.remark()));
    }
}
