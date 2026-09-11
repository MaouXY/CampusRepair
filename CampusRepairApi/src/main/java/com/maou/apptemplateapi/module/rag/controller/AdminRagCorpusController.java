package com.maou.apptemplateapi.module.rag.controller;

import com.maou.apptemplateapi.common.result.ApiResponse;
import com.maou.apptemplateapi.module.rag.dto.CorpusBatchImportRequest;
import com.maou.apptemplateapi.module.rag.dto.CorpusImportRequest;
import com.maou.apptemplateapi.module.rag.dto.CorpusImportResponse;
import com.maou.apptemplateapi.module.rag.dto.CorpusPreviewResponse;
import com.maou.apptemplateapi.module.rag.service.RagCorpusImportService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 语料导入：规范/手册原文（预览 + 导入）与已切分章节的批量导入。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/rag/corpus")
public class AdminRagCorpusController {

    private final RagCorpusImportService ragCorpusImportService;

    @PostMapping("/preview")
    public ApiResponse<CorpusPreviewResponse> preview(@RequestBody CorpusImportRequest request) {
        return ApiResponse.success(ragCorpusImportService.preview(request));
    }

    @PostMapping("/import")
    public ApiResponse<CorpusImportResponse> importDocument(@RequestBody CorpusImportRequest request) {
        return ApiResponse.success(ragCorpusImportService.importDocument(request));
    }

    @PostMapping("/import-batch")
    public ApiResponse<List<CorpusImportResponse>> importBatch(@RequestBody CorpusBatchImportRequest request) {
        return ApiResponse.success(ragCorpusImportService.importBatch(request));
    }
}
