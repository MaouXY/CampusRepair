package com.maou.apptemplateapi.module.file.entity;

import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("file_metadata")
public class FileMetadata {

    private Long id;
    private String originalName;
    private String objectKey;
    private String bucketName;
    private String contentType;
    private Long sizeBytes;
    private Long uploaderId;
    private String uploaderRole;
    private String bizType;
    private Long bizId;
    private String publicUrl;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
