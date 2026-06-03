package com.maou.apptemplateapi.module.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maou.apptemplateapi.module.user.entity.UserAccount;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserAccountMapper extends BaseMapper<UserAccount> {
}
