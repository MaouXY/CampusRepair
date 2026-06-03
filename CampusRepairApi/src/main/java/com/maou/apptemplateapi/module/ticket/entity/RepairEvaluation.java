package com.maou.apptemplateapi.module.ticket.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("repair_evaluation")
public class RepairEvaluation {

    private Long id;
    private Long ticketId;
    private Long studentId;
    private Long workerId;
    private Integer score;
    private String content;
    private LocalDateTime createdAt;
}
