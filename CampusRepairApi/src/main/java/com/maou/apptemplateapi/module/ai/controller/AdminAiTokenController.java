package com.maou.apptemplateapi.module.ai.controller;

import com.maou.apptemplateapi.common.enums.UserRole;
import com.maou.apptemplateapi.common.exception.BusinessException;
import com.maou.apptemplateapi.common.exception.ErrorCode;
import com.maou.apptemplateapi.common.result.ApiResponse;
import com.maou.apptemplateapi.common.security.CurrentUser;
import com.maou.apptemplateapi.common.security.CurrentUserProvider;
import com.maou.apptemplateapi.module.ai.dto.AiTokenUsageResponse;
import com.maou.apptemplateapi.module.ai.service.AiTokenMonitor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/ai/token-usage")
public class AdminAiTokenController {

    private final AiTokenMonitor aiTokenMonitor;

    @GetMapping
    public ApiResponse<AiTokenUsageResponse> tokenUsage() {
        CurrentUser currentUser = CurrentUserProvider.require();
        if (currentUser.getRole() != UserRole.ADMIN) {
            log.warn("ai token usage role denied, scenario=admin-ai-token-usage, userId={}, roleCode={}",
                    currentUser.getId(), currentUser.getRoleCode());
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return ApiResponse.success(aiTokenMonitor.snapshot());
    }
}
