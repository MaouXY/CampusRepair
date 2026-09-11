package com.maou.apptemplateapi.module.dispatch.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("worker_profile")
public class WorkerProfile {

    @TableId("worker_id")
    private Long workerId;
    private String departmentName;
    private String skillTags;
    private Integer dispatchEnabled;
    private Integer maxActiveOrders;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
