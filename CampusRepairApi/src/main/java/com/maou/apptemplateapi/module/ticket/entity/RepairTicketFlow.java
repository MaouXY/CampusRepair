package com.maou.apptemplateapi.module.ticket.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("repair_ticket_flow")
public class RepairTicketFlow {

    private Long id;
    private Long ticketId;
    private String fromStatus;
    private String toStatus;
    private Long operatorId;
    private String operatorRole;
    private String action;
    private String remark;
    private LocalDateTime createdAt;
}
