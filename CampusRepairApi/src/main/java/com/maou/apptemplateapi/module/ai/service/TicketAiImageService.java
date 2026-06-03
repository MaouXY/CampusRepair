package com.maou.apptemplateapi.module.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.maou.apptemplateapi.common.config.ai.AiProperties;
import com.maou.apptemplateapi.module.file.entity.FileMetadata;
import com.maou.apptemplateapi.module.file.mapper.FileMetadataMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
public class TicketAiImageService {

    private static final String BIZ_TICKET_REPORT = "TICKET_REPORT_IMAGE";

    private final FileMetadataMapper fileMetadataMapper;
    private final AiTaskService aiTaskService;
    private final AiProperties aiProperties;
    private final S3Client rustfsS3Client;

    public List<AiImageInput> loadTicketReportImages(Long ticketId, String scenario) {
        List<FileMetadata> files = fileMetadataMapper.selectList(new LambdaQueryWrapper<FileMetadata>()
                .eq(FileMetadata::getBizType, BIZ_TICKET_REPORT)
                .eq(FileMetadata::getBizId, ticketId)
                .eq(FileMetadata::getDeleted, 0)
                .orderByAsc(FileMetadata::getCreatedAt)
                .orderByAsc(FileMetadata::getId));
        if (files.isEmpty()) {
            return List.of();
        }
        List<AiImageInput> images = new ArrayList<>();
        long totalBytes = 0;
        for (FileMetadata file : files) {
            if (images.size() >= aiTaskService.maxImageCount()) {
                break;
            }
            if (file.getSizeBytes() == null || file.getSizeBytes() > aiTaskService.maxImageSizeBytes()) {
                log.warn("ticket ai image skipped, scenario={}, reason=image-size-limit, ticketId={}, fileId={}, sizeBytes={}, maxSizeBytes={}",
                        scenario, ticketId, file.getId(), file.getSizeBytes(), aiTaskService.maxImageSizeBytes());
                continue;
            }
            if (totalBytes + file.getSizeBytes() > aiTaskService.maxImageTotalBytes()) {
                log.warn("ticket ai image skipped, scenario={}, reason=image-total-size-limit, ticketId={}, fileId={}, totalBytes={}, fileSizeBytes={}, maxTotalBytes={}",
                        scenario, ticketId, file.getId(), totalBytes, file.getSizeBytes(), aiTaskService.maxImageTotalBytes());
                continue;
            }
            AiImageInput image = buildImageInput(file, ticketId, scenario);
            if (image == null) {
                continue;
            }
            images.add(image);
            totalBytes += file.getSizeBytes();
        }
        return images;
    }

    private AiImageInput buildImageInput(FileMetadata file, Long ticketId, String scenario) {
        String mode = aiProperties.imageTransferMode() == null
                ? "URL"
                : aiProperties.imageTransferMode().trim().toUpperCase(Locale.ROOT);
        if ("BASE64".equals(mode)) {
            try {
                ResponseBytes<GetObjectResponse> bytes = rustfsS3Client.getObjectAsBytes(GetObjectRequest.builder()
                        .bucket(file.getBucketName())
                        .key(file.getObjectKey())
                        .build());
                return new AiImageInput(file.getOriginalName(), file.getContentType(), null, bytes.asByteArray());
            } catch (RuntimeException exception) {
                log.error("ticket ai image load failed, scenario={}, transferMode=BASE64, ticketId={}, fileId={}, bucket={}, objectKey={}",
                        scenario, ticketId, file.getId(), file.getBucketName(), file.getObjectKey(), exception);
                return null;
            }
        }
        if (!StringUtils.hasText(file.getPublicUrl())) {
            log.warn("ticket ai image skipped, scenario={}, transferMode=URL, reason=missing-public-url, ticketId={}, fileId={}, bucket={}, objectKey={}",
                    scenario, ticketId, file.getId(), file.getBucketName(), file.getObjectKey());
            return null;
        }
        return new AiImageInput(file.getOriginalName(), file.getContentType(), file.getPublicUrl(), null);
    }
}
