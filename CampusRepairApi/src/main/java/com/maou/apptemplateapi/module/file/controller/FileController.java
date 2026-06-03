package com.maou.apptemplateapi.module.file.controller;

import com.maou.apptemplateapi.common.result.ApiResponse;
import com.maou.apptemplateapi.module.file.dto.FileBindRequest;
import com.maou.apptemplateapi.module.file.dto.FileResponse;
import com.maou.apptemplateapi.module.file.service.FileService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/files")
public class FileController {

    private final FileService fileService;

    @PostMapping("/upload")
    public ApiResponse<FileResponse> upload(@RequestParam("file") MultipartFile file) {
        return ApiResponse.success(fileService.upload(file));
    }

    @PostMapping("/tickets/{ticketId}/report-images")
    public ApiResponse<List<FileResponse>> bindTicketReportImages(
            @PathVariable Long ticketId,
            @Valid @RequestBody FileBindRequest request) {
        return ApiResponse.success(fileService.bindTicketReportImages(ticketId, request.fileIds()));
    }

    @PostMapping("/tickets/{ticketId}/result-images")
    public ApiResponse<List<FileResponse>> bindTicketResultImages(
            @PathVariable Long ticketId,
            @Valid @RequestBody FileBindRequest request) {
        return ApiResponse.success(fileService.bindTicketResultImages(ticketId, request.fileIds()));
    }

    @GetMapping("/tickets/{ticketId}")
    public ApiResponse<List<FileResponse>> listTicketFiles(
            @PathVariable Long ticketId,
            @RequestParam String bizType) {
        return ApiResponse.success(fileService.listByTicket(ticketId, bizType));
    }
}
