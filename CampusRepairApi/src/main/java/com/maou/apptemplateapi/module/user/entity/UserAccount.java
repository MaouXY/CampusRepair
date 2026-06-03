package com.maou.apptemplateapi.module.user.entity;

import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("user_account")
public class UserAccount {

    private Long id;
    private String username;
    private String passwordHash;
    private String realName;
    private String phone;
    private String roleCode;
    private Integer enabled;
    private LocalDateTime lastLoginAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
