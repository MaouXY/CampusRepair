package com.maou.apptemplateapi.module.ticket.entity;

import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("repair_ticket")
public class RepairTicket {

    private Long id;
    private Long studentId;
    private Long locationId;
    private Long categoryId;
    private String description;
    private String contactPhone;
    private String summary;
    private String priority;
    private String status;
    private Long assignedWorkerId;
    private Long assignedAdminId;
    private LocalDateTime assignedAt;
    private String rejectReason;
    private String returnReason;
    private String processResult;
    private String processRemark;
    private LocalDateTime processedAt;
    private LocalDateTime slaDeadlineAt;
    private LocalDateTime urgedAt;
    private Long urgedBy;
    private String urgeRemark;
    private String reportImageUrls;
    private String resultImageUrls;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
