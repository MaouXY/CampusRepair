package com.maou.apptemplateapi.module.audit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maou.apptemplateapi.module.audit.entity.OperationAuditLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OperationAuditLogMapper extends BaseMapper<OperationAuditLog> {
}
