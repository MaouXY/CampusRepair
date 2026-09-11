package com.maou.apptemplateapi.module.management.entity;

import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("repair_notice")
public class RepairNotice {

    private Long id;
    private String title;
    private String content;
    private String targetRole;
    private Integer published;
    private Integer sortOrder;
    /** 生效时间，空表示立即生效 */
    private LocalDateTime effectiveAt;
    /** 过期时间，空表示永不过期 */
    private LocalDateTime expireAt;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
