package com.maou.apptemplateapi.module.rag.controller;

import com.maou.apptemplateapi.common.result.ApiResponse;
import com.maou.apptemplateapi.common.result.PageResult;
import com.maou.apptemplateapi.module.rag.dto.KnowledgeDocumentRequest;
import com.maou.apptemplateapi.module.rag.dto.KnowledgeDocumentResponse;
import com.maou.apptemplateapi.module.rag.service.RagKnowledgeService;
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
@RequestMapping("/api/v1/admin/rag")
public class AdminRagController {

    private final RagKnowledgeService ragKnowledgeService;

    @GetMapping("/documents")
    public ApiResponse<PageResult<KnowledgeDocumentResponse>> documents(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size) {
        return ApiResponse.success(ragKnowledgeService.listDocuments(page, size));
    }

    @PostMapping("/documents")
    public ApiResponse<KnowledgeDocumentResponse> create(@Valid @RequestBody KnowledgeDocumentRequest request) {
        return ApiResponse.success(ragKnowledgeService.createDocument(request));
    }

    @PutMapping("/documents/{id}")
    public ApiResponse<KnowledgeDocumentResponse> update(
            @PathVariable Long id,
            @Valid @RequestBody KnowledgeDocumentRequest request) {
        return ApiResponse.success(ragKnowledgeService.updateDocument(id, request));
    }

    @DeleteMapping("/documents/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        ragKnowledgeService.deleteDocument(id);
        return ApiResponse.success();
    }

    @PostMapping("/documents/{id}/rebuild")
    public ApiResponse<Integer> rebuild(@PathVariable Long id) {
        return ApiResponse.success(ragKnowledgeService.rebuildDocumentChunks(id));
    }
}
