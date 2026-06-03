package com.maou.apptemplateapi.module.ai.entity;

import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("ai_task_record")
public class AiTaskRecord {

    private Long id;
    private Long organizationId;
    private String taskType;
    private String bizType;
    private Long bizId;
    private String modelName;
    private String requestSnapshot;
    private String responseSnapshot;
    private String status;
    private String errorMessage;
    private Long durationMs;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}

