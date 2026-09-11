package com.maou.apptemplateapi.module.rageval.controller;

import com.maou.apptemplateapi.common.result.ApiResponse;
import com.maou.apptemplateapi.common.result.PageResult;
import com.maou.apptemplateapi.module.rageval.dto.RagEvalCaseRequest;
import com.maou.apptemplateapi.module.rageval.dto.RagEvalCaseResponse;
import com.maou.apptemplateapi.module.rageval.dto.RagEvalDatasetResponse;
import com.maou.apptemplateapi.module.rageval.dto.RagEvalImportRequest;
import com.maou.apptemplateapi.module.rageval.dto.RagEvalRunDetailResponse;
import com.maou.apptemplateapi.module.rageval.dto.RagEvalRunRequest;
import com.maou.apptemplateapi.module.rageval.dto.RagEvalRunResponse;
import com.maou.apptemplateapi.module.rageval.service.RagEvalDatasetService;
import com.maou.apptemplateapi.module.rageval.service.RagEvaluationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
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
@RequestMapping("/api/v1/admin/rag/eval")
public class AdminRagEvalController {

    private final RagEvalDatasetService datasetService;
    private final RagEvaluationService evaluationService;

    @GetMapping("/datasets")
    public ApiResponse<List<RagEvalDatasetResponse>> datasets() {
        return ApiResponse.success(datasetService.listDatasets());
    }

    @GetMapping("/cases")
    public ApiResponse<PageResult<RagEvalCaseResponse>> cases(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) String datasetName) {
        return ApiResponse.success(datasetService.listCases(page, size, datasetName));
    }

    @PostMapping("/cases")
    public ApiResponse<RagEvalCaseResponse> createCase(@RequestBody RagEvalCaseRequest request) {
        return ApiResponse.success(datasetService.createCase(request));
    }

    @PostMapping("/cases/import")
    public ApiResponse<Integer> importCases(@RequestBody RagEvalImportRequest request) {
        return ApiResponse.success(datasetService.importCases(request));
    }

    @DeleteMapping("/cases/{caseId}")
    public ApiResponse<Void> deleteCase(@PathVariable Long caseId) {
        datasetService.deleteCase(caseId);
        return ApiResponse.success();
    }

    @PostMapping("/runs")
    public ApiResponse<RagEvalRunDetailResponse> run(@RequestBody(required = false) RagEvalRunRequest request) {
        return ApiResponse.success(evaluationService.run(request));
    }

    @GetMapping("/runs")
    public ApiResponse<PageResult<RagEvalRunResponse>> runs(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size) {
        return ApiResponse.success(evaluationService.listRuns(page, size));
    }

    @GetMapping("/runs/{runId}")
    public ApiResponse<RagEvalRunDetailResponse> runDetail(@PathVariable Long runId) {
        return ApiResponse.success(evaluationService.runDetail(runId));
    }
}
