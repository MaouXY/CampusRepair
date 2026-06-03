package com.maou.apptemplateapi.module.base.controller;

import com.maou.apptemplateapi.common.result.ApiResponse;
import com.maou.apptemplateapi.module.base.dto.LocationTreeResponse;
import com.maou.apptemplateapi.module.base.dto.OptionItemResponse;
import com.maou.apptemplateapi.module.base.service.BaseDataService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/base")
public class BaseDataController {

    private final BaseDataService baseDataService;

    @GetMapping("/categories")
    public ApiResponse<List<OptionItemResponse>> categories() {
        return ApiResponse.success(baseDataService.listCategories());
    }

    @GetMapping("/locations")
    public ApiResponse<List<OptionItemResponse>> locations() {
        return ApiResponse.success(baseDataService.listLocations());
    }

    @GetMapping("/locations/tree")
    public ApiResponse<List<LocationTreeResponse>> locationTree() {
        return ApiResponse.success(baseDataService.listLocationTree());
    }
}
