package com.maou.apptemplateapi.module.file.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.maou.apptemplateapi.common.config.FileProperties;
import com.maou.apptemplateapi.common.config.StorageProperties;
import com.maou.apptemplateapi.common.enums.UserRole;
import com.maou.apptemplateapi.common.exception.BusinessException;
import com.maou.apptemplateapi.common.exception.ErrorCode;
import com.maou.apptemplateapi.common.security.CurrentUser;
import com.maou.apptemplateapi.common.security.CurrentUserProvider;
import com.maou.apptemplateapi.module.file.dto.FileResponse;
import com.maou.apptemplateapi.module.file.entity.FileMetadata;
import com.maou.apptemplateapi.module.file.mapper.FileMetadataMapper;
import com.maou.apptemplateapi.module.ticket.service.TicketService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileService {

    private static final String BIZ_TEMP = "TEMP";
    private static final String BIZ_TICKET_REPORT = "TICKET_REPORT_IMAGE";
    private static final String BIZ_TICKET_RESULT = "TICKET_RESULT_IMAGE";

    private final FileMetadataMapper fileMetadataMapper;
    private final FileProperties fileProperties;
    private final StorageProperties storageProperties;
    private final S3Client rustfsS3Client;
    private final TicketService ticketService;
    @Transactional
    public FileResponse upload(MultipartFile file) {
        CurrentUser currentUser = CurrentUserProvider.require();
        validateFile(file, currentUser);
        String objectKey = buildObjectKey(file.getOriginalFilename(), currentUser);
        String bucket = storageProperties.getRustfs().getBucket();
        try {
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(objectKey)
                    .contentType(file.getContentType())
                    .contentLength(file.getSize())
                    .build();
            rustfsS3Client.putObject(request, RequestBody.fromInputStream(file.getInputStream(), file.getSize()));
        } catch (IOException exception) {
            log.error("file upload failed, scenario=file-upload, uploaderId={}, roleCode={}, originalName={}, sizeBytes={}",
                    currentUser.getId(), currentUser.getRoleCode(), file.getOriginalFilename(), file.getSize(), exception);
            throw new BusinessException(ErrorCode.FILE_UPLOAD_FAILED);
        } catch (RuntimeException exception) {
            log.error("file upload failed, scenario=file-upload-rustfs, uploaderId={}, roleCode={}, bucket={}, objectKey={}, originalName={}, sizeBytes={}",
                    currentUser.getId(), currentUser.getRoleCode(), bucket, objectKey, file.getOriginalFilename(), file.getSize(), exception);
            throw new BusinessException(ErrorCode.FILE_UPLOAD_FAILED);
        }

        FileMetadata metadata = new FileMetadata();
        metadata.setOriginalName(StringUtils.hasText(file.getOriginalFilename()) ? file.getOriginalFilename() : "unknown");
        metadata.setObjectKey(objectKey);
        metadata.setBucketName(bucket);
        metadata.setContentType(file.getContentType());
        metadata.setSizeBytes(file.getSize());
        metadata.setUploaderId(currentUser.getId());
        metadata.setUploaderRole(currentUser.getRoleCode());
        metadata.setBizType(BIZ_TEMP);
        metadata.setPublicUrl(buildPublicUrl(bucket, objectKey));
        fileMetadataMapper.insert(metadata);
        return toResponse(metadata);
    }

    @Transactional
    public List<FileResponse> bindTicketReportImages(Long ticketId, List<Long> fileIds) {
        CurrentUser currentUser = CurrentUserProvider.require();
        if (currentUser.getRole() != UserRole.STUDENT) {
            log.warn("file bind denied, scenario=bind-ticket-report-images, ticketId={}, userId={}, roleCode={}",
                    ticketId, currentUser.getId(), currentUser.getRoleCode());
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        List<FileMetadata> files = requireBindableFiles(fileIds, currentUser, BIZ_TICKET_REPORT, ticketId);
        ticketService.bindReportImages(ticketId, files.stream().map(FileMetadata::getPublicUrl).toList());
        files.forEach(file -> bindFile(file, BIZ_TICKET_REPORT, ticketId, currentUser));
        return files.stream().map(this::toResponse).toList();
    }

    @Transactional
    public List<FileResponse> bindTicketResultImages(Long ticketId, List<Long> fileIds) {
        CurrentUser currentUser = CurrentUserProvider.require();
        if (currentUser.getRole() != UserRole.WORKER) {
            log.warn("file bind denied, scenario=bind-ticket-result-images, ticketId={}, userId={}, roleCode={}",
                    ticketId, currentUser.getId(), currentUser.getRoleCode());
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        List<FileMetadata> files = requireBindableFiles(fileIds, currentUser, BIZ_TICKET_RESULT, ticketId);
        ticketService.bindResultImages(ticketId, files.stream().map(FileMetadata::getPublicUrl).toList());
        files.forEach(file -> bindFile(file, BIZ_TICKET_RESULT, ticketId, currentUser));
        return files.stream().map(this::toResponse).toList();
    }

    public List<FileResponse> listByTicket(Long ticketId, String bizType) {
        CurrentUser currentUser = CurrentUserProvider.require();
        ticketService.ensureTicketReadable(ticketId, currentUser);
        return fileMetadataMapper.selectList(new LambdaQueryWrapper<FileMetadata>()
                        .eq(FileMetadata::getBizId, ticketId)
                        .eq(FileMetadata::getBizType, bizType)
                        .eq(FileMetadata::getDeleted, 0)
                        .orderByAsc(FileMetadata::getCreatedAt)
                        .orderByAsc(FileMetadata::getId))
                .stream()
                .map(this::toResponse)
                .toList();
    }

    private void validateFile(MultipartFile file, CurrentUser currentUser) {
        if (file == null || file.isEmpty()) {
            log.warn("file upload rejected, scenario=file-upload, reason=empty-file, uploaderId={}", currentUser.getId());
            throw new BusinessException(ErrorCode.FILE_TYPE_INVALID);
        }
        if (file.getSize() > fileProperties.getMaxSize()) {
            log.warn("file upload rejected, scenario=file-upload, reason=size-exceeded, uploaderId={}, sizeBytes={}, maxSize={}",
                    currentUser.getId(), file.getSize(), fileProperties.getMaxSize());
            throw new BusinessException(ErrorCode.FILE_SIZE_EXCEEDED);
        }
        if (!fileProperties.getAllowedContentTypes().isEmpty()
                && !fileProperties.getAllowedContentTypes().contains(file.getContentType())) {
            log.warn("file upload rejected, scenario=file-upload, reason=content-type-invalid, uploaderId={}, contentType={}",
                    currentUser.getId(), file.getContentType());
            throw new BusinessException(ErrorCode.FILE_TYPE_INVALID);
        }
    }

    private List<FileMetadata> requireBindableFiles(List<Long> fileIds, CurrentUser currentUser, String bizType, Long ticketId) {
        List<FileMetadata> files = fileMetadataMapper.selectBatchIds(fileIds);
        if (files.size() != fileIds.stream().distinct().count()) {
            log.warn("file bind invalid, scenario={}, ticketId={}, uploaderId={}, fileIds={}",
                    bizType, ticketId, currentUser.getId(), fileIds);
            throw new BusinessException(ErrorCode.FILE_NOT_FOUND);
        }
        for (FileMetadata file : files) {
            if (file.getDeleted() != 0 || !currentUser.getId().equals(file.getUploaderId())) {
                log.warn("file access denied, scenario={}, ticketId={}, fileId={}, uploaderId={}, currentUserId={}",
                        bizType, ticketId, file.getId(), file.getUploaderId(), currentUser.getId());
                throw new BusinessException(ErrorCode.FILE_ACCESS_DENIED);
            }
            if (!BIZ_TEMP.equals(file.getBizType()) && !(bizType.equals(file.getBizType()) && ticketId.equals(file.getBizId()))) {
                log.warn("file already bound, scenario={}, ticketId={}, fileId={}, currentBizType={}, currentBizId={}",
                        bizType, ticketId, file.getId(), file.getBizType(), file.getBizId());
                throw new BusinessException(ErrorCode.FILE_ALREADY_BOUND);
            }
        }
        return files;
    }

    private void bindFile(FileMetadata file, String bizType, Long ticketId, CurrentUser currentUser) {
        file.setBizType(bizType);
        file.setBizId(ticketId);
        if (fileMetadataMapper.updateById(file) == 1) {
            return;
        }
        log.error("file metadata bind failed, scenario=file-bind, ticketId={}, fileId={}, userId={}, roleCode={}, bizType={}",
                ticketId, file.getId(), currentUser.getId(), currentUser.getRoleCode(), bizType);
        throw new BusinessException(ErrorCode.FILE_BIND_INVALID);
    }

    private String buildObjectKey(String originalName, CurrentUser currentUser) {
        String suffix = "";
        if (StringUtils.hasText(originalName) && originalName.contains(".")) {
            suffix = originalName.substring(originalName.lastIndexOf('.')).toLowerCase();
        }
        return "%s/%s/%s/%d/%s%s".formatted(
                storageProperties.getBasePath(),
                currentUser.getRoleCode().toLowerCase(),
                LocalDate.now(),
                currentUser.getId(),
                UUID.randomUUID(),
                suffix);
    }

    private String buildPublicUrl(String bucket, String objectKey) {
        String endpoint = storageProperties.getRustfs().getEndpoint();
        String normalizedEndpoint = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        return "%s/%s/%s".formatted(normalizedEndpoint, bucket, objectKey);
    }

    private FileResponse toResponse(FileMetadata metadata) {
        return new FileResponse(metadata.getId(), metadata.getOriginalName(), metadata.getObjectKey(), metadata.getBucketName(),
                metadata.getContentType(), metadata.getSizeBytes(), metadata.getUploaderId(), metadata.getUploaderRole(),
                metadata.getBizType(), metadata.getBizId(), metadata.getPublicUrl(), metadata.getCreatedAt());
    }
}
