package com.maou.apptemplateapi.module.ticket.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("repair_assignment")
public class RepairAssignment {

    private Long id;
    private Long ticketId;
    private Long adminId;
    private Long workerId;
    private String remark;
    private LocalDateTime assignedAt;
}
