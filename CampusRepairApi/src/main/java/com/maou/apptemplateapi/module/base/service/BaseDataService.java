package com.maou.apptemplateapi.module.base.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.maou.apptemplateapi.module.base.dto.LocationTreeResponse;
import com.maou.apptemplateapi.module.base.dto.OptionItemResponse;
import com.maou.apptemplateapi.module.base.entity.RepairCategory;
import com.maou.apptemplateapi.module.base.entity.RepairLocation;
import com.maou.apptemplateapi.module.base.mapper.RepairCategoryMapper;
import com.maou.apptemplateapi.module.base.mapper.RepairLocationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class BaseDataService {

    private final RepairCategoryMapper repairCategoryMapper;
    private final RepairLocationMapper repairLocationMapper;

    public List<OptionItemResponse> listCategories() {
        return repairCategoryMapper.selectList(new LambdaQueryWrapper<RepairCategory>()
                        .eq(RepairCategory::getEnabled, 1)
                        .eq(RepairCategory::getDeleted, 0)
                        .orderByAsc(RepairCategory::getSortOrder)
                        .orderByAsc(RepairCategory::getId))
                .stream()
                .map(category -> new OptionItemResponse(category.getId(), null, category.getName()))
                .toList();
    }

    public List<OptionItemResponse> listLocations() {
        return repairLocationMapper.selectList(new LambdaQueryWrapper<RepairLocation>()
                        .eq(RepairLocation::getEnabled, 1)
                        .eq(RepairLocation::getDeleted, 0)
                        .orderByAsc(RepairLocation::getSortOrder)
                        .orderByAsc(RepairLocation::getId))
                .stream()
                .map(location -> new OptionItemResponse(location.getId(), location.getParentId(), location.getName()))
                .toList();
    }

    public List<LocationTreeResponse> listLocationTree() {
        List<RepairLocation> locations = repairLocationMapper.selectList(new LambdaQueryWrapper<RepairLocation>()
                .eq(RepairLocation::getEnabled, 1)
                .eq(RepairLocation::getDeleted, 0)
                .orderByAsc(RepairLocation::getSortOrder)
                .orderByAsc(RepairLocation::getId));
        Map<Long, List<RepairLocation>> childrenByParent = locations.stream()
                .filter(location -> location.getParentId() != null)
                .collect(Collectors.groupingBy(RepairLocation::getParentId));
        Set<Long> ids = locations.stream().map(RepairLocation::getId).collect(Collectors.toSet());
        return locations.stream()
                .filter(location -> location.getParentId() == null || !ids.contains(location.getParentId()))
                .map(location -> toTreeNode(location, childrenByParent))
                .toList();
    }

    private LocationTreeResponse toTreeNode(RepairLocation location, Map<Long, List<RepairLocation>> childrenByParent) {
        List<LocationTreeResponse> children = childrenByParent.getOrDefault(location.getId(), new ArrayList<>())
                .stream()
                .map(child -> toTreeNode(child, childrenByParent))
                .toList();
        return new LocationTreeResponse(location.getId(), location.getParentId(), location.getName(), children);
    }
}
