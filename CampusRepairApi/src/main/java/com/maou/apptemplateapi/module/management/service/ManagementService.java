package com.maou.apptemplateapi.module.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maou.apptemplateapi.common.enums.UserRole;
import com.maou.apptemplateapi.common.exception.BusinessException;
import com.maou.apptemplateapi.common.exception.ErrorCode;
import com.maou.apptemplateapi.common.result.PageResult;
import com.maou.apptemplateapi.common.security.CurrentUser;
import com.maou.apptemplateapi.common.security.CurrentUserProvider;
import com.maou.apptemplateapi.module.base.entity.RepairCategory;
import com.maou.apptemplateapi.module.base.entity.RepairLocation;
import com.maou.apptemplateapi.module.base.mapper.RepairCategoryMapper;
import com.maou.apptemplateapi.module.base.mapper.RepairLocationMapper;
import com.maou.apptemplateapi.module.dispatch.entity.WorkerProfile;
import com.maou.apptemplateapi.module.dispatch.mapper.WorkerProfileMapper;
import com.maou.apptemplateapi.module.management.dto.AdminCategoryRequest;
import com.maou.apptemplateapi.module.management.dto.AdminCategoryResponse;
import com.maou.apptemplateapi.module.management.dto.AdminLocationRequest;
import com.maou.apptemplateapi.module.management.dto.AdminLocationResponse;
import com.maou.apptemplateapi.module.management.dto.AdminWorkerRequest;
import com.maou.apptemplateapi.module.management.dto.AdminWorkerResponse;
import com.maou.apptemplateapi.module.management.dto.NoticeRequest;
import com.maou.apptemplateapi.module.management.dto.NoticeResponse;
import com.maou.apptemplateapi.module.management.entity.RepairNotice;
import com.maou.apptemplateapi.module.management.mapper.RepairNoticeMapper;
import com.maou.apptemplateapi.module.user.entity.UserAccount;
import com.maou.apptemplateapi.module.user.mapper.UserAccountMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ManagementService {

    private static final String DEFAULT_PASSWORD = "123456";
    private static final String DEFAULT_DEPARTMENT = "综合维修组";
    private static final int DEFAULT_MAX_ACTIVE_ORDERS = 5;

    private final RepairCategoryMapper categoryMapper;
    private final RepairLocationMapper locationMapper;
    private final UserAccountMapper userAccountMapper;
    private final RepairNoticeMapper noticeMapper;
    private final WorkerProfileMapper workerProfileMapper;
    private final PasswordEncoder passwordEncoder;
    private final ObjectMapper objectMapper;

    public PageResult<AdminCategoryResponse> listCategories(long page, long size) {
        requireAdmin("admin-list-categories");
        Page<RepairCategory> result = categoryMapper.selectPage(new Page<>(safePage(page), safeSize(size)),
                new LambdaQueryWrapper<RepairCategory>()
                        .eq(RepairCategory::getDeleted, 0)
                        .orderByAsc(RepairCategory::getSortOrder)
                        .orderByDesc(RepairCategory::getId));
        return PageResult.of(result.getRecords().stream().map(this::toCategoryResponse).toList(),
                result.getCurrent(), result.getSize(), result.getTotal());
    }

    @Transactional
    public AdminCategoryResponse createCategory(AdminCategoryRequest request) {
        requireAdmin("admin-create-category");
        RepairCategory category = new RepairCategory();
        applyCategory(category, request);
        try {
            categoryMapper.insert(category);
        } catch (DuplicateKeyException exception) {
            log.warn("management data duplicated, scenario=admin-create-category, name={}", request.name(), exception);
            throw new BusinessException(ErrorCode.MANAGEMENT_DATA_DUPLICATED);
        }
        return toCategoryResponse(categoryMapper.selectById(category.getId()));
    }

    @Transactional
    public AdminCategoryResponse updateCategory(Long id, AdminCategoryRequest request) {
        requireAdmin("admin-update-category");
        RepairCategory category = requireCategory(id, "admin-update-category");
        applyCategory(category, request);
        try {
            categoryMapper.updateById(category);
        } catch (DuplicateKeyException exception) {
            log.warn("management data duplicated, scenario=admin-update-category, categoryId={}, name={}",
                    id, request.name(), exception);
            throw new BusinessException(ErrorCode.MANAGEMENT_DATA_DUPLICATED);
        }
        return toCategoryResponse(categoryMapper.selectById(id));
    }

    @Transactional
    public void deleteCategory(Long id) {
        requireAdmin("admin-delete-category");
        requireCategory(id, "admin-delete-category");
        categoryMapper.deleteById(id);
    }

    public PageResult<AdminLocationResponse> listLocations(long page, long size) {
        requireAdmin("admin-list-locations");
        Page<RepairLocation> result = locationMapper.selectPage(new Page<>(safePage(page), safeSize(size)),
                new LambdaQueryWrapper<RepairLocation>()
                        .eq(RepairLocation::getDeleted, 0)
                        .orderByAsc(RepairLocation::getSortOrder)
                        .orderByDesc(RepairLocation::getId));
        return PageResult.of(result.getRecords().stream().map(this::toLocationResponse).toList(),
                result.getCurrent(), result.getSize(), result.getTotal());
    }

    @Transactional
    public AdminLocationResponse createLocation(AdminLocationRequest request) {
        requireAdmin("admin-create-location");
        validateParentLocation(request.parentId(), null, "admin-create-location");
        RepairLocation location = new RepairLocation();
        applyLocation(location, request);
        locationMapper.insert(location);
        return toLocationResponse(locationMapper.selectById(location.getId()));
    }

    @Transactional
    public AdminLocationResponse updateLocation(Long id, AdminLocationRequest request) {
        requireAdmin("admin-update-location");
        RepairLocation location = requireLocation(id, "admin-update-location");
        validateParentLocation(request.parentId(), id, "admin-update-location");
        applyLocation(location, request);
        locationMapper.updateById(location);
        return toLocationResponse(locationMapper.selectById(id));
    }

    @Transactional
    public void deleteLocation(Long id) {
        requireAdmin("admin-delete-location");
        requireLocation(id, "admin-delete-location");
        locationMapper.deleteById(id);
    }

    public PageResult<AdminWorkerResponse> listWorkers(long page, long size) {
        requireAdmin("admin-list-workers");
        Page<UserAccount> result = userAccountMapper.selectPage(new Page<>(safePage(page), safeSize(size)),
                new LambdaQueryWrapper<UserAccount>()
                        .eq(UserAccount::getRoleCode, UserRole.WORKER.name())
                        .eq(UserAccount::getDeleted, 0)
                        .orderByDesc(UserAccount::getId));
        Map<Long, WorkerProfile> profiles = loadWorkerProfiles(result.getRecords().stream().map(UserAccount::getId).toList());
        return PageResult.of(result.getRecords().stream().map(worker -> toWorkerResponse(worker, profiles.get(worker.getId()))).toList(),
                result.getCurrent(), result.getSize(), result.getTotal());
    }

    @Transactional
    public AdminWorkerResponse createWorker(AdminWorkerRequest request) {
        requireAdmin("admin-create-worker");
        UserAccount worker = new UserAccount();
        worker.setUsername(request.username());
        worker.setPasswordHash(passwordEncoder.encode(StringUtils.hasText(request.password()) ? request.password() : DEFAULT_PASSWORD));
        worker.setRoleCode(UserRole.WORKER.name());
        applyWorker(worker, request);
        try {
            userAccountMapper.insert(worker);
        } catch (DuplicateKeyException exception) {
            log.warn("management data duplicated, scenario=admin-create-worker, username={}", request.username(), exception);
            throw new BusinessException(ErrorCode.MANAGEMENT_DATA_DUPLICATED);
        }
        saveWorkerProfile(worker.getId(), request, "admin-create-worker");
        return toWorkerResponse(userAccountMapper.selectById(worker.getId()), workerProfileMapper.selectById(worker.getId()));
    }

    @Transactional
    public AdminWorkerResponse updateWorker(Long id, AdminWorkerRequest request) {
        requireAdmin("admin-update-worker");
        UserAccount worker = requireWorker(id, "admin-update-worker");
        worker.setUsername(request.username());
        if (StringUtils.hasText(request.password())) {
            worker.setPasswordHash(passwordEncoder.encode(request.password()));
        }
        applyWorker(worker, request);
        try {
            userAccountMapper.updateById(worker);
        } catch (DuplicateKeyException exception) {
            log.warn("management data duplicated, scenario=admin-update-worker, workerId={}, username={}",
                    id, request.username(), exception);
            throw new BusinessException(ErrorCode.MANAGEMENT_DATA_DUPLICATED);
        }
        saveWorkerProfile(id, request, "admin-update-worker");
        return toWorkerResponse(userAccountMapper.selectById(id), workerProfileMapper.selectById(id));
    }

    @Transactional
    public void deleteWorker(Long id) {
        requireAdmin("admin-delete-worker");
        requireWorker(id, "admin-delete-worker");
        userAccountMapper.deleteById(id);
    }

    public PageResult<NoticeResponse> listAdminNotices(long page, long size) {
        requireAdmin("admin-list-notices");
        Page<RepairNotice> result = noticeMapper.selectPage(new Page<>(safePage(page), safeSize(size)),
                new LambdaQueryWrapper<RepairNotice>()
                        .eq(RepairNotice::getDeleted, 0)
                        .orderByAsc(RepairNotice::getSortOrder)
                        .orderByDesc(RepairNotice::getId));
        return PageResult.of(result.getRecords().stream().map(this::toNoticeResponse).toList(),
                result.getCurrent(), result.getSize(), result.getTotal());
    }

    public List<NoticeResponse> listPublishedNotices() {
        CurrentUser currentUser = CurrentUserProvider.require();
        return noticeMapper.selectList(new LambdaQueryWrapper<RepairNotice>()
                        .eq(RepairNotice::getPublished, 1)
                        .eq(RepairNotice::getDeleted, 0)
                        .and(wrapper -> wrapper.eq(RepairNotice::getTargetRole, "ALL")
                                .or()
                                .eq(RepairNotice::getTargetRole, currentUser.getRoleCode()))
                        .orderByAsc(RepairNotice::getSortOrder)
                        .orderByDesc(RepairNotice::getId)
                        .last("limit 10"))
                .stream()
                .map(this::toNoticeResponse)
                .toList();
    }

    @Transactional
    public NoticeResponse createNotice(NoticeRequest request) {
        CurrentUser admin = requireAdmin("admin-create-notice");
        validateNotice(request, "admin-create-notice");
        RepairNotice notice = new RepairNotice();
        applyNotice(notice, request);
        notice.setCreatedBy(admin.getId());
        noticeMapper.insert(notice);
        return toNoticeResponse(noticeMapper.selectById(notice.getId()));
    }

    @Transactional
    public NoticeResponse updateNotice(Long id, NoticeRequest request) {
        requireAdmin("admin-update-notice");
        validateNotice(request, "admin-update-notice");
        RepairNotice notice = requireNotice(id, "admin-update-notice");
        applyNotice(notice, request);
        noticeMapper.updateById(notice);
        return toNoticeResponse(noticeMapper.selectById(id));
    }

    @Transactional
    public void deleteNotice(Long id) {
        requireAdmin("admin-delete-notice");
        requireNotice(id, "admin-delete-notice");
        noticeMapper.deleteById(id);
    }

    private CurrentUser requireAdmin(String scenario) {
        CurrentUser currentUser = CurrentUserProvider.require();
        if (currentUser.getRole() != UserRole.ADMIN) {
            log.warn("management role denied, scenario={}, userId={}, roleCode={}",
                    scenario, currentUser.getId(), currentUser.getRoleCode());
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return currentUser;
    }

    private RepairCategory requireCategory(Long id, String scenario) {
        RepairCategory category = categoryMapper.selectById(id);
        if (category == null || category.getDeleted() != 0) {
            log.warn("management data not found, scenario={}, categoryId={}", scenario, id);
            throw new BusinessException(ErrorCode.MANAGEMENT_DATA_NOT_FOUND);
        }
        return category;
    }

    private RepairLocation requireLocation(Long id, String scenario) {
        RepairLocation location = locationMapper.selectById(id);
        if (location == null || location.getDeleted() != 0) {
            log.warn("management data not found, scenario={}, locationId={}", scenario, id);
            throw new BusinessException(ErrorCode.MANAGEMENT_DATA_NOT_FOUND);
        }
        return location;
    }

    private UserAccount requireWorker(Long id, String scenario) {
        UserAccount worker = userAccountMapper.selectById(id);
        if (worker == null || worker.getDeleted() != 0 || !UserRole.WORKER.name().equals(worker.getRoleCode())) {
            log.warn("management data not found, scenario={}, workerId={}", scenario, id);
            throw new BusinessException(ErrorCode.MANAGEMENT_DATA_NOT_FOUND);
        }
        return worker;
    }

    private RepairNotice requireNotice(Long id, String scenario) {
        RepairNotice notice = noticeMapper.selectById(id);
        if (notice == null || notice.getDeleted() != 0) {
            log.warn("management data not found, scenario={}, noticeId={}", scenario, id);
            throw new BusinessException(ErrorCode.MANAGEMENT_DATA_NOT_FOUND);
        }
        return notice;
    }

    private void validateParentLocation(Long parentId, Long selfId, String scenario) {
        if (parentId == null) {
            return;
        }
        if (parentId.equals(selfId)) {
            log.warn("management data invalid, scenario={}, locationId={}, parentId={}", scenario, selfId, parentId);
            throw new BusinessException(ErrorCode.MANAGEMENT_DATA_INVALID);
        }
        RepairLocation parent = locationMapper.selectById(parentId);
        if (parent == null || parent.getDeleted() != 0) {
            log.warn("management data invalid, scenario={}, locationId={}, parentId={}", scenario, selfId, parentId);
            throw new BusinessException(ErrorCode.MANAGEMENT_DATA_INVALID);
        }
    }

    private void validateNotice(NoticeRequest request, String scenario) {
        if (!List.of("ALL", UserRole.STUDENT.name(), UserRole.WORKER.name(), UserRole.ADMIN.name()).contains(request.targetRole())) {
            log.warn("management data invalid, scenario={}, targetRole={}", scenario, request.targetRole());
            throw new BusinessException(ErrorCode.MANAGEMENT_DATA_INVALID);
        }
    }

    private void applyCategory(RepairCategory category, AdminCategoryRequest request) {
        category.setName(request.name());
        category.setSortOrder(request.sortOrder());
        category.setEnabled(normalizeEnabled(request.enabled()));
    }

    private void applyLocation(RepairLocation location, AdminLocationRequest request) {
        location.setParentId(request.parentId());
        location.setName(request.name());
        location.setSortOrder(request.sortOrder());
        location.setEnabled(normalizeEnabled(request.enabled()));
    }

    private void applyWorker(UserAccount worker, AdminWorkerRequest request) {
        worker.setRealName(request.realName());
        worker.setPhone(request.phone());
        worker.setEnabled(normalizeEnabled(request.enabled()));
    }

    private void saveWorkerProfile(Long workerId, AdminWorkerRequest request, String scenario) {
        WorkerProfile profile = workerProfileMapper.selectById(workerId);
        boolean creating = profile == null;
        if (profile == null) {
            profile = new WorkerProfile();
            profile.setWorkerId(workerId);
        }
        profile.setDepartmentName(StringUtils.hasText(request.departmentName()) ? request.departmentName() : DEFAULT_DEPARTMENT);
        profile.setSkillTags(writeSkillTags(request.skillTags(), scenario, workerId));
        profile.setDispatchEnabled(normalizeEnabled(request.dispatchEnabled()));
        profile.setMaxActiveOrders(normalizeMaxActiveOrders(request.maxActiveOrders()));
        profile.setDeleted(0);
        if (creating) {
            workerProfileMapper.insert(profile);
        } else {
            workerProfileMapper.updateById(profile);
        }
    }

    private void applyNotice(RepairNotice notice, NoticeRequest request) {
        notice.setTitle(request.title());
        notice.setContent(request.content());
        notice.setTargetRole(request.targetRole());
        notice.setPublished(normalizeEnabled(request.published()));
        notice.setSortOrder(request.sortOrder());
    }

    private Integer normalizeEnabled(Integer value) {
        return value != null && value == 1 ? 1 : 0;
    }

    private Integer normalizeMaxActiveOrders(Integer value) {
        if (value == null) {
            return DEFAULT_MAX_ACTIVE_ORDERS;
        }
        return Math.min(Math.max(value, 1), 20);
    }

    private long safePage(long page) {
        return Math.max(page, 1);
    }

    private long safeSize(long size) {
        return Math.min(Math.max(size, 1), 100);
    }

    private AdminCategoryResponse toCategoryResponse(RepairCategory category) {
        return new AdminCategoryResponse(category.getId(), category.getName(), category.getSortOrder(), category.getEnabled(),
                category.getCreatedAt(), category.getUpdatedAt());
    }

    private AdminLocationResponse toLocationResponse(RepairLocation location) {
        return new AdminLocationResponse(location.getId(), location.getParentId(), location.getName(), location.getSortOrder(),
                location.getEnabled(), location.getCreatedAt(), location.getUpdatedAt());
    }

    private Map<Long, WorkerProfile> loadWorkerProfiles(List<Long> workerIds) {
        List<Long> ids = workerIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return workerProfileMapper.selectBatchIds(ids).stream()
                .filter(profile -> profile.getDeleted() == null || profile.getDeleted() == 0)
                .collect(Collectors.toMap(WorkerProfile::getWorkerId, Function.identity(), (left, right) -> left));
    }

    private AdminWorkerResponse toWorkerResponse(UserAccount worker, WorkerProfile profile) {
        List<String> skillTags = profile == null ? List.of() : readSkillTags(profile.getSkillTags(), "admin-worker-response", worker.getId());
        return new AdminWorkerResponse(worker.getId(), worker.getUsername(), worker.getRealName(), worker.getPhone(),
                worker.getEnabled(),
                profile == null ? DEFAULT_DEPARTMENT : profile.getDepartmentName(),
                skillTags,
                profile == null ? 1 : profile.getDispatchEnabled(),
                profile == null ? DEFAULT_MAX_ACTIVE_ORDERS : profile.getMaxActiveOrders(),
                worker.getCreatedAt(), worker.getUpdatedAt());
    }

    private String writeSkillTags(List<String> skillTags, String scenario, Long workerId) {
        List<String> normalized = skillTags == null
                ? List.of()
                : skillTags.stream().filter(StringUtils::hasText).map(String::trim).distinct().limit(10).toList();
        try {
            return objectMapper.writeValueAsString(normalized);
        } catch (JsonProcessingException exception) {
            log.error("worker profile skill tags write failed, scenario={}, workerId={}, skillTags={}",
                    scenario, workerId, skillTags, exception);
            throw new BusinessException(ErrorCode.MANAGEMENT_DATA_INVALID);
        }
    }

    private List<String> readSkillTags(String rawJson, String scenario, Long workerId) {
        if (!StringUtils.hasText(rawJson)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(rawJson, new TypeReference<List<String>>() {
            });
        } catch (JsonProcessingException exception) {
            log.error("worker profile skill tags json invalid, scenario={}, workerId={}, rawJson={}",
                    scenario, workerId, rawJson, exception);
            return List.of();
        }
    }

    private NoticeResponse toNoticeResponse(RepairNotice notice) {
        return new NoticeResponse(notice.getId(), notice.getTitle(), notice.getContent(), notice.getTargetRole(),
                notice.getPublished(), notice.getSortOrder(), notice.getCreatedBy(), notice.getCreatedAt(), notice.getUpdatedAt());
    }
}
